package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.core.model.EpisodeParser
import com.ashchan.cloudsaver.core.model.VideoTypes

/**
 * 盘里已有集数的**按季分区统计**（纯函数）。
 *
 * 旧实现把所有季目录的文件名倒进同一个集号集合，多季任务必然算错
 * （凡人修仙传 124 个集号被当成"已收 124 集"、缺 87 集就是这么来的）。
 *
 * 三种口径，与任务设置一一对应：
 * - [Mode.FIXED]：指定某一季（含 0 = 特别版）。目标目录本身就是 `Season N`，只数这一季；
 * - [Mode.MULTI]：多季。按文件自身季号分区，只数 [targetSeason]（通常是最新一季）那一区；
 * - [Mode.FLAT]：平铺。平铺 + 通用命名的口径就是第一季（`剧名.S01E01.后缀`），
 *   所以只数「第一季或无季号」的文件，Season 0 与其它季目录不计入。
 */
object EpisodeStats {

    /** 一条盘内文件：相对目标目录的目录链 + 文件名 */
    data class Entry(val dirs: List<String>, val name: String)

    data class Result(
        val episodes: Int,
        val lastEpisode: Int,
        val bySeason: Map<Int, Int> = emptyMap(),
    )

    enum class Mode { FIXED, MULTI, FLAT }

    /**
     * @param fixedSeason [Mode.FIXED] 时的季号（0 = 特别版）
     * @param targetSeason [Mode.MULTI] 时要统计的季（0 = 特别版，默认第一季）
     */
    fun count(
        entries: List<Entry>,
        mode: Mode,
        fixedSeason: Int = 1,
        targetSeason: Int = 1,
    ): Result {
        // 先把所有视频按季归桶（诊断与展示用），再按口径挑出要统计的那一桶
        val bySeason = mutableMapOf<Int?, MutableSet<Int>>()

        entries.forEach { entry ->
            if (!VideoTypes.isVideo(entry.name) || VideoTypes.isNoise(entry.name)) return@forEach
            val key = EpisodeKey.analyze(entry.name, entry.dirs)
            if (key.movie) return@forEach
            val ep = key.parsed?.episode?.takeIf { it in 1..9999 } ?: return@forEach
            bySeason.getOrPut(seasonOf(entry)) { mutableSetOf() } += ep
        }

        val chosen: Set<Int> = when (mode) {
            // 目标目录本身就是 `Season N`，无季号的文件也算这一季
            Mode.FIXED -> (bySeason[fixedSeason] ?: emptySet()) + (bySeason[null] ?: emptySet())
            // 多季只数目标那一季；无季号的文件按第一季算
            Mode.MULTI -> (bySeason[targetSeason] ?: emptySet()) +
                (if (targetSeason == 1) bySeason[null] ?: emptySet() else emptySet())
            // 平铺的口径就是第一季（剧名.S01E01.后缀）
            Mode.FLAT -> (bySeason[1] ?: emptySet()) + (bySeason[null] ?: emptySet())
        }

        val sorted = chosen.toSortedSet()
        return Result(
            episodes = sorted.size,
            lastEpisode = sorted.lastOrNull() ?: 0,
            bySeason = bySeason.filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.size }
                .toSortedMap(),
        )
    }

    /** 文件季号：文件名 `SxxExx` 优先，其次最近一层季目录名 */
    fun seasonOf(entry: Entry): Int? =
        EpisodeParser.seasonOf(entry.name) ?: EpisodeKey.dirSeason(entry.dirs)
}
