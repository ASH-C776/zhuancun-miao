package com.ashchan.cloudsaver.rename

/**
 * 从文件名解析占位符值（移植自 CASX magic_rename 的 magic_variable 解析表）。
 * 全部本地正则解析，不依赖网络。
 */
class FileNameContext(fileName: String) {

    /** 扩展名（不含点），如 mp4 */
    val ext: String? = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }

    /** 季号 S01（无则 null） */
    val season: String? = SEASON.find(fileName)?.groupValues?.get(0)?.uppercase()

    /** 集数原始数字（如 3、24）。按优先级解析：SxxExx > EPxx/Exx > 第X集 > 开头数字 > 分隔符 > 孤立数字 */
    val episodeRaw: String? = episodeOf(fileName)

    /** 集数（两位补零由引擎处理） */
    val episode: String? = episodeRaw?.takeIf { it.isNotBlank() }

    /** 分P标识：上/中/下/一~十 */
    val part: String? = PART.find(fileName)?.value

    /** 日期 YYYYMMDD（从文件名解析；缺年份用当前年份补齐） */
    val date: String? = parseDate(fileName)

    companion object {
        // 季号：S01（后接 E/X 或独立）
        private val SEASON = Regex("""(?i)[sS]\d{1,2}""")

        // 集数：按优先级依次尝试（严格格式优先，宽松格式最后）
        // 1. SxxExx（从 S 开始整体匹配，避免 "S01E02" 被 "S01" 抢先）
        // 2. EPxx / Exx
        // 3. 第X集/期/话/部/篇
        // 4. 开头纯数字（01.mkv）
        // 5. 分隔符包裹数字（.02.、_02_）
        // 6. 孤立数字（前后非字母数字，宽松兜底；需排除年份/分辨率这类 >=1000 的数字）
        private val EPISODE = Regex(
            """(?i)(?:s\d{1,2}e(\d{1,3}))|""" +
                """(?i)(?:ep(\d{1,3}))|""" +
                """(?i)(?:e(\d{1,3}))|""" +
                """(?:第(\d{1,3})[集期话部篇])|""" +
                """(?:^(\d{1,3})(?=\.\w))|""" +
                """(?:[._-](\d{1,3})[._-])|""" +
                """(?:(?<![0-9A-Za-z])(\d{1,3})(?![0-9A-Za-z])(?!$)(?<!\d{4}))"""
        )

        /** 取 EPISODE 第一个实际参与匹配的捕获组（或用整个匹配值） */
        private fun episodeOf(name: String): String? {
            val m = EPISODE.find(name) ?: return null
            for (g in 1..m.groupValues.size - 1) {
                val v = m.groupValues[g]
                if (v.isNotBlank()) {
                    val num = v.toIntOrNull() ?: return v
                    // 排除年份/分辨率等大数字（>=1000 视为非集数）
                    if (num < 1000) return v
                }
            }
            return null
        }

        // 分P：上/中/下/一~十
        private val PART = Regex("""(?:(?<=[集期话部篇第])[上中下一二三四五六七八九十])|(?:[上中下一二三四五六七八九十])""")

        // 日期
        private val DATE_1 = Regex("""(18|19|20)?\d{2}[.\-/年]\d{1,2}[.\-/月]\d{1,2}""")
        private val DATE_2 = Regex("""(?<!\d)[12]\d{3}[01]?\d[0123]?\d""")
        private val DATE_3 = Regex("""(?<!\d)[01]?\d[.\-/月][0123]?\d""")

        fun parseDate(name: String): String? {
            val raw = DATE_1.find(name)?.value
                ?: DATE_2.find(name)?.value
                ?: DATE_3.find(name)?.value
                ?: return null
            val digits = raw.filter { it.isDigit() }
            if (digits.isEmpty()) return null
            val year = java.time.LocalDate.now().year.toString()
            return year.take(8 - digits.length) + digits
        }
    }
}
