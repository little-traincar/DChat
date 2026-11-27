package com.dchat.app.data.droid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dchat.app.data.tools.FileTools
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 安卓能力桥（droid API 规范 `docs/spec/droid-api.md`）。
 *
 * Python 工具通过 `import droid`（CHILD 模式走 PyProcessRuntime 的 droid 请求，IN_PROCESS
 * 模式直接调本对象）拿到一组**受控**的原生能力；本对象是唯一实现，对外入口只有：
 *
 * ```
 * DroidHost.call(method, argsJson, toolId, conversationId, mode): String
 * // {"ok":true,"result":...} / {"ok":false,"code":"DROID_DENIED|...","error":"..."}
 * ```
 *
 * 约定：
 * - 调用方必须在**后台线程**调用（pick_photo/take_photo 最长等 115s、locate 默认等 30s），
 *   内部把 Toast/通知/ActivityResult/权限申请统一 post 到主线程；
 * - 权限档位按会话权限（`read` / `workspace` / `full`）判定，不够直接 DROID_DENIED；
 * - 系统运行时权限（通知 / 定位 / 相机）由宿主在主线程申请，拒绝 → DROID_DENIED；
 * - 相册/相机结果复制到**会话工作区** `workspaces/<convId>/droid/` 并返回绝对路径；
 * - 任何异常都转成错误 JSON，不向调用方抛异常。
 *
 * Activity 注入：MainActivity 在 onCreate（Composition 内）调 [attach] 注入 Activity 弱引用 +
 * 内置浏览器打开回调，onDispose 时 [detach]。没有 Activity 时只读能力仍可用；相册/相机/权限
 * 相关能力返回 DROID_UNSUPPORTED。
 */
object DroidHost {

    private const val TAG = "DroidHost"
    private const val CHANNEL_ID = "droid_tools"
    private const val CHANNEL_NAME = "DChat"

    /** 普通能力内部等待上限（外部 PyProcessRuntime 10s 会先兜底，这里留余量） */
    private const val OP_TIMEOUT_MS = 9_000L

    /** 相册/相机等待上限：必须小于 PyProcessRuntime 的 120s 外部超时，保证先回包 */
    private const val MEDIA_TIMEOUT_MS = 115_000L

    /** 相机运行时权限最多等 15s；take_photo 的媒体等待再留 100s（合计 < 外部 120s） */
    private const val CAMERA_PERMISSION_TIMEOUT_MS = 15_000L
    private const val CAMERA_MEDIA_WAIT_MS = 100_000L

    /** 通知权限最多等 8s：notify 不属于 PyProcessRuntime 的“长能力”，外部只有 10s */
    private const val NOTIFY_PERMISSION_TIMEOUT_MS = 8_000L

    private const val LOCATE_DEFAULT_TIMEOUT_S = 30
    private const val LOCATE_MAX_TIMEOUT_S = 120

    /** 复制进工作区的图片上限（防止模型选超大文件把内存/存储打爆） */
    private const val COPY_LIMIT_BYTES = 64L * 1024 * 1024

    private const val MAX_TOAST_CHARS = 300

    /** 能力权限档位：只读 < 工作区 < 完全权限 */
    private enum class Level(val rank: Int, val label: String) {
        READ(0, "只读"),
        WORKSPACE(1, "工作区"),
        FULL(2, "完全权限")
    }

    /** 能力 → 最低会话权限档位（规范第 1 节表格） */
    private val REQUIRED_LEVEL: Map<String, Level> = mapOf(
        "toast" to Level.READ,
        "vibrate" to Level.READ,
        "clipboard_get" to Level.READ,
        "share_text" to Level.READ,
        "open_url" to Level.READ,
        "battery" to Level.READ,
        "network" to Level.READ,
        "sensors" to Level.READ,
        "device" to Level.READ,
        "clipboard_set" to Level.WORKSPACE,
        "notify" to Level.WORKSPACE,
        "locate" to Level.FULL,
        "pick_photo" to Level.FULL,
        "take_photo" to Level.FULL
    )

    /** 这些能力需要等用户交互/系统回调，主线程调用会死锁 → 要求后台线程 */
    private val OFF_MAIN_REQUIRED = setOf("locate", "pick_photo", "take_photo")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(1)

    /**
     * 交互类能力（相册/相机/定位/通知权限）的全局串行锁：
     * PyProcessRuntime 最多 5 个 CHILD worker 可并发调 DroidHost，但同一时间只允许一个
     * 需要“弹用户界面”的操作，避免两个相册/相机/权限框叠在一起。
     */
    private val interactiveLock = java.util.concurrent.Semaphore(1)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activityRef: WeakReference<ComponentActivity>? = null

    /** 内置浏览器打开回调（MainActivity 注入；拿不到时退化为系统 ACTION_VIEW） */
    @Volatile
    private var openUrlHandler: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ 生命周期注入

    /** MainActivity 注入：Activity 弱引用 + 内置浏览器打开方式（可选） */
    fun attach(activity: ComponentActivity, openUrl: ((String) -> Unit)? = null) {
        appContext = activity.applicationContext
        activityRef = WeakReference(activity)
        openUrlHandler = openUrl
    }

    /** 解除注入（onDispose/onDestroy） */
    fun detach(activity: ComponentActivity? = null) {
        val cur = activityRef?.get()
        if (activity == null || cur == null || cur === activity) {
            activityRef = null
            openUrlHandler = null
        }
    }

    private fun activity(): ComponentActivity? {
        val a = activityRef?.get() ?: return null
        return a.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun context(): Context? = appContext ?: activity()?.applicationContext

    // ------------------------------------------------------------------ 唯一入口

    /**
     * 执行一次原生能力调用（阻塞；**必须在后台线程调用**）。
     *
     * @return `{"ok":true,"result":...}` 或 `{"ok":false,"code":"DROID_*","error":"..."}`
     */
    @JvmStatic
    fun call(
        method: String,
        argsJson: String,
        toolId: String,
        conversationId: String,
        mode: String
    ): String {
        return try {
            val m = method.trim().lowercase(Locale.ROOT)
            val need = REQUIRED_LEVEL[m] ?: return err("DROID_UNSUPPORTED", "未知能力：$m")
            val current = levelOf(mode)
            if (current.rank < need.rank) {
                return err(
                    "DROID_DENIED",
                    "当前会话权限为「${current.label}」，调用 $m 需要「${need.label}」"
                )
            }
            if (Looper.myLooper() == Looper.getMainLooper() && m in OFF_MAIN_REQUIRED) {
                return err("DROID_ERROR", "$m 需要等待用户操作，必须在后台线程调用")
            }
            val ctx = context()
                ?: return err("DROID_UNSUPPORTED", "宿主能力未初始化（Activity 未注入）")
            val args = runCatching {
                if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
            }.getOrElse { return err("DROID_ERROR", "参数不是合法 JSON 对象") }
            Log.d(TAG, "droid call tool=$toolId conv=$conversationId method=$m mode=$mode")
            // 交互类能力全局串行：最多等 1.5s 拿锁，拿不到就明确报错（不叠 UI、不长时间排队）
            if (m == "locate" || m == "pick_photo" || m == "take_photo" || m == "notify") {
                if (!interactiveLock.tryAcquire(1_500L, TimeUnit.MILLISECONDS)) {
                    return err("DROID_ERROR", "另一个 droid 交互操作正在进行，请稍后重试")
                }
                try {
                    return dispatch(m, ctx, args, conversationId)
                } finally {
                    interactiveLock.release()
                }
            }
            dispatch(m, ctx, args, conversationId)
        } catch (t: Throwable) {
            Log.w(TAG, "droid call failed: $method", t)
            err("DROID_ERROR", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun levelOf(mode: String): Level = when (mode.trim().lowercase(Locale.ROOT)) {
        "read", "read_only" -> Level.READ
        "workspace", "workspace_write" -> Level.WORKSPACE
        "full" -> Level.FULL
        // 未知档位按最低权限处理，避免越权
        else -> Level.READ
    }

    private fun dispatch(method: String, ctx: Context, args: JSONObject, conversationId: String): String =
        when (method) {
            "toast" -> toast(ctx, args)
            "vibrate" -> vibrate(ctx, args)
            "clipboard_get" -> clipboardGet(ctx)
            "clipboard_set" -> clipboardSet(ctx, args)
            "notify" -> notify(ctx, args)
            "share_text" -> shareText(ctx, args)
            "open_url" -> openUrl(args)
            "battery" -> battery(ctx)
            "network" -> network(ctx)
            "sensors" -> sensors(ctx)
            "device" -> device(ctx)
            "locate" -> locate(ctx, args)
            "pick_photo" -> pickPhoto(ctx, conversationId)
            "take_photo" -> takePhoto(ctx, conversationId)
            else -> err("DROID_UNSUPPORTED", "未知能力：$method")
        }

    // ------------------------------------------------------------------ 只读能力

    /** 轻提示（只读档位） */
    private fun toast(ctx: Context, args: JSONObject): String {
        val text = args.optString("text").trim().take(MAX_TOAST_CHARS).ifBlank {
            return err("DROID_ERROR", "toast 缺少 text")
        }
        val app = ctx.applicationContext ?: ctx
        runOnMain { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show() }
        return ok(null)
    }

    /** 振动（只读档位；需要 manifest 声明 VIBRATE 普通权限） */
    private fun vibrate(ctx: Context, args: JSONObject): String {
        if (!isPermissionDeclared(ctx, Manifest.permission.VIBRATE)) {
            return err("DROID_UNSUPPORTED", "应用未声明 VIBRATE 权限，无法振动")
        }
        val ms = args.optInt("ms", 50).coerceIn(1, 1_000)
        val ctxApp = ctx.applicationContext ?: ctx
        val vibrator: Vibrator = (
            if (Build.VERSION.SDK_INT >= 31) {
                (ctxApp.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctxApp.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            ) ?: return err("DROID_UNSUPPORTED", "设备不支持振动")
        return try {
            runOnMain {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(ms.toLong())
                    }
                }.onFailure { Log.w(TAG, "vibrate failed", it) }
            }
            ok(null)
        } catch (t: Throwable) {
            err("DROID_ERROR", t.message ?: "振动失败")
        }
    }

    /** 读剪贴板（只读档位） */
    private fun clipboardGet(ctx: Context): String {
        val latch = CountDownLatch(1)
        var text = ""
        runOnMain {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clipboard_get failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return err("DROID_TIMEOUT", "读取剪贴板超时")
        }
        return ok(text)
    }

    /** 写剪贴板（工作区档位） */
    private fun clipboardSet(ctx: Context, args: JSONObject): String {
        val text = args.optString("text")
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        runOnMain {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: throw IllegalStateException("剪贴板不可用")
                cm.setPrimaryClip(ClipData.newPlainText("DChat", text))
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return err("DROID_TIMEOUT", "写入剪贴板超时")
        }
        failure?.let { return err("DROID_ERROR", it.message ?: "写入剪贴板失败") }
        return ok(null)
    }

    /** 系统分享文本（只读档位） */
    private fun shareText(ctx: Context, args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) return err("DROID_ERROR", "share_text 缺少 text")
        val title = args.optString("title").trim()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, title.ifBlank { null })
        val app = ctx.applicationContext ?: ctx
        return try {
            runOnMain {
                runCatching {
                    val launcherCtx: Context = activity() ?: app
                    if (launcherCtx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launcherCtx.startActivity(chooser)
                }.onFailure { Log.w(TAG, "share_text failed", it) }
            }
            ok(null)
        } catch (t: Throwable) {
            err("DROID_ERROR", t.message ?: "分享失败")
        }
    }

    /** 打开链接：优先内置浏览器（MainActivity 注入的 opener），否则系统浏览器 */
    private fun openUrl(args: JSONObject): String {
        val raw = args.optString("url").trim()
        if (raw.isBlank()) return err("DROID_ERROR", "open_url 缺少 url")
        val lower = raw.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return err("DROID_ERROR", "open_url 只允许 http/https 链接")
        }
        val handler = openUrlHandler
        if (handler != null) {
            runOnMain { runCatching { handler(raw) }.onFailure { Log.w(TAG, "open_url handler failed", it) } }
            return ok(null)
        }
        val ctx = context() ?: return err("DROID_UNSUPPORTED", "宿主未初始化，无法打开链接")
        val app = ctx.applicationContext ?: ctx
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw))
            runOnMain {
                runCatching {
                    val launcherCtx: Context = activity() ?: app
                    if (launcherCtx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launcherCtx.startActivity(intent)
                }.onFailure { Log.w(TAG, "open_url system failed", it) }
            }
            ok(null)
        } catch (t: Throwable) {
            err("DROID_ERROR", t.message ?: "打开链接失败")
        }
    }

    /** 电量（只读档位）：{"level":0..1,"charging":bool} */
    private fun battery(ctx: Context): String {
        val app = ctx.applicationContext ?: ctx
        val sticky = runCatching {
            app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val levelRaw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val level = when {
            levelRaw >= 0 && scale > 0 -> (levelRaw.toDouble() / scale).coerceIn(0.0, 1.0)
            Build.VERSION.SDK_INT >= 21 -> {
                val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (pct in 0..100) pct / 100.0 else 0.0
            }
            else -> 0.0
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            (Build.VERSION.SDK_INT >= 23 &&
                (app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true)
        return ok(JSONObject().put("level", level).put("charging", charging))
    }

    /** 网络（只读档位）：{"type":"wifi|mobile|none","online":bool} */
    private fun network(ctx: Context): String {
        val app = ctx.applicationContext ?: ctx
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ok(JSONObject().put("type", "none").put("online", false))
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "none"
        }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return ok(JSONObject().put("type", type).put("online", online))
    }

    /**
     * 传感器（只读档位）：最近一次采样，{'accelerometer':[x,y,z], 'gyroscope':[...],
     * 'light':[lux,0,0], 'magnetic':[...]}；没有的传感器给 [0,0,0]。
     */
    private fun sensors(ctx: Context): String {
        val app = ctx.applicationContext ?: ctx
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return ok(emptySensors())
        val targets = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "light" to Sensor.TYPE_LIGHT,
            "magnetic" to Sensor.TYPE_MAGNETIC_FIELD
        )
        val expected = targets.values.count { type ->
            runCatching { sm.getDefaultSensor(type) != null }.getOrDefault(false)
        }
        val out = emptySensors()
        val latch = CountDownLatch(1)
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val key = when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> "accelerometer"
                    Sensor.TYPE_GYROSCOPE -> "gyroscope"
                    Sensor.TYPE_LIGHT -> "light"
                    Sensor.TYPE_MAGNETIC_FIELD -> "magnetic"
                    else -> return
                }
                val arr = JSONArray()
                for (i in 0 until 3) {
                    arr.put(event.values.getOrElse(i) { 0f }.toDouble())
                }
                synchronized(out) { out.put(key, arr) }
                // 所有可用传感器都出过一次采样就提前结束（避免固定等 1s）
                if (received.incrementAndGet() >= expected) latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runOnMain {
            runCatching {
                val smgr = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                targets.forEach { (_, type) ->
                    val sensor = smgr?.getDefaultSensor(type) ?: return@forEach
                    smgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                }
                if (expected == 0) latch.countDown()
            }.onFailure { latch.countDown() }
        }
        // 最多等 1s 收集一次采样；超时返回已收到的部分（没传感器就全是 0）
        latch.await(1_000, TimeUnit.MILLISECONDS)
        runOnMain {
            runCatching {
                (app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(listener)
            }
        }
        return ok(out)
    }

    private fun emptySensors(): JSONObject = JSONObject()
        .put("accelerometer", JSONArray().put(0.0).put(0.0).put(0.0))
        .put("gyroscope", JSONArray().put(0.0).put(0.0).put(0.0))
        .put("light", JSONArray().put(0.0).put(0.0).put(0.0))
        .put("magnetic", JSONArray().put(0.0).put(0.0).put(0.0))

    /** 设备信息（只读档位） */
    private fun device(ctx: Context): String {
        val app = ctx.applicationContext ?: ctx
        val cfg = app.resources.configuration
        val dm = app.resources.displayMetrics
        val widthDp = if (cfg.screenWidthDp > 0) cfg.screenWidthDp else (dm.widthPixels / dm.density).toInt()
        val heightDp = if (cfg.screenHeightDp > 0) cfg.screenHeightDp else (dm.heightPixels / dm.density).toInt()
        return ok(
            JSONObject()
                .put("model", Build.MODEL.orEmpty())
                .put("brand", Build.BRAND.orEmpty())
                .put("manufacturer", Build.MANUFACTURER.orEmpty())
                .put("os", "Android ${Build.VERSION.RELEASE}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("widthDp", widthDp)
                .put("heightDp", heightDp)
                .put("density", dm.density.toDouble())
                .put("language", Locale.getDefault().toLanguageTag())
        )
    }

    // ------------------------------------------------------------------ 通知（工作区档位）

    private fun notify(ctx: Context, args: JSONObject): String {
        val title = args.optString("title").trim().ifBlank { "DChat" }
        val text = args.optString("text").trim()
        if (text.isBlank()) return err("DROID_ERROR", "notify 缺少 text")
        val requestedId = args.optInt("id", 0)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!ensurePermission(Manifest.permission.POST_NOTIFICATIONS, NOTIFY_PERMISSION_TIMEOUT_MS)) {
                return err("DROID_DENIED", "通知权限被拒绝")
            }
        }
        val app = ctx.applicationContext ?: ctx
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        runOnMain {
            try {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: throw IllegalStateException("通知服务不可用")
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val n = NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(text.take(200))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2_000)))
                    .setAutoCancel(true)
                    .build()
                val id = if (requestedId != 0) requestedId else (10_000 + (seq.incrementAndGet() % 100_000)).toInt()
                nm.notify(id, n)
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return err("DROID_TIMEOUT", "发送通知超时")
        }
        failure?.let { return err("DROID_ERROR", it.message ?: "发送通知失败") }
        return ok(null)
    }

    // ------------------------------------------------------------------ 定位（完全权限）

    private fun locate(ctx: Context, args: JSONObject): String {
        val timeoutS = args.optInt("timeout", LOCATE_DEFAULT_TIMEOUT_S)
            .coerceIn(5, LOCATE_MAX_TIMEOUT_S)
        if (!ensureLocationPermission()) {
            return err("DROID_DENIED", "定位权限被拒绝")
        }
        val app = ctx.applicationContext ?: ctx
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return err("DROID_UNSUPPORTED", "设备不支持定位")
        val last = bestLastKnown(lm)
        // 先试最近一次定位（够新就直接回，不额外等 30s）
        if (last != null && last.time > 0 && System.currentTimeMillis() - last.time < 10L * 60_000L) {
            return ok(locationJson(last))
        }
        val latch = CountDownLatch(1)
        var best: Location? = last
        var callbackAt = 0L
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (callbackAt == 0L || location.time >= callbackAt) {
                    callbackAt = location.time
                    best = if (best == null || location.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) location else best
                }
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            return best?.let { ok(locationJson(it)) } ?: err("DROID_UNSUPPORTED", "定位服务未开启")
        }
        runOnMain {
            providers.forEach { provider ->
                runCatching {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure { Log.w(TAG, "locate request failed: $provider", it) }
            }
        }
        val got = latch.await(timeoutS.toLong() * 1_000L, TimeUnit.MILLISECONDS)
        runOnMain { runCatching { lm.removeUpdates(listener) } }
        val result = best
        return when {
            result != null -> ok(locationJson(result))
            got -> err("DROID_TIMEOUT", "定位回调无有效坐标")
            else -> err("DROID_TIMEOUT", "定位超时（$timeoutS s）")
        }
    }

    private fun locationJson(loc: Location): JSONObject = JSONObject()
        .put("lat", loc.latitude)
        .put("lng", loc.longitude)
        .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)

    private fun bestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            if (best == null || loc.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) best = loc
        }
        return best
    }

    private fun ensureLocationPermission(): Boolean {
        val ctx = context() ?: return false
        fun granted(): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted()) return true
        ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        // 用户可能只给了「大致位置」（COARSE）：授权结果回调 false 但 coarse 已授予，这里复查一次
        return granted()
    }

    // ------------------------------------------------------------------ 相册 / 相机（完全权限）

    /** 系统 Photo Picker 选一张图 → 复制到会话工作区 */
    private fun pickPhoto(ctx: Context, conversationId: String): String {
        val act = activity() ?: return err("DROID_UNSUPPORTED", "没有前台 Activity，无法打开相册")
        val latch = CountDownLatch(1)
        var uri: Uri? = null
        var launchFailed = false
        val key = "droid_pick_${seq.incrementAndGet()}"
        var pickLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
        runOnMain {
            try {
                val launcher: ActivityResultLauncher<PickVisualMediaRequest> =
                    act.activityResultRegistry.register(
                        key,
                        act,
                        ActivityResultContracts.PickVisualMedia()
                    ) { picked: Uri? ->
                        uri = picked
                        latch.countDown()
                    }
                pickLauncher = launcher
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "pick_photo launch failed", t)
                launchFailed = true
                latch.countDown()
            }
        }
        val done = latch.await(MEDIA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runOnMain { runCatching { pickLauncher?.unregister() } }
        if (!done) return err("DROID_TIMEOUT", "等待相册选择超时（115s）")
        if (launchFailed) return err("DROID_UNSUPPORTED", "无法打开系统相册")
        val picked = uri ?: return err("DROID_CANCELLED", "用户取消了相册选择")
        val app = ctx.applicationContext ?: ctx
        return try {
            val saved = copyUriToWorkspace(app, conversationId, picked)
            val mime = detectMime(app, picked, saved.file.name)
            ok(
                JSONObject()
                    .put("path", saved.file.absolutePath)
                    .put("name", saved.file.name)
                    .put("mime", mime)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "pick_photo import failed", t)
            err("DROID_ERROR", "保存相册图片失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 系统相机拍照 → 临时文件 → 复制到会话工作区 */
    private fun takePhoto(ctx: Context, conversationId: String): String {
        val app = ctx.applicationContext ?: ctx
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return err("DROID_UNSUPPORTED", "设备没有相机")
        }
        val act = activity() ?: return err("DROID_UNSUPPORTED", "没有前台 Activity，无法打开相机")
        // 说明：系统相机 App（ACTION_IMAGE_CAPTURE/TakePicture）本身不需要 CAMERA 权限；
        // 仅当 manifest 声明了 CAMERA 时按规范申请运行时权限（未声明时跳过，避免必然拒绝）。
        if (isPermissionDeclared(app, Manifest.permission.CAMERA)) {
            if (!ensurePermission(Manifest.permission.CAMERA, CAMERA_PERMISSION_TIMEOUT_MS)) {
                return err("DROID_DENIED", "相机权限被拒绝")
            }
        }
        val dir = File(app.cacheDir, "camera").also { it.mkdirs() }
        val temp = File(dir, "droid_photo_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", temp)
        } catch (t: Throwable) {
            temp.delete()
            return err("DROID_ERROR", "创建相机临时文件失败：${t.message}")
        }
        val latch = CountDownLatch(1)
        var success = false
        var launchFailed = false
        val key = "droid_camera_${seq.incrementAndGet()}"
        var shotLauncher: ActivityResultLauncher<Uri>? = null
        runOnMain {
            try {
                val launcher: ActivityResultLauncher<Uri> = act.activityResultRegistry.register(
                    key,
                    act,
                    ActivityResultContracts.TakePicture()
                ) { okShot: Boolean ->
                    success = okShot
                    latch.countDown()
                }
                shotLauncher = launcher
                launcher.launch(uri)
            } catch (t: Throwable) {
                Log.w(TAG, "take_photo launch failed", t)
                launchFailed = true
                latch.countDown()
            }
        }
        val done = latch.await(CAMERA_MEDIA_WAIT_MS, TimeUnit.MILLISECONDS)
        runOnMain { runCatching { shotLauncher?.unregister() } }
        if (!done) {
            temp.delete()
            return err("DROID_TIMEOUT", "等待拍照超时（100s）")
        }
        if (launchFailed) {
            temp.delete()
            return err("DROID_UNSUPPORTED", "无法打开系统相机")
        }
        if (!success || !temp.isFile || temp.length() <= 0L) {
            temp.delete()
            return err("DROID_CANCELLED", "用户取消了拍照")
        }
        return try {
            val name = "photo_${System.currentTimeMillis()}.jpg"
            val file = copyFileToWorkspace(app, conversationId, temp, name)
            val mime = detectMime(app, null, file.name)
            ok(
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("name", file.name)
                    .put("mime", mime)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "take_photo import failed", t)
            err("DROID_ERROR", "保存相机照片失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------ 运行时权限

    /**
     * 主线程申请一个运行时权限并阻塞等待结果。
     * 当前线程是主线程时无法阻塞等待（会死锁）→ 直接返回 false（调用方给 DENIED）。
     */
    private fun ensurePermission(permission: String, timeoutMs: Long = 60_000L): Boolean {
        val ctx = context() ?: return false
        if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "ensurePermission on main thread, refuse: $permission")
            return false
        }
        val act = activity() ?: return false
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val key = "droid_perm_${seq.incrementAndGet()}"
        var permLauncher: ActivityResultLauncher<String>? = null
        var launchFailed = false
        runOnMain {
            try {
                val launcher: ActivityResultLauncher<String> = act.activityResultRegistry.register(
                    key,
                    act,
                    ActivityResultContracts.RequestPermission()
                ) { result: Boolean ->
                    granted.set(result)
                    latch.countDown()
                }
                permLauncher = launcher
                launcher.launch(permission)
            } catch (t: Throwable) {
                Log.w(TAG, "permission request failed: $permission", t)
                launchFailed = true
                latch.countDown()
            }
        }
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runOnMain { runCatching { permLauncher?.unregister() } }
        if (!done || launchFailed) return false
        return granted.get() ||
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPermissionDeclared(ctx: Context, permission: String): Boolean = runCatching {
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_PERMISSIONS
        )
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 文件复制

    private data class SavedMedia(val file: File)

    /** 复制 content Uri 到会话工作区 `workspaces/<conv>/droid/` */
    private fun copyUriToWorkspace(ctx: Context, conversationId: String, uri: Uri): SavedMedia {
        val displayName = queryDisplayName(ctx, uri)
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        val fallbackExt = extForMime(mime)
        val preferred = displayName?.takeIf { it.isNotBlank() }
            ?: "picked_${System.currentTimeMillis()}$fallbackExt"
        val target = allocateWorkspaceFile(ctx, conversationId, preferred)
        try {
            val input: InputStream = ctx.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取图片内容")
            input.use { ins ->
                target.outputStream().use { outs -> copyLimited(ins, outs) }
            }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        return SavedMedia(target)
    }

    /** 复制本地文件到会话工作区 */
    private fun copyFileToWorkspace(
        ctx: Context,
        conversationId: String,
        source: File,
        preferredName: String
    ): File {
        val target = allocateWorkspaceFile(ctx, conversationId, preferredName)
        try {
            source.inputStream().use { ins ->
                target.outputStream().use { outs -> copyLimited(ins, outs) }
            }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        return target
    }

    private fun allocateWorkspaceFile(ctx: Context, conversationId: String, preferredName: String): File {
        val root = File(FileTools.workspaceDir(ctx, conversationId), "droid").also { it.mkdirs() }
        val safe = sanitizeFileName(preferredName)
        var candidate = File(root, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (i < 1_000) {
            candidate = File(root, "${stem}_$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(root, "${stem}_${System.currentTimeMillis()}$ext")
    }

    private fun sanitizeFileName(raw: String): String {
        val name = raw.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").trim('_', '.', ' ')
        return name.take(64).ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun copyLimited(input: InputStream, output: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > COPY_LIMIT_BYTES) throw IOException("文件超过 64MB 上限")
            output.write(buf, 0, n)
        }
        output.flush()
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun detectMime(ctx: Context, uri: Uri?, name: String): String {
        val fromUri = uri?.let { runCatching { ctx.contentResolver.getType(it) }.getOrNull() }
        if (!fromUri.isNullOrBlank()) return fromUri
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            else -> "image/*"
        }
    }

    private fun extForMime(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic", "image/heif" -> ".heic"
        "image/bmp" -> ".bmp"
        else -> ".jpg"
    }

    // ------------------------------------------------------------------ JSON / 线程小工具

    private fun ok(result: Any?): String = JSONObject()
        .put("ok", true)
        .put("result", result ?: JSONObject.NULL)
        .toString()

    private fun err(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("error", message.take(500))
        .toString()

    /** 主线程执行；已在主线程则直接执行（避免无谓 post 带来的时序问题） */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
