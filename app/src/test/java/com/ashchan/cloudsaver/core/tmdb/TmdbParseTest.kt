package com.ashchan.cloudsaver.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TMDB 解析纯逻辑：播出日 → 星期、最新集号口径 */
class TmdbParseTest {

    // 2026-11-23 是周一
    @Test
    fun `播出日换算星期`() {
        assertEquals(1, weekdayOf("2026-11-23")) // 周一
        assertEquals(6, weekdayOf("2026-11-28")) // 周六
        assertEquals(7, weekdayOf("2026-11-29")) // 周日
    }

    @Test
    fun `非法日期返回 null`() {
        assertNull(weekdayOf(""))
        assertNull(weekdayOf("2026-11"))
        assertNull(weekdayOf("abc"))
        assertNull(weekdayOf("2026-13-45"))
    }

    @Test
    fun `多季总数只累加正季 特别版不算进去`() {
        val withSeasons = TmdbTvInfo(
            id = 1,
            name = "一念永恒",
            lastEpisode = null,
            nextEpisode = null,
            numberOfSeasons = 4,
            totalEpisodes = 211,
            seasons = listOf(
                TmdbSeason(0, "特别版", 3, "2020-01-01"),
                TmdbSeason(1, "Season 1", 52, "2020-08-12"),
                TmdbSeason(2, "Season 2", 54, "2022-07-20"),
                TmdbSeason(3, "Season 3", 59, "2024-07-03"),
                TmdbSeason(4, "Final Season", 46, "2026-07-22"),
            ),
        )
        assertEquals(211, withSeasons.totalOfProperSeasons())
        assertEquals(59, withSeasons.seasonCount(3))
        assertEquals(0, withSeasons.seasonCount(9))
    }

    private val info = TmdbTvInfo(
        id = 1,
        name = "测试剧",
        lastEpisode = TmdbEpisode(season = 2, episode = 10, airDate = "2026-11-21"),
        nextEpisode = TmdbEpisode(season = 2, episode = 11, airDate = "2026-11-28"),
        numberOfSeasons = 5,
    )

    @Test
    fun `未指定季 取整部剧最新集号`() {
        assertEquals(10, info.latestEpisodeNumber())
    }

    @Test
    fun `指定季与最新集同季 取该集号`() {
        assertEquals(10, info.latestEpisodeNumber(season = 2))
    }

    @Test
    fun `指定季不是最新季 返回 0 避免误报缺集`() {
        assertEquals(0, info.latestEpisodeNumber(season = 1))
    }

    @Test
    fun `更新日优先下周播出日`() {
        assertEquals(6, info.weekdayHint())
    }

    @Test
    fun `没有下一集时退回上一集播出日`() {
        val ended = info.copy(nextEpisode = null)
        assertEquals(6, ended.weekdayHint())
    }

    @Test
    fun `没有任何播出日期返回 null`() {
        val unknown = info.copy(nextEpisode = null, lastEpisode = null)
        assertNull(unknown.weekdayHint())
    }
}
