package com.dchat.app.data.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 第四期 4a：用户工具的依赖安装器。
 *
 * 流程：包名 → 查我们的轮子索引（[WHEEL_INDEX_URL]，占位待填）→ 下载 → sha256 校验 →
 * 解压到 `site-packages/` → 写回 tool.json requirements。
 *
 * - 含 native 的包只允许从索引装（PyPI 的 manylinux 轮子不能直接跑Chainguard/Android）；
 * - 纯 Python 包（`*-none-any.whl`）允许从 PyPI 兜底安装，UI 必须显示「第三方来源，风险自担」；
 * - 递归安装 requires_dist 里的纯 Python 依赖（requests 需要 urllib3/certifi/idna/charset_normalizer）。
 *
 * 注意：索引地址目前是常量占位，未配置前只会走 PyPI 纯 Python 兜底。
 */
object PackageInstaller {

    /** 托管轮子索引（后续填真实地址；占位时自动跳过，走 PyPI 纯 Python 兜底） */
    const val WHEEL_INDEX_URL = "https://<your-host>/dchat-wheels/index.json"

    private const val PYPI_JSON = "https://pypi.org/pypi/"
    private const val MAX_DEPTH = 6

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Result(
        val ok: Boolean,
        val installed: List<String> = emptyList(),
        val error: String? = null
    )

    suspend fun install(
        context: Context,
        toolId: String,
        packageName: String,
        allowPypi: Boolean = true
    ): Result = withContext(Dispatchers.IO) {
        val name = packageName.trim()
        if (!name.matches(Regex("^[A-Za-z0-9_.-]{1,80}$"))) {
            return@withContext Result(false, error = "包名不合法")
        }
        val def = UserToolStore.find(context, toolId)
            ?: return@withContext Result(false, error = "工具不存在")
        val site = UserToolStore.sitePackagesDir(context, toolId)
        site.mkdirs()
        val installed = ArrayList<String>()
        val failed = try {
            val index = fetchIndex()
            installRecursive(name, def, site, index, allowPypi, 0, visited = HashSet(), installed = installed)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (failed != null) {
            Result(false, installed, failed)
        } else {
            UserToolStore.addRequirements(context, toolId, installed)
            Result(true, installed)
        }
    }

    private fun installRecursive(
        rawName: String,
        def: UserToolDef,
        site: File,
        index: JSONArray?,
        allowPypi: Boolean,
        depth: Int,
        visited: MutableSet<String>,
        installed: MutableList<String>
    ): String? {
        val name = normalizeName(rawName)
        if (name.isBlank()) return null
        if (!visited.add(name.lowercase())) return null
        if (depth > MAX_DEPTH) return "依赖层级过深：$name"

        val fromIndex = index?.let { findIndexEntry(it, name) }
        if (fromIndex != null) {
            val pure = fromIndex.optBoolean("purePython", false)
            val abi = fromIndex.optString("abi", "any")
            if (!pure && abi != "arm64-v8a" && abi != "any") {
                return "含 native 的包 $name 没有 arm64-v8a 轮子"
            }
            val filename = fromIndex.optString("filename")
            val sha = fromIndex.optString("sha256")
            if (sha.isBlank()) return "索引缺少 sha256：$name"
            val url = fromIndex.optString("url").ifBlank {
                runCatching { URI(WHEEL_INDEX_URL).resolve(filename).toString() }
                    .getOrDefault(WHEEL_INDEX_URL.substringBeforeLast('/') + "/" + filename)
            }
            val bytes = download(url) ?: return "下载失败：$name"
            if (sha.isNotBlank() && !sha256Hex(bytes).equals(sha, ignoreCase = true)) {
                return "sha256 校验失败：$name"
            }
            extractWheel(bytes, site) ?: return "解压失败：$name"
            installed.add(name)
            return null
        }

        // PyPI 兜底：只允许纯 Python 轮子
        if (!allowPypi) return "含 native 的包只能从索引安装：$name（索引里没有）"
        val meta = fetchPypi(name) ?: return "未找到包：$name"
        val info = meta.optJSONObject("info") ?: return "包信息不合法：$name"
        val version = info.optString("version")
        val urls = meta.optJSONArray("urls") ?: JSONArray()
        var chosen: JSONObject? = null
        for (i in 0 until urls.length()) {
            val u = urls.optJSONObject(i) ?: continue
            if (u.optBoolean("yanked", false)) continue
            val filename = u.optString("filename")
            if (!filename.endsWith("-none-any.whl")) continue
            chosen = u
            break
        }
        if (chosen == null) {
            return "含 native 的包只能从索引安装：$name（PyPI 没有纯 Python 轮子）"
        }
        // 先装依赖，再装自己（顺序对 import 不敏感，但失败时不会留下半套）
        val reqs = info.optJSONArray("requires_dist")
        if (reqs != null) {
            for (i in 0 until reqs.length()) {
                val spec = reqs.optString(i)
                if (spec.isBlank()) continue
                if (spec.contains("extra ==")) continue
                val dep = normalizeName(spec)
                if (dep.isBlank()) continue
                val err = installRecursive(dep, def, site, index, allowPypi, depth + 1, visited, installed)
                if (err != null) return err
            }
        }
        val url = chosen.optString("url")
        val sha = chosen.optJSONObject("digests")?.optString("sha256").orEmpty()
        if (sha.isBlank()) return "PyPI 未提供 sha256：$name"
        val bytes = download(url) ?: return "下载失败：$name"
        if (sha.isNotBlank() && !sha256Hex(bytes).equals(sha, ignoreCase = true)) {
            return "sha256 校验失败：$name"
        }
        extractWheel(bytes, site) ?: return "解压失败：$name"
        installed.add("$name==$version".trimEnd('=', ' '))
        return null
    }

    private fun findIndexEntry(index: JSONArray, name: String): JSONObject? {
        for (i in 0 until index.length()) {
            val o = index.optJSONObject(i) ?: continue
            if (!o.optString("name").equals(name, ignoreCase = true)) continue
            if (o.has("abi") && o.optString("abi").isNotBlank() &&
                o.optString("abi") != "arm64-v8a" && o.optString("abi") != "any"
            ) continue
            return o
        }
        return null
    }

    /** 拉一次索引；占位地址/网络失败都返回 null（调用方转 PyPI 纯 Python 兜底） */
    private fun fetchIndex(): JSONArray? {
        if (WHEEL_INDEX_URL.contains("<your-host>")) return null
        val body = httpGet(WHEEL_INDEX_URL) ?: return null
        return runCatching {
            val o = JSONObject(body)
            o.optJSONArray("packages") ?: JSONArray(body)
        }.getOrNull()
    }

    private fun fetchPypi(name: String): JSONObject? {
        val body = httpGet(PYPI_JSON + name + "/json") ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun download(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        }.getOrNull()
    }

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    /** 解压 wheel/zip 到 site-packages；路径必须落在目标目录内 */
    private fun extractWheel(bytes: ByteArray, site: File): Boolean = runCatching {
        val root = site.canonicalFile
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && name.isNotBlank() && !name.contains("..")) {
                    val out = File(root, name).canonicalFile
                    if (out.path == root.path || out.path.startsWith(root.path + File.separator)) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        true
    }.getOrDefault(false)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** "requests" / "urllib3<3,>=1.21.1" / "charset-normalizer~=3.0" → 合法包名 */
    private fun normalizeName(raw: String): String =
        raw.trim()
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("[<>=!~;].*$"), "")
            .trim()
}
