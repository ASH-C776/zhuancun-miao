package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.ShareNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转存前分类、同集去重与裁剪：
 * - 命中的转、未命中的不转、剧场版跳过
 * - 同一集有「重置版 / V2」时只留一个
 * - 裁剪后的树不该再带空目录
 */
class TransferClassifierTest {

    private fun dir(fid: String, name: String, children: List<ShareNode> = emptyList()) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = true), children, 0)

    private fun file(fid: String, name: String) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = false), emptyList(), 0)

    private fun tree(vararg children: ShareNode) =
        ShareNode(DriveFile(fid = "root", name = "分享根目录", isDir = true), children.toList(), 0)

    @Test
    fun `命中的留下 未命中的裁掉`() {
        val t = tree(
            dir("s1", "某某剧", listOf(
                file("f1", "某某剧.S01E01.mkv"),
                file("f2", "某某剧.S01E02.mkv"),
                file("f3", "幕后花絮.mkv"),
                file("f4", "分享说明.txt"),
            ))
        )
        val plan = TransferClassifier.plan(t, filterEnabled = true)
        assertEquals(2, plan.kept.size)
        // 「幕后花絮」是噪声（不算未命中），.txt 不是视频
        assertEquals(0, plan.unmatched.size)
        val pruned = plan.tree
        assertEquals(1, pruned.children.size)
        assertEquals(setOf("某某剧.S01E01.mkv", "某某剧.S01E02.mkv"),
            pruned.children.first().children.map { it.name }.toSet())
    }

    @Test
    fun `同集多版本只留重置版`() {
        val t = tree(
            dir("s1", "某某剧", listOf(
                file("f1", "某某剧.S01E01.mkv"),
                file("f2", "某某剧.S01E01.重置版.mkv"),
                file("f3", "某某剧.S01E02.mkv"),
            ))
        )
        val plan = TransferClassifier.plan(t, filterEnabled = true)
        assertEquals(2, plan.distinctEpisodes)
        assertEquals(2, plan.kept.size)
        assertEquals(1, plan.duplicates.size)
        assertTrue(plan.kept.any { it.name.contains("重置版") })
    }

    @Test
    fun `剧场版不进转存也不进集数`() {
        val t = tree(
            dir("s1", "某某剧", listOf(
                file("f1", "某某剧.S01E01.mkv"),
                file("f2", "某某剧 剧场版.mp4"),
            ))
        )
        val plan = TransferClassifier.plan(t)
        assertEquals(1, plan.kept.size)
        assertEquals(1, plan.movies.size)
        assertEquals(1, plan.distinctEpisodes)
    }

    @Test
    fun `未选命名规则时按原样保留全部视频`() {
        val t = tree(
            dir("s1", "某某剧", listOf(
                file("f1", "a.mkv"),
                file("f2", "b.mkv"),
            ))
        )
        val plan = TransferClassifier.plan(t, filterEnabled = false)
        assertEquals(2, plan.kept.size)
        assertNotNull(plan.tree.children.firstOrNull())
        assertTrue(plan.summary().contains("按原名转存 2 个视频"))
    }

    @Test
    fun `全部未命中时裁剪结果没有视频`() {
        val t = tree(dir("s1", "某某剧", listOf(file("f1", "预告.mkv"))))
        val plan = TransferClassifier.plan(t, filterEnabled = true)
        assertTrue(plan.kept.isEmpty())
        assertNull(plan.tree.children.firstOrNull()?.children?.firstOrNull())
    }

    @Test
    fun `多季按各自季号去重`() {
        val t = tree(
            dir("s2", "Season 2", listOf(
                file("a1", "Show.S02E01.mkv"),
                file("a2", "Show.S02E02.mkv"),
            )),
            dir("s1", "Season 1", listOf(
                file("b1", "Show.S01E01.mkv"),
            ))
        )
        val plan = TransferClassifier.plan(t)
        assertEquals(3, plan.kept.size)
        assertEquals(3, plan.distinctEpisodes)
        assertEquals(setOf("Season 2", "Season 1"), plan.tree.children.map { it.name }.toSet())
    }
}
