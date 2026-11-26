package com.dchat.app.data.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 第四期 4c：定时任务 `schedule.*`。
 *
 * - 数量**不设上限**；
 * - 用 `AlarmManager.setAndAllowWhileIdle`（非精确、免 `SCHEDULE_EXACT_ALARM` 权限）实现，
 *   最小间隔强制 15 分钟；支持 `intervalMinutes` 与 `dailyAt`（HH:mm）两种；
 * - 定义持久化到 `filesDir/schedules.json`，`BOOT_COMPLETED` 后由 [ScheduleReceiver] 恢复注册；
 * - 触发后把动作交给 [BgTasks]（复用同一套受控动作白名单与并发/通知机制）。
 *
 * 说明：任务书建议 WorkManager periodic，但本工程禁止新增第三方依赖（WorkManager 不在依赖表里），
 * 因此用系统 AlarmManager 达到同样的「免权限 + 非精确 + 最小 15 分钟」语义。
 */
object ScheduleStore {

    private const val TAG = "ScheduleStore"
    private const val FILE_NAME = "schedules.json"
    const val MIN_INTERVAL_MINUTES = 15
    const val ACTION_TRIGGER = "com.dchat.app.action.SCHEDULE_TRIGGER"
    const val EXTRA_ID = "schedule_id"

    data class ScheduleRecord(
        val id: String,
        val name: String,
        val action: String,
        val paramsJson: String,
        val intervalMinutes: Int,
        val dailyAt: String,
        val nextAt: Long,
        val lastRunAt: Long,
        val createdAt: Long,
        val conversationId: String,
        /** 启停开关：false 时注销闹钟、不再触发（任务定义保留） */
        val enabled: Boolean = true,
        /** 最近一次触发/立即运行派生的后台任务 id（UI 用来展示「上次结果」） */
        val lastTaskId: String = ""
    )

    private val lock = Any()
    private val schedules = LinkedHashMap<String, ScheduleRecord>()

    @Volatile
    private var appContext: Context? = null
    private var file: File? = null

    @Volatile
    private var initialized = false

    // ---------------------------------------------------------------- 初始化 / 持久化

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext ?: context
            appContext = app
            val f = File(app.filesDir, FILE_NAME)
            file = f
            loadLocked(f)
            initialized = true
        }
    }

    private fun loadLocked(f: File) {
        schedules.clear()
        if (!f.isFile) return
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("schedules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                schedules[id] = ScheduleRecord(
                    id = id,
                    name = o.optString("name"),
                    action = o.optString("action"),
                    paramsJson = o.optString("params", "{}"),
                    intervalMinutes = o.optInt("intervalMinutes", 0),
                    dailyAt = o.optString("dailyAt"),
                    nextAt = o.optLong("nextAt"),
                    lastRunAt = o.optLong("lastRunAt"),
                    createdAt = o.optLong("createdAt"),
                    conversationId = o.optString("conversationId"),
                    enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else true,
                    lastTaskId = o.optString("lastTaskId")
                )
            }
        }.onFailure { Log.w(TAG, "读取 schedules.json 失败", it) }
    }

    private fun persistLocked() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            schedules.values.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("action", s.action)
                        .put("params", s.paramsJson)
                        .put("intervalMinutes", s.intervalMinutes)
                        .put("dailyAt", s.dailyAt)
                        .put("nextAt", s.nextAt)
                        .put("lastRunAt", s.lastRunAt)
                        .put("createdAt", s.createdAt)
                        .put("conversationId", s.conversationId)
                        .put("enabled", s.enabled)
                        .put("lastTaskId", s.lastTaskId)
                )
            }
            val text = JSONObject().put("version", 1).put("schedules", arr).toString()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(text)
            if (!part.renameTo(f)) {
                f.delete()
                if (!part.renameTo(f)) {
                    f.writeText(text)
                    part.delete()
                }
            }
        }.onFailure { Log.w(TAG, "写入 schedules.json 失败", it) }
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---------------------------------------------------------------- 闹钟

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context.applicationContext ?: context, ScheduleReceiver::class.java)
            .setAction(ACTION_TRIGGER)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context.applicationContext ?: context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun armLocked(context: Context, s: ScheduleRecord) {
        if (!s.enabled) {
            disarmLocked(context, s.id)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = s.nextAt.coerceAtLeast(now() + 5_000L)
        runCatching {
            // 非精确闹钟：不需要 SCHEDULE_EXACT_ALARM，Doze 下允许唤醒
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, s.id))
        }.onFailure { Log.w(TAG, "注册定时任务失败: ${s.id}", it) }
    }

    private fun disarmLocked(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context, id)) }
    }

    private fun rearmAll() {
        val ctx = appContext ?: return
        synchronized(lock) { schedules.values.forEach { armLocked(ctx, it) } }
    }

    /** App 启动后调用：把持久化的定时任务重新挂到 AlarmManager */
    fun restoreOnStartup(context: Context) {
        init(context)
        rearmAll()
    }

    /** BOOT_COMPLETED 后调用：重算错过的下一次并重新注册 */
    fun onBoot(context: Context) {
        init(context)
        val ctx = context.applicationContext ?: context
        synchronized(lock) {
            val t = now()
            schedules.forEach { (id, s) ->
                val next = if (s.nextAt <= t) computeNextAt(s, t) else s.nextAt
                schedules[id] = s.copy(nextAt = next)
                armLocked(ctx, schedules[id]!!)
            }
            persistLocked()
        }
    }

    /** 闹钟触发（[ScheduleReceiver] 调用）：先排下一次再执行，保证不会因为动作异常停掉周期 */
    fun onAlarm(context: Context, id: String) {
        init(context)
        val ctx = context.applicationContext ?: context
        val record = synchronized(lock) {
            val s = schedules[id] ?: return
            // 已停用（或刚被 UI 停用）的任务，残留闹钟直接忽略，不再排下一次
            if (!s.enabled) return
            val t = now()
            val next = computeNextAt(s, t)
            val updated = s.copy(nextAt = next, lastRunAt = t)
            schedules[id] = updated
            persistLocked()
            armLocked(ctx, updated)
            updated
        }
        if (!BgTasks.isAllowedAction(record.action)) {
            Log.w(TAG, "定时任务 action 不在白名单，跳过: ${record.action}")
            return
        }
        BgTasks.init(ctx)
        val params = runCatching { JSONObject(record.paramsJson) }.getOrDefault(JSONObject())
        val task = BgTasks.submit(ctx, record.conversationId, record.action, params) ?: return
        synchronized(lock) {
            schedules[record.id]?.let { schedules[record.id] = it.copy(lastTaskId = task.id) }
            persistLocked()
        }
    }

    // ---------------------------------------------------------------- 任务管理界面（按会话）

    /** 当前会话创建的定时任务（UI 列表用）：按创建时间正序 */
    fun listFor(conversationId: String): List<ScheduleRecord> = synchronized(lock) {
        schedules.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
    }

    /** 当前会话的定时任务数量（会话页「⋮」菜单角标用） */
    fun countFor(conversationId: String): Int = synchronized(lock) {
        schedules.values.count { it.conversationId == conversationId }
    }

    /** 删除会话时：移除该会话的全部定时任务并注销闹钟 */
    fun removeForConversation(context: Context, conversationId: String): Int {
        init(context)
        val ids = synchronized(lock) {
            schedules.values.filter { it.conversationId == conversationId }.map { it.id }
        }
        ids.forEach { remove(context, it) }
        return ids.size
    }

    /** 删除所有数据时：移除全部定时任务并注销闹钟 */
    fun removeAll(context: Context): Int {
        init(context)
        val ids = synchronized(lock) { schedules.keys.toList() }
        ids.forEach { remove(context, it) }
        return ids.size
    }

    /**
     * 编辑定时任务（名称 / 参数 / 周期 / 每天时间 / 启停）。参数为 null 表示保持原值；
     * `dailyAt` 传空串表示改为 intervalMinutes 周期模式。
     * @return 更新后的记录；不存在或参数非法（JSON 之外的周期/时间不合法）时 null
     */
    fun update(
        context: Context,
        id: String,
        name: String? = null,
        paramsJson: String? = null,
        intervalMinutes: Int? = null,
        dailyAt: String? = null,
        enabled: Boolean? = null
    ): ScheduleRecord? {
        init(context)
        val ctx = context.applicationContext ?: context
        val t = now()
        var out: ScheduleRecord? = null
        synchronized(lock) {
            val s = schedules[id] ?: return null
            val newDaily = if (dailyAt != null) dailyAt.trim() else s.dailyAt
            val newInterval = intervalMinutes ?: s.intervalMinutes
            if (newDaily.isNotBlank()) {
                val okTime = newDaily.matches(Regex("^\\d{2}:\\d{2}$")) &&
                    runCatching { LocalTime.parse(newDaily) }.getOrNull() != null
                if (!okTime) return null
            } else if (newInterval < MIN_INTERVAL_MINUTES) {
                return null
            }
            val base = s.copy(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: s.name,
                paramsJson = paramsJson ?: s.paramsJson,
                intervalMinutes = if (newDaily.isNotBlank()) 1440 else newInterval,
                dailyAt = newDaily,
                enabled = enabled ?: s.enabled
            )
            val updated = base.copy(nextAt = computeNextAt(base, t))
            schedules[id] = updated
            persistLocked()
            if (updated.enabled) armLocked(ctx, updated) else disarmLocked(ctx, id)
            out = updated
        }
        return out
    }

    /** 删除一个定时任务（UI 用，同时注销闹钟） */
    fun remove(context: Context, id: String): Boolean {
        init(context)
        val ctx = context.applicationContext ?: context
        var ok = false
        synchronized(lock) {
            if (schedules.remove(id) != null) {
                disarmLocked(ctx, id)
                persistLocked()
                ok = true
            }
        }
        return ok
    }

    /** 立即运行一次（走 BgTasks，受控动作白名单与并发/通知不变） */
    fun runNow(context: Context, id: String): BgTasks.TaskRecord? {
        init(context)
        val rec = synchronized(lock) { schedules[id] } ?: return null
        if (!BgTasks.isAllowedAction(rec.action)) return null
        BgTasks.init(context)
        val params = runCatching { JSONObject(rec.paramsJson) }.getOrDefault(JSONObject())
        val task = BgTasks.submit(context, rec.conversationId, rec.action, params) ?: return null
        synchronized(lock) {
            schedules[id]?.let { schedules[id] = it.copy(lastRunAt = now(), lastTaskId = task.id) }
            persistLocked()
        }
        return task
    }

    private fun computeNextAt(s: ScheduleRecord, from: Long): Long {
        if (s.dailyAt.isNotBlank()) {
            val time = runCatching { LocalTime.parse(s.dailyAt) }.getOrNull()
            if (time != null) {
                val zone = ZonedDateTime.now().zone
                var candidate = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), zone)
                    .withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
                if (candidate.toInstant().toEpochMilli() <= from) candidate = candidate.plusDays(1)
                return candidate.toInstant().toEpochMilli()
            }
        }
        val minutes = s.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        return from + minutes * 60_000L
    }

    // ---------------------------------------------------------------- 工具入口

    /** schedule.create / schedule.list / schedule.cancel 总入口；不是 schedule.* 返回 null */
    fun execute(context: Context, toolId: String, argsJson: String, conversationId: String?): PyResult? {
        if (!toolId.startsWith("schedule.")) return null
        init(context)
        val n = Lang.current.local
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return PyResult(ok = false, errorCode = "INVALID_ARGS", errorMessage = n.fsInvalidArgs)
        val convId = conversationId ?: "preview"
        val ctx = context.applicationContext ?: context
        return when (toolId) {
            "schedule.create" -> {
                val name = args.optString("name").trim()
                val action = args.optString("action").trim()
                if (!BgTasks.isAllowedAction(action)) {
                    return PyResult(ok = false, errorCode = "INVALID_ARGS", errorMessage = n.bgActionNotAllowed)
                }
                val params = args.optJSONObject("params") ?: JSONObject()
                val dailyAt = args.optString("daily_at", args.optString("dailyAt", "")).trim()
                val intervalRaw = args.optInt("interval_minutes", args.optInt("intervalMinutes", 0))
                if (dailyAt.isBlank() && intervalRaw <= 0) {
                    return PyResult(ok = false, errorCode = "INVALID_ARGS", errorMessage = n.scheduleNeedInterval)
                }
                if (dailyAt.isNotBlank()) {
                    val time = runCatching { LocalTime.parse(dailyAt) }.getOrNull()
                        ?: return PyResult(
                            ok = false, errorCode = "INVALID_ARGS",
                            errorMessage = n.scheduleBadDailyAt
                        )
                    // 兼容 8:30 这类写法
                    if (!dailyAt.matches(Regex("^\\d{2}:\\d{2}$"))) {
                        return PyResult(
                            ok = false, errorCode = "INVALID_ARGS",
                            errorMessage = n.scheduleBadDailyAt
                        ).also { Log.w(TAG, "dailyAt 非 HH:mm: $dailyAt ${time.hour}:${time.minute}") }
                    }
                }
                val interval = if (dailyAt.isNotBlank()) 1440 else intervalRaw
                if (interval < MIN_INTERVAL_MINUTES) {
                    return PyResult(
                        ok = false, errorCode = "INVALID_ARGS",
                        errorMessage = Lang.current.format(n.scheduleMinInterval, MIN_INTERVAL_MINUTES)
                    )
                }
                val t = now()
                val record = ScheduleRecord(
                    id = "sch_" + t.toString(36) + "_" + (schedules.size + 1),
                    name = name.ifBlank { action },
                    action = action,
                    paramsJson = params.toString(),
                    intervalMinutes = interval,
                    dailyAt = dailyAt,
                    nextAt = 0L,
                    lastRunAt = 0L,
                    createdAt = t,
                    conversationId = convId
                )
                val withNext = record.copy(nextAt = computeNextAt(record, t))
                synchronized(lock) {
                    schedules[withNext.id] = withNext
                    persistLocked()
                    armLocked(ctx, withNext)
                }
                PyResult(
                    ok = true,
                    output = JSONObject()
                        .put("schedule_id", withNext.id)
                        .put("name", withNext.name)
                        .put("action", withNext.action)
                        .put("interval_minutes", withNext.intervalMinutes)
                        .put("daily_at", withNext.dailyAt)
                        .put("next_at", withNext.nextAt)
                        .put("note", Lang.current.format(n.scheduleCreated, withNext.name))
                        .toString()
                )
            }

            "schedule.list" -> {
                val arr = JSONArray()
                // 只回当前会话创建的任务，避免模型跨会话看到别人的定时任务
                listFor(convId).forEach { s ->
                    arr.put(
                        JSONObject()
                            .put("schedule_id", s.id)
                            .put("name", s.name)
                            .put("action", s.action)
                            .put("params", s.paramsJson.take(300))
                            .put("interval_minutes", s.intervalMinutes)
                            .put("daily_at", s.dailyAt)
                            .put("next_at", s.nextAt)
                            .put("last_run_at", s.lastRunAt)
                            .put("enabled", s.enabled)
                    )
                }
                PyResult(ok = true, output = JSONObject().put("schedules", arr).toString())
            }

            "schedule.cancel" -> {
                val id = args.optString("schedule_id", args.optString("id", "")).trim()
                if (id.isEmpty()) {
                    return PyResult(ok = false, errorCode = "INVALID_ARGS", errorMessage = n.fsInvalidArgs)
                }
                // 归属校验：只能取消当前会话创建的任务（fail-closed）
                val owner = synchronized(lock) { schedules[id]?.conversationId }
                if (owner != null && owner != convId) {
                    return PyResult(
                        ok = false, errorCode = "PERMISSION_DENIED",
                        errorMessage = n.notOwned
                    )
                }
                val removed = remove(ctx, id)
                if (!removed) {
                    PyResult(
                        ok = false, errorCode = "SCHEDULE_NOT_FOUND",
                        errorMessage = Lang.current.format(n.scheduleNotFound, id)
                    )
                } else {
                    PyResult(ok = true, output = JSONObject().put("schedule_id", id).put("cancelled", true).toString())
                }
            }

            else -> null
        }
    }

    /** 设置页用的只读快照（恢复/展示） */
    fun list(): List<ScheduleRecord> = synchronized(lock) { schedules.values.toList() }
}

/**
 * 定时任务广播接收器：
 * - `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`：恢复注册；
 * - [ScheduleStore.ACTION_TRIGGER]：闹钟触发，交给 [BgTasks] 执行受控动作。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { ScheduleStore.onBoot(context) }
                    .onFailure { Log.w("ScheduleReceiver", "BOOT_COMPLETED 恢复定时任务失败", it) }
                runCatching { BgTasks.init(context) }
            }

            ScheduleStore.ACTION_TRIGGER -> {
                val id = intent.getStringExtra(ScheduleStore.EXTRA_ID) ?: return
                runCatching { ScheduleStore.onAlarm(context, id) }
                    .onFailure { Log.w("ScheduleReceiver", "定时任务触发失败: $id", it) }
            }
        }
    }
}
