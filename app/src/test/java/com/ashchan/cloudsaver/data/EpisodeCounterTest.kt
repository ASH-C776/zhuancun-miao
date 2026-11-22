package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 集数统计口径测试：两层合并、集号去重、非视频剔除。 */
class EpisodeCounterTest {

    @Test
    fun `根目录平铺 旧集在下级目录 合并去重`() {
        val root = listOf("219-4K.mp4", "218_4K.mkv", "217-4K.mp4")
        val archive = listOf("216-4K.mp4", "215-4K.mp4", "215-4K.duplicate.mp4")
        val result = EpisodeCounter.count(root + archive)
        // 216..219 共 4 个集号，215 重复只算一次
        assertEquals(5, result.episodes)
        assertEquals(219, result.lastEpisode)
    }

    @Test
    fun `SxxExx 命名`() {
        val result = EpisodeCounter.count(
            listOf(
                "Fights Break Sphere S01E01 - 第 1 集.mp4",
                "Fights Break Sphere S01E02 - 第 2 集.mp4",
                "Fights Break Sphere S01E10 - 第 10 集.mp4",
            )
        )
        assertEquals(3, result.episodes)
        assertEquals(10, result.lastEpisode)
    }

    @Test
    fun `海报 nfo zip 不算集数`() {
        val result = EpisodeCounter.count(
            listOf(
                "219-4K.mp4",
                "poster.jpg",
                "tvshow.nfo",
                "130-4K.zip",
                "sample-trailer.mp4",
            )
        )
        assertEquals(1, result.episodes)
        assertEquals(219, result.lastEpisode)
    }

    @Test
    fun `空目录`() {
        val result = EpisodeCounter.count(emptyList())
        assertEquals(0, result.episodes)
        assertEquals(0, result.lastEpisode)
    }

    @Test
    fun `分辨率不会被统计`() {
        val result = EpisodeCounter.count(listOf("1080p.mp4", "2160p.mp4", "第3集.mp4"))
        assertEquals(1, result.episodes)
        assertEquals(3, result.lastEpisode)
    }
}
