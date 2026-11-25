package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 三段进度条的配比口径：绿=已有，黄=已播缺集，粉=未播；和恒等于总集数。 */
class EpisodeSegmentsTest {

    @Test
    fun `三等分示例 10 已播 20 共 30`() {
        val s = episodeSegments(localLastEpisode = 10, tmdbLatest = 20, tmdbTotal = 30)
        assertEquals(10, s.have)
        assertEquals(10, s.missing)
        assertEquals(10, s.unaired)
        assertEquals(30, s.total)
    }

    @Test
    fun `全部追完 全部变绿`() {
        val s = episodeSegments(localLastEpisode = 30, tmdbLatest = 30, tmdbTotal = 30)
        assertEquals(EpisodeSegments(30, 0, 0), s)
    }

    @Test
    fun `盘里超前于已播 黄色为 0`() {
        // 去重集号 12 集，TMDB 只播到 10，共 30
        val s = episodeSegments(localLastEpisode = 12, tmdbLatest = 10, tmdbTotal = 30)
        assertEquals(12, s.have)
        assertEquals(0, s.missing)
        assertEquals(18, s.unaired)
    }

    @Test
    fun `集数超过总集数 只保留绿色到底`() {
        val s = episodeSegments(localLastEpisode = 35, tmdbLatest = 35, tmdbTotal = 30)
        assertEquals(30, s.have)
        assertEquals(0, s.missing)
        assertEquals(0, s.unaired)
    }

    @Test
    fun `一集都没有 全黄加粉`() {
        val s = episodeSegments(localLastEpisode = 0, tmdbLatest = 12, tmdbTotal = 30)
        assertEquals(0, s.have)
        assertEquals(12, s.missing)
        assertEquals(18, s.unaired)
    }

    @Test
    fun `未绑定 TMDB 无总集数 返回全零`() {
        val s = episodeSegments(localLastEpisode = 8, tmdbLatest = 10, tmdbTotal = 0)
        assertEquals(EpisodeSegments(0, 0, 0), s)
    }

    @Test
    fun `负数输入按 0 处理`() {
        val s = episodeSegments(localLastEpisode = -1, tmdbLatest = 5, tmdbTotal = 10)
        assertEquals(0, s.have)
        assertEquals(5, s.missing)
        assertEquals(5, s.unaired)
    }

    @Test
    fun `中间缺集不影响绿条 只看最大集号`() {
        // 盘里只有 114 个文件（缺 97 集），但最大集号已到 201、TMDB 最新也是 201、共 211
        val s = episodeSegments(localLastEpisode = 201, tmdbLatest = 201, tmdbTotal = 211)
        assertEquals(201, s.have)
        assertEquals(0, s.missing)
        assertEquals(10, s.unaired)
    }
}

