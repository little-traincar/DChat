package com.dchat.app.ui.usage

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.AppState
import com.dchat.app.data.DayUsage
import com.dchat.app.data.UsageReport
import com.dchat.app.data.UsageStats
import com.dchat.app.data.UsageTotals
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.conversationTitle
import com.dchat.app.ui.lang.dayLabel
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.lang.hourLabel
import com.dchat.app.ui.lang.money
import com.dchat.app.ui.lang.moneyCompact
import com.dchat.app.ui.lang.percent
import com.dchat.app.ui.lang.tokens
import com.dchat.app.ui.theme.LocalColors
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * 用量统计页（纯本地记录聚合，不依赖官方历史用量接口 —— 开放平台没有公开的用量查询 API）。
 *
 * 统计口径：
 *   - 顶部「当前会话」+ 命中率进度条 + 下方「当前会话开销」：只算当前会话；
 *   - 每日堆叠柱（7 天 / 30 天可切换）+ 每日明细列表 + 分时 + 按模型：全部本地会话；
 *   - 分时固定为最近 7 天（含今天），按消息完成时间落到本地时区的小时；
 *   - 只统计 promptTokens / cacheReadTokens / completionTokens 有记录的消息，
 *     旧数据 / 导入的历史会话没有 usage 记录，不参与统计。
 */
@Composable
fun UsageScreen(app: AppState, onBack: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    // 地区影响日期/数字/货币格式：key 里带上 language+region，切完立刻重算一遍展示文案
    val report = remember(app.conversations, app.currentId, L.language, L.region) {
        UsageStats.build(app.conversations, app.currentId, dayLabel = { L.dayLabel(it) })
    }
    // 每日视图默认最近 7 天，可切到 30 天（与官方用量页一致的两档）
    var rangeDays by remember { mutableStateOf(RANGE_7) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val days = report.days.takeLast(rangeDays.coerceIn(1, report.days.size))
    val selectedIndex = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 }
    val todayIndex = days.lastIndex

    fun select(date: LocalDate) {
        selectedDate = if (selectedDate == date) null else date
    }

    fun changeRange(n: Int) {
        rangeDays = n
        val visible = report.days.takeLast(n.coerceIn(1, report.days.size))
        if (selectedDate != null && visible.none { it.date == selectedDate }) selectedDate = null
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(L.usage.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))
            SessionSummaryCard(report)
            Spacer(Modifier.height(12.dp))
            DailyCard(
                days = days,
                rangeDays = rangeDays,
                selectedIndex = selectedIndex,
                todayIndex = todayIndex,
                onRange = { changeRange(it) },
                onSelect = { select(days[it].date) }
            )
            Spacer(Modifier.height(12.dp))
            DailyListCard(
                days = days,
                rangeDays = rangeDays,
                selectedIndex = selectedIndex,
                todayIndex = todayIndex,
                onSelect = { select(days[it].date) }
            )
            Spacer(Modifier.height(12.dp))
            HourlyCard(report)
            Spacer(Modifier.height(12.dp))
            ModelsCard(report)
            Spacer(Modifier.height(12.dp))
            SessionCostCard(report)
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private const val RANGE_7 = 7

/* ---------------- 顶部汇总：当前会话 + 缓存命中率进度条 ---------------- */

@Composable
private fun SessionSummaryCard(report: UsageReport) {
    val c = LocalColors.current
    val L = LocalStrings.current
    UsageCard(
        title = L.usage.currentSession,
        subtitle = report.sessionTitle.ifBlank { L.usage.noSession }.let { L.conversationTitle(it) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatCell(L.usage.statInput, L.tokens(report.session.promptTokens), Modifier.weight(1f))
            StatCell(L.usage.statOutput, L.tokens(report.session.completionTokens), Modifier.weight(1f))
            StatCell(L.usage.statTotal, L.tokens(report.session.totalTokens), Modifier.weight(1f))
            StatCell(L.usage.statCost, L.moneyCompact(report.session.cost), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        HitRateBar(report.session)
        Spacer(Modifier.height(10.dp))
        Text(
            L.format(
                L.usage.sessionFoot,
                report.session.calls,
                L.tokens(report.all.totalTokens)
            ),
            fontSize = 10.5.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

/**
 * 当前会话缓存命中率横向进度条。
 * 颜色只用主题色：<30% 中性灰，30%~70% 半透明品牌蓝，>=70% 品牌蓝（不用红绿灯色）。
 * 请求数为 0 时显示空态文案，不出现 NaN / 0.0%。
 */
@Composable
private fun HitRateBar(session: UsageTotals) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val hasData = session.calls > 0 && session.promptTokens > 0L
    val rate = if (hasData) session.cacheHitRate else 0.0
    val barColor = when {
        !hasData -> c.text3
        rate >= 0.7 -> c.brand
        rate >= 0.3 -> c.brand.copy(alpha = 0.6f)
        else -> c.text3
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L.usage.cacheHitRate,
                fontSize = 11.5.sp,
                color = c.text2,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (hasData) L.percent(rate) else "--",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (hasData) barColor else c.text3
            )
        }
        Spacer(Modifier.height(6.dp))
        if (!hasData) {
            Text(L.usage.hitRateEmpty, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(c.bg3)
            ) {
                if (rate > 0.0) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(rate.toFloat().coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(50))
                            .background(barColor)
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                L.format(
                    L.usage.hitRateDetail,
                    L.tokens(session.cacheReadTokens),
                    L.tokens(session.promptTokens)
                ),
                fontSize = 10.5.sp,
                color = c.text3
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(modifier) {
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ---------------- 每日用量：7/30 天切换 + 堆叠柱 + 当天明细 ---------------- */

@Composable
private fun DailyCard(
    days: List<DayUsage>,
    rangeDays: Int,
    selectedIndex: Int?,
    todayIndex: Int,
    onRange: (Int) -> Unit,
    onSelect: (Int) -> Unit
) {
    val L = LocalStrings.current
    val day = selectedIndex?.let { days.getOrNull(it) }
    val subtitle = if (day != null) {
        L.format(
            L.usage.daySummary,
            day.label,
            day.calls,
            if (day.calls > 0) L.percent(day.cacheHitRate) else "--"
        )
    } else {
        L.format(L.usage.dailySubtitle, rangeDays) + " · " + L.usage.dailyHint
    }
    UsageCard(
        title = L.usage.dailyTitle,
        subtitle = subtitle,
        trailing = { RangeSwitch(rangeDays, onRange) }
    ) {
        StackedBarChart(
            days = days,
            labels = days.mapIndexed { i, d ->
                if (rangeDays <= RANGE_7) d.label
                else if (i % 5 == 0 || i == todayIndex) d.label else null
            },
            selected = selectedIndex,
            highlight = todayIndex,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(10.dp))
        ChartLegend()
        if (day != null) {
            UsageDivider()
            DayDetail(day)
        }
    }
}

/** 7 天 / 30 天分段切换 */
@Composable
private fun RangeSwitch(rangeDays: Int, onRange: (Int) -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(c.bg3)
            .padding(2.dp)
    ) {
        listOf(RANGE_7, UsageStats.DAYS).forEach { n ->
            val on = rangeDays == n
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) c.bg else Color.Transparent)
                    .clickable { onRange(n) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    L.format(L.usage.rangeDays, n),
                    fontSize = 10.8.sp,
                    color = if (on) c.text1 else c.text3,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/** 堆叠图三色图例（与柱体顺序一致：命中 / 未命中 / 输出） */
@Composable
private fun ChartLegend() {
    val c = LocalColors.current
    val L = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(c.brand, L.usage.legendHit)
        LegendItem(c.brand.copy(alpha = 0.5f), L.usage.legendMiss)
        LegendItem(c.text3, L.usage.legendOutput)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.5.sp, color = c.text3, maxLines = 1)
    }
}

/** 图下方「当天明细」：日期、请求数、命中率、输入拆分、输出、费用 */
@Composable
private fun DayDetail(day: DayUsage) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                L.usage.dayDetailTitle,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                modifier = Modifier.weight(1f)
            )
            Text(day.label, fontSize = 10.5.sp, color = c.text3)
        }
        if (day.calls <= 0) {
            EmptyHint(L.usage.dayNoData)
        } else {
            CostRow(L.usage.requestsLabel, value = L.format(L.usage.requestCount, day.calls))
            UsageDivider()
            CostRow(
                L.usage.cacheHitRate,
                detail = L.format(
                    L.usage.hitRateDetail,
                    L.tokens(day.cacheHitInput),
                    L.tokens(day.promptTokens)
                ),
                value = L.percent(day.cacheHitRate)
            )
            UsageDivider()
            CostRow(
                L.usage.statInput,
                detail = L.format(
                    L.usage.inputBreakdown,
                    L.tokens(day.cacheHitInput),
                    L.tokens(day.cacheMissInput)
                ),
                value = L.tokens(day.promptTokens)
            )
            UsageDivider()
            CostRow(L.usage.costOutput, value = L.tokens(day.completion))
            UsageDivider()
            CostRow(L.usage.statCost, value = L.money(day.cost))
        }
    }
}

/* ---------------- 每日明细列表（与柱状图联动） ---------------- */

@Composable
private fun DailyListCard(
    days: List<DayUsage>,
    rangeDays: Int,
    selectedIndex: Int?,
    todayIndex: Int,
    onSelect: (Int) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    UsageCard(title = L.usage.dailyListTitle, subtitle = L.usage.dailyHint) {
        if (days.isEmpty() || days.all { it.calls <= 0 }) {
            EmptyHint(L.format(L.usage.dailyListEmpty, rangeDays))
        } else {
            // 最新的日子排最上面，今天在第一行
            days.indices.reversed().forEachIndexed { pos, index ->
                val day = days[index]
                if (pos > 0) UsageDivider()
                val selected = index == selectedIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) c.brand.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                day.label,
                                fontSize = 13.sp,
                                fontWeight = if (index == todayIndex) FontWeight.SemiBold else FontWeight.Normal,
                                color = c.text1,
                                maxLines = 1
                            )
                            if (index == todayIndex) {
                                Spacer(Modifier.width(6.dp))
                                TodayBadge()
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (day.calls <= 0) {
                                L.format(L.usage.requestCount, 0)
                            } else {
                                L.format(
                                    L.usage.dailyRowMeta,
                                    day.calls,
                                    L.percent(day.cacheHitRate),
                                    L.tokens(day.completion)
                                )
                            },
                            fontSize = 10.5.sp,
                            color = c.text3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        L.money(day.cost),
                        fontSize = 12.5.sp,
                        color = if (day.calls > 0) c.text1 else c.text3,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayBadge() {
    val c = LocalColors.current
    val L = LocalStrings.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.brand.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(L.usage.todayLabel, fontSize = 9.sp, color = c.brand, fontWeight = FontWeight.Medium)
    }
}

/* ---------------- 24 小时分时柱形图（最近 7 天，保留） ---------------- */

@Composable
private fun HourlyCard(report: UsageReport) {
    val L = LocalStrings.current
    val nowHour = LocalTime.now().hour
    UsageCard(
        title = L.usage.hourlyTitle,
        subtitle = L.format(L.usage.hourlySubtitle, UsageStats.HOUR_DAYS)
    ) {
        UsageBarChart(
            values = report.hours,
            labels = report.hours.indices.map { h -> if (h % 6 == 0 || h == 23) L.hourLabel(h) else null },
            highlight = nowHour,
            showValueOn = nowHour,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

/* ---------------- 按模型用量（保留） ---------------- */

@Composable
private fun ModelsCard(report: UsageReport) {
    val c = LocalColors.current
    val L = LocalStrings.current
    UsageCard(title = L.usage.byModelTitle, subtitle = L.usage.byModelSubtitle) {
        if (report.models.isEmpty()) {
            EmptyHint(L.usage.byModelEmpty)
        } else {
            report.models.forEachIndexed { i, m ->
                if (i > 0) UsageDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.model.ifBlank { L.usage.modelUnrecorded },
                            fontSize = 13.5.sp,
                            color = c.text1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            L.format(
                                L.usage.modelMeta,
                                m.calls,
                                L.tokens(m.promptTokens),
                                L.tokens(m.completionTokens)
                            ),
                            fontSize = 10.8.sp,
                            color = c.text3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        L.money(m.cost),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.text1,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/* ---------------- 当前会话开销（保留费用构成） ---------------- */

@Composable
private fun SessionCostCard(report: UsageReport) {
    val c = LocalColors.current
    val L = LocalStrings.current
    UsageCard(
        title = L.usage.costTitle,
        subtitle = report.sessionTitle.ifBlank { L.usage.noSession }.let { L.conversationTitle(it) }
    ) {
        if (report.session.calls == 0) {
            EmptyHint(L.usage.costEmpty)
        } else {
            CostRow(
                L.usage.cacheHitInput,
                "${L.tokens(report.session.cacheReadTokens)} tokens",
                L.money(report.sessionCost.cacheHitInputCost)
            )
            UsageDivider()
            CostRow(
                L.usage.cacheMissInput,
                "${L.tokens(report.session.cacheMissInputTokens)} tokens",
                L.money(report.sessionCost.cacheMissInputCost)
            )
            UsageDivider()
            CostRow(
                L.usage.cacheHitRate,
                L.format(
                    L.usage.cacheRateDetail,
                    L.tokens(report.session.cacheReadTokens),
                    L.tokens(report.session.promptTokens)
                ),
                L.percent(report.session.cacheHitRate)
            )
            UsageDivider()
            CostRow(
                L.usage.costOutput,
                "${L.tokens(report.session.completionTokens)} tokens",
                L.money(report.sessionCost.outputCost)
            )
            UsageDivider()
            CostRow(
                L.usage.costTotal,
                L.format(
                    L.usage.costTotalDetail,
                    report.session.calls,
                    L.tokens(report.session.totalTokens)
                ),
                L.money(report.sessionCost.total),
                bold = true
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            L.usage.estimateNote,
            fontSize = 10.5.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

@Composable
private fun CostRow(
    label: String,
    detail: String? = null,
    value: String,
    bold: Boolean = false
) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                color = if (bold) c.text1 else c.text2,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
            )
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, fontSize = 10.5.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            fontSize = 13.sp,
            color = c.text1,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/* ---------------- 通用卡片 / 分割线 ---------------- */

@Composable
private fun UsageCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.bg2)
            .padding(vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        subtitle,
                        fontSize = 10.8.sp,
                        color = c.text3,
                        maxLines = 2,
                        lineHeight = 15.sp
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** 分割线左右各缩进 14dp，和卡片内边距对齐 */
@Composable
private fun UsageDivider() {
    val c = LocalColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(c.line)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = LocalColors.current.text3,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

/* ---------------- 自绘堆叠柱状图（Compose Canvas，无第三方图表库） ---------------- */

/**
 * 每日堆叠柱：每根柱子自下而上分三段（缓存命中输入 / 未命中输入 / 输出），三色 + 图例。
 *
 * @param days 视图内的天（7 或 30 天，旧 → 新）
 * @param labels 横轴标签，null 表示该位置不画（30 天时每 5 天画一个，避免重叠）
 * @param selected 选中天下标；非选中柱会稍微变淡
 * @param highlight 今天下标（高亮 + 柱顶显示总数）
 * @param onSelect 点柱子回调
 */
@Composable
private fun StackedBarChart(
    days: List<DayUsage>,
    labels: List<String?>,
    selected: Int?,
    highlight: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 176.dp
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    if (days.isEmpty() || days.all { it.tokens <= 0L }) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(L.usage.emptyChart, fontSize = 12.sp, color = c.text3)
        }
        return
    }

    val density = LocalDensity.current
    val leftGutterPx = with(density) { 34.dp.toPx() }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val gridColor = c.line
    val textColor = c.text3.toArgb()
    val hitColor = c.brand
    val missColor = c.brand.copy(alpha = 0.5f)
    val outColor = c.text3

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(days.size) {
                detectTapGestures { off ->
                    val plotW = size.width - leftGutterPx
                    if (plotW <= 0f) return@detectTapGestures
                    val slot = plotW / days.size
                    val idx = ((off.x - leftGutterPx) / slot).toInt()
                    if (idx in days.indices) onSelect(idx)
                }
            }
    ) {
        val plotTop = 20.dp.toPx()
        val plotBottom = size.height - 18.dp.toPx()
        val plotLeft = leftGutterPx
        val plotRight = size.width
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val axisMax = niceCeil(days.maxOfOrNull { it.tokens } ?: 0L).toDouble().coerceAtLeast(1.0)
        val slot = plotW / days.size
        val barW = min(slot * 0.66f, 16.dp.toPx())

        labelPaint.textSize = 9.sp.toPx()
        labelPaint.color = textColor

        // 横向网格 + 纵轴刻度（0 / 一半 / 顶部）
        listOf(0f, 0.5f, 1f).forEach { frac ->
            val y = plotBottom - plotH * frac
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f
            )
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                L.tokens((axisMax * frac).toLong()),
                plotLeft - 5.dp.toPx(),
                y + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }

        // 柱子（每根自下而上：命中 / 未命中 / 输出）
        days.forEachIndexed { i, day ->
            val x = plotLeft + slot * i + (slot - barW) / 2f
            val total = day.tokens
            if (total <= 0L) {
                // 没有记录的日子画一条细底线，保持「每天一根」的节奏
                drawRoundRect(
                    color = c.line2,
                    topLeft = Offset(x, plotBottom - 1.5.dp.toPx()),
                    size = Size(barW, 1.5.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
                return@forEachIndexed
            }
            val dim = selected != null && i != selected
            val alpha = if (dim) 0.38f else 1f
            val barH = (total.toDouble() / axisMax * plotH).toFloat().coerceAtLeast(2f)
            val top = plotBottom - barH

            // 三段按 tokens 比例分配高度，自下而上叠
            var y = plotBottom

            fun segment(tokens: Long, color: Color) {
                if (tokens <= 0L) return
                val h = (tokens.toDouble() / total * barH).toFloat()
                if (h <= 0f) return
                drawRect(
                    color = color.copy(alpha = color.alpha * alpha),
                    topLeft = Offset(x, y - h),
                    size = Size(barW, h)
                )
                y -= h
            }

            segment(day.cacheHitInput, hitColor)
            segment(day.cacheMissInput, missColor)
            segment(day.completion, outColor)

            // 选中的柱子描边（和明细列表联动）
            if (i == selected) {
                drawRoundRect(
                    color = c.brand,
                    topLeft = Offset(x - 1.dp.toPx(), top - 1.dp.toPx()),
                    size = Size(barW + 2.dp.toPx(), barH + 2.dp.toPx()),
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }
            // 今天：柱脚一条短横线
            if (i == highlight) {
                drawRoundRect(
                    color = c.brand,
                    topLeft = Offset(x, plotBottom + 2.dp.toPx()),
                    size = Size(barW, 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }

        // 柱顶数值（选中天 / 今天）
        val valueIndex = (selected ?: highlight).takeIf {
            it in days.indices && days[it].tokens > 0L
        }
        if (valueIndex != null) {
            val day = days[valueIndex]
            val barH = (day.tokens.toDouble() / axisMax * plotH).toFloat().coerceAtLeast(2f)
            val x = plotLeft + slot * valueIndex + slot / 2f
            val y = (plotBottom - barH - 3.dp.toPx()).coerceAtLeast(plotTop + labelPaint.textSize)
            labelPaint.textAlign = Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(L.tokens(day.tokens), x, y, labelPaint)
        }

        // 横轴日期
        labelPaint.textAlign = Paint.Align.CENTER
        days.indices.forEach { i ->
            val label = labels.getOrNull(i) ?: return@forEach
            val x = plotLeft + slot * i + slot / 2f
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                size.height - 3.dp.toPx(),
                labelPaint
            )
        }
    }
}

/* ---------------- 自绘普通柱形图（分时图，保留） ---------------- */

/**
 * @param values 每根柱子一个值
 * @param labels 横轴标签，null 表示该位置不画（避免标签重叠）
 * @param highlight 高亮柱（分时图=当前小时）
 * @param showValueOn 柱顶显示数值的那一根
 */
@Composable
private fun UsageBarChart(
    values: List<Long>,
    labels: List<String?>,
    highlight: Int,
    modifier: Modifier = Modifier,
    showValueOn: Int? = null,
    height: Dp = 148.dp
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    if (values.isEmpty() || values.all { it <= 0L }) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(L.usage.emptyChart, fontSize = 12.sp, color = c.text3)
        }
        return
    }

    val density = LocalDensity.current
    val leftGutterPx = with(density) { 34.dp.toPx() }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val gridColor = c.line
    val textColor = c.text3.toArgb()
    val brand = c.brand

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val plotTop = 20.dp.toPx()
        val plotBottom = size.height - 18.dp.toPx()
        val plotLeft = leftGutterPx
        val plotRight = size.width
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val axisMax = niceCeil(values.maxOrNull() ?: 0L).toDouble().coerceAtLeast(1.0)
        val slot = plotW / values.size
        val barW = min(slot * 0.62f, 13.dp.toPx())

        labelPaint.textSize = 9.sp.toPx()
        labelPaint.color = textColor

        listOf(0f, 0.5f, 1f).forEach { frac ->
            val y = plotBottom - plotH * frac
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f
            )
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                L.tokens((axisMax * frac).toLong()),
                plotLeft - 5.dp.toPx(),
                y + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }

        values.forEachIndexed { i, raw ->
            if (raw <= 0L) return@forEachIndexed
            val barH = (raw.toDouble() / axisMax * plotH).toFloat().coerceIn(1.5f, plotH)
            val x = plotLeft + slot * i + (slot - barW) / 2f
            val color = when {
                i == highlight -> brand
                else -> brand.copy(alpha = 0.26f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, plotBottom - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(2.5.dp.toPx())
            )
        }

        val valueIndex = showValueOn?.takeIf { it in values.indices && values[it] > 0L }
        if (valueIndex != null) {
            val raw = values[valueIndex]
            val barH = (raw.toDouble() / axisMax * plotH).toFloat().coerceIn(1.5f, plotH)
            val x = plotLeft + slot * valueIndex + slot / 2f
            val y = (plotBottom - barH - 3.dp.toPx())
                .coerceAtLeast(plotTop + labelPaint.textSize)
            labelPaint.textAlign = Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(L.tokens(raw), x, y, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.CENTER
        values.indices.forEach { i ->
            val label = labels.getOrNull(i) ?: return@forEach
            val x = plotLeft + slot * i + slot / 2f
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                size.height - 3.dp.toPx(),
                labelPaint
            )
        }
    }
}

/** 把最大值抬到 1/2/2.5/5×10^n 这样的"整刻度"，让纵轴不出现 13784 这种数字 */
private fun niceCeil(value: Long): Long {
    if (value <= 0L) return 1L
    val exp = floor(log10(value.toDouble())).toInt()
    val base = 10.0.pow(exp.toDouble())
    val f = value.toDouble() / base
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return (nice * base).toLong().coerceAtLeast(1L)
}
