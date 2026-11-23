package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.data.SeasonParser

/**
 * 目录级同步引擎（1.5 转存粒度 / 1.6 深层改名）。
 *
 * 与 [TransferManager]（一次转存 + 一层改名）的区别：这里按**目录**为单位，
 * 保留分享原有层级，边比对边补齐，这就是原项目 `_sync_share_dir` 的语义。
 *
 * 决策规则（按原项目逻辑，不做扁平化）：
 * 1. 分享根的直接子节点切成「转存单元」：目录各自一个单元，根下的散视频文件合为一个单元。
 * 2. 只有一个目录单元时，转存后把新顶层目录改名为任务名（剧名）。
 * 3. 目标目录已有同名目录 → **不重复转存**，进入该目录递归补缺失文件。
 * 4. 目标缺同名目录 → 整目录转存一次（保留其内部层级），再递归把内部文件按重命名计划改名。
 * 5. 深度超过 [DEFAULT_MAX_DEPTH] 不再下钻，避免异常数据把请求打爆。
 */
class TreeSyncEngine(
    private val adapter: CloudDriveAdapter,
) {

    /** 一次同步的执行统计，供 UI 展示（Stage 4 会写进快照）。 */
    data class Report(
        val restoredDirs: Int,
        val restoredFiles: Int,
        val renamedItems: Int,
        val skippedFiles: Int,
        val failures: List<String>,
        /**
         * 覆盖默认判定。多源任务里「源2 失败但源1 成功」仍算这一轮成功，
         * 此时 failures 里留着源2 的原因给人看，但整轮不该标红。
         * null = 按 failures 是否为空判定（单源与原行为一致）。
         */
        val explicitOk: Boolean? = null,
    ) {
        val ok: Boolean get() = explicitOk ?: failures.isEmpty()
    }

    /**
     * 转存范围。
     *
     * 真实资源有两种形态，必须由任务显式选一种：
     * - [SUBTREE]：整棵子树，保留原有层级（「剧名/第 1 季/14 集」这类）
     * - [DIRECT_ONLY]：只转这棵树根的直接视频文件，不新建剧名文件夹、不进子目录
     *   （夸克常见「剧名/219-4K.mp4、218_4K.mkv…」平铺，下一层还挂着「1-200」等大目录）
     */
    enum class Scope { SUBTREE, DIRECT_ONLY }

    /**
     * 执行一次目录级同步。
     *
     * @param tree 已扫描好的分享目录树（[CloudDriveAdapter.fetchShareTree] 产出）
     * @param destRootFid 目标文件夹 fid（"0" = 根目录）
     * @param taskName 任务名（剧名）；单目录分享且 SUBTREE 时用它作为顶层目录名
     * @param renamePlan 分享文件 fid -> 新文件名
     * @param scope 转存范围，默认按原项目逻辑保留层级
     */
    suspend fun sync(
        share: ShareInfo,
        tree: ShareNode,
        destRootFid: String,
        taskName: String = "",
        renamePlan: Map<String, String> = emptyMap(),
        scope: Scope = Scope.SUBTREE,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onProgress: (stage: Int, percent: Int, message: String) -> Unit = { _, _, _ -> },
    ): Result<Report> = runCatching {
        val session = Session(adapter, share, renamePlan, maxDepth.coerceAtMost(MAX_DEPTH), onProgress)
        when (scope) {
            Scope.SUBTREE -> session.run(tree, destRootFid, taskName)
            Scope.DIRECT_ONLY -> session.runDirect(tree, destRootFid)
        }
        session.report()
    }

    /**
     * 扁平同步：把分享里（可选指定季）的所有视频**直接平铺**到 [destRootFid]，
     * 不再把分享方的目录名带进网盘。
     *
     * 这是主路径：用户要的是 `剧名/视频文件` 或 `剧名/Season N/视频文件`，
     * 分享里那层「鉴定 4K」「[高清]xxx」之类的文件夹名对用户没有意义，只会多一层嵌套，
     * 也让集数统计和改名都多绕一层。
     *
     * @param seasonNo >0 时只取名字里能解析出同一季号的目录（如「第 2 季」「Season 2」），
     *                 其它季的子树直接跳过；0 = 全部收进来。
     */
    suspend fun syncFlat(
        share: ShareInfo,
        tree: ShareNode,
        destRootFid: String,
        renamePlan: Map<String, String> = emptyMap(),
        seasonNo: Int = 0,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onProgress: (stage: Int, percent: Int, message: String) -> Unit = { _, _, _ -> },
    ): Result<Report> = runCatching {
        val session = Session(adapter, share, renamePlan, maxDepth.coerceAtMost(MAX_DEPTH), onProgress)
        session.runFlat(tree, destRootFid, seasonNo)
        session.report()
    }

    companion object {
        /** 与接口的树扫描默认深度保持一致：根的直接子级 = 1。 */
        const val DEFAULT_MAX_DEPTH: Int = 3
        const val MAX_DEPTH: Int = 5

        /** 一次转存请求最多带多少个文件（太多容易被判超时/限流） */
        const val RESTORE_BATCH: Int = 8

        /**
         * 把分享根切成转存单元（纯函数，便于单测）。
         * 目录按自然序，根下散文件归入 [Units.looseFiles]。
         */
        fun selectUnits(tree: ShareNode): Units {
            val sorted = tree.children.sortedWith(compareBy(NaturalOrder) { it.name })
            return Units(
                dirs = sorted.filter { it.isDir },
                looseFiles = sorted.filter { !it.isDir },
            )
        }

        data class Units(val dirs: List<ShareNode>, val looseFiles: List<ShareNode>) {
            val isEmpty: Boolean get() = dirs.isEmpty() && looseFiles.isEmpty()
            val singleDir: Boolean get() = dirs.size == 1 && looseFiles.isEmpty()
        }

        private val NATURAL: Comparator<ShareNode> =
            Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    // ---------------- 执行会话 ----------------

    private class Session(
        private val adapter: CloudDriveAdapter,
        private val share: ShareInfo,
        private val renamePlan: Map<String, String>,
        private val maxDepth: Int,
        private val onProgress: (Int, Int, String) -> Unit,
    ) {
        private var restoredDirs = 0
        private var restoredFiles = 0
        private var renamedItems = 0
        private var skippedFiles = 0
        /** 本轮待转存视频总数（分母，用于「转存 3/20」） */
        private var videoTotal = 0
        /** 本轮需要改名的文件数（分母，用于「改名 5/12」） */
        private var renameTotal = 0
        private val failures = mutableListOf<String>()

        /** 目标目录内容缓存：同一次同步里同一目录只列一次 */
        private val destCache = mutableMapOf<String, List<DriveFile>>()

        fun report() = Report(restoredDirs, restoredFiles, renamedItems, skippedFiles, failures.toList())

        /** 只转树根的直接视频文件，不建文件夹、不下钻（对应平铺型分享） */
        suspend fun runDirect(tree: ShareNode, destRootFid: String) {
            val videos = tree.children.filter { it.isVideo }
            if (videos.isEmpty()) throw IllegalStateException("该目录下没有可转存的视频文件")
            onProgress(0, 0, "正在处理 ${videos.size} 个视频文件")
            syncFiles(videos, destRootFid)
        }

        /** 扁平同步：收集视频（可按季过滤）→ 批量转存 → 逐个按规则改名 */
        suspend fun runFlat(tree: ShareNode, destRootFid: String, seasonNo: Int) {
            val videos = mutableListOf<ShareNode>()
            collectVideos(tree, videos, seasonNo, depth = 0)
            if (videos.isEmpty()) throw IllegalStateException("分享中没有找到可转存的视频文件")
            videoTotal = videos.size
            onProgress(0, 0, "比对文件")
            syncFiles(videos, destRootFid)
        }

        /**
         * 递归收集视频文件。
         * @param seasonNo >0 时，遇到「看起来是别的季」的目录整棵跳过
         */
        private fun collectVideos(
            node: ShareNode,
            out: MutableList<ShareNode>,
            seasonNo: Int,
            depth: Int,
        ) {
            node.children.sortedWith(NATURAL).forEach { child ->
                if (child.isDir) {
                    if (depth + 1 > maxDepth) {
                        failures += "深度超限，跳过「${child.name}」"
                        return@forEach
                    }
                    val season = SeasonParser.parse(child.name)
                    if (seasonNo > 0 && season != null && season != seasonNo) return@forEach
                    collectVideos(child, out, seasonNo, depth + 1)
                } else if (child.isVideo) {
                    out += child
                }
            }
        }

        suspend fun run(tree: ShareNode, destRootFid: String, taskName: String) {
            val units = selectUnits(tree)
            if (units.isEmpty) throw IllegalStateException("分享中没有可转存的内容")

            val topRename = if (units.singleDir && taskName.isNotBlank()) taskName.trim() else null
            val total = units.dirs.size + if (units.looseFiles.isNotEmpty()) 1 else 0
            var done = 0

            units.dirs.forEach { dir ->
                onProgress(0, done * 100 / total, "正在处理「${dir.name}」")
                syncDir(dir, destRootFid, depth = 1, nameOverride = topRename)
                done++
                onProgress(0, done * 100 / total, "「${dir.name}」已完成")
            }

            if (units.looseFiles.isNotEmpty()) {
                syncFiles(units.looseFiles, destRootFid)
                done++
                onProgress(0, done * 100 / total, "根目录散文件已完成")
            }
        }

        /**
         * 同步一个目录节点到 [destParentFid] 下。
         * @param depth 该目录自身在分享内的层数（根的直接子级 = 1）
         * @param nameOverride 期望的目录名（仅单目录分享时用剧名覆盖）
         */
        private suspend fun syncDir(
            node: ShareNode,
            destParentFid: String,
            depth: Int,
            nameOverride: String?,
        ) {
            if (depth > maxDepth) {
                failures += "深度超限，跳过「${node.name}」"
                return
            }
            val wantedName = nameOverride?.takeIf { it.isNotBlank() } ?: node.name
            val siblings = destList(destParentFid)
            val existing = siblings.firstOrNull { it.isDir && it.name == wantedName }

            if (existing != null) {
                // 目标已有同名目录：不重复转存，逐层补齐
                val subDirs = node.children.filter { it.isDir }
                val videos = node.children.filter { it.isVideo }
                subDirs.sortedWith(NATURAL).forEach { syncDir(it, existing.fid, depth + 1, null) }
                if (videos.isNotEmpty()) syncFiles(videos, existing.fid)
                return
            }

            // 目标缺该目录：整目录转存一次，保留内部层级
            val saved = adapter.restoreShare(share, listOf(node.file), destParentFid)
                .getOrElse {
                    failures += "转存「${node.name}」失败：${it.message ?: "未知错误"}"
                    return
                }
            val newFid = saved.firstOrNull()?.file?.fid
            if (newFid.isNullOrBlank()) {
                failures += "「${node.name}」转存后未定位到新目录"
                return
            }
            restoredDirs++
            destCache.remove(destParentFid)

            if (wantedName != node.name) {
                if (adapter.renameFile(newFid, wantedName).isSuccess) {
                    renamedItems++
                    destCache.remove(destParentFid)
                } else {
                    failures += "目录改名失败：${node.name} -> $wantedName"
                }
            }

            // 深层改名：内部文件按重命名计划处理
            renameInside(newFid, node, depth)
        }

        /**
         * 同步一批视频到 [destFid]：按**改名后的目标名**判断是否已存在，缺的才转存。
         *
         * 判断必须用目标名而不是分享原名：上一轮已经按规则把 `01丨4K.mp4` 改名成
         * `剧名.S01E01.mp4`，如果还拿原名比对，就会判定"全都没转过"→ 整批重下 →
         * 再改名时撞上已有文件（光鸭直接返回 `code:160 文件名已存在`），
         * 表现为同步一直在转圈、目录条目越滚越多。
         *
         * 缺的按 [RESTORE_BATCH] 个一批转存：光鸭/夸克都支持一次传多个 fileId，
         * 一集一次请求会把几十集拖成好几分钟。
         */
        private suspend fun syncFiles(files: List<ShareNode>, destFid: String) {
            val existing = destList(destFid).filter { !it.isDir }.map { it.name }.toMutableSet()
            val missing = mutableListOf<ShareNode>()
            // 需要改名的文件数：分母只统计「最终名 ≠ 原名」的，避免「改名 0/0」
            renameTotal += files.count { expectedName(it) != it.name }

            files.sortedWith(NATURAL).forEach { node ->
                val expected = expectedName(node)
                when {
                    expected in existing -> skippedFiles++
                    // 本地已有原名文件但还没改名 → 就地改名，不重复转存
                    node.name in existing -> {
                        val fid = destList(destFid).firstOrNull { !it.isDir && it.name == node.name }?.fid
                        if (fid != null && adapter.renameFile(fid, expected).isSuccess) {
                            renamedItems++
                            existing += expected
                            existing -= node.name
                            destCache.remove(destFid)
                            reportRename()
                        } else {
                            skippedFiles++
                        }
                    }
                    else -> missing += node
                }
            }

            if (missing.isEmpty()) return
            missing.chunked(RESTORE_BATCH).forEach { chunk ->
                restoreChunk(chunk, destFid, existing)
            }
        }

        /** 「改名 5/12」：分母是本轮需要改名的文件数 */
        private fun reportRename() {
            onProgress(0, 0, "改名 $renamedItems/$renameTotal")
        }

        /** 「转存 3/20」：分母是本轮分享里的视频总数 */
        private fun reportRestore(done: Int) {
            val total = if (videoTotal > 0) videoTotal else done
            onProgress(0, 0, "转存 $done/$total")
        }

        /** 一批转存 + 逐个改名；批次的 diff 对不上时退回逐个转存 */
        private suspend fun restoreChunk(
            chunk: List<ShareNode>,
            destFid: String,
            existing: MutableSet<String>,
        ) {
            val saved = adapter.restoreShare(share, chunk.map { it.file }, destFid).getOrNull()
            if (saved == null || saved.size != chunk.size) {
                chunk.forEach { node ->
                    if (syncFile(node, destFid, expectedName(node))) existing += expectedName(node)
                }
                return
            }
            val byShareFid = chunk.associateBy { it.fid }
            val base = restoredFiles
            restoredFiles += saved.size
            destCache.remove(destFid)
            var landed = 0
            saved.forEach { restored ->
                val node = byShareFid[restored.shareFileId]
                val expected = node?.let { expectedName(it) } ?: restored.file.name
                landed++
                reportRestore(base + landed)
                if (node != null && expected != node.name) {
                    if (adapter.renameFile(restored.file.fid, expected).isSuccess) {
                        renamedItems++
                        reportRename()
                    } else {
                        failures += "改名失败：${node.name} -> $expected"
                    }
                }
                existing += expected
                destCache.remove(destFid)
            }
        }

        /** 分享文件按命名规则应有的最终文件名（没配规则就是原名） */
        private fun expectedName(node: ShareNode): String =
            renamePlan[node.fid]?.trim()?.takeIf { it.isNotBlank() } ?: node.name

        /** 单个文件转存（批量失败时的兜底）；成功返回 true */
        private suspend fun syncFile(node: ShareNode, destFid: String, expected: String): Boolean {
            val saved = adapter.restoreShare(share, listOf(node.file), destFid)
                .getOrElse {
                    failures += "转存「${node.name}」失败：${it.message ?: "未知错误"}"
                    return false
                }
            val newFile = saved.firstOrNull()?.file
            if (newFile == null) {
                failures += "「${node.name}」转存后未定位到新文件"
                return false
            }
            restoredFiles++
            destCache.remove(destFid)
            reportRestore(restoredFiles)

            if (expected == node.name) return true
            if (adapter.renameFile(newFile.fid, expected).isSuccess) {
                renamedItems++
                reportRename()
                destCache.remove(destFid)
                return true
            }
            failures += "改名失败：${node.name} -> $expected"
            return true
        }

        /**
         * 刚转存完的新目录里，按「原名 → 新名」把内部文件逐层改名。
         * 转存不改名，所以新目录里的文件名与分享树里一一对应，按名匹配即可。
         */
        private suspend fun renameInside(newDirFid: String, node: ShareNode, depth: Int) {
            if (depth >= maxDepth) return
            val destChildren = destList(newDirFid, refresh = true)
            node.children.sortedWith(NATURAL).forEach { child ->
                if (child.isDir) {
                    val sub = destChildren.firstOrNull { it.isDir && it.name == child.name }
                    if (sub != null) renameInside(sub.fid, child, depth + 1)
                } else {
                    val target = destChildren.firstOrNull { !it.isDir && it.name == child.name } ?: return@forEach
                    val newName = renamePlan[child.fid]?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (newName == child.name) return@forEach
                    if (adapter.renameFile(target.fid, newName).isSuccess) {
                        renamedItems++
                        destCache.remove(newDirFid)
                    } else {
                        failures += "改名失败：${child.name} -> $newName"
                    }
                }
            }
        }

        private suspend fun destList(fid: String, refresh: Boolean = false): List<DriveFile> {
            if (refresh) destCache.remove(fid)
            return destCache.getOrPut(fid) {
                adapter.listFiles(fid).getOrElse {
                    throw IllegalStateException("读取目标目录失败：${it.message ?: "未知错误"}")
                }
            }
        }
    }
}
