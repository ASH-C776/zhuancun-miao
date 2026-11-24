package com.ashchan.cloudsaver.data

/**
 * 从「季名」里解析季号（纯函数，可单测）。
 *
 * 网盘里的季目录命名五花八门，实测过的几种：
 * - `第 2 季 - 2160p WEB-DL HEVC AAC`
 * - `Season 3` / `season 3`
 * - `S02` / `s02`
 * - `第二季`、`第一季`、`第十二季`、`第二十一季`（中文数字，含「第零季」）
 * - `第一部` / `第2部` / `第一篇`（不少资源站用「部」代替「季」）
 * - 特别版：`Specials`、`SP`、`Season 0`、`特别篇`、`特别版`、`花絮`、`番外`、`OVA`、`OAD`
 *
 * 目的有两个：
 * 1. 任务绑到某一季时，TMDB 的最新集要按「这一季」算（否则追旧季的任务永远显示"缺 N 集"）；
 * 2. 多季模式下按季目录 / 文件名分组落盘。
 */
object SeasonParser {

    private val CN = mapOf(
        '零' to 0,
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    private val patterns = listOf(
        Regex("""第\s*(\d+)\s*[季部篇辑]"""),
        Regex("""(?i)(?:seasons?|series)\s*(\d+)"""),
        Regex("""(?i)(?<![A-Za-z])s(\d{1,2})(?!\d)"""),
        Regex("""^(\d{1,2})$"""),
    )

    /** 中文数字季号：「第一季」「第二季」「第十二季」「第零季」 */
    private val cnSeason = Regex("""第\s*([一二三四五六七八九十零]+)\s*[季部篇辑]""")

    /**
     * 「特别版」类季名 → 0。实测命名：`Specials`、`Special`、`SP`、`SPs`、
     * `Season 0`、`特别篇`、`特别版`、`花絮`、`番外`、`OVA`、`OAD`。
     *
     * 注意：**0 是一个真实存在的季**（TMDB 的 Season 0 / 特别篇），
     * 它和「任务里没选季度」是两件事，不要混。
     */
    private val specialPatterns = listOf(
        Regex("""(?i)(?:^|[^a-z])(?:specials?|sps?)(?![a-z])"""),
        Regex("""特别[篇版]|花絮|番外(?:篇)?|(?i)(?:^|[^a-z])(?:ova|oad)(?![a-z])"""),
        Regex("""(?i)(?<![a-z])s0{1,2}(?!\d)"""),
    )

    fun parse(seasonName: String?): Int? {
        val name = seasonName?.trim().orEmpty()
        if (name.isBlank()) return null
        for (p in patterns) {
            val v = p.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 0..100) return v
        }
        cnSeason.find(name)?.let { m -> cnToInt(m.groupValues[1])?.let { return it } }
        // 特别版最后判断，避免把带 SP 字样的常规季名（如「S01 SP04」）误判成特别版
        if (specialPatterns.any { it.containsMatchIn(name) }) return 0
        return null
    }

    /** 「第二季」这类中文季号 → 2；「第零季」→ 0；无法解析返回 null */
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
