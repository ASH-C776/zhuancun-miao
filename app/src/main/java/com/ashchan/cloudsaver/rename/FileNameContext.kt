package com.ashchan.cloudsaver.rename

import com.ashchan.cloudsaver.data.EpisodeKey

/**
 * 从文件名解析占位符值（移植自 CASX magic_rename 的 magic_variable 解析表）。
 * 全部本地正则解析，不依赖网络。
 *
 * 集号与季号已统一到 [EpisodeKey]：以前这里自带一套只认 3 位数字的正则，
 * 与统计侧（4 位）不一致 —— 同一个文件统计说是第 1024 集、通知却写第 102 集，
 * 「剧名.1-12.合集」统计认 12、改名认 1。现在只保留一份解析口径。
 */
class FileNameContext(fileName: String) {

    /** 扩展名（不含点），如 mp4 */
    val ext: String? = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }

    /** 季号 S01（无则 null）：先信统一解析器的结论，再退回文件名里的 Sxx 标记 */
    val season: String? = run {
        val fromKey = EpisodeKey.analyze(fileName).parsed?.season
        if (fromKey != null && fromKey > 0) {
            "S%02d".format(fromKey)
        } else {
            SEASON.find(fileName)?.groupValues?.get(0)?.uppercase()
        }
    }

    /** 集数（与统计 / 通知 / 去重同一口径，见 [EpisodeKey.numberOf]） */
    val episodeRaw: String? = EpisodeKey.numberOf(fileName)?.toString()

    /** 集数（两位补零由引擎处理） */
    val episode: String? = episodeRaw?.takeIf { it.isNotBlank() }

    /**
     * 分P标识：上 / 中 / 下。
     *
     * 只认三种位置：「第…集/期/话/部/篇」后一位、括号包起来的、被分隔符夹住的独立字。
     * 以前是漫天的单字匹配，文件名里只要有「一」「十」就命中 ——
     * 「一人之下」的 {PART} 变成「一」、「十月围城」变成「十」。
     */
    val part: String? = PART_AFTER.find(fileName)?.value
        ?: PART_BRACKET.find(fileName)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: PART_TOKEN.find(fileName)?.value

    /** 日期 YYYYMMDD（必须有年份；无年份的「01.2」多半是集号邻位数字，宁可当没有） */
    val date: String? = parseDate(fileName)

    companion object {
        // 季号：独立成段的 S01 / S02（前后不能紧贴字母数字，避免吃进片名）
        private val SEASON = Regex("""(?<![A-Za-z0-9])[sS]\d{1,2}(?!\d)""")

        private val PART_AFTER = Regex("""(?<=[集期话部篇第])[上中下]""")
        private val PART_BRACKET = Regex("""[（(\[]([上中下])[）)\]]""")
        private val PART_TOKEN = Regex("""(?<=[._\-· ])[上中下](?=[._\-· .])""")

        // 日期：yyyy-MM-dd / yyyy.MM.dd / yyyy年MM月dd日；年份必须 19xx/20xx，月日必须合法
        private val DATE_FULL = Regex(
            """(?<!\d)((?:19|20)\d{2})[.\-/年](0?[1-9]|1[0-2])[.\-/月](0?[1-9]|[12]\d|3[01])日?""",
        )

        // 紧凑日期 yyyyMMdd（同样要求 19xx/20xx 开头，月日合法）
        private val DATE_COMPACT =
            Regex("""(?<!\d)((?:19|20)\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(?!\d)""")

        /** 解析日期，统一输出 yyyyMMdd；解析不出返回 null（不再用当前年份瞎补） */
        fun parseDate(name: String): String? {
            DATE_FULL.find(name)?.let { m ->
                val y = m.groupValues[1]
                val mo = m.groupValues[2].padStart(2, '0')
                val d = m.groupValues[3].padStart(2, '0')
                return "$y$mo$d"
            }
            DATE_COMPACT.find(name)?.let { m ->
                return m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
            }
            return null
        }
    }
}
