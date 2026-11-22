package com.ashchan.cloudsaver.data

/**
 * 从「季名」里解析季号（纯函数，可单测）。
 *
 * 网盘里的季目录命名五花八门，实测过的几种：
 * - `第 2 季 - 2160p WEB-DL HEVC AAC`
 * - `Season 3`
 * - `S02`
 * - `第二季`
 *
 * 目的只有一个：任务绑到某一季时，TMDB 的最新集要按「这一季」算，
 * 而不是整部剧最新一集（否则追旧季的任务会永远显示"缺 N 集"）。
 */
object SeasonParser {

    private val CN = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    private val patterns = listOf(
        Regex("""第\s*(\d+)\s*季"""),
        Regex("""(?:Season|SEASON|season)\s*(\d+)"""),
        Regex("""(?<![A-Za-z])S(\d{1,2})(?!\d)"""),
        Regex("""^(\d{1,2})$"""),
    )

    fun parse(seasonName: String?): Int? {
        val name = seasonName?.trim().orEmpty()
        if (name.isBlank()) return null
        for (p in patterns) {
            val v = p.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 0..100) return v
        }
        // 中文数字：「第二季」「第十二季」
        Regex("""第\s*([一二三四五六七八九十]+)\s*季""").find(name)?.let { m ->
            cnToInt(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /** 「第二季」这类中文季号 → 2；无法解析返回 null */
    private fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s == "十") return 10
        if (s[0] == '十') {
            val ones = s.getOrNull(1) ?: return 10
            return 10 + (CN[ones] ?: return null)
        }
        val idx = s.indexOf('十')
        if (idx == 1) {
            // 二十 / 二十一
            val tens = CN[s[0]] ?: return null
            val ones = s.getOrNull(2)?.let { CN[it] ?: return null } ?: 0
            return tens * 10 + ones
        }
        return if (s.length == 1) CN[s[0]] else null
    }
}
