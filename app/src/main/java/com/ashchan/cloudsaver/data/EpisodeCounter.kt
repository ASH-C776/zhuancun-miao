package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.core.model.EpisodeParser
import com.ashchan.cloudsaver.core.model.VideoTypes

/**
 * 「盘里已有多少集」的统计口径（纯函数）。
 *
 * 为什么不能只看目标目录一层：
 * 常见结构是**根目录平铺当前季、旧集收在下一级目录**，只看根目录会把 200 集算成 1 集。
 * 所以调用方应把「目标目录 + 直接子目录」的文件名一起传进来，
 * 这里按**集号去重**合并，两层自然不会重复计数。
 */
object EpisodeCounter {

    data class Result(val episodes: Int, val lastEpisode: Int)

    fun count(fileNames: Collection<String>): Result {
        val episodes = fileNames.asSequence()
            // 只认可识别的视频，跳过海报 / nfo / zip（zip 名里的数字不是集号）
            .filter { VideoTypes.isVideo(it) && !VideoTypes.isNoise(it) }
            .mapNotNull { EpisodeParser.episodeOf(it) }
            .filter { it in 1..9999 }
            .toSortedSet()
        return Result(episodes = episodes.size, lastEpisode = episodes.lastOrNull() ?: 0)
    }
}
