package com.dchat.app.data.droid

import android.util.Log
import org.json.JSONObject

/**
 * 宿主侧 droid 能力桥（CHILD 子进程模式的回包在这里落地）。
 *
 * 真正的实现由 ui-bridge-worker 提供：
 * ```
 * object DroidHost {
 *     @JvmStatic fun call(method: String, argsJson: String, toolId: String,
 *                         conversationId: String, mode: String): String
 * }
 * ```
 * 返回 `{"ok":true,"result":...}` 或 `{"ok":false,"code":"...","error":"..."}`。
 *
 * 这里用反射而不是直接引用，保证「DroidHost 还没合并/换包名」时本模块也能编译通过；
 * 运行期第一次调用时解析并缓存，任何异常都转成 DROID_ERROR/DROID_UNSUPPORTED 回包，
 * 不让 Python 工具因为宿主缺能力而崩。
 */
object DroidClient {

    private const val TAG = "DroidClient"

    /** 约定的实现类；前两个是候选包名（uibridge 落哪个都能用） */
    private val HOST_CLASSES = arrayOf(
        "com.dchat.app.data.droid.DroidHost",
        "com.dchat.app.data.tools.DroidHost"
    )

    private val lock = Any()

    @Volatile
    private var resolved = false

    @Volatile
    private var hostCall: java.lang.reflect.Method? = null

    /**
     * 转发一次能力调用（阻塞）。**必须在后台线程调用**：locate/pick_photo/take_photo
     * 可能阻塞到 120s。返回宿主 JSON 字符串，永不抛异常。
     */
    fun call(
        method: String,
        argsJson: String,
        toolId: String,
        conversationId: String,
        mode: String
    ): String {
        val m = resolve()
            ?: return errorJson("DROID_UNSUPPORTED", "宿主未提供 DroidHost 能力桥")
        return try {
            val raw = m.invoke(
                null, method, argsJson,
                toolId, conversationId, mode
            ) as? String
            raw?.takeIf { it.isNotBlank() }
                ?: errorJson("DROID_ERROR", "DroidHost.call 返回空结果")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            Log.w(TAG, "DroidHost.call 抛异常: $method", cause)
            errorJson("DROID_ERROR", cause.message ?: cause.javaClass.simpleName)
        } catch (t: Throwable) {
            Log.w(TAG, "DroidHost.call 调用失败: $method", t)
            errorJson("DROID_ERROR", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun resolve(): java.lang.reflect.Method? {
        if (resolved) return hostCall
        synchronized(lock) {
            if (resolved) return hostCall
            for (name in HOST_CLASSES) {
                val m = runCatching {
                    Class.forName(name).getMethod(
                        "call",
                        String::class.java, String::class.java,
                        String::class.java, String::class.java, String::class.java
                    )
                }.getOrNull()
                if (m != null) {
                    hostCall = m
                    Log.i(TAG, "droid 能力桥已绑定: $name")
                    break
                }
            }
            resolved = true
            if (hostCall == null) Log.w(TAG, "未找到 DroidHost，droid 调用将返回 DROID_UNSUPPORTED")
            return hostCall
        }
    }

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("error", message)
        .toString()
}
