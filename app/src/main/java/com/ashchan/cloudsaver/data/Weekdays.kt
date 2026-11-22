package com.ashchan.cloudsaver.data

/**
 * 更新星期集合（1=周一 … 7=周日，与 java.time.DayOfWeek / CASX 的 isoweekday 一致）。
 *
 * 存库用逗号分隔字符串（"1,3,5"），不用位掩码：可读性好，出问题时能直接看库。
 */
object Weekdays {

    const val MIN: Int = 1
    const val MAX: Int = 7

    private val NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 解析 "1,3,5"，忽略空段与越界值 */
    fun parse(csv: String): Set<Int> =
        csv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in MIN..MAX }
            .toSortedSet()

    /** 规范化为 "1,3,5"（去重、升序） */
    fun format(days: Set<Int>): String = days.filter { it in MIN..MAX }.sorted().joinToString(",")

    fun nameOf(day: Int): String = NAMES.getOrNull(day - MIN) ?: ""

    /** 卡片文案：每日更新 / 周一、周三 / 未设更新日 */
    fun label(csv: String): String {
        val days = parse(csv)
        return when {
            days.isEmpty() -> "未设更新日"
            days.size == MAX -> "每日更新"
            else -> days.map { nameOf(it) }.joinToString("、")
        }
    }

    fun isEveryDay(csv: String): Boolean = parse(csv).size == MAX

    /** 快捷预设：工作日 1-5 */
    fun workdays(): Set<Int> = (1..5).toSortedSet()

    /** 快捷预设：每天 */
    fun everyDay(): Set<Int> = (MIN..MAX).toSortedSet()
}
