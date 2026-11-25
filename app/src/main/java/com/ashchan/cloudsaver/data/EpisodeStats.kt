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
 *
 * [Result] 里除了结论还带**诊断字段**（扫到几个、几个重复、几个解析不出、季分布），
 * 卡片数字对不上时可以直接从运行日志里看出是哪一类问题，不用再靠猜。
 */
object EpisodeStats {

    /** 一条盘内文件：相对目标目录的目录链 + 文件名 */
    data class Entry(val dirs: List<String>, val name: String)

    data class Result(
        val episodes: Int,
        val lastEpisode: Int,
        val bySeason: Map<Int, Int> = emptyMap(),
        /** 扫到的视频文件数（已排除样本/预告等噪声） */
        val scanned: Int = 0,
        /** 落进统计口径的文件数（同集多版本也会重复计） */
        val counted: Int = 0,
        /** 同一集的文件多出来的部分：counted - 不同集号数 */
        val duplicates: Int = 0,
        /** 有集号、但季号不在统计口径里（例如 Season 0 / 第二季）的文件数 */
        val otherSeason: Int = 0,
        /** 解析不出集号的文件（最多保留 8 个名字用于排查） */
        val unparsed: List<String> = emptyList(),
        val unparsedCount: Int = 0,
        /** 有多个候选数字、判不出集号的文件数 */
        val ambiguous: Int = 0,
        /** 被判成剧场版而跳过的文件数 */
        val movies: Int = 0,
        /**
         * 多季口径：所有季各自去重后相加的集数（无季号的文件算第 1 季）。
         * 季内集号各从 1 起，不能全局去重，只能逐季数完再加。
         */
        val episodesAll: Int = 0,
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
        val bucketFiles = mutableMapOf<Int?, Int>()
        var scanned = 0
        var unparsedCount = 0
        var ambiguous = 0
        var movies = 0
        val unparsed = mutableListOf<String>()

        entries.forEach { entry ->
            if (!VideoTypes.isVideo(entry.name) || VideoTypes.isNoise(entry.name)) return@forEach
            scanned++
            val key = EpisodeKey.analyze(entry.name, entry.dirs)
            if (key.movie) {
                movies++
                return@forEach
            }
            val ep = key.parsed?.episode?.takeIf { it in 1..9999 }
            if (ep == null) {
                unparsedCount++
                if (key.ambiguous) ambiguous++
                if (unparsed.size < 8) unparsed += entry.name
                return@forEach
            }
            val season = seasonOf(entry)
            bySeason.getOrPut(season) { mutableSetOf() } += ep
            bucketFiles[season] = (bucketFiles[season] ?: 0) + 1
        }

        val chosenBuckets: List<Int?> = when (mode) {
            // 目标目录本身就是 `Season N`，无季号的文件也算这一季
            Mode.FIXED -> listOf(fixedSeason, null)
            // 多季只数目标那一季；无季号的文件按第一季算
            Mode.MULTI -> if (targetSeason == 1) listOf(targetSeason, null) else listOf(targetSeason)
            // 平铺的口径就是第一季（剧名.S01E01.后缀）
            Mode.FLAT -> listOf(1, null)
        }
        val chosen = chosenBuckets.flatMap { bySeason[it].orEmpty() }.toSortedSet()
        val countedFiles = chosenBuckets.sumOf { bucketFiles[it] ?: 0 }
        val otherSeason = bucketFiles.filterKeys { it !in chosenBuckets }.values.sum()
        val mergedAll = mutableMapOf<Int, MutableSet<Int>>()
        bySeason.forEach { (k, set) ->
            // 特别版（Season 0）不算进多季总数，跟 TMDB 只累加正季的口径保持一致
            if (k == 0) return@forEach
            val key = k ?: 1
            mergedAll.getOrPut(key) { mutableSetOf() } += set
        }
        val episodesAll = mergedAll.values.sumOf { it.size }

        return Result(
            episodes = chosen.size,
            lastEpisode = chosen.lastOrNull() ?: 0,
            bySeason = bySeason.filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.size }
                .toSortedMap(),
            scanned = scanned,
            counted = countedFiles,
            duplicates = (countedFiles - chosen.size).coerceAtLeast(0),
            otherSeason = otherSeason,
            unparsed = unparsed.toList(),
            unparsedCount = unparsedCount,
            ambiguous = ambiguous,
            movies = movies,
            episodesAll = episodesAll,
        )
    }

    /** 文件季号：文件名 `SxxExx` 优先，其次最近一层季目录名 */
    fun seasonOf(entry: Entry): Int? =
        EpisodeParser.seasonOf(entry.name) ?: EpisodeKey.dirSeason(entry.dirs)
}
