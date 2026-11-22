package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.AccountInfo
import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.adapter.RestoredFile
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.FileSource
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.ShareNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5 / 1.6 单元测试：目录级同步 + 深层改名。
 * 用内存假云盘模拟「分享树」与「我的网盘」，不触网。
 */
class TreeSyncEngineTest {

    // ---------- 假网盘 ----------

    private class FakeDriveAdapter(
        /** 分享内目录内容：parentFid -> children（null 表示分享根） */
        private val shareTree: Map<String?, List<DriveFile>>,
    ) : CloudDriveAdapter {
        /** 我的网盘：fid -> 子项，初始只有根 "0" */
        val mine = mutableMapOf<String, MutableList<DriveFile>>("0" to mutableListOf())
        var restoreFileCalls = 0
            private set
        var restoreShareCalls = 0
            private set
        val renames = mutableListOf<Pair<String, String>>()
        private var seq = 0

        override val driveType = DriveType.QUARK
        override suspend fun validateLogin(credential: String) = Result.success(AccountInfo("fake"))
        override fun parseShareLink(link: String) = Result.success(ShareInfo(DriveType.QUARK, "sid"))
        override suspend fun resolveShareAccess(share: ShareInfo) =
            Result.success(share.copy(accessToken = "tok"))

        override suspend fun fetchShareChildren(share: ShareInfo, parentId: String?) =
            Result.success(shareTree[parentId].orEmpty())

        override suspend fun listFiles(parentId: String?) =
            Result.success(mine[parentId ?: "0"]?.toList().orEmpty())

        override suspend fun listFolders(parentId: String?) =
            Result.success(mine[parentId ?: "0"].orEmpty().filter { it.isDir })

        override suspend fun restoreShare(
            share: ShareInfo,
            files: List<DriveFile>,
            parentId: String,
        ): Result<List<RestoredFile>> {
            restoreShareCalls++
            val created = files.map { copyInto(it, parentId) }
            return Result.success(
                created.mapIndexed { i, f -> RestoredFile(f, files[i].fid, files[i].name) }
            )
        }

        override suspend fun renameFile(fileId: String, newName: String): Result<Unit> {
            mine.values.forEach { list ->
                val idx = list.indexOfFirst { it.fid == fileId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(name = newName)
                    renames += fileId to newName
                    return Result.success(Unit)
                }
            }
            return Result.failure(IllegalStateException("fid not found: $fileId"))
        }

        /** 把分享里的节点复制进我的网盘（模拟转存整目录，含内部层级） */
        private fun copyInto(src: DriveFile, parentFid: String): DriveFile {
            val newFid = "new_${++seq}"
            val saved = DriveFile(fid = newFid, name = src.name, isDir = src.isDir, source = FileSource.SAVED)
            mine.getOrPut(parentFid) { mutableListOf() }.add(saved)
            if (src.isDir) {
                shareTree[src.fid].orEmpty().forEach { child -> copyInto(child, newFid) }
            } else {
                restoreFileCalls++
            }
            return saved
        }

        fun newDir(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = true)
        fun newFile(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = false)
    }

    private fun shareInfo() = ShareInfo(DriveType.QUARK, "sid")

    private fun buildTree(adapter: FakeDriveAdapter) = runBlocking {
        adapter.fetchShareTree(shareInfo(), maxDepth = 3).getOrThrow()
    }

    // ---------- 测试 ----------

    @Test
    fun `单目录分享 目标为空时整目录只转存一次 顶层改名 内部文件按计划改名`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("s_show", "凡人修仙传")),
                "s_show" to listOf(
                    adapterFile("s_ep1", "凡人修仙传.EP01.mkv"),
                    adapterFile("s_ep2", "凡人修仙传.EP02.mkv"),
                ),
            )
        )
        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(),
            tree = tree,
            destRootFid = "0",
            taskName = "凡人修仙传(2025)",
            renamePlan = mapOf("s_ep1" to "凡人修仙传.S01E01.1080p.mkv"),
        ).getOrThrow()

        assertEquals(1, adapter.restoreShareCalls)          // 整个目录一次，不是逐文件
        assertEquals(1, report.restoredDirs)
        val root = adapter.mine["0"]!!
        assertEquals(1, root.size)
        assertEquals("凡人修仙传(2025)", root[0].name)        // 顶层目录已改名为任务名
        val inside = adapter.mine[root[0].fid]!!
        assertEquals(2, inside.size)
        assertEquals("凡人修仙传.S01E01.1080p.mkv", inside[0].name)  // 深层改名生效
        assertTrue(report.ok)
    }

    @Test
    fun `目标已有同名剧目录时 只补缺失的集`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("s_show", "凡人修仙传")),
                "s_show" to listOf(
                    adapterFile("s_ep1", "EP01.mkv"),
                    adapterFile("s_ep2", "EP02.mkv"),
                ),
            )
        )
        // 目标已有剧目录，且里面已有 EP01
        adapter.mine["0"] = mutableListOf(adapter.newDir("m_show", "凡人修仙传"))
        adapter.mine["m_show"] = mutableListOf(adapter.newFile("m_ep1", "EP01.mkv"))

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", taskName = "凡人修仙传",
        ).getOrThrow()

        assertEquals(0, report.restoredDirs)      // 不重复建剧目录
        assertEquals(1, adapter.restoreShareCalls) // 只转存 EP02
        assertEquals(1, report.restoredFiles)
        assertEquals(2, adapter.mine["m_show"]!!.size)
        assertTrue(report.ok)
    }

    @Test
    fun `三层结构 目标为空时整剧目录转存一次 深层文件改名生效`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("s_show", "仙逆")),
                "s_show" to listOf(adapterDir("s_s1", "第01季")),
                "s_s1" to listOf(
                    adapterFile("s_ep1", "仙逆.EP01.mkv"),
                    adapterFile("s_ep2", "仙逆.EP02.mkv"),
                    adapterFile("s_ep3", "仙逆.EP03.mkv"),
                ),
            )
        )
        val tree = buildTree(adapter)
        assertEquals(3, tree.maxDepth())

        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", taskName = "仙逆",
            renamePlan = mapOf("s_ep3" to "仙逆.S01E03.mkv"),
        ).getOrThrow()

        assertEquals(1, adapter.restoreShareCalls) // 只转存「仙逆」目录一次
        val show = adapter.mine["0"]!!.single()
        assertEquals("仙逆", show.name)
        val season = adapter.mine[show.fid]!!.single()
        assertEquals("第01季", season.name)       // 二级目录名保留
        val eps = adapter.mine[season.fid]!!
        assertEquals(3, eps.size)
        assertEquals("仙逆.S01E03.mkv", eps.first { it.name.startsWith("仙逆.S01E03") }.name)
        assertTrue(report.ok)
    }

    @Test
    fun `三层结构 目标已有季目录时 递归下钻只补缺失集`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("s_show", "仙逆")),
                "s_show" to listOf(adapterDir("s_s1", "第01季")),
                "s_s1" to listOf(
                    adapterFile("s_ep1", "EP01.mkv"),
                    adapterFile("s_ep2", "EP02.mkv"),
                    adapterFile("s_ep3", "EP03.mkv"),
                ),
            )
        )
        adapter.mine["0"] = mutableListOf(adapter.newDir("m_show", "仙逆"))
        adapter.mine["m_show"] = mutableListOf(adapter.newDir("m_s1", "第01季"))
        adapter.mine["m_s1"] = mutableListOf(
            adapter.newFile("m_ep1", "EP01.mkv"),
            adapter.newFile("m_ep2", "EP02.mkv"),
        )

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", taskName = "仙逆",
        ).getOrThrow()

        assertEquals(0, report.restoredDirs)
        assertEquals(1, adapter.restoreShareCalls)  // 只转 EP03
        assertEquals(3, adapter.mine["m_s1"]!!.size)
        assertTrue(report.ok)
    }

    @Test
    fun `深度超限时不再下钻 但上层目录照常转存`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("s_show", "仙逆")),
                "s_show" to listOf(adapterDir("s_s1", "第01季")),
                "s_s1" to listOf(adapterFile("s_ep1", "EP01.mkv")),
            )
        )
        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", taskName = "仙逆",
            maxDepth = 1,
            renamePlan = mapOf("s_ep1" to "仙逆.S01E01.mkv"),
        ).getOrThrow()

        assertEquals(1, adapter.restoreShareCalls)
        // 深度 1 已到上限：内部不再改名
        val show = adapter.mine["0"]!!.single()
        val season = adapter.mine[show.fid]!!.single()
        assertEquals("EP01.mkv", adapter.mine[season.fid]!!.single().name)
        assertEquals(0, report.renamedItems)
    }

    @Test
    fun `根下散文件按名字跳过已存在的`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(
                    adapterFile("s_a", "A.mkv"),
                    adapterFile("s_b", "B.mkv"),
                )
            )
        )
        adapter.mine["0"] = mutableListOf(adapter.newFile("m_a", "A.mkv"))

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", taskName = "",
        ).getOrThrow()

        assertEquals(1, report.skippedFiles)
        assertEquals(1, adapter.restoreShareCalls)
        assertEquals(2, adapter.mine["0"]!!.size)
    }

    @Test
    fun `selectUnits 单目录与散文件判定`() {
        val single = ShareNode(adapterDir("0", "根"), listOf(ShareNode(adapterDir("s", "剧"))), 0)
        val units1 = TreeSyncEngine.selectUnits(single)
        assertTrue(units1.singleDir)
        assertFalse(units1.isEmpty)

        val mixed = ShareNode(
            adapterDir("0", "根"),
            listOf(ShareNode(adapterDir("s1", "剧1")), ShareNode(adapterFile("f", "x.mkv"))),
            0,
        )
        val units2 = TreeSyncEngine.selectUnits(mixed)
        assertFalse(units2.singleDir)
        assertEquals(1, units2.dirs.size)
        assertEquals(1, units2.looseFiles.size)

        val empty = TreeSyncEngine.selectUnits(ShareNode(adapterDir("0", "根"), emptyList(), 0))
        assertTrue(empty.isEmpty)
    }

    // ---------- 小工具 ----------

    private fun adapterDir(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = true)
    private fun adapterFile(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = false)
}
