package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.ShareNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 源优先级打分：规范命名的源分数必须高于脏命名的源 */
class SourceQualityTest {

    private fun dir(fid: String, name: String, children: List<ShareNode> = emptyList()) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = true), children, 0)

    private fun file(fid: String, name: String) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = false), emptyList(), 0)

    private fun tree(children: List<ShareNode>) =
        ShareNode(DriveFile(fid = "root", name = "分享根目录", isDir = true), children, 0)

    @Test
    fun `SxxExx 命名比脏命名分高`() {
        val clean = SourceQuality.score(
            tree(listOf(dir("a", "剧", (1..12).map { file("c$it", "Show.S01E%02d.mkv".format(it)) })))
        )
        val dirty = SourceQuality.score(
            tree(listOf(dir("b", "剧", listOf(
                file("d1", "EP01.mkv"),
                file("d2", "第2集.mkv"),
                file("d3", "12 25.mkv"),
                file("d4", "幕後.mkv"),
            ))))
        )
        assertTrue(clean.score > dirty.score)
        assertEquals(12, clean.hits)
        assertEquals(0, clean.ambiguous)
        assertTrue(dirty.ambiguous > 0)
    }

    @Test
    fun `同集多版本和歧义扣分`() {
        val dup = SourceQuality.score(
            tree(listOf(dir("a", "剧", listOf(
                file("1", "Show.S01E01.mkv"),
                file("2", "Show.S01E01.V2.mkv"),
                file("3", "Show.S01E02.mkv"),
            ))))
        )
        val withoutDup = SourceQuality.score(
            tree(listOf(dir("b", "剧", listOf(
                file("1", "Show.S01E01.mkv"),
                file("2", "Show.S01E02.mkv"),
            ))))
        )
        assertEquals(1, dup.duplicateExtras)
        assertTrue(dup.score < withoutDup.score)
    }

    @Test
    fun `空分享返回 0 分不崩`() {
        val s = SourceQuality.score(tree(emptyList()))
        assertEquals(0, s.videos)
        assertEquals(0, s.score)
    }
}
