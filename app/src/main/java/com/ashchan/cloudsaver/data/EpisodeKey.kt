package com.ashchan.cloudsaver.data

/**
 * 单个视频文件的「季 + 集」判定（纯函数，可单测）。
 *
 * 这是「命中才转存」的判据：只有这里能给出唯一、无歧义的集号，文件才允许被转存与改名；
 * 判不出来的一律当未命中，不转、也不进统计。
 *
 * 与 [com.ashchan.cloudsaver.core.model.EpisodeParser] 的分工：
 * - `EpisodeParser` 只关心「有没有集号」，用于统计盘里已有多少集；
 * - 这里关心「集号可不可信」，多一层质量分与歧义标记，用于决定转不转。
 *
 * 质量分（[Parsed.quality]）：
 * - 3 = 强证据：`S01E05`、`第5集`、`EP05`
 * - 2 = 中等证据：末尾/开头的独立数字（`凡人修仙传.201.mp4`、`219-4K.mp4`）
 * - 1 = 弱证据：藏在中间的孤立数字（只在全名只有一个数字时才认）
 */
object EpisodeKey {

    /** 判定结果。[movie] = 剧场版，[ambiguous] = 有多个候选集号无法定论 */
    data class Analysis(
        val parsed: Parsed? = null,
        val movie: Boolean = false,
        val ambiguous: Boolean = false,
    ) {
        /** 可以转存 = 有唯一集号、不是剧场版、不歧义 */
        val isHit: Boolean get() = parsed != null && !ambiguous && !movie
    }

    data class Parsed(val season: Int?, val episode: Int, val quality: Int)

    private val SEASON_EP = Regex("""[Ss](\d{1,2})\s*[ ,._\-]*[Ee][Pp]?\s*(\d{1,4})""")
    private val CN_EP = Regex("""第\s*(\d{1,4})\s*[集话話期]""")
    private val EP_PREFIX = Regex("""(?i)(?:^|[^a-z0-9])(?:ep|e)\s*[._\- ]?\s*(\d{1,4})(?!\d)""")
    // 结尾的独立数字：后面必须是结尾，或「分隔符 + 非数字」。
    // 以前写成 `[^0-9]*`（允许空），`剧名.1-12.合集` 会先命中 «.1-»（- 后面还没看是不是数字）→ 判成第 1 集；
    // 现在要求分隔符后面不能紧跟数字，尾部才会正确跳到 12。
    private val SEPARATED_TAIL = Regex("""[._\- ](\d{1,4})\s*(?=$|[._\- ](?!\d))""")
    private val LEADING = Regex("""^\s*(\d{1,4})(?![\dpPiI0-9A-Za-z])""")
    private val ANY_NUMBER = Regex("""(?<![0-9A-Za-z])(\d{1,4})(?![0-9A-Za-z])""")

    /** 剧场版 / 电影版：整体跳过，不做「剧集」处理（没有集号也不是一集） */
    private val MOVIE = Regex("""(?i)剧场版|劇場版|电影版|劇場版|映画|the[._ ]movie|(?<![a-z])movie(?![a-z])""")

    /** 技术噪声：分辨率、码率、帧率、编码、音轨、季标记、版本号（这些数字不是集号） */
    private val TECH = listOf(
        Regex("""(?i)\b\d{3,4}[pi]\b"""),
        // 4K / 2K / 8K：之前没抹掉，导致「171 4K.mp4」里 4 被当成候选集号 → 判歧义 → 整批未命中
        Regex("""(?i)\b[248]k\b"""),
        // v2 / (v2) / ver2：同集多版本的版本号，不是集号
        Regex("""(?i)\b(?:v|ver|version)\s*\.?\s*\d{1,2}\b"""),
        Regex("""(?i)\b\d{1,3}\s?[fF][pP][sS]\b"""),
        Regex("""(?i)\b\d{1,3}\s?[bB]it\b"""),
        Regex("""(?i)\b\d{3,4}\s?[x×]\s?\d{3,4}\b"""),
        Regex("""(?i)[hx]\.?26[45]"""),
        Regex("""(?i)\b(?:dd[p+]?|[aA][aA][cC]|e?[aA][cC]3)\s?\d(?:\.\d)?\b"""),
        Regex("""(?i)\b\d\.\d\b"""),
        Regex("""(?i)[Ss]\d{1,2}(?![0-9])"""),
        Regex("""(?i)\bseason\s*\d{1,2}\b"""),
        Regex("""第\s*\d{1,4}\s*[季部篇辑]""" ),
    )

    /** 排除的常见非集号数字：年份、常见分辨率 */
    private val BANNED = setOf(480, 576, 720, 1080, 1440, 2160, 4320, 3840, 360, 2048)

    /**
     * 集号的**唯一出口**：改名、通知、去重（「盘里是否已有这一集」）都调这里。
     *
     * 以前这几处各自用一套正则（[com.ashchan.cloudsaver.rename.FileNameContext] 只认 3 位），
     * 结果同一个文件在统计侧是第 1024 集、在通知侧变成第 102 集；
     * `剧名.1-12.合集` 也一样，统计认 12、改名认 1。
     * 现在统一复用 [analyze]，口径只有一份。
     *
     * 返回 null = 判不出唯一集号（有歧义、剧场版、或根本不是剧集文件），调用方按「未命中」处理。
     */
    fun numberOf(fileName: String): Int? = analyze(fileName).parsed?.episode

    /**
     * 分析一个视频文件。
     *
     * @param dirNames 从分享根到该文件父目录的目录名链（由内到外或由外到内都行，这里只找最近一层季号）
     */
    fun analyze(fileName: String, dirNames: List<String> = emptyList()): Analysis {
        val raw = fileName.substringBeforeLast('.', fileName)
        // 只认「文件名」和「紧邻父目录」，且父目录必须是纯剧场版目录。
        // 分享根/上层容器（如「F 凡人修.XZ 4K 附剧场版」）只是说明里面有剧场版，
        // 不能把整棵树都判成电影 —— 实测这会把整部剧 211 集全砍掉。
        if (MOVIE.containsMatchIn(raw) || isMovieFolder(dirNames.lastOrNull())) {
            return Analysis(movie = true)
        }

        // 1. SxxExx：季、集都拿到了，最可信
        SEASON_EP.find(raw)?.let { m ->
            val season = m.groupValues[1].toIntOrNull()
            val ep = m.groupValues[2].toIntOrNull()
            if (ep != null && ep in 1..9999) return Analysis(Parsed(season, ep, 3))
        }
        // 2. 第 N 集 / 话 / 期
        CN_EP.find(raw)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..9999 }?.let {
                return Analysis(Parsed(dirSeason(dirNames), it, 3))
            }
        }
        // 3. EP05 / E05
        EP_PREFIX.find(raw)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..9999 }?.let {
                return Analysis(Parsed(dirSeason(dirNames), it, 3))
            }
        }

        // 4. 纯数字：先把技术噪声抹掉，再从剩下的数字里挑
        val cleaned = stripTechnical(raw)
        val numbers = candidates(cleaned)
        if (numbers.isEmpty()) return Analysis()

        val tail = SEPARATED_TAIL.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
        val leading = LEADING.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()

        // 只有一个数字：看它是不是在开头/结尾这种有意义的位置
        if (numbers.size == 1) {
            val only = numbers.first()
            val quality = if (only == tail || only == leading) 2 else 1
            return Analysis(Parsed(dirSeason(dirNames), only, quality))
        }

        // 多个数字：只有「唯一一个结尾数字」或「唯一一个开头数字」才敢认，否则算歧义
        val tailValid = tail != null && tail in numbers
        val leadingValid = leading != null && leading in numbers
        return when {
            tailValid && !leadingValid || (tailValid && leadingValid && tail == leading) ->
                Analysis(Parsed(dirSeason(dirNames), tail!!, 2))
            leadingValid && !tailValid ->
                Analysis(Parsed(dirSeason(dirNames), leading!!, 2))
            else -> Analysis(ambiguous = true)
        }
    }

    /** 最近一层目录名里的季号（`Season 2` → 2，`特别篇` → 0）；没有返回 null */
    fun dirSeason(dirNames: List<String>): Int? {
        for (name in dirNames.reversed()) {
            SeasonParser.parse(name)?.let { return it }
        }
        return null
    }

    /**
     * 只有「纯剧场版语义」的目录才算电影目录：`剧场版`、`剧场版 1080p`、`Movie`。
     * `凡人修仙传 剧场版合集` 这种带剧名的容器目录不算 —— 里面通常主要是正片。
     */
    fun isMovieFolder(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || !MOVIE.containsMatchIn(n)) return false
        val rest = n
            .replace(MOVIE, "")
            .replace(
                Regex(
                    """(?i)1080p|2160p|1440p|720p|576p|480p|4k|uhd|web-?dl|webrip|blu-?ray|hdtv|remux|hevc|h\.?26[45]|aac|ddp?[\d.]*|flac|[\s._\-\[\]()【】]"""
                ),
                "",
            )
        return rest.isBlank()
    }

    /** 抹掉技术参数，剩下的数字才有可能是集号 */
    private fun stripTechnical(name: String): String {
        var out = name
        TECH.forEach { out = it.replace(out, " ") }
        return out
    }

    /** 提取候选数字：去掉年份与分辨率，按出现顺序 */
    private fun candidates(name: String): List<Int> = ANY_NUMBER.findAll(name)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it in 1..9999 && it !in BANNED && it !in 1900..2099 }
        .toList()
}
