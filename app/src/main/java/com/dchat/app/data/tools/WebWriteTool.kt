package com.dchat.app.data.tools

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/** local.web_write：写用户工具的 web/ 目录（HTML/CSS/JS/assets）。 */
object WebWriteTool {
    private const val MAX_FILE = 512 * 1024
    private const val MAX_TOTAL = 8 * 1024 * 1024

    fun run(context: Context, argsJson: String): PyResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return fail("INVALID_ARGS", "参数不是合法 JSON")
        }
        val toolId = args.optString("tool_id").trim()
        if (!toolId.startsWith("user.") || UserToolStore.find(context, toolId) == null) {
            return fail("INVALID_TOOL", "tool_id 必须是已存在的用户工具（user.xxx）")
        }
        val path = args.optString("path").trim()
        if (!safePath(path)) {
            return fail("INVALID_PATH", "path 必须是 web/ 下的相对路径，禁止 .. 或绝对路径")
        }
        val bytes = try {
            if (args.optBoolean("is_base64", false)) {
                Base64.decode(args.optString("content"), Base64.NO_WRAP)
            } else {
                args.optString("content").toByteArray(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            return fail("INVALID_CONTENT", "content 不是合法 base64")
        }
        if (bytes.size > MAX_FILE) {
            return fail("FILE_TOO_LARGE", "单文件不能超过 512KB（当前 ${bytes.size} 字节）")
        }
        val webRoot = File(UserToolStore.toolDir(context, toolId), "web")
        val target = File(webRoot, path)
        val canonical = runCatching { target.canonicalFile }.getOrNull()
            ?: return fail("INVALID_PATH", "无法解析目标路径")
        val rootCanonical = runCatching { webRoot.canonicalFile }.getOrNull()
            ?: return fail("INVALID_PATH", "无法创建 web 目录")
        if (!canonical.path.startsWith(rootCanonical.path + File.separator)) {
            return fail("INVALID_PATH", "path 越界")
        }
        return try {
            val current = webRoot.walkTopDown().filter { it.isFile && it.absolutePath != canonical.absolutePath }
                .sumOf { it.length() }
            if (current + bytes.size > MAX_TOTAL) {
                return fail("TOOL_TOO_LARGE", "web/ 总大小不能超过 8MB")
            }
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    target.writeBytes(bytes)
                    part.delete()
                }
            }
            PyResult(true, "已写入 web/$path（${bytes.size} 字节）")
        } catch (t: Throwable) {
            fail("WRITE_FAILED", "写入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun safePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return true
    }

    private fun fail(code: String, message: String) = PyResult(false, errorCode = code, errorMessage = message)
}
