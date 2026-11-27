package com.dchat.app.ui.tools

import android.content.Context
import com.dchat.app.data.tools.FileTools
import com.dchat.app.data.tools.UserToolStore
import org.json.JSONObject
import java.io.File

/** UI v3 存储：ui/manifest.json + ui/pages 下的页面文件 + ui/assets。原子写，坏文件返回 null。 */
object UiV3Store {
    fun uiDir(ctx: Context, toolId: String): File =
        File(UserToolStore.toolDir(ctx, toolId), "ui")

    fun pagesDir(ctx: Context, toolId: String): File = File(uiDir(ctx, toolId), "pages")

    fun assetsDir(ctx: Context, toolId: String): File = File(uiDir(ctx, toolId), "assets")

    fun readManifest(ctx: Context, toolId: String): UiManifest? = runCatching {
        val f = File(uiDir(ctx, toolId), "manifest.json")
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (!UiV3Model.validateManifest(o).ok) return null
        UiV3Model.parseManifest(o)
    }.getOrNull()

    fun readPage(ctx: Context, toolId: String, page: String): UiPage? = runCatching {
        if (!UiV3Model.isPageName(page)) return null
        val f = File(pagesDir(ctx, toolId), "$page.json")
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (!UiV3Model.validatePage(o).ok) return null
        UiV3Model.parsePage(o)
    }.getOrNull()

    fun listPages(ctx: Context, toolId: String): List<String> =
        pagesDir(ctx, toolId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.filter { UiV3Model.isPageName(it) }
            ?.sorted()
            .orEmpty()

    fun writePage(ctx: Context, toolId: String, page: String, json: String): UiV3Validation {
        if (!UiV3Model.isPageName(page)) return UiV3Validation(listOf("page 名非法（1-32 位小写字母/数字/下划线）"))
        val obj = runCatching { JSONObject(json) }.getOrElse {
            return UiV3Validation(listOf("json 不是合法 JSON 对象"))
        }
        val v = UiV3Model.validatePage(obj)
        if (!v.ok) return v
        return runCatching {
            writeAtomic(File(pagesDir(ctx, toolId), "$page.json"), obj.toString())
            UiV3Validation(emptyList())
        }.getOrElse { UiV3Validation(listOf("写入失败：${it.message ?: it.javaClass.simpleName}")) }
    }

    fun writeManifest(ctx: Context, toolId: String, manifest: JSONObject): UiV3Validation {
        val v = UiV3Model.validateManifest(manifest)
        if (!v.ok) return v
        return runCatching {
            writeAtomic(File(uiDir(ctx, toolId), "manifest.json"), manifest.toString())
            UiV3Validation(emptyList())
        }.getOrElse { UiV3Validation(listOf("manifest 写入失败：${it.message ?: it.javaClass.simpleName}")) }
    }

    /** 规范 2.1：workspace: → 会话工作区；其它 → 工具目录；禁止 .. / 绝对路径。 */
    fun resolveSrc(ctx: Context, toolId: String, conversationId: String?, src: String): File? {
        if (!UiV3Model.safeSrc(src)) return null
        return runCatching {
            val root: File
            val rel: String
            if (src.startsWith("workspace:")) {
                root = FileTools.workspaceDir(ctx, conversationId)
                rel = src.removePrefix("workspace:")
            } else {
                // 规范目录：ui/assets/…；兼容老写法（工具根目录相对路径）
                val uiRoot = uiDir(ctx, toolId).canonicalFile
                val uiFile = File(uiRoot, src).canonicalFile
                if (uiFile.path.startsWith(uiRoot.path + File.separator) && uiFile.isFile) {
                    return@runCatching uiFile
                }
                root = UserToolStore.toolDir(ctx, toolId)
                rel = src
            }
            val f = File(root, rel).canonicalFile
            val base = root.canonicalFile
            if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f.takeIf { it.isFile } else null
        }.getOrNull()
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.writeText(text)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                target.writeText(text)
                part.delete()
            }
        }
    }
}
