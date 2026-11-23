package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.SourceLinks
import org.junit.Assert.assertEquals
import org.junit.Test

/** 多源链接整理：去空 / 去重 / 上限 5 / 空列表退回任务的旧 shareUrl */
class SourceLinksTest {

    @Test
    fun `单源原样保留`() {
        assertEquals(
            listOf("https://pan.quark.cn/s/aaa"),
            SourceLinks.normalize(listOf("https://pan.quark.cn/s/aaa")),
        )
    }

    @Test
    fun `空行被丢弃`() {
        assertEquals(
            listOf("a", "b"),
            SourceLinks.normalize(listOf("a", "  ", "", "b")),
        )
    }

    @Test
    fun `重复链接只留第一次`() {
        assertEquals(
            listOf("a", "b"),
            SourceLinks.normalize(listOf("a", "b", " a ")),
        )
    }

    @Test
    fun `顺序即行序`() {
        assertEquals(
            listOf("a", "b", "c"),
            SourceLinks.normalize(listOf("a", "b", "c")),
        )
    }

    @Test
    fun `超过五个只留前五个`() {
        val six = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(listOf("a", "b", "c", "d", "e"), SourceLinks.normalize(six))
    }

    @Test
    fun `全空时退回 fallback`() {
        assertEquals(
            listOf("old"),
            SourceLinks.normalize(listOf("", " "), fallback = "old"),
        )
    }

    @Test
    fun `全空且无 fallback 返回空列表`() {
        assertEquals(emptyList<String>(), SourceLinks.normalize(emptyList()))
    }

    @Test
    fun `有值时忽略 fallback`() {
        assertEquals(
            listOf("new"),
            SourceLinks.normalize(listOf("new"), fallback = "old"),
        )
    }
}
