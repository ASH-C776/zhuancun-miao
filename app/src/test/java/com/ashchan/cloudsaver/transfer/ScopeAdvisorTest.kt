package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.ShareNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 转存范围推断测试：覆盖「根目录平铺新集 + 下层放旧集」这一常见结构。 */
class ScopeAdvisorTest {

    private fun file(fid: String, name: String) = ShareNode(DriveFile(fid, name, false), depth = 1)
    private fun dir(fid: String, name: String) = ShareNode(DriveFile(fid, name, true), depth = 1)

    @Test
    fun `根目录直接有视频 推荐只跟当前目录`() {
        val root = ShareNode(
            DriveFile("0", "斗 P穹4", true),
            children = listOf(
                file("v1", "219-4K.mp4"),
                file("v2", "218_4K.mkv"),
                dir("d1", "之前季番"),
            ),
            depth = 0,
        )
        assertEquals(TreeSyncEngine.Scope.DIRECT_ONLY, ScopeAdvisor.recommend(root))
    }

    @Test
    fun `只有季目录 推荐整棵子树`() {
        val root = ShareNode(
            DriveFile("0", "斗破苍穹 (2017)", true),
            children = listOf(
                dir("s1", "第 1 季 - 2160p"),
                dir("s2", "第 2 季 - 2160p"),
            ),
            depth = 0,
        )
        assertEquals(TreeSyncEngine.Scope.SUBTREE, ScopeAdvisor.recommend(root))
    }

    @Test
    fun `空目录默认整棵子树`() {
        val root = ShareNode(DriveFile("0", "空", true), children = emptyList(), depth = 0)
        assertEquals(TreeSyncEngine.Scope.SUBTREE, ScopeAdvisor.recommend(root))
    }

    @Test
    fun `文案与名称互转`() {
        assertEquals("只跟当前目录的视频", ScopeAdvisor.label(TreeSyncEngine.Scope.DIRECT_ONLY))
        assertEquals(TreeSyncEngine.Scope.DIRECT_ONLY, ScopeAdvisor.fromName("DIRECT_ONLY"))
        assertEquals(TreeSyncEngine.Scope.SUBTREE, ScopeAdvisor.fromName("SUBTREE"))
        assertEquals(TreeSyncEngine.Scope.SUBTREE, ScopeAdvisor.fromName("随便什么"))
    }
}
