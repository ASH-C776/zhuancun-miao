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

        // 集数：**按优先级逐个去全文找**，不能把几种格式塞进一个正则里用 alternation ——
        // `Regex.find` 取的是「全文最靠前的匹配」，不是「最靠前的候选分支」。
        // 真实踩到的坑：`Sword.and.Fairy.3.2025.S01E02.2160p...` 里片名那个 "3" 位置比 S01E02 靠前，
        // 结果一整季 26 集全被解析成第 3 集，改名时全部指向同一个文件名，服务器逐个拒绝。
        private val EP_SXXEXX = Regex("""(?i)s\d{1,2}\s*[ ,._\-]*e[Pp]?\s*(\d{1,3})""")
        private val EP_CN = Regex("""第\s*(\d{1,3})\s*[集期话話部篇]""")
        private val EP_PREFIX = Regex("""(?i)(?:^|[^a-z])(?:ep|e)[Pp]?\s*[._\- ]?\s*(\d{1,3})(?!\d)""")
        private val EP_LEADING = Regex("""^\s*(\d{1,3})(?=\.\w)""")
        private val EP_SEPARATED = Regex("""[._\-](\d{1,3})[._\-]""")
        private val EP_LOOSE = Regex("""(?<![0-9A-Za-z])(\d{1,3})(?![0-9A-Za-z])(?!$)""")

        private val EP_ORDER = listOf(
            EP_SXXEXX to 1,
            EP_CN to 1,
            EP_PREFIX to 1,
            EP_LEADING to 1,
            EP_SEPARATED to 1,
            EP_LOOSE to 1,
        )

        /** 按优先级依次尝试；命中即返回（排除 1080/2160 这类分辨率以外的三位以内数字） */
        private fun episodeOf(name: String): String? {
            EP_ORDER.forEach { (regex, group) ->
                val m = regex.find(name) ?: return@forEach
                val v = m.groupValues.getOrNull(group) ?: return@forEach
                if (v.isBlank()) return@forEach
                val num = v.toIntOrNull()
                if (num != null && num >= 1000) return@forEach
                return v
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
