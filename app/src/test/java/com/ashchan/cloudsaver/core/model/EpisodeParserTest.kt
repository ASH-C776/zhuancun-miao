package com.ashchan.cloudsaver.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 集号解析测试。样本全部取自真实分享的文件名。 */
class EpisodeParserTest {

    @Test
    fun `夸克真实命名 集号在开头`() {
        assertEquals(219, EpisodeParser.episodeOf("219-4K.mp4"))
        assertEquals(218, EpisodeParser.episodeOf("218_4K.mkv"))
        assertEquals(201, EpisodeParser.episodeOf("201-4k.mp4"))
    }

    @Test
    fun `光鸭真实命名 SxxExx 优先`() {
        assertEquals(
            1,
            EpisodeParser.episodeOf(
                "Fights Break Sphere S01E01 - 第 1 集 - 2160p WEB-DL HEVC AAC.mp4"
            )
        )
        assertEquals(
            13,
            EpisodeParser.episodeOf("Fights Break Sphere S01E13 - 第 13 集 - 2160p.mp4")
        )
    }

    @Test
    fun `E 与 EP 前缀`() {
        assertEquals(5, EpisodeParser.episodeOf("凡人修仙传.E05.mkv"))
        assertEquals(5, EpisodeParser.episodeOf("凡人修仙传.EP05.1080p.mkv"))
        assertEquals(5, EpisodeParser.episodeOf("凡人修仙传.EP.05.mkv"))
    }

    @Test
    fun `中文集号`() {
        assertEquals(5, EpisodeParser.episodeOf("仙逆 第5集.mp4"))
        assertEquals(12, EpisodeParser.episodeOf("仙逆_第 12 集_2160p.mkv"))
    }

    @Test
    fun `分辨率不会被当成集号`() {
        assertNull(EpisodeParser.episodeOf("1080p.mp4"))
        assertNull(EpisodeParser.episodeOf("2160p WEB-DL.mkv"))
        assertNull(EpisodeParser.episodeOf("poster.jpg"))
        assertNull(EpisodeParser.episodeOf("tvshow.nfo"))
    }

    @Test
    fun `季号解析`() {
        assertEquals(2, EpisodeParser.seasonOf("Fights Break Sphere S02E13.mp4"))
        assertNull(EpisodeParser.seasonOf("219-4K.mp4"))
    }

    @Test
    fun `是否剧集文件`() {
        assertTrue(EpisodeParser.isEpisodeFile("219-4K.mp4", isVideo = true))
        assertFalse(EpisodeParser.isEpisodeFile("219-4K.mp4", isVideo = false))
        assertFalse(EpisodeParser.isEpisodeFile("poster.jpg", isVideo = false))
    }
}
