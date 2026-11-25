package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分季统计：多季不再混算、特别篇不混进正片、平铺按第一季。
 * 数字取自用户截图里的真实问题（凡人修仙传「124 集却显示缺 87」）。
 */
class EpisodeStatsTest {

    private fun e(vararg dirs: String, name: String) = EpisodeStats.Entry(dirs.toList(), name)

    @Test
    fun `指定季只数该季 别的季目录不算`() {
        val entries = listOf(
            e("Season 1", name = "Show.S01E01.mkv"),
            e("Season 1", name = "Show.S01E02.mkv"),
            e("Season 2", name = "Show.S02E01.mkv"),
        )
        // 目标目录本身就是 Season 1，不会扫到 Season 2；这里模拟混进来的情况
        val r = EpisodeStats.count(entries, EpisodeStats.Mode.FIXED, fixedSeason = 1)
        assertEquals(2, r.episodes)
        assertEquals(2, r.lastEpisode)
    }

    @Test
    fun `多季只看目标季 不把两季集号合并`() {
        val entries = (1..10).map { e("Season 1", name = "Show.S01E%02d.mkv".format(it)) } +
            (1..5).map { e("Season 2", name = "Show.S02E%02d.mkv".format(it)) }
        val r2 = EpisodeStats.count(entries, EpisodeStats.Mode.MULTI, targetSeason = 2)
        assertEquals(5, r2.episodes)
        assertEquals(mapOf(1 to 10, 2 to 5), r2.bySeason)
        // 多季总数：各季分别去重后相加（季内集号各从 1 起，不能全局去重）
        assertEquals(15, r2.episodesAll)

        val r1 = EpisodeStats.count(entries, EpisodeStats.Mode.MULTI, targetSeason = 1)
        assertEquals(10, r1.episodes)
        assertEquals(10, r1.lastEpisode)
    }

    @Test
    fun `平铺按第一季 特别篇和别的季不计`() {
        val entries = (1..3).map { e(name = "Show.%02d.mp4".format(it)) } +
            listOf(e("Season 0", name = "特别篇.01.mkv"), e("Season 2", name = "Show.S02E01.mkv"))
        val r = EpisodeStats.count(entries, EpisodeStats.Mode.FLAT)
        assertEquals(3, r.episodes)
        assertEquals(3, r.lastEpisode)
    }

    @Test
    fun `剧场版文件不进集数`() {
        val entries = listOf(
            e(name = "Show.S01E01.mkv"),
            e(name = "Show 剧场版.1080p.mp4"),
            e(name = "Show.S01E02.mkv"),
        )
        val r = EpisodeStats.count(entries, EpisodeStats.Mode.FIXED, fixedSeason = 1)
        assertEquals(2, r.episodes)
    }

    @Test
    fun `目录名给季号 文件名只有集号时按目录归季`() {
        val entries = listOf(
            e("第 2 季", name = "E05.mkv"),
            e("第 2 季", name = "E06.mkv"),
            e("第 1 季", name = "E01.mkv"),
        )
        val r = EpisodeStats.count(entries, EpisodeStats.Mode.MULTI, targetSeason = 2)
        assertEquals(2, r.episodes)
        assertEquals(6, r.lastEpisode)
    }
}
