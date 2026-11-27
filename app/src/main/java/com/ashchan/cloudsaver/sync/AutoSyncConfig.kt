package com.ashchan.cloudsaver.sync

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 自动刷新的纯逻辑层：时段解析 + 下一次触发时刻计算。
 *
 * 刻意不依赖任何 Android API，原因有两条：
 * 1. 能直接放在 `src/test` 里单测，调度层写错也不会把业务逻辑一起埋掉；
 * 2. 时钟规则是这个功能里最容易出错的部分（跨零点、正好到点、夏令时），
 *    集中在一处纯函数里，改起来只有一处。
 */
object AutoSyncConfig {

    /** 最多允许 4 个定时点；超出部分在保存时截断，UI 会禁用「+」 */
    const val MAX_SLOTS = 4

    /** 默认两个时刻；用户没改过时段表时用它 */
    const val DEFAULT_SLOTS: String = "09:00,20:00"

    /** 严格 HH:mm，24 小时制，不接受 "9:00"（避免排序与显示歧义） */
    private val SLOT_REGEX = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    fun isValidSlot(slot: String): Boolean = SLOT_REGEX.matches(slot.trim())

    /** 转成「当天第几分钟」，用于排序与去重比较 */
    fun toMinutes(slot: String): Int {
        val raw = slot.trim()
        require(isValidSlot(raw)) { "非法时段：$slot" }
        return raw.substring(0, 2).toInt() * 60 + raw.substring(3, 5).toInt()
    }

    /**
     * 规范化时段表：去空白 → 丢弃非法项 → 去重 → 按时间升序 → 截断到 [MAX_SLOTS]。
     * 任何来源（输入框、旧版本 prefs、手改配置文件）都走这一个函数，保证顺序稳定。
     */
    fun normalize(csv: String): List<String> =
        csv.split(",")
            .map { it.trim() }
            .filter { isValidSlot(it) }
            .distinct()
            .sortedBy { toMinutes(it) }
            .take(MAX_SLOTS)

    fun toCsv(slots: List<String>): String = normalize(slots.joinToString(",")).joinToString(",")

    /**
     * 下一次该时段的触发时刻（毫秒，严格晚于 [nowMillis]）。
     *
     * 「正好等于这个时刻」算已过，取明天同一时刻 —— 否则刚跑完的一轮会被自己
     * 重新排成立刻再跑一次，形成死循环。
     */
    fun nextTriggerAt(
        slot: String,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /**
         * 宽限：冷启动 / 用户重排时，允许「刚好过点几十秒」也算今天这一趟。
         * Worker 续排传 0（严格晚于 now），否则刚跑完的一轮会把自己再排一次。
         */
        graceMillis: Long = 0L,
    ): Long {
        val time = LocalTime.of(toMinutes(slot) / 60, toMinutes(slot) % 60)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate().atTime(time).atZone(zone)
        val todayMs = today.toInstant().toEpochMilli()
        val withinGrace = graceMillis > 0L && nowMillis - todayMs in 0..graceMillis
        return if (todayMs > nowMillis || withinGrace) {
            todayMs
        } else {
            now.toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /**
     * 该时段「最近一次应该触发」的时刻（≤ now）。
     *
     * 用途：Worker 真正开跑时，拿它和实际开始时间比，
     * 就能告诉用户「12:00 计划、12:07 才开始」，把系统的延后量化出来。
     */
    fun previousTriggerAt(
        slot: String,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val time = LocalTime.of(toMinutes(slot) / 60, toMinutes(slot) % 60)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate().atTime(time).atZone(zone)
        return if (today.toInstant().toEpochMilli() <= nowMillis) {
            today.toInstant().toEpochMilli()
        } else {
            now.toLocalDate().minusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** 多个时段里最近的下一跳；时段表为空时返回 null（此时不该排任何 Work） */
    fun nextTriggerAt(
        slots: List<String>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = normalize(slots.joinToString(","))
        .minOfOrNull { nextTriggerAt(it, nowMillis, zone) }
}
