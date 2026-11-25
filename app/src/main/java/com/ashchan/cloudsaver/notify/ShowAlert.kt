package com.ashchan.cloudsaver.notify

/**
 * 「一条剧集提醒」的数据模型 —— 纯数据 + 纯文案，不碰 Android。
 *
 * 文案口径（用户定）：
 * - 标题：`斗破苍穹更新 S05E207`
 * - 正文：`共 1 集`
 * - 连更多集仍是一条，标题写范围：`斗破苍穹更新 S05E207-E209`
 */
data class ShowAlert(
    val taskId: Long,
    /** 剧名（已去掉末尾年份与 tmdb 尾巴） */
    val title: String,
    /** 季号；判不出为 null */
    val season: Int?,
    val firstEpisode: Int?,
    val lastEpisode: Int?,
    /** 实际新增集数（去重后） */
    val count: Int,
    /** 末集所在季；跨季时会与 [season] 不同 */
    val lastSeason: Int? = season,
) {

    /** `S05E207` / `S05E207-E209` / `S01E012-S02E001`；判不出集号时为 null */
    val episodeLabel: String? =
        AlertText.episodeLabel(season, firstEpisode, lastEpisode, lastSeason)

    val notificationTitle: String = AlertText.title(title, episodeLabel)

    val notificationText: String = AlertText.body(count)

    /** 去重指纹：同一剧同一批集只提醒一次 */
    val fingerprint: String get() = "$taskId|$season|$firstEpisode|$lastEpisode|$count"
}

/** 一条「落盘文件对应的集」：季/集都是结构化字段，不再把文件名带进通知层 */
typealias EpisodeTag = com.ashchan.cloudsaver.core.model.EpisodeTag

/** 通知文案的唯一出口（纯函数，单测覆盖） */
object AlertText {

    /** 去掉剧名里的末尾年份/TMDB 尾巴：「斗破苍穹 (2017)」→「斗破苍穹」 */
    fun stripYear(raw: String): String {
        var name = raw.trim()
        // 只裁结尾的年份括号，剧名中间的括号（「(2024) 特别篇」）原样保留
        name = name.replace(Regex("""\s*[（(]\s*(?:18|19|20)\d{2}\s*[）)]\s*$"""), "")
        name = name.replace(
            Regex("""\s*[（(]\s*tmdb\s*[：:]?\s*\d+\s*[）)]\s*$""", RegexOption.IGNORE_CASE),
            "",
        )
        // 裸年份尾巴（「斗破苍穹 2017」）：只在结尾、且前面有内容时裁掉
        name = name.replace(Regex("""(?<=\S)\s+(?:18|19|20)\d{2}$"""), "")
        return name.trim().ifEmpty { raw.trim() }
    }

    /** 单集标签：`S05E207`；季号为空/0 退化成 `E207` */
    fun episodeTag(season: Int?, episode: Int): String {
        val ep = "E" + episode.toString().padStart(3, '0')
        return if (season != null && season > 0) "S${season.toString().padStart(2, '0')}$ep" else ep
    }

    /** 范围标签：同季时尾部省略 `S05`（`S05E207-E209`），跨季两头都写全 */
    fun episodeLabel(
        season: Int?,
        first: Int?,
        last: Int?,
        lastSeason: Int? = season,
    ): String? {
        val head = first ?: return null
        val tail = last ?: head
        val headTag = episodeTag(season, head)
        if (tail == head && lastSeason == season) return headTag
        val tailTag = if (season != null && season > 0 && lastSeason == season) {
            "E" + tail.toString().padStart(3, '0')
        } else {
            episodeTag(lastSeason, tail)
        }
        return "$headTag-$tailTag"
    }

    fun title(name: String, label: String?): String =
        label?.let { "${name}更新 $it" } ?: "${name}更新"

    fun body(count: Int): String = "共 $count 集"
}
