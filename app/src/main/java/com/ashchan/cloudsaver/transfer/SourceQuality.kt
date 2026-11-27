package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.core.model.VideoTypes

/**
 * 源质量打分（纯函数）：多源任务里**谁的名字更好解析，就先跑谁**。
 *
 * 只看目录树里的文件名，不下载、不请求额外接口。五项指标：
 * 1. 可解析率 40 分：能定出唯一集号的文件占比（主项）
 * 2. 命名规范度 20 分：命中文件里 `SxxExx`/`第N集` 这类强证据的占比
 * 3. 集号连续度 15 分：集号区间内的覆盖比例（跳号、重号都扣）
 * 4. 同集多版本 −15 分：每多一个版本文件扣 5 分，上限 15
 * 5. 集号歧义 −20 分：每个判不出集号的文件扣 5 分，上限 20
 */
data class SourceScore(
    val videos: Int,
    val hits: Int,
    val strong: Int,
    val medium: Int,
    val movies: Int,
    val unmatched: Int,
    val ambiguous: Int,
    val duplicateExtras: Int,
    val coverage: Double,
    val score: Int,
) {
    val hitRate: Double get() = if (videos <= 0) 0.0 else hits.toDouble() / videos

    /** 展示用一行：`命中 96%（24/25），命名规范 22/25，集号覆盖 96%` */
    fun note(): String = buildString {
        append("命中 ").append(percent(hitRate)).append("（").append(hits).append('/').append(videos).append("）")
        append("，命名规范 ").append(strong).append('/').append(videos)
        if (coverage > 0) append("，集号覆盖 ").append(percent(coverage))
        if (duplicateExtras > 0) append("，同集多版本 ").append(duplicateExtras)
        if (ambiguous > 0) append("，歧义 ").append(ambiguous)
        if (movies > 0) append("，剧场版 ").append(movies)
    }

    private fun percent(v: Double): String = "${(v * 100).toInt()}%"
}

object SourceQuality {

    /** 同一批源里分数越高越优先（分数相同保持原链接顺序） */
    fun score(tree: ShareNode): SourceScore {
        val items = TransferClassifier.classify(tree)
        val videos = tree.flattenFiles().count { it.isVideo && !VideoTypes.isNoise(it.name) }
        val main = items.filter { it.kind == ItemKind.MAIN }
        val strong = main.count { it.quality >= 3 }
        val medium = main.count { it.quality == 2 }
        val movies = items.count { it.kind == ItemKind.MOVIE }
        val unmatched = items.count { it.kind == ItemKind.UNMATCHED }
        val ambiguous = items.count { it.ambiguous }

        // 同集多版本：多出来的那部分文件数
        val duplicateExtras = main.groupBy { it.key ?: it.node.fid }
            .values.sumOf { (it.size - 1).coerceAtLeast(0) }

        val hitRate = if (videos <= 0) 0.0 else main.size.toDouble() / videos
        val namingRate = if (videos <= 0) 0.0 else (strong + medium * 0.6) / videos
        // 覆盖度按季分开算再加权：各季集号都从 1 起，混在一起算会把「两季都齐」误判成「覆盖 50%」
        val coverage = if (main.isEmpty()) {
            0.0
        } else {
            main.groupBy { it.season ?: -1 }.values.sumOf { group ->
                coverage(group.mapNotNull { it.episode }) * group.size
            } / main.size
        }

        val raw = 40.0 * hitRate +
            20.0 * namingRate +
            15.0 * coverage -
            5.0 * duplicateExtras.coerceAtMost(3) -
            5.0 * ambiguous.coerceAtMost(4)

        return SourceScore(
            videos = videos,
            hits = main.size,
            strong = strong,
            medium = medium,
            movies = movies,
            unmatched = unmatched,
            ambiguous = ambiguous,
            duplicateExtras = duplicateExtras,
            coverage = coverage,
            score = raw.coerceIn(0.0, 100.0).toInt(),
        )
    }

    /** 集号覆盖：区间内有多少比例集号存在（只有 1 集时按 1 算，避免除零） */
    private fun coverage(episodes: List<Int>): Double {
        if (episodes.size < 2) return if (episodes.isEmpty()) 0.0 else 1.0
        val min = episodes.min()
        val max = episodes.max()
        val span = max - min + 1
        if (span <= 0) return 1.0
        return (episodes.distinct().size.toDouble() / span).coerceIn(0.0, 1.0)
    }
}
