package com.ashchan.cloudsaver.data

/**
 * 三段进度条的纯计算部分（不涉及 Compose，便于单测）。
 *
 * 进度条只看「追到第几集」，不看中间有没有断档：
 *  - have   ：盘里**最大集号**占到的进度（绿色）
 *  - missing：已播到最新，但盘里最大集号还没追上（黄色）
 *  - unaired ：还没播出（浅橘粉）
 *
 * 分母统一是 TMDB 总集数，三者相加 = total。
 * 注意：这里**不涉及**「一共有多少个文件」——盘里缺了中间几集不会让绿条变短，
 * 那种「完整度缺口」由 [com.ashchan.cloudsaver.data.SnapshotCalc.gap]
 * 单独算，只写进运行日志，不在进度条上表达。
 */
data class EpisodeSegments(val have: Int, val missing: Int, val unaired: Int) {
    val total: Int get() = have + missing + unaired
}

fun episodeSegments(localLastEpisode: Int, tmdbLatest: Int, tmdbTotal: Int): EpisodeSegments {
    if (tmdbTotal <= 0) return EpisodeSegments(0, 0, 0)
    val have = localLastEpisode.coerceIn(0, tmdbTotal)
    val aired = tmdbLatest.coerceIn(0, tmdbTotal)
    val missing = (aired - have).coerceAtLeast(0)
    val unaired = (tmdbTotal - maxOf(have, aired)).coerceAtLeast(0)
    return EpisodeSegments(have, missing, unaired)
}
