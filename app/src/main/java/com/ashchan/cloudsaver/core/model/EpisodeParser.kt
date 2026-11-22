package com.ashchan.cloudsaver.core.model

/**
 * 从文件名解析集号。
 *
 * 实测的真实命名（夸克分享「斗 P穹4」）：`219-4K.mp4`、`218_4K.mkv`、`201-4k.mp4`
 * —— 集号在开头，没有 S/E 前缀。光鸭的命名是 `... S01E01 - 第 1 集 - 2160p ...`。
 * 两种都必须认，否则「盘里已有多少集」会算成 0。
 *
 * 识别顺序（先精确后宽松）：
 * 1. `S01E05` / `S01.E05` / `S01_EP5`
 * 2. `第5集` / `第 5 话`
 * 3. `E05` / `EP05` / `EP.05`
 * 4. 文件名开头的数字（`219-4K` → 219）
 *
 * 明确排除分辨率：`1080p` / `2160p` 前缀后紧跟 p/P 不当作集号。
 */
object EpisodeParser {

    private val SEASON_EP = Regex("""[Ss](\d{1,2})\s*[ ,._\-]*[Ee][Pp]?\s*(\d{1,4})""")
    private val CN_EP = Regex("""第\s*(\d{1,4})\s*[集话話期]""")
    private val EP = Regex("""(?<![A-Za-z])[Ee][Pp]?\s*[._\- ]?\s*(\d{1,4})""")
    private val LEADING = Regex("""^\s*(\d{1,4})(?![\dpPiI])""")

    /** 解析失败返回 null（非剧集文件、海报、压缩包等） */
    fun episodeOf(fileName: String): Int? {
        val name = fileName.substringBeforeLast('.', fileName)
        SEASON_EP.find(name)?.let { return it.groupValues[2].toIntOrNull() }
        CN_EP.find(name)?.let { return it.groupValues[1].toIntOrNull() }
        EP.find(name)?.let { return it.groupValues[1].toIntOrNull() }
        LEADING.find(name)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** 季号（有则返回，用于「多季合集」去重；没有季号返回 null） */
    fun seasonOf(fileName: String): Int? {
        val name = fileName.substringBeforeLast('.', fileName)
        return SEASON_EP.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 是否剧集文件：能解析出集号，且是视频扩展名 */
    fun isEpisodeFile(fileName: String, isVideo: Boolean): Boolean =
        isVideo && episodeOf(fileName) != null
}
