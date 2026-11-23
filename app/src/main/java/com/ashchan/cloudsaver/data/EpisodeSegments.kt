package com.ashchan.cloudsaver.data

/**
 * 三段进度条的纯计算部分（不涉及 Compose，便于单测）。
 *
 * 分母统一是 TMDB 总集数：
 *  - have   ：盘里已有（绿色）
 *  - missing：已播但盘里没有（黄色）
 *  - unaired ：还没播出（浅橘粉）
 * 三者相加 = total。任何一段都不为负；盘里集数超过已播时，黄色为 0。
 */
data class EpisodeSegments(val have: Int, val missing: Int, val unaired: Int) {
    val total: Int get() = have + missing + unaired
}

fun episodeSegments(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int): EpisodeSegments {
    if (tmdbTotal <= 0) return EpisodeSegments(0, 0, 0)
    val have = localEpisodes.coerceIn(0, tmdbTotal)
    val aired = tmdbLatest.coerceIn(0, tmdbTotal)
    val missing = (aired - have).coerceAtLeast(0)
    val unaired = (tmdbTotal - maxOf(have, aired)).coerceAtLeast(0)
    return EpisodeSegments(have, missing, unaired)
}
