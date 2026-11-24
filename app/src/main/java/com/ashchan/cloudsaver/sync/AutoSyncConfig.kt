package com.ashchan.cloudsaver.sync

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 自动刷新的纯逻辑层：时段解析 + 下一次触发时刻计算 + 充电冷却判定。
 *
 * 刻意不依赖任何 Android API，原因有两条：
 * 1. 能直接放在 `src/test` 里单测，调度层写错也不会把业务逻辑一起埋掉；
 * 2. 时钟规则是这个功能里最容易出错的部分（跨零点、正好到点、夏令时），
 *    集中在一处纯函数里，改起来只有一处。
 */
object AutoSyncConfig {

    /** 最多允许 4 个定时点；超出部分在保存时截断，UI 会禁用「+」 */
    const val MAX_SLOTS = 4

    /**
     * 充电触发的最小间隔：**只约束插电，不约束定时点**。
     * 定时点由用户人工设定，到点必跑；插电是系统事件，短时间反复插拔不该反复跑。
     */
    const val CHARGE_MIN_INTERVAL_MS: Long = 3L * 60 * 60 * 1000

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
    ): Long {
        val time = LocalTime.of(toMinutes(slot) / 60, toMinutes(slot) % 60)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate().atTime(time).atZone(zone)
        val next = if (today.toInstant().toEpochMilli() > nowMillis) {
            today
        } else {
            now.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        }
        return next.toInstant().toEpochMilli()
    }

    /** 多个时段里最近的下一跳；时段表为空时返回 null（此时不该排任何 Work） */
    fun nextTriggerAt(
        slots: List<String>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = normalize(slots.joinToString(","))
        .minOfOrNull { nextTriggerAt(it, nowMillis, zone) }

    /** 插电是否应被冷却挡下：距上次运行开始不足 [CHARGE_MIN_INTERVAL_MS] */
    fun shouldSkipCharge(lastRunAt: Long, nowMillis: Long): Boolean =
        lastRunAt > 0 && nowMillis - lastRunAt < CHARGE_MIN_INTERVAL_MS

    /** 冷却剩余毫秒；已解冻返回 0，供状态卡显示「距上次 1 小时 20 分」 */
    fun chargeCooldownLeft(lastRunAt: Long, nowMillis: Long): Long =
        if (lastRunAt <= 0) 0 else (lastRunAt + CHARGE_MIN_INTERVAL_MS - nowMillis).coerceAtLeast(0)
}
