package com.dchat.app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * DeepSeek 官方定价 + 本地用量聚合（纯 Kotlin，无第三方依赖）。
 *
 * 价格来源（2026-11-25 查证）：https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
 *   - deepseek-flash  ：缓存命中输入 0.04 / 2（高峰）；缓存未命中输入 2 / 1；输出 8 / 4
 *   - deepseek-v4-pro ：缓存命中输入 0.30 / 0.15；缓存未命中输入 9 / 4.5；输出 27 / 13.5
 * 单位均为「元 / 百万 tokens」，每组第一项为高峰价、第二项为空闲价。
 * 官方规则：北京时间周一至周五（不含法定节假日）9:00–12:00、14:00–18:00 为高峰，
 * 其余时段（含周末）为空闲，空闲价 = 高峰价的一半。
 *
 * 说明：这里没有内置中国法定节假日日历，节假日会按「工作日的高峰/空闲时刻」估算，
 * 可能略微高估；需要精确时可在此处补节日表或直接改成单一价格。
 */

/** 一个价格档：元 / 百万 tokens */
data class ModelPrice(
    val cacheHitInput: Double,
    val cacheMissInput: Double,
    val output: Double
)

/** 同一模型的高峰 / 空闲两套价格 */
data class PriceBook(val peak: ModelPrice, val idle: ModelPrice)

/** 一次回复的估算结果；priced=false 表示模型不在价表里，按 Flash 兜底价估算 */
data class CostEstimate(val cost: Double, val priced: Boolean)

object Pricing {

    private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    /** deepseek-flash（含旧名 deepseek-v4-flash*，官方按 Flash 计费） */
    val FLASH = PriceBook(
        peak = ModelPrice(cacheHitInput = 0.04, cacheMissInput = 2.0, output = 8.0),
        idle = ModelPrice(cacheHitInput = 0.02, cacheMissInput = 1.0, output = 4.0)
    )

    /** deepseek-v4-pro（Anthropic 协议下 claude-opus* 映射到此，按 Pro 计费） */
    val PRO = PriceBook(
        peak = ModelPrice(cacheHitInput = 0.30, cacheMissInput = 9.0, output = 27.0),
        idle = ModelPrice(cacheHitInput = 0.15, cacheMissInput = 4.5, output = 13.5)
    )

    /** 未知模型兜底用的价格（Flash 档），UI 会标注「估算」，不冒充准确值 */
    val FALLBACK: PriceBook = FLASH

    /** 官方高峰时段判断（北京时间，周一至周五 9-12、14-18，不含节假日） */
    fun isPeak(atMillis: Long): Boolean {
        val t = Instant.ofEpochMilli(atMillis).atZone(BEIJING)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val minutes = t.hour * 60 + t.minute
        return minutes in 9 * 60 until 12 * 60 || minutes in 14 * 60 until 18 * 60
    }

    /** 模型名 → 价目；第二项表示是否命中官方价表 */
    fun priceBookFor(model: String): Pair<PriceBook, Boolean> {
        val m = model.trim().lowercase(Locale.US)
        return when {
            m.contains("v4-pro") || m.startsWith("deepseek-v4-pro") || m.startsWith("claude-opus") ->
                PRO to true
            m.contains("flash") || m.startsWith("claude-haiku") || m.startsWith("claude-sonnet") ->
                FLASH to true
            else -> FALLBACK to false
        }
    }

    /**
     * 估算一次回复的花费（元）。
     * @param promptTokens 总输入 tokens（含缓存命中）
     * @param cacheReadTokens promptTokens 中命中缓存的部分
     */
    fun estimate(
        model: String,
        promptTokens: Long,
        cacheReadTokens: Long,
        completionTokens: Long,
        atMillis: Long
    ): CostEstimate {
        val (book, priced) = priceBookFor(model)
        val price = if (isPeak(atMillis)) book.peak else book.idle
        val cached = cacheReadTokens.coerceIn(0L, promptTokens)
        val uncached = promptTokens - cached
        val cost = uncached / 1_000_000.0 * price.cacheMissInput +
            cached / 1_000_000.0 * price.cacheHitInput +
            completionTokens.coerceAtLeast(0L) / 1_000_000.0 * price.output
        return CostEstimate(cost, priced)
    }
}

// ---------------- 聚合结果 ----------------

data class UsageTotals(
    val calls: Int = 0,
    val promptTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cost: Double = 0.0
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val cacheMissInputTokens: Long get() = (promptTokens - cacheReadTokens).coerceAtLeast(0L)

    /**
     * 缓存命中率 = 命中缓存的输入 / 总输入（含命中部分），0..1。
     * 没有输入（无记录 / 空会话）时返回 0，UI 负责显示空态，不会出现 NaN。
     */
    val cacheHitRate: Double get() = if (promptTokens <= 0L) 0.0 else cacheReadTokens.toDouble() / promptTokens
}

data class ModelUsage(
    val model: String,
    val calls: Int,
    val promptTokens: Long,
    val cacheReadTokens: Long,
    val completionTokens: Long,
    val cost: Double
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

data class DayUsage(
    val date: LocalDate,
    /** 地区化的短日期（MM-dd / dd/MM / MM.dd），用于横轴和每日明细 */
    val label: String,
    /** 当天总量 = 输入（含缓存命中）+ 输出，保留旧字段给其它调用方 */
    val tokens: Long,
    val cost: Double,
    /** 当天输入里命中缓存的 tokens */
    val cacheHitInput: Long = 0L,
    /** 当天输入里未命中缓存的 tokens = 总输入 - 命中 */
    val cacheMissInput: Long = 0L,
    /** 当天输出 tokens */
    val completion: Long = 0L,
    /** 当天已记录 usage 的回复数（请求数） */
    val calls: Int = 0
) {
    /** 当天总输入 tokens（含缓存命中） */
    val promptTokens: Long get() = cacheHitInput + cacheMissInput

    /** 当天级缓存命中率 = 命中 / 总输入；没有输入时返回 0，不产生 NaN */
    val cacheHitRate: Double get() = if (promptTokens <= 0L) 0.0 else cacheHitInput.toDouble() / promptTokens
}

/** 当前会话的费用构成（元） */
data class CostBreakdown(
    val cacheHitInputCost: Double = 0.0,
    val cacheMissInputCost: Double = 0.0,
    val outputCost: Double = 0.0
) {
    val total: Double get() = cacheHitInputCost + cacheMissInputCost + outputCost
}

data class UsageReport(
    val sessionTitle: String,
    /** 当前会话（只含本机记录过 usage 的 AI 回复） */
    val session: UsageTotals,
    val sessionCost: CostBreakdown,
    /** 全部本地会话 */
    val all: UsageTotals,
    val models: List<ModelUsage>,
    /** 最近 30 天，旧 → 新，最后一项是今天 */
    val days: List<DayUsage>,
    /** 最近 7 天（含今天）0-23 点每小时的 total tokens */
    val hours: List<Long>,
    /** 价表里没有、按 Flash 兜底估算的模型名 */
    val unpricedModels: List<String>
)

object UsageStats {

    const val DAYS = 30
    const val HOUR_DAYS = 7

    /** 从全部本地会话聚合统计；只统计 fromMe=false 且记录过 tokens 的回复 */
    fun build(
        conversations: List<Conversation>,
        currentId: String?,
        nowMillis: Long = System.currentTimeMillis(),
        /** 横轴日期文案（地区相关）；默认 MM-dd */
        dayLabel: (LocalDate) -> String = { d -> String.format(Locale.US, "%02d-%02d", d.monthValue, d.dayOfMonth) }
    ): UsageReport {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val firstDay = today.minusDays(DAYS - 1L)
        val hourCutoff = today.minusDays(HOUR_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessionTitle = conversations.firstOrNull { it.id == currentId }?.title.orEmpty()

        val dayAcc = HashMap<LocalDate, DayAcc>()
        val hours = LongArray(24)
        var all = UsageTotals()
        var session = UsageTotals()
        var sessionBreakdown = CostBreakdown()
        val models = LinkedHashMap<String, ModelAcc>()
        val unpriced = LinkedHashSet<String>()

        for (conv in conversations) {
            val isSession = conv.id == currentId
            for (m in conv.messages) {
                if (m.fromMe) continue
                val prompt = m.promptTokens.toLong().coerceAtLeast(0L)
                val completion = m.completionTokens.toLong().coerceAtLeast(0L)
                if (prompt + completion <= 0L) continue // 没记录用量的消息（旧数据 / 本地模拟）不参与统计
                val cacheRead = m.cacheReadTokens.toLong().coerceIn(0L, prompt)
                val at = if (m.at > 0L) m.at else conv.updatedAt
                val model = m.modelName.trim()
                val est = Pricing.estimate(model, prompt, cacheRead, completion, at)
                if (!est.priced) unpriced.add(model)

                all = all.merge(prompt, cacheRead, completion, est.cost)
                if (isSession) {
                    session = session.merge(prompt, cacheRead, completion, est.cost)
                    val (book, _) = Pricing.priceBookFor(model)
                    val price = if (Pricing.isPeak(at)) book.peak else book.idle
                    val cached = cacheRead.coerceIn(0L, prompt)
                    sessionBreakdown = CostBreakdown(
                        cacheHitInputCost = sessionBreakdown.cacheHitInputCost +
                            cached / 1_000_000.0 * price.cacheHitInput,
                        cacheMissInputCost = sessionBreakdown.cacheMissInputCost +
                            (prompt - cached) / 1_000_000.0 * price.cacheMissInput,
                        outputCost = sessionBreakdown.outputCost +
                            completion / 1_000_000.0 * price.output
                    )
                }

                val acc = models.getOrPut(model) { ModelAcc() }
                acc.calls++
                acc.prompt += prompt
                acc.cacheRead += cacheRead
                acc.completion += completion
                acc.cost += est.cost

                val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                if (!date.isBefore(firstDay)) {
                    val acc = dayAcc.getOrPut(date) { DayAcc() }
                    acc.cacheHit += cacheRead
                    acc.cacheMiss += prompt - cacheRead
                    acc.completion += completion
                    acc.calls++
                    acc.cost += est.cost
                }
                if (at >= hourCutoff) {
                    val hour = Instant.ofEpochMilli(at).atZone(zone).hour
                    hours[hour] += prompt + completion
                }
            }
        }

        val days = (0 until DAYS).map { i ->
            val d = firstDay.plusDays(i.toLong())
            val acc = dayAcc[d]
            DayUsage(
                date = d,
                label = dayLabel(d),
                tokens = (acc?.cacheHit ?: 0L) + (acc?.cacheMiss ?: 0L) + (acc?.completion ?: 0L),
                cost = acc?.cost ?: 0.0,
                cacheHitInput = acc?.cacheHit ?: 0L,
                cacheMissInput = acc?.cacheMiss ?: 0L,
                completion = acc?.completion ?: 0L,
                calls = acc?.calls ?: 0
            )
        }
        val modelList = models.map { (name, acc) ->
            ModelUsage(
                model = name,
                calls = acc.calls,
                promptTokens = acc.prompt,
                cacheReadTokens = acc.cacheRead,
                completionTokens = acc.completion,
                cost = acc.cost
            )
        }.sortedWith(compareByDescending<ModelUsage> { it.cost }.thenByDescending { it.totalTokens })

        return UsageReport(
            sessionTitle = sessionTitle,
            session = session,
            sessionCost = sessionBreakdown,
            all = all,
            models = modelList,
            days = days,
            hours = hours.toList(),
            unpricedModels = unpriced.toList()
        )
    }

    private class ModelAcc {
        var calls = 0
        var prompt = 0L
        var cacheRead = 0L
        var completion = 0L
        var cost = 0.0
    }

    /** 按天聚合的中间态（输入按命中/未命中拆开） */
    private class DayAcc {
        var cacheHit = 0L
        var cacheMiss = 0L
        var completion = 0L
        var calls = 0
        var cost = 0.0
    }
}

// ---------------- 展示格式化（K = 千，M = 百万） ----------------

fun formatTokens(value: Long): String = when {
    value >= 999_950L -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

/** 只返回数字部分，货币符号由 UI 加 */
fun formatYuan(cost: Double): String = when {
    cost <= 0.0 -> "0.00"
    cost >= 1.0 -> String.format(Locale.US, "%.2f", cost)
    else -> String.format(Locale.US, "%.4f", cost)
}

private fun UsageTotals.merge(
    prompt: Long,
    cacheRead: Long,
    completion: Long,
    cost: Double
): UsageTotals = copy(
    calls = calls + 1,
    promptTokens = promptTokens + prompt,
    cacheReadTokens = cacheReadTokens + cacheRead,
    completionTokens = completionTokens + completion,
    cost = this.cost + cost
)
