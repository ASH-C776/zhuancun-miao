package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.ShareNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 季判定：文件名优先、目录名兜底、冲突单列。
 * 用例刻意选「不规范分享」的真实形态（目录名跟文件名打架、只有一侧有季号、多季混层）。
 */
class SeasonResolverTest {

    private fun dir(fid: String, name: String, children: List<ShareNode> = emptyList()) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = true), children, 0)

    private fun file(fid: String, name: String) =
        ShareNode(DriveFile(fid = fid, name = name, isDir = false), emptyList(), 0)

    private fun tree(vararg children: ShareNode) =
        ShareNode(DriveFile(fid = "root", name = "分享根目录", isDir = true), children.toList(), 0)

    @Test
    fun `文件名季号优先 文件名有目录没有`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_root", "某某剧", listOf(
                    file("f1", "Show.S01E01.mkv"),
                    file("f2", "Show.S02E01.mkv"),
                ))
            )
        )
        assertEquals(2, plan.items.size)
        assertEquals(0, plan.conflictCount)
        // 两个文件靠文件名分到两季
        assertEquals(setOf(1, 2), plan.items.mapNotNull { it.season(SeasonPrefer.FILE) }.toSet())
        assertTrue(plan.items.all { it.source == SeasonSource.FILE })
        assertEquals(setOf(1, 2), plan.groups(SeasonPrefer.FILE).keys.filterNotNull().toSet())
    }

    @Test
    fun `目录有季号 文件名只有集号时用目录兜底`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_s2", "Season 2", listOf(
                    file("f1", "Show.E05.mkv"),
                    file("f2", "Show.E06.mkv"),
                ))
            )
        )
        assertEquals(2, plan.items.size)
        assertEquals(0, plan.conflictCount)
        assertTrue(plan.items.all { it.source == SeasonSource.DIR })
        assertEquals(mapOf<Int?, Int>(2 to 2), plan.groups(SeasonPrefer.FILE).mapValues { it.value.size })
        assertEquals("Season 2", plan.items.first().dirName)
    }

    @Test
    fun `两边一致就直接用 不算冲突`() {
        val plan = SeasonResolver.resolve(
            tree(dir("d_s1", "Season 1", listOf(file("f1", "Show.S01E01.mkv"))))
        )
        assertEquals(SeasonSource.BOTH, plan.items.single().source)
        assertEquals(0, plan.conflictCount)
        assertEquals(1, plan.items.single().season(SeasonPrefer.FILE))
    }

    @Test
    fun `两边打架算冲突 默认取文件名 也可以取目录名`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_s1", "Season 1", listOf(
                    file("f1", "Show.S02E05.mkv"),
                    file("f2", "Show.S01E01.mkv"),
                ))
            )
        )
        assertEquals(1, plan.conflictCount)
        // 默认按文件名 → 冲突那个进 Season 2
        assertEquals(setOf(1, 2), plan.groups(SeasonPrefer.FILE).keys.filterNotNull().toSet())
        val g2 = plan.groups(SeasonPrefer.FILE)[2]!!.map { it.name }
        assertEquals(listOf("Show.S02E05.mkv"), g2)
        // 改按目录名 → 两个都进 Season 1
        assertEquals(setOf(1), plan.groups(SeasonPrefer.DIR).keys.filterNotNull().toSet())
        assertTrue(plan.summary(SeasonPrefer.FILE).contains("1 个季号冲突"))
        assertTrue(plan.conflictExamples(3).first().contains("Season 1"))
    }

    @Test
    fun `中文季目录与特别版都算正常季`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_cn1", "第一季", listOf(file("f1", "剧.01.mkv"))),
                dir("d_cn2", "第二季", listOf(file("f2", "剧.02.mkv"))),
                dir("d_sp", "特别篇", listOf(file("f3", "剧.SP01.mkv"))),
                dir("d_s0", "Specials", listOf(file("f4", "幕后花絮.mkv"))),
            )
        )
        assertEquals(4, plan.items.size)
        assertEquals(0, plan.conflictCount)
        val groups = plan.groups(SeasonPrefer.FILE)
        // 0 表示特别版，是一个真实季，不是「没线索」
        assertEquals(setOf(1, 2, 0), groups.keys.filterNotNull().toSet())
        assertEquals(2, groups[0]!!.size)
    }

    @Test
    fun `完全没季号线索的文件落在 null 组 也就是剧名根目录`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_root", "某剧", listOf(
                    file("f1", "第01集.mkv"),
                    file("f2", "第02集.mkv"),
                ))
            )
        )
        assertEquals(2, plan.items.size)
        assertTrue(plan.items.all { it.season(SeasonPrefer.FILE) == null })
        assertEquals(2, plan.groups(SeasonPrefer.FILE)[null]?.size)
        assertTrue(plan.summary(SeasonPrefer.FILE).contains("没有季号线索"))
    }

    @Test
    fun `深层季目录 中间夹着 4K 目录也能继承季号`() {
        val plan = SeasonResolver.resolve(
            tree(
                dir("d_show", "剧名", listOf(
                    dir("d_s2", "Season 2", listOf(
                        dir("d_4k", "2160p", listOf(file("f1", "Show.E01.mkv"))),
                    )),
                ))
            )
        )
        assertEquals(2, plan.items.single().season(SeasonPrefer.FILE))
    }
}
