package com.dchat.app.data.tools

import android.os.SystemClock

/**
 * 第三期：客户端工具的健康状态（内存态，按会话隔离，不落盘）。
 *
 * - 同一工具连续失败 3 次 → 本次会话内暂停该工具；
 * - 成功一次就清零连续失败计数并恢复；
 * - 暂停后带冷却探测：首次暂停 60s 后放行一次，成功恢复；探测再失败退避到 5min；
 * - 设置里把工具关掉再打开会调用 [clearTool] 立即恢复。
 */
object ToolHealth {
    private const val PAUSE_AFTER_FAILURES = 3
    private const val FIRST_COOLDOWN_MS = 60_000L
    private const val MAX_COOLDOWN_MS = 5 * 60_000L

    private data class PauseState(val pausedAt: Long, val backoffMs: Long)

    private val lock = Any()
    /** convId -> (toolId -> 连续失败次数) */
    private val failures = HashMap<String, HashMap<String, Int>>()
    /** "$convId|$toolId" -> 暂停时间/退避 */
    private val paused = HashMap<String, PauseState>()

    private fun key(conversationId: String, toolId: String): String = "$conversationId|$toolId"

    /** true = 仍在校验冷却期；冷却到期后返回 false，放行一次探测 */
    fun isPaused(conversationId: String, toolId: String): Boolean {
        synchronized(lock) {
            val state = paused[key(conversationId, toolId)] ?: return false
            return SystemClock.elapsedRealtime() - state.pausedAt < state.backoffMs
        }
    }

    /** 剩余冷却秒数（向上取整；不在暂停态返回 0） */
    fun retryInSeconds(conversationId: String, toolId: String): Long {
        synchronized(lock) {
            val state = paused[key(conversationId, toolId)] ?: return 0L
            val left = state.backoffMs - (SystemClock.elapsedRealtime() - state.pausedAt)
            return if (left <= 0) 0L else (left + 999) / 1000
        }
    }

    /** 成功一次：清零连续失败计数并彻底恢复 */
    fun recordSuccess(conversationId: String, toolId: String) {
        synchronized(lock) {
            failures[conversationId]?.remove(toolId)
            paused.remove(key(conversationId, toolId))
        }
    }

    /**
     * 失败一次；达到 3 次时进入暂停/退避。
     * @return true = 本次失败触发了暂停（调用方应提示）
     */
    fun recordFailure(conversationId: String, toolId: String): Boolean {
        synchronized(lock) {
            val map = failures.getOrPut(conversationId) { HashMap() }
            val count = (map[toolId] ?: 0) + 1
            map[toolId] = count
            if (count >= PAUSE_AFTER_FAILURES) {
                val k = key(conversationId, toolId)
                val prev = paused[k]
                val backoff = if (prev == null) FIRST_COOLDOWN_MS else MAX_COOLDOWN_MS
                paused[k] = PauseState(SystemClock.elapsedRealtime(), backoff)
                return true
            }
            return false
        }
    }

    fun failureCount(conversationId: String, toolId: String): Int {
        synchronized(lock) {
            return failures[conversationId]?.get(toolId) ?: 0
        }
    }

    /** 设置里关→开时立即恢复该工具（所有会话） */
    fun clearTool(toolId: String) {
        synchronized(lock) {
            val suffix = "|$toolId"
            paused.keys.removeAll { it.endsWith(suffix) }
            failures.values.forEach { it.remove(toolId) }
        }
    }

    /** 会话删除时可清理 */
    fun clearConversation(conversationId: String) {
        synchronized(lock) {
            failures.remove(conversationId)
            paused.keys.removeAll { it.startsWith("$conversationId|") }
        }
    }

    /** 删除所有数据时清理全部健康状态 */
    fun clearAll() {
        synchronized(lock) {
            failures.clear()
            paused.clear()
        }
    }
}
