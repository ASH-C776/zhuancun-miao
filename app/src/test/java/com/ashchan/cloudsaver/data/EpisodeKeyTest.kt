package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集号判定：只有「唯一、无歧义」才算命中。
 * 用例全部来自真实资源形态（夸克平铺、光鸭 SxxExx、特别篇、剧场版、带分辨率/年份的脏名）。
 */
class EpisodeKeyTest {

    @Test
    fun `SxxExx 是强证据 季和集都拿到`() {
        val a = EpisodeKey.analyze("Show.S02E05.2160p.WEB-DL.mkv")
        assertTrue(a.isHit)
        assertEquals(2, a.parsed?.season)
        assertEquals(5, a.parsed?.episode)
        assertEquals(3, a.parsed?.quality)
    }

    @Test
    fun `第N集 与 EP 前缀都是强证据`() {
        assertEquals(7, EpisodeKey.analyze("某某剧 第07集.mp4").parsed?.episode)
        assertEquals(12, EpisodeKey.analyze("Show.EP12.mkv").parsed?.episode)
        assertEquals(12, EpisodeKey.analyze("Show - E12.mkv").parsed?.episode)
    }

    @Test
    fun `末尾数字是中等证据 开头数字也算`() {
        val tail = EpisodeKey.analyze("凡人修仙传.201.mp4")
        assertTrue(tail.isHit)
        assertEquals(201, tail.parsed?.episode)
        assertEquals(2, tail.parsed?.quality)

        val leading = EpisodeKey.analyze("219-4K.mp4")
        assertTrue(leading.isHit)
        assertEquals(219, leading.parsed?.episode)
    }

    @Test
    fun `年份和分辨率不是集号`() {
        // 只有 2020 这种年份 + 1080p，不应命中
        val a = EpisodeKey.analyze("Show.2020.1080p.mkv")
        assertFalse(a.isHit)
    }

    @Test
    fun `开头和结尾各有一个数字 判不出 不命中`() {
        val a = EpisodeKey.analyze("12-Show-25.mkv")
        assertFalse(a.isHit)
        assertTrue(a.ambiguous)
    }

    @Test
    fun `剧场版整体不算剧集`() {
        val file = EpisodeKey.analyze("凡人修仙传 剧场版.mp4")
        assertTrue(file.movie)
        assertFalse(file.isHit)

        // 纯剧场版目录才算电影目录
        val dir = EpisodeKey.analyze("E01.mp4", listOf("某某剧", "剧场版"))
        assertTrue(dir.movie)
    }

    @Test
    fun `剧名里带剧场版的容器目录不误杀正片`() {
        // 回报的真实分享名：F 凡人修.XZ 4K 附剧场版 —— 里面主要是正片
        val a = EpisodeKey.analyze("181 4K.mp4", listOf("F 凡人修.XZ 4K 附剧场版", "1-190集"))
        assertTrue(a.isHit)
        assertFalse(a.movie)
        assertEquals(181, a.parsed?.episode)
    }

    @Test
    fun `特别篇目录给出 Season 0`() {
        val a = EpisodeKey.analyze("Special.01.mkv", listOf("某某剧", "特别篇"))
        assertEquals(0, a.parsed?.season)
        assertEquals(1, a.parsed?.episode)
    }

    @Test
    fun `最近一层季目录名优先`() {
        val a = EpisodeKey.analyze("E05.mkv", listOf("剧名", "Season 2", "2160p"))
        assertEquals(2, a.parsed?.season)
    }

    @Test
    fun `没有数字的预告片不命中`() {
        val a = EpisodeKey.analyze("预告.mkv")
        assertFalse(a.isHit)
        assertNull(a.parsed)
    }
}
