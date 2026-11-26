package com.dchat.app.ui.tools

import android.content.Context
import android.util.Log
import com.dchat.app.data.tools.UserToolStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * UI Schema v2 的静态视图存储：`filesDir/tools/<toolId>/ui.json`。
 *
 * - 写：先写 `ui.json.part`，`fsync` 后 rename 覆盖（同目录 rename 原子），失败时保留旧文件；
 * - 读：缺失 / JSON 损坏 / version≠2 / 缺 blocks 都返回 null，由调用方决定回退旧 open 链路；
 * - `ui.json` 只存**视图**（与数据无关），页面的数据快照仍在 `state/page.json`（走
 *   [UserToolStore.readState] / [UserToolStore.writeState]）。
 *
 * 旧工具（v1）首次 `open` 成功后，由宿主把返回的 UI 树包成
 * `{"type":"legacy","schema":<旧树>}` 写入本文件，之后打开就走静态路径。
 */
object UiSpecStore {

    const val FILE_NAME = "ui.json"
    const val VERSION = 2
    private const val TAG = "UiSpecStore"

    fun file(context: Context, toolId: String): File =
        File(UserToolStore.toolDir(context, toolId), FILE_NAME)

    fun exists(context: Context, toolId: String): Boolean = file(context, toolId).isFile

    /** 读取并校验；文件不存在 / 损坏 / 版本不符都返回 null */
    fun read(context: Context, toolId: String): JSONObject? = readRaw(context, toolId)?.takeIf { isValid(it) }

    /** 只读不校验（用于诊断「文件在但内容坏了」） */
    fun readRaw(context: Context, toolId: String): JSONObject? = runCatching {
        val f = file(context, toolId)
        if (!f.isFile) null else JSONObject(f.readText())
    }.onFailure { Log.w(TAG, "读取 ui.json 失败: $toolId", it) }.getOrNull()

    fun isValid(spec: JSONObject?): Boolean =
        spec != null && spec.optInt("version", 0) == VERSION && spec.optJSONArray("blocks") != null

    /**
     * 原子写：`.part` → rename。
     * 任何异常都返回 false，磁盘上的旧 `ui.json` 保持不变。
     */
    fun write(context: Context, toolId: String, spec: JSONObject): Boolean = runCatching {
        val target = file(context, toolId)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part).use { fos ->
            fos.write(spec.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!part.renameTo(target)) {
            // 目标已存在时部分文件系统 rename 会失败：删旧文件再试一次，仍失败就降级为复制
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }
        true
    }.onFailure { Log.w(TAG, "写入 ui.json 失败: $toolId", it) }.getOrDefault(false)

    fun delete(context: Context, toolId: String): Boolean =
        runCatching { file(context, toolId).delete() }.getOrDefault(false)

    /** 旧 UI 树 / 旧数据包装成 v2 spec（`legacy` 块） */
    fun legacy(schema: Any?, uiMode: String): JSONObject {
        val block = JSONObject()
            .put("type", "legacy")
            .put("uiMode", if (uiMode == "schema") "schema" else "data")
            .put("schema", wrap(schema))
        return JSONObject()
            .put("version", VERSION)
            .put("blocks", JSONArray().put(block))
    }

    /**
     * 取「纯旧工具」的 legacy 块：只有整份 spec 是单个 legacy 块时才算。
     * 用于决定刷新是走 `load` 还是重跑旧 `open`。
     */
    fun legacyBlock(spec: JSONObject?): JSONObject? {
        val blocks = spec?.optJSONArray("blocks") ?: return null
        if (blocks.length() != 1) return null
        val block = blocks.optJSONObject(0) ?: return null
        return if (block.optString("type").equals("legacy", ignoreCase = true)) block else null
    }

    /** 深拷贝 spec，并替换 legacy 块里的旧 UI 树（旧工具动作返回新树时落盘） */
    fun withLegacySchema(spec: JSONObject, schema: Any?): JSONObject {
        val copy = JSONObject(spec.toString())
        val blocks = copy.optJSONArray("blocks") ?: return copy
        blocks.optJSONObject(0)?.let { block ->
            if (block.optString("type").equals("legacy", ignoreCase = true)) {
                block.put("schema", wrap(schema))
            }
        }
        return copy
    }

    private fun wrap(schema: Any?): Any = when (schema) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> schema
        else -> JSONObject.wrap(schema) ?: JSONObject.NULL
    }
}
