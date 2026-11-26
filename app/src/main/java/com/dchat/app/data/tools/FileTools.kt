package com.dchat.app.data.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections

/**
 * 第四期 4c：文件工具 `fs.*` 的执行器。
 *
 * 工作区（授权根）：
 * - `filesDir/workspaces/<conversationId>/`：会话专属（相对路径默认落这里）；
 * - `filesDir/shared/`：共享区，路径前缀 `shared/`；
 * - 用户 SAF 授权目录：路径前缀 `saf:<授权名>/`，启动用 `saf:` 可列出全部授权根。
 *
 * 权限矩阵（与任务书一致）：
 * | 操作 | 工作区内 | 工作区外 |
 * | --- | --- | --- |
 * | list / read | 只读档免审批 | 完全权限 + 逐次审批 |
 * | write / append / mkdir / move | 工作区档，本会话首次审批 | 完全权限 + 逐次审批 |
 * | delete | 工作区档 + 每次审批 | 完全权限 + 每次审批 |
 *
 * 安全：所有路径 `canonicalPath` 规范化后必须落在某个授权根内；显式拒绝含 `..` 的路径；
 * 单文件读写上限 [MAX_FILE_BYTES]（超出只回元信息），读文本超 [MAX_TEXT_BYTES] 截断；
 * 本地写盘用 `.part` → rename 原子替换。
 */
object FileTools {

    private const val TAG = "FileTools"

    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_TEXT_BYTES = 64 * 1024
    const val MAX_LIST_ENTRIES = 500
    const val WORKSPACES_DIR = "workspaces"
    const val SHARED_DIR = "shared"
    /** 虚拟导出根：只能写（MediaStore → 下载/DChat），不能读/列目录 */
    const val EXPORT_PREFIX = "export/"
    const val EXPORT_DIR_NAME = "DChat"
    private val EXPORT_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR_NAME
    private const val ROOTS_FILE = "fs_roots.json"
    private const val SAF_PREFIX = "saf:"

    /** 工作区内「工作区档首次审批」的内存授权（会话级，进程重启后重新询问，失败关闭） */
    private val granted = Collections.synchronizedSet(HashSet<String>())

    // ---------------------------------------------------------------- 路径 / 根

    fun workspaceDir(context: Context, conversationId: String?): File {
        val safeId = (conversationId ?: "preview")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "preview" }
        return File(File(context.filesDir, WORKSPACES_DIR), safeId)
    }

    fun sharedDir(context: Context): File = File(context.filesDir, SHARED_DIR)

    // ---------------------------------------------------------------- 导出到「下载/DChat」

    /** 导出结果：成功返回系统里实际的显示名（重名时 MediaStore/我们会加序号） */
    sealed class ExportOutcome {
        data class Ok(val displayName: String) : ExportOutcome()
        /** 当前系统版本不支持（Android < 10 或 provider 不可用） */
        data class Unsupported(val detail: String) : ExportOutcome()
        data class Failed(val detail: String) : ExportOutcome()
    }

    /** 工具/UI 共用：字节数组导出到系统「下载/DChat」 */
    fun exportBytes(context: Context, rawName: String, bytes: ByteArray): ExportOutcome =
        exportCopy(context, rawName, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }

    /** UI 单文件导出：流式复制，不设大小配额（MediaStore 自身限制除外） */
    fun exportFile(context: Context, file: File): ExportOutcome {
        if (!file.isFile) return ExportOutcome.Failed("not a file")
        return exportCopy(context, file.name, file.length()) { file.inputStream() }
    }

    private fun sanitizeExportName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trimEnd('.')
        return cleaned.ifBlank { "export" }.take(120)
    }

    private fun mimeForExportName(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    private fun exportCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun exportNameExists(resolver: android.content.ContentResolver, name: String): Boolean =
        runCatching {
            resolver.query(
                exportCollection(),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(EXPORT_RELATIVE_PATH + "/", name),
                null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    private fun splitNameExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun uniqueExportName(resolver: android.content.ContentResolver, sanitized: String): String {
        val (base, ext) = splitNameExt(sanitized)
        var n = 1
        var candidate = sanitized
        while (exportNameExists(resolver, candidate) && n < 10_000) {
            candidate = "$base ($n)$ext"
            n++
        }
        return candidate
    }

    private fun queryExportDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun deleteExportQuietly(resolver: android.content.ContentResolver, uri: Uri?) {
        if (uri == null) return
        runCatching { resolver.delete(uri, null, null) }
    }

    /**
     * 写 MediaStore.Downloads（RELATIVE_PATH=Download/DChat）：
     * - Android < 10 → [ExportOutcome.Unsupported]，**不降级到别的路径**；
     * - 写入后校验字节数（expectedBytes > 0 时）与真实 displayName；失败删除刚建的 uri，不留垃圾；
     * - 重名自动加 (1)(2)… 序号。
     */
    private fun exportCopy(
        context: Context,
        rawName: String,
        expectedBytes: Long,
        openInput: () -> java.io.InputStream?
    ): ExportOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ExportOutcome.Unsupported("system-version")
        }
        val resolver = context.contentResolver
        val displayName = uniqueExportName(resolver, sanitizeExportName(rawName))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeForExportName(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        var input: java.io.InputStream? = null
        return try {
            uri = resolver.insert(exportCollection(), values)
                ?: return ExportOutcome.Failed("insert returned null")
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                deleteExportQuietly(resolver, uri)
                return ExportOutcome.Failed("openOutputStream returned null")
            }
            input = openInput()
            if (input == null) {
                deleteExportQuietly(resolver, uri)
                return ExportOutcome.Failed("openInputStream returned null")
            }
            val written = out.use { o ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input!!.read(buf)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                    total += n
                }
                total
            }
            runCatching { input?.close() }
            if (expectedBytes > 0 && written != expectedBytes) {
                deleteExportQuietly(resolver, uri)
                return ExportOutcome.Failed("size mismatch: written=$written expected=$expectedBytes")
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            ExportOutcome.Ok(queryExportDisplayName(resolver, uri) ?: displayName)
        } catch (t: Throwable) {
            runCatching { input?.close() }
            deleteExportQuietly(resolver, uri)
            ExportOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** SAF 授权根；`uri` 是 `ACTION_OPEN_DOCUMENT_TREE` 返回的 tree uri */
    data class SafRoot(val uri: String, val name: String)

    private fun rootsFile(context: Context) = File(context.filesDir, ROOTS_FILE)

    fun safRoots(context: Context): List<SafRoot> {
        val f = rootsFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("roots") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                val name = o.optString("name")
                if (uri.isBlank() || name.isBlank()) null else SafRoot(uri, name)
            }
        }.getOrElse {
            Log.w(TAG, "读取 fs_roots.json 失败", it)
            emptyList()
        }
    }

    private fun persistRoots(context: Context, roots: List<SafRoot>) {
        runCatching {
            val arr = JSONArray()
            roots.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
            val text = JSONObject().put("version", 1).put("roots", arr).toString()
            val target = rootsFile(context)
            val part = File(target.parentFile, target.name + ".part")
            part.writeText(text)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    target.writeText(text)
                    part.delete()
                }
            }
        }.onFailure { Log.w(TAG, "保存 SAF 授权根失败", it) }
    }

    /**
     * 记住一个 SAF 授权目录（takePersistableUriPermission），同名目录覆盖。
     * @return 保存后的根；URI 无法取持久权限时 null
     */
    fun addSafRoot(context: Context, uri: Uri): SafRoot? {
        return runCatching {
            val resolver = context.contentResolver
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = displayNameOfRoot(context, uri)
            val root = SafRoot(uri.toString(), name)
            val list = safRoots(context).filterNot { it.name.equals(name, ignoreCase = true) } + root
            persistRoots(context, list)
            root
        }.onFailure { Log.w(TAG, "授权 SAF 目录失败", it) }.getOrNull()
    }

    fun removeSafRoot(context: Context, name: String): Boolean {
        val roots = safRoots(context)
        val hit = roots.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(hit.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        persistRoots(context, roots.filterNot { it.uri == hit.uri })
        return true
    }

    private fun displayNameOfRoot(context: Context, uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val base = docId.substringAfterLast(':').substringAfterLast('/').ifBlank { "外部目录" }
        val docUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, docId) }.getOrNull()
        if (docUri != null) {
            runCatching {
                context.contentResolver.query(
                    docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        }
        return base
    }

    // ---------------------------------------------------------------- 解析结果

    private sealed class Target {
        abstract val display: String
        abstract val inside: Boolean
        abstract val isDir: Boolean

        data class Local(
            val file: File,
            override val display: String,
            override val inside: Boolean
        ) : Target() {
            override val isDir: Boolean get() = file.isDirectory
        }

        data class Saf(
            val tree: SafTree,
            val rel: String,
            /** null = 目标尚不存在（write / mkdir / move 的目标），读类操作需自行判空 */
            val doc: SafEntry?,
            override val display: String
        ) : Target() {
            override val inside: Boolean get() = true
            override val isDir: Boolean get() = doc?.isDir == true
        }

        /** `export/xxx`：虚拟导出根，只写不读；真正落盘走 MediaStore Downloads/DChat */
        data class Export(
            /** 期望文件名（可含扩展名；子目录会被压平到最后一段） */
            val name: String,
            override val display: String
        ) : Target() {
            override val inside: Boolean get() = false
            override val isDir: Boolean get() = false
        }
    }

    private class ResolveException(val code: String, val msg: String) : Exception(msg)

    private fun invalidPath(): Nothing {
        val n = Lang.current.local
        throw ResolveException("FS_INVALID_PATH", n.fsInvalidPath)
    }

    private fun hasDotDot(path: String): Boolean =
        path.split('/', '\\').any { it == ".." }

    /** 把模型给的路径解析成授权根内的目标；不允许时抛 [ResolveException] */
    private fun resolve(context: Context, conversationId: String?, mode: String, raw: String): Target {
        val path = raw.trim()
        if (path.isEmpty()) invalidPath()
        if (path.indexOf('\u0000') >= 0 || hasDotDot(path)) invalidPath()

        if (path.startsWith(SAF_PREFIX)) {
            val rest = path.removePrefix(SAF_PREFIX).removePrefix("/")
            val name = rest.substringBefore('/').trim()
            val rel = rest.substringAfter('/', "").trim('/')
            if (name.isEmpty()) invalidPath()
            val root = safRoots(context).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw ResolveException(
                    "FS_INVALID_PATH",
                    Lang.current.format(Lang.current.local.fsNotFound, SAF_PREFIX + name)
                )
            val tree = SafTree(context, root)
            val doc = if (rel.isEmpty()) tree.rootEntry() else tree.resolve(rel)
            return Target.Saf(tree, rel, doc, "$SAF_PREFIX${root.name}" + if (rel.isEmpty()) "" else "/$rel")
        }

        // 虚拟导出根：必须在本地路径解析之前拦截，绝不能当工作区/绝对路径处理
        if (path == "export" || path == EXPORT_PREFIX || path.startsWith(EXPORT_PREFIX)) {
            val rest = path.removePrefix("export").removePrefix("/").trim()
            if (rest.isEmpty()) {
                throw ResolveException("FS_UNSUPPORTED", Lang.current.local.fsExportUnreadable)
            }
            // 压平子目录：MediaStore 只落「下载/DChat」一层
            val name = rest.substringAfterLast('/').trim()
            if (name.isEmpty() || hasDotDot(name)) invalidPath()
            return Target.Export(name = name, display = EXPORT_PREFIX + name)
        }

        val workspace = workspaceDir(context, conversationId)
        val shared = sharedDir(context)
        val candidate = when {
            path.startsWith(SHARED_DIR + "/") -> File(shared, path.removePrefix(SHARED_DIR + "/"))
            path == SHARED_DIR -> shared
            // 兼容模型直接给 `workspaces/<会话id>/...` 的写法
            path.startsWith(WORKSPACES_DIR + "/") -> File(context.filesDir, path)
            File(path).isAbsolute || path.matches(Regex("^[A-Za-z]:[\\\\/].*")) -> File(path)
            else -> File(workspace, path)
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrElse {
            throw ResolveException("FS_INVALID_PATH", Lang.current.local.fsInvalidPath)
        }
        val inWorkspace = isInside(canonical, workspace)
        val inShared = isInside(canonical, shared)
        val inside = inWorkspace || inShared
        // 相对路径要用「同一个根」算：workspace 可能是 /data/user/0/...，而 canonicalFile
        // 解析出来是 /data/data/...，直接 relativeTo 会得到 ../../.. 这种鬼路径
        fun relOf(root: File): String = runCatching {
            canonical.relativeTo(root.canonicalFile).path
        }.getOrNull()?.takeIf { !it.startsWith("..") } ?: canonical.name
        val display = when {
            inWorkspace -> "$WORKSPACES_DIR/${workspace.name}/" + relOf(workspace)
            inShared -> "$SHARED_DIR/" + relOf(shared)
            else -> canonical.path
        }
        return Target.Local(canonical, display, inside)
    }

    private fun isInside(child: File, root: File): Boolean = runCatching {
        val c = child.canonicalPath
        val r = root.canonicalPath
        c == r || c.startsWith(r + File.separator)
    }.getOrDefault(false)

    private fun requireMode(mode: String, needWrite: Boolean): Boolean {
        val normalized = ToolRegistry.normalizeConversationMode(mode)
        return if (needWrite) normalized != ToolRegistry.CONV_READ else true
    }

    // ---------------------------------------------------------------- 审批

    /**
     * 写入类操作的一次性审批。
     *
     * 权限规则（用户定的）：
     * - **完全权限**：免审批，直接放行（连工作区外也一样）；
     * - 工作区内修改：同一会话同一工具首次询问，允许后就记住（`onceKey`）；
     * - 删除 / 移动这类危险操作在非完全权限下每次都问（传 `onceKey = null`）。
     */
    private suspend fun approve(
        toolId: String,
        conversationId: String?,
        mode: String,
        action: String,
        args: String,
        onceKey: String?
    ): Boolean {
        // 完全权限 = 免审批。之前这里没看档位，导致「给了完全权限还每次弹窗」
        if (ToolRegistry.normalizeConversationMode(mode) == ToolRegistry.CONV_FULL) return true
        if (onceKey != null) {
            synchronized(granted) { if (granted.contains(onceKey)) return true }
        }
        val n = Lang.current.local
        val name = Lang.current.tools.names[toolId] ?: toolId
        val allowed = ToolApproval.request(toolId, name, action, args)
        if (allowed && onceKey != null) {
            synchronized(granted) { granted.add(onceKey) }
        }
        return allowed
    }

    private fun denyResult(): PyResult =
        PyResult(ok = false, errorCode = "APPROVAL_REJECTED", errorMessage = Lang.current.local.rejected)

    private fun outsideDenied(): PyResult =
        PyResult(
            ok = false,
            errorCode = "PERMISSION_DENIED",
            errorMessage = Lang.current.local.fsOutsideNeedFull
        )

    private fun notFound(display: String): PyResult =
        PyResult(
            ok = false,
            errorCode = "FS_NOT_FOUND",
            errorMessage = Lang.current.format(Lang.current.local.fsNotFound, display)
        )

    // ---------------------------------------------------------------- 对外入口

    /** fs.* 的总入口；不支持的 id 返回 null 由上层处理 */
    suspend fun execute(
        context: Context,
        toolId: String,
        argsJson: String,
        conversationId: String?,
        mode: String
    ): PyResult? {
        if (!toolId.startsWith("fs.")) return null
        return withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse {
                return@withContext PyResult(
                    ok = false, errorCode = "INVALID_ARGS",
                    errorMessage = Lang.current.local.fsInvalidArgs
                )
            }
            try {
                when (toolId) {
                    "fs.list" -> list(context, conversationId, mode, args)
                    "fs.read" -> read(context, conversationId, mode, args)
                    "fs.write" -> write(context, conversationId, mode, args, append = false)
                    "fs.append" -> write(context, conversationId, mode, args, append = true)
                    "fs.mkdir" -> mkdir(context, conversationId, mode, args)
                    "fs.move" -> move(context, conversationId, mode, args)
                    "fs.delete" -> delete(context, conversationId, mode, args)
                    else -> null
                }
            } catch (e: ResolveException) {
                PyResult(ok = false, errorCode = e.code, errorMessage = e.message ?: "")
            } catch (t: Throwable) {
                Log.w(TAG, "$toolId 执行失败", t)
                PyResult(
                    ok = false, errorCode = "FS_IO_ERROR",
                    errorMessage = Lang.current.format(Lang.current.local.fsIoError, t.message ?: t.javaClass.simpleName)
                )
            }
        }
    }

    // ---------------------------------------------------------------- 各操作

    private suspend fun list(
        context: Context, conversationId: String?, mode: String, args: JSONObject
    ): PyResult {
        val raw = args.optString("path", "").trim()
        // `saf:` / `saf:/`：列出全部已授权 SAF 根
        if (raw == "saf:" || raw == "saf:/" || raw.isEmpty()) {
            val roots = safRoots(context)
            if (raw.isEmpty()) {
                // 空路径 = 当前会话工作区根（首次访问时建出来）
                val ws = workspaceDir(context, conversationId)
                ws.mkdirs()
                return listLocal(context, Target.Local(ws, "workspaces/${ws.name}", true))
            }
            val arr = JSONArray()
            roots.forEach { arr.put(JSONObject().put("root", it.name).put("path", "$SAF_PREFIX${it.name}")) }
            return PyResult(ok = true, output = JSONObject().put("roots", arr).toString())
        }
        val target = resolve(context, conversationId, mode, raw)
        // 导出目录只写不读：读/列目录直接拒绝
        if (target is Target.Export) {
            return PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.local.fsExportUnreadable)
        }
        // 工作区外：完全权限 + 逐次审批
        if (!target.inside) {
            if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
            val n = Lang.current.local
            if (!approve(
                    "fs.list", conversationId, mode,
                    Lang.current.format(n.fsApproveOutside, target.display),
                    target.display, null
                )
            ) return denyResult()
        }
        return when (target) {
            is Target.Local -> listLocal(context, target)
            is Target.Saf -> listSaf(target)
            is Target.Export -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.local.fsExportUnreadable)
        }
    }

    private fun listLocal(context: Context, target: Target.Local): PyResult {
        val n = Lang.current.local
        val dir = target.file
        if (!dir.exists()) {
            return PyResult(
                ok = false, errorCode = "FS_NOT_FOUND",
                errorMessage = Lang.current.format(n.fsNotFound, target.display)
            )
        }
        if (!dir.isDirectory) {
            return PyResult(ok = false, errorCode = "FS_NOT_A_DIR", errorMessage = n.fsNotADir)
        }
        // 相对路径（空 path）首次使用时把会话工作区建出来
        if (!dir.exists()) dir.mkdirs()
        val all = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList<File>()
        val arr = JSONArray()
        all.take(MAX_LIST_ENTRIES).forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("type", if (f.isDirectory) "dir" else "file")
                    .put("size", if (f.isDirectory) 0 else f.length())
                    .put("modified", f.lastModified())
            )
        }
        val out = JSONObject()
            .put("path", target.display)
            .put("type", "dir")
            .put("entries", arr)
        if (all.size > MAX_LIST_ENTRIES) {
            out.put("truncated", true).put("total", all.size)
        }
        return PyResult(ok = true, output = out.toString())
    }

    private fun listSaf(target: Target.Saf): PyResult {
        val n = Lang.current.local
        val doc = target.doc ?: return notFound(target.display)
        if (!doc.isDir) {
            return PyResult(ok = false, errorCode = "FS_NOT_A_DIR", errorMessage = n.fsNotADir)
        }
        val children = target.tree.children(doc.docId)
        val arr = JSONArray()
        children.take(MAX_LIST_ENTRIES).forEach { e ->
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("type", if (e.isDir) "dir" else "file")
                    .put("size", e.size)
                    .put("modified", e.lastModified)
            )
        }
        val out = JSONObject().put("path", target.display).put("type", "dir").put("entries", arr)
        if (children.size > MAX_LIST_ENTRIES) out.put("truncated", true).put("total", children.size)
        return PyResult(ok = true, output = out.toString())
    }

    private suspend fun read(
        context: Context, conversationId: String?, mode: String, args: JSONObject
    ): PyResult {
        val raw = args.optString("path", "").trim()
        val target = resolve(context, conversationId, mode, raw)
        if (target is Target.Export) {
            return PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.local.fsExportUnreadable)
        }
        if (!target.inside) {
            if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
            val n = Lang.current.local
            if (!approve(
                    "fs.read", conversationId, mode,
                    Lang.current.format(n.fsApproveOutside, target.display), target.display, null
                )
            ) return denyResult()
        }
        if (!target.inside && target !is Target.Local) return outsideDenied()
        return when (target) {
            is Target.Local -> readLocal(context, target)
            is Target.Saf -> readSaf(target)
            is Target.Export -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.local.fsExportUnreadable)
        }
    }

    private fun readLocal(context: Context, target: Target.Local): PyResult {
        val n = Lang.current.local
        val file = target.file
        if (!file.exists()) {
            return PyResult(ok = false, errorCode = "FS_NOT_FOUND",
                errorMessage = Lang.current.format(n.fsNotFound, target.display))
        }
        if (file.isDirectory) {
            return PyResult(ok = false, errorCode = "FS_NOT_A_FILE", errorMessage = n.fsNotAFile)
        }
        val size = file.length()
        val modified = file.lastModified()
        if (size > MAX_FILE_BYTES) {
            val meta = JSONObject()
                .put("path", target.display).put("type", "file").put("size", size)
                .put("modified", modified).put("binary", true)
                .put("note", Lang.current.format(n.fsTooLargeMeta, formatSize(MAX_FILE_BYTES)))
            return PyResult(ok = true, output = meta.toString())
        }
        val head = readHead(file, MAX_TEXT_BYTES)
        if (isBinary(head)) {
            val meta = JSONObject()
                .put("path", target.display).put("type", "file").put("size", size)
                .put("modified", modified).put("binary", true)
                .put("note", Lang.current.format(n.fsBinaryMeta, size))
            return PyResult(ok = true, output = meta.toString())
        }
        val text = String(head, Charsets.UTF_8)
        if (size <= MAX_TEXT_BYTES) return PyResult(ok = true, output = text)
        val suffix = "\n\n…" + Lang.current.format(n.fsTruncated, formatSize(MAX_TEXT_BYTES.toLong()))
        return PyResult(ok = true, output = text + suffix)
    }

    private fun readSaf(target: Target.Saf): PyResult {
        val n = Lang.current.local
        val doc = target.doc ?: return notFound(target.display)
        if (doc.isDir) {
            return PyResult(ok = false, errorCode = "FS_NOT_A_FILE", errorMessage = n.fsNotAFile)
        }
        if (doc.size > MAX_FILE_BYTES) {
            val meta = JSONObject()
                .put("path", target.display).put("type", "file").put("size", doc.size)
                .put("modified", doc.lastModified).put("binary", true)
                .put("note", Lang.current.format(n.fsTooLargeMeta, formatSize(MAX_FILE_BYTES)))
            return PyResult(ok = true, output = meta.toString())
        }
        val bytes = target.tree.readBytes(doc.docId, MAX_TEXT_BYTES) ?: return PyResult(
            ok = false, errorCode = "FS_IO_ERROR",
            errorMessage = Lang.current.format(n.fsIoError, target.display)
        )
        if (isBinary(bytes)) {
            val meta = JSONObject()
                .put("path", target.display).put("type", "file").put("size", doc.size)
                .put("modified", doc.lastModified).put("binary", true)
                .put("note", Lang.current.format(n.fsBinaryMeta, doc.size))
            return PyResult(ok = true, output = meta.toString())
        }
        val text = String(bytes, Charsets.UTF_8)
        val suffix = if (doc.size > MAX_TEXT_BYTES) {
            "\n\n…" + Lang.current.format(n.fsTruncated, formatSize(MAX_TEXT_BYTES.toLong()))
        } else ""
        return PyResult(ok = true, output = text + suffix)
    }

    private suspend fun write(
        context: Context, conversationId: String?, mode: String, args: JSONObject, append: Boolean
    ): PyResult {
        val toolId = if (append) "fs.append" else "fs.write"
        val n = Lang.current.local
        val raw = args.optString("path", "").trim()
        val content = args.optString("content", "")
        val bytes = content.toByteArray(Charsets.UTF_8)
        val target = resolve(context, conversationId, mode, raw)
        // 虚拟导出根：只支持 fs.write（不支持 append / 读 / 删除等）
        if (target is Target.Export) {
            if (append) {
                return PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                    errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display))
            }
            val action = Lang.current.format(n.fsApproveWrite, target.display)
            if (!target.inside) {
                if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
                if (!approve(toolId, conversationId, mode, action, target.display, null)) return denyResult()
            }
            // 用户明确不设配额：导出目录不受 10MB 限制
            return when (val r = exportBytes(context, target.name, bytes)) {
                is ExportOutcome.Ok -> PyResult(ok = true,
                    output = Lang.current.format(n.fsExportDone, EXPORT_DIR_NAME + "/" + r.displayName))
                is ExportOutcome.Unsupported -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                    errorMessage = n.fsExportUnsupported)
                is ExportOutcome.Failed -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                    errorMessage = Lang.current.format(n.fsExportFailed, r.detail))
            }
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return PyResult(
                ok = false, errorCode = "FS_TOO_LARGE",
                errorMessage = Lang.current.format(n.fsTooLargeMeta, formatSize(MAX_FILE_BYTES))
            )
        }
        if (target.isDir) {
            return PyResult(ok = false, errorCode = "FS_IS_DIR", errorMessage = n.fsIsDir)
        }
        val action = if (append) {
            Lang.current.format(n.fsApproveAppend, target.display)
        } else {
            Lang.current.format(n.fsApproveWrite, target.display)
        }
        if (!target.inside) {
            if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
            if (!approve(toolId, conversationId, mode, action, target.display, null)) return denyResult()
        } else {
            if (!requireMode(mode, needWrite = true)) {
                return PyResult(ok = false, errorCode = "PERMISSION_DENIED",
                    errorMessage = Lang.current.local.fsNeedWorkspace)
            }
            val key = "{$conversationId}|$toolId"
            if (!approve(toolId, conversationId, mode, action, target.display, key)) return denyResult()
        }
        return when (target) {
            is Target.Local -> runCatching {
                if (append && target.file.exists() &&
                    target.file.length() + bytes.size > MAX_FILE_BYTES
                ) {
                    return@runCatching PyResult(
                        ok = false, errorCode = "FS_TOO_LARGE",
                        errorMessage = Lang.current.format(n.fsTooLargeMeta, formatSize(MAX_FILE_BYTES))
                    )
                }
                writeLocal(target.file, bytes, append)
                val size = target.file.length()
                PyResult(ok = true, output = Lang.current.format(
                    if (append) n.fsAppendDone else n.fsWriteDone, target.display, size
                ))
            }.getOrElse {
                PyResult(ok = false, errorCode = "FS_IO_ERROR",
                    errorMessage = Lang.current.format(n.fsIoError, it.message ?: ""))
            }
            is Target.Saf -> {
                val result = if (append) {
                    target.tree.appendTextResult(target.rel, content)
                } else {
                    target.tree.writeTextResult(target.rel, content)
                }
                when (result) {
                    SafWrite.OK -> PyResult(ok = true, output = Lang.current.format(
                        if (append) n.fsAppendDone else n.fsWriteDone, target.display, bytes.size
                    ))
                    SafWrite.UNSUPPORTED -> PyResult(
                        ok = false, errorCode = "FS_UNSUPPORTED",
                        errorMessage = Lang.current.format(n.fsSafCreateUnsupported, target.display)
                    )
                    SafWrite.FAILED -> PyResult(
                        ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsSafNameFailed, target.display)
                    )
                }
            }
            is Target.Export -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display))
        }
    }

    private suspend fun mkdir(
        context: Context, conversationId: String?, mode: String, args: JSONObject
    ): PyResult {
        val n = Lang.current.local
        val target = resolve(context, conversationId, mode, args.optString("path", "").trim())
        if (target is Target.Export) {
            return PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display))
        }
        val action = Lang.current.format(n.fsApproveMkdir, target.display)
        if (!target.inside) {
            if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
            if (!approve("fs.mkdir", conversationId, mode, action, target.display, null)) return denyResult()
        } else {
            if (!requireMode(mode, needWrite = true)) {
                return PyResult(ok = false, errorCode = "PERMISSION_DENIED",
                    errorMessage = n.fsNeedWorkspace)
            }
            if (!approve("fs.mkdir", conversationId, mode, action, target.display,
                    "{$conversationId}|fs.mkdir")) return denyResult()
        }
        return when (target) {
            is Target.Local -> {
                if (target.file.exists() && !target.file.isDirectory) {
                    PyResult(ok = false, errorCode = "FS_EXISTS", errorMessage = n.fsExists)
                } else {
                    target.file.mkdirs()
                    PyResult(ok = true, output = Lang.current.format(n.fsMkdirDone, target.display))
                }
            }
            is Target.Saf -> {
                when (target.tree.mkdirResult(target.rel)) {
                    SafWrite.OK -> PyResult(ok = true, output = Lang.current.format(n.fsMkdirDone, target.display))
                    SafWrite.UNSUPPORTED -> PyResult(
                        ok = false, errorCode = "FS_UNSUPPORTED",
                        errorMessage = Lang.current.format(n.fsSafCreateUnsupported, target.display)
                    )
                    SafWrite.FAILED -> PyResult(ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsSafNameFailed, target.display))
                }
            }
            is Target.Export -> PyResult(
                ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display)
            )
        }
    }

    private suspend fun move(
        context: Context, conversationId: String?, mode: String, args: JSONObject
    ): PyResult {
        val n = Lang.current.local
        val fromRaw = args.optString("from", args.optString("src", "")).trim()
        val toRaw = args.optString("to", args.optString("dst", "")).trim()
        if (fromRaw.isEmpty() || toRaw.isEmpty()) {
            return PyResult(ok = false, errorCode = "INVALID_ARGS", errorMessage = n.fsInvalidArgs)
        }
        val from = resolve(context, conversationId, mode, fromRaw)
        val to = resolve(context, conversationId, mode, toRaw)
        val action = Lang.current.format(n.fsApproveMove, from.display, to.display)
        if (!from.inside || !to.inside) {
            if (ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) return outsideDenied()
            if (!approve("fs.move", conversationId, mode, action,
                    from.display + " -> " + to.display, null)) return denyResult()
        } else {
            if (!requireMode(mode, needWrite = true)) {
                return PyResult(ok = false, errorCode = "PERMISSION_DENIED",
                    errorMessage = n.fsNeedWorkspace)
            }
            if (!approve("fs.move", conversationId, mode, action,
                    from.display + " -> " + to.display, "{$conversationId}|fs.move")) return denyResult()
        }
        return runCatching {
            when {
                from is Target.Export -> return@runCatching PyResult(
                    ok = false, errorCode = "FS_UNSUPPORTED",
                    errorMessage = Lang.current.format(n.fsExportWriteOnly, from.display)
                )
                to is Target.Export && from is Target.Local -> {
                    if (!from.file.isFile) return@runCatching PyResult(
                        ok = false, errorCode = "FS_NOT_A_FILE", errorMessage = n.fsNotAFile)
                    val srcSize = from.file.length()
                    when (val r = exportCopy(context, to.name, srcSize) { from.file.inputStream() }) {
                        is ExportOutcome.Ok -> {
                            if (!from.file.delete()) return@runCatching PyResult(
                                ok = false, errorCode = "FS_IO_ERROR",
                                errorMessage = Lang.current.format(
                                    n.fsMoveCopyOnly, EXPORT_DIR_NAME + "/" + r.displayName, from.display
                                )
                            )
                            PyResult(ok = true, output = Lang.current.format(
                                n.fsExportDone, EXPORT_DIR_NAME + "/" + r.displayName
                            ))
                        }
                        is ExportOutcome.Unsupported -> PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED", errorMessage = n.fsExportUnsupported)
                        is ExportOutcome.Failed -> PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED",
                            errorMessage = Lang.current.format(n.fsExportFailed, r.detail))
                    }
                }
                to is Target.Export && from is Target.Saf -> {
                    val srcDoc = from.doc ?: return@runCatching notFound(from.display)
                    if (srcDoc.isDir) return@runCatching PyResult(
                        ok = false, errorCode = "FS_NOT_A_FILE", errorMessage = n.fsNotAFile)
                    when (val r = exportCopy(context, to.name, srcDoc.size) {
                            from.tree.openInput(srcDoc.docId)
                        }) {
                        is ExportOutcome.Ok -> {
                            if (!from.tree.delete(srcDoc.docId)) return@runCatching PyResult(
                                ok = false, errorCode = "FS_IO_ERROR",
                                errorMessage = Lang.current.format(
                                    n.fsMoveCopyOnly, EXPORT_DIR_NAME + "/" + r.displayName, from.display
                                )
                            )
                            PyResult(ok = true, output = Lang.current.format(
                                n.fsExportDone, EXPORT_DIR_NAME + "/" + r.displayName
                            ))
                        }
                        is ExportOutcome.Unsupported -> PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED", errorMessage = n.fsExportUnsupported)
                        is ExportOutcome.Failed -> PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED",
                            errorMessage = Lang.current.format(n.fsExportFailed, r.detail))
                    }
                }
                from is Target.Local && to is Target.Local -> {
                    if (!from.file.exists()) {
                        return@runCatching PyResult(ok = false, errorCode = "FS_NOT_FOUND",
                            errorMessage = Lang.current.format(n.fsNotFound, from.display))
                    }
                    if (to.file.exists()) {
                        return@runCatching PyResult(ok = false, errorCode = "FS_EXISTS",
                            errorMessage = n.fsExists)
                    }
                    to.file.parentFile?.mkdirs()
                    val moved = from.file.renameTo(to.file) || run {
                        from.file.copyRecursively(to.file, overwrite = false)
                        from.file.deleteRecursively()
                        true
                    }
                    if (moved) PyResult(ok = true,
                        output = Lang.current.format(n.fsMoveDone, from.display, to.display))
                    else PyResult(ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsIoError, from.display))
                }
                from is Target.Saf && to is Target.Saf -> {
                    val srcDoc = from.doc ?: return@runCatching notFound(from.display)
                    // review 修复：先看元数据大小，超限直接拒绝并保留源文件
                    if (srcDoc.size > MAX_FILE_BYTES) {
                        return@runCatching PyResult(
                            ok = false, errorCode = "FS_TOO_LARGE",
                            errorMessage = Lang.current.format(
                                n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(srcDoc.size)
                            )
                        )
                    }
                    if (to.tree.exists(to.rel)) {
                        return@runCatching PyResult(ok = false, errorCode = "FS_EXISTS", errorMessage = n.fsExists)
                    }
                    // 二进制直通：不再经过 String(UTF-8) 解码，图片/压缩包不会被破坏
                    val bytes = when (val r = from.tree.readAll(srcDoc.docId, MAX_FILE_BYTES)) {
                        is ByteRead.Ok -> r.bytes
                        ByteRead.TooLarge -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_TOO_LARGE",
                            errorMessage = Lang.current.format(
                                n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(srcDoc.size)
                            )
                        )
                        ByteRead.Failed -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsIoError, from.display)
                        )
                    }
                    // 只有目标写成功（且文件名校验通过）才删源；不支持按名新建时 fail-closed
                    when (to.tree.writeBytesResult(to.rel, bytes)) {
                        SafWrite.OK -> Unit
                        SafWrite.UNSUPPORTED -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED",
                            errorMessage = Lang.current.format(n.fsSafCreateUnsupported, to.display)
                        )
                        SafWrite.FAILED -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsSafNameFailed, to.display)
                        )
                    }
                    if (!from.tree.delete(srcDoc.docId)) {
                        return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsMoveCopyOnly, to.display, from.display)
                        )
                    }
                    PyResult(ok = true,
                        output = Lang.current.format(n.fsMoveDone, from.display, to.display))
                }
                from is Target.Local && to is Target.Saf -> {
                    if (!from.file.isFile) return@runCatching PyResult(
                        ok = false, errorCode = "FS_NOT_A_FILE", errorMessage = n.fsNotAFile)
                    val srcSize = from.file.length()
                    if (srcSize > MAX_FILE_BYTES) return@runCatching PyResult(
                        ok = false, errorCode = "FS_TOO_LARGE",
                        errorMessage = Lang.current.format(
                            n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(srcSize)
                        ))
                    if (to.tree.exists(to.rel)) {
                        return@runCatching PyResult(ok = false, errorCode = "FS_EXISTS", errorMessage = n.fsExists)
                    }
                    val bytes = runCatching { from.file.readBytes() }.getOrElse {
                        return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsIoError, from.display)
                        )
                    }
                    if (bytes.size.toLong() > MAX_FILE_BYTES) return@runCatching PyResult(
                        ok = false, errorCode = "FS_TOO_LARGE",
                        errorMessage = Lang.current.format(
                            n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(bytes.size.toLong())
                        ))
                    when (to.tree.writeBytesResult(to.rel, bytes)) {
                        SafWrite.OK -> Unit
                        SafWrite.UNSUPPORTED -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_UNSUPPORTED",
                            errorMessage = Lang.current.format(n.fsSafCreateUnsupported, to.display)
                        )
                        SafWrite.FAILED -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsSafNameFailed, to.display)
                        )
                    }
                    if (!from.file.delete()) return@runCatching PyResult(
                        ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsMoveCopyOnly, to.display, from.display)
                    )
                    PyResult(ok = true, output = Lang.current.format(n.fsMoveDone, from.display, to.display))
                }
                else -> {
                    val src = from as Target.Saf
                    val dst = to as Target.Local
                    val srcDoc = src.doc ?: return@runCatching notFound(from.display)
                    if (srcDoc.size > MAX_FILE_BYTES) return@runCatching PyResult(
                        ok = false, errorCode = "FS_TOO_LARGE",
                        errorMessage = Lang.current.format(
                            n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(srcDoc.size)
                        ))
                    if (dst.file.exists()) return@runCatching PyResult(
                        ok = false, errorCode = "FS_EXISTS", errorMessage = n.fsExists)
                    val bytes = when (val r = src.tree.readAll(srcDoc.docId, MAX_FILE_BYTES)) {
                        is ByteRead.Ok -> r.bytes
                        ByteRead.TooLarge -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_TOO_LARGE",
                            errorMessage = Lang.current.format(
                                n.fsMoveTooLarge, formatSize(MAX_FILE_BYTES), formatSize(srcDoc.size)
                            ))
                        ByteRead.Failed -> return@runCatching PyResult(
                            ok = false, errorCode = "FS_IO_ERROR",
                            errorMessage = Lang.current.format(n.fsIoError, from.display))
                    }
                    // 本地写盘失败/写了一半就报错，绝不删源
                    if (!writeLocalChecked(dst.file, bytes)) return@runCatching PyResult(
                        ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsIoError, dst.display)
                    )
                    if (!src.tree.delete(srcDoc.docId)) return@runCatching PyResult(
                        ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsMoveCopyOnly, dst.display, from.display)
                    )
                    PyResult(ok = true, output = Lang.current.format(n.fsMoveDone, from.display, to.display))
                }
            }
        }.getOrElse {
            PyResult(ok = false, errorCode = "FS_IO_ERROR",
                errorMessage = Lang.current.format(n.fsIoError, it.message ?: ""))
        }
    }

    private suspend fun delete(
        context: Context, conversationId: String?, mode: String, args: JSONObject
    ): PyResult {
        val n = Lang.current.local
        val target = resolve(context, conversationId, mode, args.optString("path", "").trim())
        if (target is Target.Export) {
            return PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display))
        }
        val action = Lang.current.format(
            n.fsApproveDelete, if (target.isDir) target.display + " " + n.fsDirSuffix else target.display
        )
        // 工作区内 delete 每次审批；工作区外还要求完全权限
        if (!target.inside && ToolRegistry.normalizeConversationMode(mode) != ToolRegistry.CONV_FULL) {
            return outsideDenied()
        }
        if (!requireMode(mode, needWrite = true)) {
            return PyResult(ok = false, errorCode = "PERMISSION_DENIED", errorMessage = n.fsNeedWorkspace)
        }
        if (!approve("fs.delete", conversationId, mode, action, target.display, null)) return denyResult()
        return when (target) {
            is Target.Local -> {
                if (!target.file.exists()) {
                    PyResult(ok = false, errorCode = "FS_NOT_FOUND",
                        errorMessage = Lang.current.format(n.fsNotFound, target.display))
                } else {
                    val ok = target.file.deleteRecursively()
                    if (ok) PyResult(ok = true, output = Lang.current.format(n.fsDeleteDone, target.display))
                    else PyResult(ok = false, errorCode = "FS_IO_ERROR",
                        errorMessage = Lang.current.format(n.fsIoError, target.display))
                }
            }
            is Target.Saf -> {
                val doc = target.doc ?: return notFound(target.display)
                val ok = target.tree.delete(doc.docId)
                if (ok) PyResult(ok = true, output = Lang.current.format(n.fsDeleteDone, target.display))
                else PyResult(ok = false, errorCode = "FS_IO_ERROR",
                    errorMessage = Lang.current.format(n.fsIoError, target.display))
            }
            is Target.Export -> PyResult(ok = false, errorCode = "FS_UNSUPPORTED",
                errorMessage = Lang.current.format(n.fsExportWriteOnly, target.display))
        }
    }

    // ---------------------------------------------------------------- 本地 IO 小工具

    /** 原子写并校验落盘大小；失败/半写返回 false（调用方据此不删源） */
    private fun writeLocalChecked(file: File, bytes: ByteArray): Boolean = runCatching {
        writeLocal(file, bytes, append = false)
        file.isFile && file.length() == bytes.size.toLong()
    }.getOrDefault(false)

    private fun writeLocal(file: File, bytes: ByteArray, append: Boolean) {
        file.parentFile?.mkdirs()
        if (append) {
            FileOutputStream(file, true).use { it.write(bytes) }
            return
        }
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            file.delete()
            if (!part.renameTo(file)) {
                file.writeBytes(bytes)
                part.delete()
            }
        }
    }

    private fun readHead(file: File, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(limit.coerceAtMost(file.length().toInt().coerceAtLeast(1)))
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var total = 0
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
        }
        return out.toByteArray()
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var control = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || (v in 0x0E..0x1F)) control++
        }
        return control * 100 / bytes.size > 10
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }

    // ---------------------------------------------------------------- SAF 适配

    /**
     * SAF 文档整读结果。
     * - [Ok]：完整字节（调用方已保证不超过上限）；
     * - [TooLarge]：已超过上限（**不会**返回被截断的前缀，避免“写回截断文件 + 删源”）；
     * - [Failed]：IO / 权限错误。
     */
    private sealed class ByteRead {
        class Ok(val bytes: ByteArray) : ByteRead()
        object TooLarge : ByteRead()
        object Failed : ByteRead()
    }

    private class SafEntry(
        val name: String,
        val docId: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long
    )

    /** SAF 写入结果：新增文件需要 provider 支持按名创建，不支持时返回 UNSUPPORTED（fail-closed） */
    private enum class SafWrite { OK, UNSUPPORTED, FAILED }

    private sealed class CreateOutcome {
        class Ok(val uri: Uri) : CreateOutcome()
        object Unsupported : CreateOutcome()
        object Failed : CreateOutcome()
    }

    /**
     * 已知「不支持按指定文件名新建」的 provider（authority）。
     * Honor 的文件 provider 会丢 displayName 且 rename/delete 均无效，首次失败后记住，
     * 之后直接返回 FS_UNSUPPORTED，不再反复制造 `(invalid)` 垃圾。
     */
    private val safCreateUnsupported = Collections.synchronizedSet(HashSet<String>())

    private class SafTree(private val context: Context, private val root: SafRoot) {
        private val tree: Uri = Uri.parse(root.uri)
        private val resolver get() = context.contentResolver
        private val rootDocId: String = runCatching { DocumentsContract.getTreeDocumentId(tree) }
            .getOrElse { "" }

        fun rootEntry(): SafEntry {
            val name = root.name
            val size = 0L
            val modified = 0L
            var dir = true
            runCatching {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
                resolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        dir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                        return@use
                    }
                }
            }
            return SafEntry(name, rootDocId, dir, size, modified)
        }

        fun children(parentDocId: String): List<SafEntry> {
            val out = ArrayList<SafEntry>()
            runCatching {
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
                resolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(1) ?: continue
                        val name = c.getString(0) ?: continue
                        val mime = c.getString(2)
                        out.add(
                            SafEntry(
                                name = name,
                                docId = id,
                                isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                size = if (c.isNull(3)) 0L else c.getLong(3),
                                lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "SAF 列目录失败: ${root.name}", it) }
            return out
        }

        private fun child(parentDocId: String, name: String): SafEntry? {
            val kids = children(parentDocId)
            // provider 返回的 displayName 可能有大小写/首尾空白差异，精确匹配后再宽匹配一次
            return kids.firstOrNull { it.name == name }
                ?: kids.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
        }

        fun resolve(rel: String): SafEntry? {
            var current = rootEntry()
            for (seg in rel.split('/').filter { it.isNotBlank() }) {
                if (!current.isDir) return null
                current = child(current.docId, seg) ?: return null
            }
            return current
        }

        fun exists(rel: String): Boolean = resolve(rel) != null

        private fun uriFor(docId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(tree, docId)

        /** 打开可读流（导出/复制用）；失败返回 null，由调用方处理 */
        fun openInput(docId: String): InputStream? = runCatching {
            resolver.openInputStream(uriFor(docId))
        }.onFailure { Log.w(TAG, "SAF 打开读取流失败: ${root.name}", it) }.getOrNull()

        /** 打开可写流：provider 对 mode 支持不一（有的只认 "w"，不认 "wt"），依次尝试 */
        private fun openOut(uri: Uri): java.io.OutputStream? =
            runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()

        fun readBytes(docId: String, limit: Int): ByteArray? = runCatching {
            resolver.openInputStream(uriFor(docId))?.use { input: InputStream ->
                readCap(input, limit)
            }
        }.onFailure { Log.w(TAG, "SAF 读取失败: ${root.name}", it) }.getOrNull()

        /**
         * 读完整个文档；超过 [maxBytes] 返回 [ByteRead.TooLarge]（绝不返回截断后的字节当成功）。
         */
        fun readAll(docId: String, maxBytes: Long): ByteRead = runCatching {
            val input = resolver.openInputStream(uriFor(docId)) ?: return@runCatching ByteRead.Failed
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream(8192)
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return@use ByteRead.TooLarge
                    out.write(buf, 0, n)
                }
                ByteRead.Ok(out.toByteArray())
            }
        }.onFailure { Log.w(TAG, "SAF 读取失败: ${root.name}", it) }.getOrDefault(ByteRead.Failed)

        private fun providerKey(): String = tree.authority ?: tree.toString()

        /**
         * 建立文档并**校验真实文件名**。
         *
         * 实测：Honor「文件」provider 的 `createDocument` 丢 displayName（落成 `(invalid)`），
         * 用 create 返回 URI 或 children 列表 docId 调 rename/delete 均无效。
         * 因此：能校验成功 → [CreateOutcome.Ok]；否则记 provider 不支持、尽力清理残留，
         * 返回 [CreateOutcome.Unsupported]（调用方返回 FS_UNSUPPORTED，不删源）。
         */
        private fun createVerified(parentDoc: SafEntry, desiredName: String, mime: String): CreateOutcome {
            if (providerKey() in safCreateUnsupported) return CreateOutcome.Unsupported
            val before = children(parentDoc.docId).map { it.docId }.toHashSet()
            val created = runCatching {
                DocumentsContract.createDocument(resolver, uriFor(parentDoc.docId), mime, desiredName)
            }.getOrNull() ?: return CreateOutcome.Failed
            // provider 若尊重 displayName，列表里会直接出现目标名
            child(parentDoc.docId, desiredName)?.let { return CreateOutcome.Ok(uriFor(it.docId)) }
            // 找出刚创建的那一项（按 docId 差集），拿它列表里的 docId 构造 child URI 再 rename
            val after = children(parentDoc.docId)
            val createdDocId = runCatching { DocumentsContract.getDocumentId(created) }.getOrNull()
            val newEntry = after.firstOrNull { it.docId !in before }
                ?: after.firstOrNull { createdDocId != null && it.docId == createdDocId }
            val targetDocId = newEntry?.docId ?: createdDocId
            if (targetDocId != null) {
                val targetUri = uriFor(targetDocId)
                runCatching { DocumentsContract.renameDocument(resolver, targetUri, desiredName) }
                    .onFailure { Log.w(TAG, "SAF renameDocument 失败: ${root.name}/$desiredName", it) }
                child(parentDoc.docId, desiredName)?.let { return CreateOutcome.Ok(uriFor(it.docId)) }
                // 名字仍不可控：尽力清理（用列表 docId + create 返回 URI 各试一次），失败只记日志
                val deleted = runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
                    .getOrDefault(false) ||
                    runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
                if (!deleted) {
                    Log.w(TAG, "SAF 清理 (invalid) 残留失败: ${root.name}/$desiredName docId=$targetDocId")
                }
            }
            // 这个 provider 不支持按名新建：记住，后续直接 fail-closed，不再产生垃圾
            safCreateUnsupported.add(providerKey())
            Log.w(TAG, "SAF provider 不支持按指定文件名新建: ${providerKey()} try=$desiredName")
            return CreateOutcome.Unsupported
        }

        /** 二进制直通写（保留 Boolean 兼容包装，内部走 [writeBytesResult]） */
        fun writeBytes(rel: String, bytes: ByteArray, mime: String = "application/octet-stream"): Boolean =
            writeBytesResult(rel, bytes, mime) == SafWrite.OK

        /**
         * 二进制直通写：已有同名文件直接覆盖（provider 支持）；目标不存在时按
         * [createVerified] 尝试按名新建，provider 不支持则返回 [SafWrite.UNSUPPORTED]
         * （fail-closed，调用方返回 FS_UNSUPPORTED，不动源文件）。
         */
        fun writeBytesResult(rel: String, bytes: ByteArray, mime: String = "application/octet-stream"): SafWrite =
            runCatching {
                val parentRel = rel.substringBeforeLast('/', "")
                val fileName = rel.substringAfterLast('/', "")
                val parentDoc = if (parentRel.isEmpty()) rootEntry() else resolve(parentRel)
                if (parentDoc == null || !parentDoc.isDir) return SafWrite.FAILED
                val existing = child(parentDoc.docId, fileName)
                if (existing != null && !existing.isDir) {
                    val out = openOut(uriFor(existing.docId)) ?: return SafWrite.FAILED
                    out.use { it.write(bytes) }
                    return SafWrite.OK
                }
                when (val created = createVerified(parentDoc, fileName, mime)) {
                    is CreateOutcome.Ok -> {
                        val out = openOut(created.uri)
                        if (out == null) {
                            runCatching { DocumentsContract.deleteDocument(resolver, created.uri) }
                            SafWrite.FAILED
                        } else {
                            out.use { it.write(bytes) }
                            SafWrite.OK
                        }
                    }
                    CreateOutcome.Unsupported -> SafWrite.UNSUPPORTED
                    CreateOutcome.Failed -> SafWrite.FAILED
                }
            }.onFailure { Log.w(TAG, "SAF 写入失败: ${root.name}", it) }.getOrDefault(SafWrite.FAILED)

        fun writeTextResult(rel: String, text: String): SafWrite =
            writeBytesResult(rel, text.toByteArray(Charsets.UTF_8), "text/plain")

        /** 追加：先读回完整旧内容（超上限直接失败），二进制直通，不做文本解码 */
        fun appendTextResult(rel: String, text: String): SafWrite = runCatching {
            val existing = resolve(rel)
            if (existing == null || existing.isDir) {
                writeTextResult(rel, text)
            } else {
                if (existing.size > MAX_FILE_BYTES) return SafWrite.FAILED
                val old = when (val r = readAll(existing.docId, MAX_FILE_BYTES)) {
                    is ByteRead.Ok -> r.bytes
                    else -> return SafWrite.FAILED
                }
                val extra = text.toByteArray(Charsets.UTF_8)
                if (old.size.toLong() + extra.size > MAX_FILE_BYTES) return SafWrite.FAILED
                writeBytesResult(rel, old + extra, "text/plain")
            }
        }.onFailure { Log.w(TAG, "SAF 追加失败: ${root.name}", it) }.getOrDefault(SafWrite.FAILED)

        fun mkdirResult(rel: String): SafWrite = runCatching {
            if (resolve(rel) != null) return SafWrite.OK
            val parentRel = rel.substringBeforeLast('/', "")
            val dirName = rel.substringAfterLast('/', "")
            val parentDoc = if (parentRel.isEmpty()) rootEntry() else resolve(parentRel)
            if (parentDoc == null || !parentDoc.isDir) return SafWrite.FAILED
            when (createVerified(parentDoc, dirName, DocumentsContract.Document.MIME_TYPE_DIR)) {
                is CreateOutcome.Ok -> SafWrite.OK
                CreateOutcome.Unsupported -> SafWrite.UNSUPPORTED
                CreateOutcome.Failed -> SafWrite.FAILED
            }
        }.onFailure { Log.w(TAG, "SAF 建目录失败: ${root.name}", it) }.getOrDefault(SafWrite.FAILED)

        fun delete(docId: String): Boolean = runCatching {
            DocumentsContract.deleteDocument(resolver, uriFor(docId))
            true
        }.onFailure { Log.w(TAG, "SAF 删除失败: ${root.name}", it) }.getOrDefault(false)

        private fun readCap(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
            val buf = ByteArray(8192)
            var total = 0
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}
