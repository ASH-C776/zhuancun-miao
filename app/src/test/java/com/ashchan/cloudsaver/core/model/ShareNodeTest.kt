package com.ashchan.cloudsaver.core.model

import com.ashchan.cloudsaver.core.adapter.AccountInfo
import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.adapter.RestoredFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1 单元测试：目录树模型 + 递归扫描 + 剧集目录定位 + 自然排序。
 * 全部为纯 JVM 逻辑，不触网、不依赖 Android 运行时。
 */
class ShareNodeTest {

    /** 用内存数据模拟网盘目录：parentId -> 子项 */
    private class FakeAdapter(
        private val dirs: Map<String?, List<DriveFile>>,
    ) : CloudDriveAdapter {
        override val driveType = DriveType.QUARK
        override suspend fun validateLogin(credential: String) = Result.success(AccountInfo("fake"))
        override fun parseShareLink(link: String) = Result.success(ShareInfo(DriveType.QUARK, "sid"))
        override suspend fun resolveShareAccess(share: ShareInfo) = Result.success(share.copy(accessToken = "tok"))
        override suspend fun fetchShareChildren(share: ShareInfo, parentId: String?) =
            Result.success(dirs[parentId].orEmpty())

        override suspend fun listFolders(parentId: String?) = Result.success(emptyList<DriveFile>())
        override suspend fun listFiles(parentId: String?) = Result.success(emptyList<DriveFile>())
        override suspend fun restoreShare(share: ShareInfo, files: List<DriveFile>, parentId: String) =
            Result.success(emptyList<RestoredFile>())

        override suspend fun renameFile(fileId: String, newName: String) = Result.success(Unit)
    }

    private fun file(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = false)
    private fun dir(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = true)

    /** 构造一个典型三层结构：根 / [剧名] / [第01季] / 12 个 mkv */
    private fun threeLayer(): FakeAdapter {
        val videos = (1..12).map { file("v$it", "凡人修仙传.EP$it.mkv") }
        return FakeAdapter(
            mapOf(
                null to listOf(dir("d_show", "凡人修仙传")),
                "d_show" to listOf(dir("d_s1", "第01季"), file("t1", "简介.txt")),
                "d_s1" to videos,
            )
        )
    }

    @Test
    fun `递归扫描能构建三层目录树`() = runBlocking {
        val adapter = threeLayer()
        val tree = adapter.fetchShareTree(ShareInfo(DriveType.QUARK, "sid"), maxDepth = 3).getOrThrow()

        assertEquals(1, tree.children.size)
        val show = tree.children[0]
        assertEquals("凡人修仙传", show.name)
        assertEquals(1, show.depth)

        val s1 = show.children.first { it.isDir }
        assertEquals("第01季", s1.name)
        assertEquals(2, s1.depth)
        assertEquals(12, s1.directVideoCount)
        assertEquals(12, s1.videoCount)
        assertTrue(s1.children.all { it.isVideo })
    }

    @Test
    fun `超过 maxDepth 的目录保留节点但不展开`() = runBlocking {
        val adapter = threeLayer()
        val tree = adapter.fetchShareTree(ShareInfo(DriveType.QUARK, "sid"), maxDepth = 2).getOrThrow()

        val s1 = tree.children[0].children.first { it.isDir }
        assertEquals("第01季", s1.name)
        // 深度 2 已到上限，节点仍在但无子项
        assertTrue(s1.children.isEmpty())
        assertEquals(0, s1.directVideoCount)
    }

    @Test
    fun `剧集目录只按直接子级判定 不会把上层容器误判`() {
        val tree = ShareNode(
            file = dir("0", "根"),
            children = listOf(
                ShareNode(
                    file = dir("d_show", "凡人修仙传"),
                    depth = 1,
                    children = listOf(
                        ShareNode(
                            file = dir("d_s1", "第01季"),
                            depth = 2,
                            children = (1..3).map { ShareNode(file("v$it", "EP$it.mkv"), depth = 3) }
                        )
                    )
                )
            )
        )

        val hits = tree.findVideoDirs(minVideos = 2)
        assertEquals(1, hits.size)
        assertEquals("第01季", hits[0].name)
        // 上层容器 [凡人修仙传] 有 3 个后代视频，但直接子级是 1 个目录，不应命中
        assertFalse(tree.children[0].findVideoDirs(minVideos = 2).isEmpty() &&
            tree.children[0].directVideoCount >= 2)
        assertEquals(0, tree.children[0].directVideoCount)
        assertEquals(3, tree.children[0].videoCount)
    }

    @Test
    fun `单文件分享不会命中剧集目录`() {
        val tree = ShareNode(
            file = dir("0", "根"),
            children = listOf(ShareNode(file("v1", "某电影.2024.mkv"), depth = 1))
        )
        assertTrue(tree.findVideoDirs(minVideos = 2).isEmpty())
        assertEquals(1, tree.flattenFiles().size)
    }

    @Test
    fun `自然序让 EP2 排在 EP10 前面`() {
        val names = listOf("EP10.mkv", "EP2.mkv", "EP1.mkv", "EP20.mkv")
        val sorted = names.sortedWith(compareBy(NaturalOrder) { it })
        assertEquals(listOf("EP1.mkv", "EP2.mkv", "EP10.mkv", "EP20.mkv"), sorted)
    }

    @Test
    fun `中文集数也能按数值排序`() {
        val names = listOf("第10集.mkv", "第2集.mkv", "第1集.mkv")
        val sorted = names.sortedWith(compareBy(NaturalOrder) { it })
        assertEquals(listOf("第1集.mkv", "第2集.mkv", "第10集.mkv"), sorted)
    }

    @Test
    fun `flattenFiles 只返回文件且带自然序`() = runBlocking {
        val tree = threeLayer().fetchShareTree(ShareInfo(DriveType.QUARK, "sid"), maxDepth = 3).getOrThrow()
        val files = tree.flattenFiles()
        assertEquals(13, files.size) // 12 个视频 + 1 个 txt
        assertEquals("凡人修仙传.EP1.mkv", files.first().name)
        assertTrue(files.none { it.isDir })
    }

    @Test
    fun `成环数据不会死循环`() = runBlocking {
        // A 目录里又包含 A 自己
        val adapter = FakeAdapter(
            mapOf(
                null to listOf(dir("A", "回环")),
                "A" to listOf(dir("A", "回环")),
            )
        )
        val tree = adapter.fetchShareTree(ShareInfo(DriveType.QUARK, "sid"), maxDepth = 5).getOrThrow()
        // 第二次遇到 fid=A 时停止展开：第一层 A 正常展开，第二层同名 A 保留但无子项
        assertEquals(1, tree.children.size)
        assertEquals(1, tree.children[0].children.size)
        assertEquals("回环", tree.children[0].children[0].name)
        assertTrue(tree.children[0].children[0].children.isEmpty())
    }
}
