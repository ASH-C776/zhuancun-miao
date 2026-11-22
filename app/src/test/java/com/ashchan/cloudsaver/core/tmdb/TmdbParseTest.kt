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
