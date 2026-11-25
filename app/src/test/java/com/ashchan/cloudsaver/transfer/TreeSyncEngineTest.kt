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
        /** 列目录次数：用来断言「改名不要每次都重列目录」 */
        var listCalls = 0
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

        override suspend fun listFiles(parentId: String?): Result<List<DriveFile>> {
            listCalls++
            return Result.success(mine[parentId ?: "0"]?.toList().orEmpty())
        }

        override suspend fun listFolders(parentId: String?) =
            Result.success(mine[parentId ?: "0"].orEmpty().filter { it.isDir })

        /** 多季模式要给每季建 `Season N` 目录 */
        override suspend fun ensureFolder(parentFid: String, name: String): Result<String> {
            val list = mine.getOrPut(parentFid) { mutableListOf() }
            list.firstOrNull { it.isDir && it.name == name }?.let { return Result.success(it.fid) }
            val fid = "dir_${++seq}"
            list.add(DriveFile(fid = fid, name = name, isDir = true, source = FileSource.SAVED))
            return Result.success(fid)
        }

        /** 让转存返回网络类失败，用来验证「断网时只记一条并中止」 */
        var failRestoreWith: String? = null
        /** 所有转存请求（含失败重试）的次数 */
        var restoreAttempts = 0
            private set

        override suspend fun restoreShare(
            share: ShareInfo,
            files: List<DriveFile>,
            parentId: String,
        ): Result<List<RestoredFile>> {
            restoreAttempts++
            failRestoreWith?.let { return Result.failure(java.io.IOException(it)) }
            restoreShareCalls++
            val created = files.map { copyInto(it, parentId) }
            return Result.success(
                created.mapIndexed { i, f -> RestoredFile(f, files[i].fid, files[i].name) }
            )
        }

        override suspend fun renameFile(fileId: String, newName: String): Result<Unit> =
            synchronized(mine) {
                mine.values.forEach { list ->
                    val idx = list.indexOfFirst { it.fid == fileId }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(name = newName)
                        renames += fileId to newName
                        return@synchronized Result.success(Unit)
                    }
                }
                Result.failure(IllegalStateException("fid not found: $fileId"))
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

    @Test
    fun `DIRECT_ONLY 只转平铺视频 不进子目录`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(
                    adapterFile("s_219", "219-4K.mp4"),
                    adapterFile("s_218", "218_4K.mkv"),
                )
            )
        )
        // 手工建树：树根 = 「斗 P穹4」这一层，直接子级是平铺视频 + 两个大目录
        val tree = ShareNode(
            file = adapterDir("d9", "斗 P穹4"),
            depth = 0,
            children = listOf(
                ShareNode(adapterFile("s_219", "219-4K.mp4"), depth = 1),
                ShareNode(adapterFile("s_218", "218_4K.mkv"), depth = 1),
                ShareNode(adapterDir("d_prev", "之前季番"), depth = 1),
                ShareNode(adapterDir("d_1200", "1-200"), depth = 1),
            ),
        )
        adapter.mine["0"] = mutableListOf(adapter.newDir("m_show", "斗 P穹4"))

        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(),
            tree = tree,
            destRootFid = "m_show",
            taskName = "斗 P穹4",
            scope = TreeSyncEngine.Scope.DIRECT_ONLY,
        ).getOrThrow()

        assertEquals(2, report.restoredFiles)
        assertEquals(0, report.restoredDirs)          // 不新建文件夹
        // 两个视频合成一次转存请求（RESTORE_BATCH 内），子目录没碰
        assertEquals(1, adapter.restoreShareCalls)
        assertEquals(2, adapter.mine["m_show"]!!.size)
        assertTrue(report.ok)
    }

    @Test
    fun `DIRECT_ONLY 目录下没视频时报错`() = runBlocking {
        val adapter = FakeDriveAdapter(mapOf(null to emptyList()))
        val tree = ShareNode(
            file = adapterDir("d", "空目录"),
            children = listOf(ShareNode(adapterDir("d_sub", "子目录"), depth = 1)),
            depth = 0,
        )
        val r = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0",
            scope = TreeSyncEngine.Scope.DIRECT_ONLY,
        )
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("没有可转存") == true)
    }

    @Test
    fun `一批已有原名的文件 只读一次目录 并发改名全部生效`() = runBlocking {
        val n = 12
        val shareFiles = (1..n).map {
            adapterFile("s_$it", "Sword.and.Fairy.3.2025.S01E%02d.2160p.mp4".format(it))
        }
        val adapter = FakeDriveAdapter(mapOf(null to shareFiles))
        // 上一轮已转存成原名，只差改名
        adapter.mine["0"] = (1..n)
            .map { adapter.newFile("m_$it", shareFiles[it - 1].name) }
            .toMutableList()
        val tree = buildTree(adapter)
        val plan = shareFiles.mapIndexed { i, f ->
            f.fid to "仙剑奇侠传三.S01E%02d.mp4".format(i + 1)
        }.toMap()

        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0", renamePlan = plan,
        ).getOrThrow()

        assertEquals(n, report.renamedItems)
        assertEquals(n, adapter.renames.size)
        assertTrue(report.ok)
        // 只列一次目录：改名成功后在内存索引里改键，不再逐次重列
        assertTrue("listCalls=${adapter.listCalls}", adapter.listCalls <= 2)
        assertEquals(
            (1..n).map { "仙剑奇侠传三.S01E%02d.mp4".format(it) }.toSet(),
            adapter.mine["0"]!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `断网时只记一条失败并中止 不会逐个文件刷错`() = runBlocking {
        val adapter = FakeDriveAdapter(
            mapOf(null to (1..16).map { adapterFile("s_$it", "EP%02d.mkv".format(it)) })
        )
        adapter.failRestoreWith = """Unable to resolve host "api.guangyapan.com": No address associated with hostname"""

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).sync(
            share = shareInfo(), tree = tree, destRootFid = "0",
        ).getOrThrow()

        assertEquals(1, report.failures.size)
        assertTrue(report.failures.first().contains("网络中断"))
        assertEquals(TreeSyncEngine.RESTORE_ATTEMPTS, adapter.restoreAttempts) // 重试完就中止，不逐文件刷
        assertEquals(0, report.restoredFiles)
    }

    @Test
    fun `多季模式按文件季号各进 Season N 且改名保留各自季号`() = runBlocking {
        val shareFiles = listOf(
            adapterFile("s_1", "Blue.Planet.III.S01E01.mkv"),
            adapterFile("s_2", "Blue.Planet.III.S01E02.mkv"),
            adapterFile("s_3", "Blue.Planet.III.S02E01.mkv"),
            adapterFile("s_4", "Blue.Planet.III.S02E02.mkv"),
        )
        val adapter = FakeDriveAdapter(mapOf(null to shareFiles))
        val plan = shareFiles.mapIndexed { i, f ->
            val s = if (i < 2) 1 else 2
            val e = if (i % 2 == 0) 1 else 2
            f.fid to "蓝色星球3.S%02dE%02d.mkv".format(s, e)
        }.toMap()

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).syncFlat(
            share = shareInfo(),
            tree = tree,
            destRootFid = "0",
            renamePlan = plan,
            multiSeason = true,
        ).getOrThrow()

        assertEquals(4, report.restoredFiles)
        assertEquals(4, report.renamedItems)
        // 根下只有两个季目录，没有视频散落
        val dirs = adapter.mine["0"]!!
        assertEquals(setOf("Season 1", "Season 2"), dirs.map { it.name }.toSet())
        assertEquals(0, dirs.count { !it.isDir })
        // 每季各 2 个文件，且文件名季号与所在目录一致
        dirs.sortedBy { it.name }.forEach { dir ->
            val expectSeason = dir.name.removePrefix("Season ")
            val names = adapter.mine[dir.fid]!!.map { it.name }
            assertEquals(2, names.size)
            assertTrue("$names", names.all { it.contains("S0${expectSeason}E") })
        }
    }

    @Test
    fun `多季模式把特别版当成正常一季 进 Season 0`() = runBlocking {
        // 分享结构：Season 1 目录里文件名带季号；Season 2 目录里文件名不带季号（靠目录兜底）；
        // Specials 目录里是 S00Exx / 不带季号两种
        val adapter = FakeDriveAdapter(
            mapOf(
                null to listOf(adapterDir("d_s1", "Season 1"), adapterDir("d_s2", "Season 2"), adapterDir("d_sp", "Specials")),
                "d_s1" to listOf(adapterFile("f_1", "Show.S01E01.mkv")),
                "d_s2" to listOf(adapterFile("f_2", "Show.E05.mkv")),
                "d_sp" to listOf(adapterFile("f_3", "Show.S00E01.mkv"), adapterFile("f_4", "幕后花絮.mkv")),
            )
        )

        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).syncFlat(
            share = shareInfo(), tree = tree, destRootFid = "0", multiSeason = true,
        ).getOrThrow()

        assertEquals(4, report.restoredFiles)
        val dirs = adapter.mine["0"]!!.filter { it.isDir }.map { it.name }.toSet()
        // 特别版没有被丢到根下，Season 2 里不带季号的文件也没落根
        assertEquals(setOf("Season 0", "Season 1", "Season 2"), dirs)
        assertEquals(0, adapter.mine["0"]!!.count { !it.isDir })
        val s0 = adapter.mine.keys.first { fid -> adapter.mine[fid]!!.size == 2 }
        assertEquals(2, adapter.mine[s0]!!.size)
    }

    // ---------- 小工具 ----------

    @Test
    fun `单季从第 3 集起 只转存 E03 及以后`() = runBlocking {
        val shareFiles = (1..5).map { adapterFile("f_$it", "Show.E%02d.mkv".format(it)) }
        val adapter = FakeDriveAdapter(mapOf(null to shareFiles))
        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).syncFlat(
            share = shareInfo(), tree = tree, destRootFid = "0", startEpisode = 3,
        ).getOrThrow()

        assertEquals(3, report.restoredFiles)
        val names = adapter.mine["0"]!!.map { it.name }.toSet()
        assertEquals(setOf("Show.E03.mkv", "Show.E04.mkv", "Show.E05.mkv"), names)
    }

    @Test
    fun `多季从第 2 季第 2 集起 前一季跳过 起点季按集过滤`() = runBlocking {
        val shareFiles = listOf(
            adapterFile("a1", "Show.S01E01.mkv"),
            adapterFile("a2", "Show.S01E02.mkv"),
            adapterFile("b1", "Show.S02E01.mkv"),
            adapterFile("b2", "Show.S02E02.mkv"),
            adapterFile("c1", "Show.S03E01.mkv"),
        )
        val adapter = FakeDriveAdapter(mapOf(null to shareFiles))
        val tree = buildTree(adapter)
        val report = TreeSyncEngine(adapter).syncFlat(
            share = shareInfo(), tree = tree, destRootFid = "0",
            multiSeason = true, startSeason = 2, startEpisode = 2,
        ).getOrThrow()

        assertEquals(2, report.restoredFiles)
        assertEquals(setOf("Season 2", "Season 3"), adapter.mine["0"]!!.filter { it.isDir }.map { it.name }.toSet())
    }

    private fun adapterDir(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = true)
    private fun adapterFile(fid: String, name: String) = DriveFile(fid = fid, name = name, isDir = false)
}
