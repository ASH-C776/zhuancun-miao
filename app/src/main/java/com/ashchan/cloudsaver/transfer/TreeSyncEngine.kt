package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.adapter.RestoredFile
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.EpisodeParser
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.data.SeasonParser
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.rename.FileNameContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 *
 * 性能（Stage 34）：目标目录只列一次，之后**在内存索引里就地记账**（改名成功不重新列目录）；
 * 改名请求按 [RENAME_CONCURRENCY] 并发发出，几十集不再一个一个排队。
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
        /** 不是错误、但要让人看到的结论（季判定 / 冲突已按文件名处理等） */
        val notes: List<String> = emptyList(),
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
        val session = Session(adapter, share, renamePlan, maxDepth.coerceAtMost(MAX_DEPTH), onProgress, 1, 1)
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
     * @param seasonNo [TaskEntity.SEASON_FLAT]（-2）= 全部收进来平铺；
     *                 [TaskEntity.SEASON_SPECIALS]（0）= 只取特别版 / Season 0；
     *                 1..99 = 只取名字里能解析出同一季号的目录（如「第 2 季」「Season 2」），其它季的子树直接跳过。
     */
    suspend fun syncFlat(
        share: ShareInfo,
        tree: ShareNode,
        destRootFid: String,
        renamePlan: Map<String, String> = emptyMap(),
        seasonNo: Int = TaskEntity.SEASON_FLAT,
        multiSeason: Boolean = false,
        /** 开始保存的起点：多季看季+集，单季只看集；1/1 = 从头 */
        startSeason: Int = 1,
        startEpisode: Int = 1,
        /** 多季模式下「文件名季号」与「目录季号」冲突时以哪边为准（默认文件名，用户口径） */
        seasonPrefer: SeasonPrefer = SeasonPrefer.FILE,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onProgress: (stage: Int, percent: Int, message: String) -> Unit = { _, _, _ -> },
    ): Result<Report> = runCatching {
        val session = Session(
            adapter, share, renamePlan, maxDepth.coerceAtMost(MAX_DEPTH), onProgress,
            startSeason.coerceAtLeast(1), startEpisode.coerceAtLeast(1),
        )
        if (multiSeason) session.runFlatMulti(tree, destRootFid, seasonPrefer) else session.runFlat(tree, destRootFid, seasonNo)
        session.report()
    }

    companion object {
        /**
         * 分享树默认扫描层数（根的直接子级 = 1）。
         *
         * 原来是 3，真实资源里经常不够：光鸭常见的「剧名 / 第 3 季 / 2160p / 视频」就已经到第 4 层，
         * 深度卡在 3 会判成「分享里没有视频」。放到 5（= 接口允许上限），
         * 只多几条列表请求，换来不会漏掉深层视频。
         */
        const val DEFAULT_MAX_DEPTH: Int = 5
        const val MAX_DEPTH: Int = 5

        /** 一次转存请求最多带多少个文件（太多容易被判超时/限流） */
        const val RESTORE_BATCH: Int = 8

        /**
         * 改名同时发几个请求。改名是「一个文件一次请求」的纯网络操作，
         * 串行发几十个就是几十个 RTT；4 路并发在光鸭/夸克上都实测不触发限流，
         * 再高容易撞到「文件操作太频繁」。
         */
        const val RENAME_CONCURRENCY: Int = 2

        /**
         * 单个改名最多尝试几次。光鸭连续改名十几个后会开始限流（返回「操作过于频繁」），
         * 冷却后重发基本就过了，没必要让整集留在原名上。
         */
        const val RENAME_ATTEMPTS: Int = 4

        /** 重试退避基数：第 1 次等 1s、第 2 次等 2s */
        const val RENAME_RETRY_DELAY_MS: Long = 1000L

        /** 光鸭限流（「操作过于频繁」）的冷却：按尝试次数递增 2.5s / 5s / 7.5s */
        const val THROTTLE_DELAY_MS: Long = 2500L

        /** 转存遇到网络类错误（断网、DNS 解析失败）时的重试次数与退避 */
        const val RESTORE_ATTEMPTS: Int = 3
        const val RESTORE_RETRY_DELAY_MS: Long = 1500L

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
        private val startSeason: Int,
        private val startEpisode: Int,
    ) {
        private var restoredDirs = 0
        private var restoredFiles = 0
        private var renamedItems = 0
        private var skippedFiles = 0
        /** 本轮待转存视频总数（分母，用于「转存 3/20」） */
        private var videoTotal = 0
        /** 本轮需要改名的文件数（分母，用于「改名 5/12」） */
        private var renameTotal = 0
        /** 网络断了就别继续发请求（否则每个文件都刷一条同样的错） */
        private var aborted = false
        private val failures = mutableListOf<String>()
        /** 不是错、但需要让人看到的结论（如「季判定：Season 1 × 12、3 个冲突已按文件名」） */
        private val notes = mutableListOf<String>()
        private val depthSkipped = mutableListOf<String>()

        /**
         * 目标目录内容缓存：同一次同步里同一目录只列一次。
         * 改名成功后**不**清缓存，而是在这张表里改键——否则每改一个文件都要重新列一次目录，
         * 几十集的目录就是几十次多余请求（这是之前「转存+改名」慢的主因之一）。
         */
        private val destCache = mutableMapOf<String, MutableMap<String, DriveFile>>()

        fun report() = Report(
            restoredDirs = restoredDirs,
            restoredFiles = restoredFiles,
            renamedItems = renamedItems,
            skippedFiles = skippedFiles,
            failures = (failures + depthSkipped).toList(),
            notes = notes.toList(),
        )

        /** 只转树根的直接视频文件，不建文件夹、不下钻（对应平铺型分享） */
        suspend fun runDirect(tree: ShareNode, destRootFid: String) {
            val videos = tree.children.filter { it.isVideo }
            if (videos.isEmpty()) throw IllegalStateException("该目录下没有可转存的视频文件")
            val kept = videos.filter { !belowSingleStart(it) }
            if (kept.isEmpty()) throw IllegalStateException(singleStartMessage())
            onProgress(0, 0, "正在处理 ${kept.size} 个视频文件")
            syncFiles(kept, destRootFid)
        }

        /**
         * 多季模式：不按任务季度过滤，而是**按每个文件自身的季号**分组，每季落进 `剧名/Season N/`。
         *
         * 判定细节统一交给 [SeasonResolver]（文件名优先、目录名兜底、冲突单列）：
         * - 文件名有季号就按文件名（`S02E01` 一定进 Season 2）；
         * - 文件名没有、「Season 2/xxx.mkv」这种就用目录名兜底；
         * - 两边都有但互相矛盾 → 按 [prefer] 取一边，并把冲突数写进本轮说明；
         * - 完全没线索 → 留在剧名根目录。
         *
         * **Season 0 / 特别版是正常一季**（`Specials`、`Season 0`、`特别篇`、`S00E01`），进 `Season 0`。
         */
        suspend fun runFlatMulti(
            tree: ShareNode,
            destRootFid: String,
            prefer: SeasonPrefer = SeasonPrefer.FILE,
        ) {
            val plan = SeasonResolver.resolve(tree, maxDepth)
            depthSkipped.addAll(plan.depthSkipped)
            if (plan.items.isEmpty()) throw IllegalStateException(noVideoMessage(tree, TaskEntity.SEASON_FLAT))
            val keptPlan = plan.copy(
                items = plan.items.filter { passesMultiStart(it.node, it.season(prefer)) },
            )
            if (keptPlan.items.isEmpty()) throw IllegalStateException(multiStartMessage())
            videoTotal = keptPlan.items.size
            onProgress(0, 0, "比对文件")

            val note = keptPlan.summary(prefer)
            notes += if (keptPlan.conflictCount > 0) {
                "$note（默认按文件名；要改按目录名请编辑任务后「运行一次」确认）"
            } else {
                note
            }

            val groups = keptPlan.groups(prefer).toSortedMap(
                compareBy<Int?> { it == null }.thenBy { it ?: 0 }
            )
            val realSeasons = groups.keys.count { it != null }
            if (realSeasons > 1) onProgress(0, 0, "共 $realSeasons 季")

            groups.forEach { (season, list) ->
                val destFid = if (season != null) {
                    adapter.ensureFolder(destRootFid, seasonFolderName(season)).getOrElse { e ->
                        failures += "创建 Season $season 目录失败：${e.message ?: "未知错误"}"
                        destRootFid
                    }
                } else {
                    destRootFid
                }
                syncFiles(list, destFid)
            }
        }

        /** 扁平同步：收集视频（选了季就只取那一季）→ 批量转存 → 逐个按规则改名 */
        suspend fun runFlat(tree: ShareNode, destRootFid: String, seasonNo: Int) {
            val videos = mutableListOf<ShareNode>()
            collectVideos(tree, videos, seasonNo, depth = 0)
            if (videos.isEmpty()) throw IllegalStateException(noVideoMessage(tree, seasonNo))
            videos.removeAll { belowSingleStart(it) }
            if (videos.isEmpty()) throw IllegalStateException(singleStartMessage())
            videoTotal = videos.size
            onProgress(0, 0, "比对文件")
            syncFiles(videos, destRootFid)
        }

        /**
         * 找不到视频时把「实际看到了什么」写出来，不然只有一句「没有可转存的视频文件」，
         * 分不清是分享是空的、季度填错了、深度不够、还是扩展名不认。
         */
        private fun noVideoMessage(tree: ShareNode, seasonNo: Int): String {
            val seasons = mutableSetOf<Int>()
            collectSeasonNumbers(tree, depth = 0, out = seasons)
            if (seasonNo >= 0 && seasons.isNotEmpty() && seasonNo !in seasons) {
                val found = seasons.sorted().joinToString("、") { seasonLabel(it) }
                return "只取${seasonLabel(seasonNo)}，但分享里只有 $found；" +
                    "把任务里的「季度」改成 ${if (seasons.size == 1) seasons.first() else "对应季号（不选 = 平铺）"} 再跑"
            }
            val top = tree.children.take(6).joinToString("、") { child ->
                if (child.isDir) "${child.name}/" else child.name
            }
            val dirs = countDirs(tree)
            return buildString {
                append("分享中没有找到可转存的视频文件")
                append("（根下 ${tree.children.size} 项")
                if (top.isNotBlank()) append("：").append(top)
                if (tree.children.size > 6) append("…")
                append("\n共 $dirs 个目录，最深 ${minOf(maxDepth, tree.maxDepth())} 层")
                if (seasonNo >= 0) append("，").append("只取了").append(seasonLabel(seasonNo))
                append("）")
            }
        }

        /** 季号的人话：0 是特别版，不是「没选」 */
        private fun seasonLabel(seasonNo: Int): String =
            if (seasonNo == TaskEntity.SEASON_SPECIALS) "特别版（Season 0）" else "第 $seasonNo 季"

        /** 分享里出现过的季号（目录名能解析出季号的都算） */
        private fun collectSeasonNumbers(node: ShareNode, depth: Int, out: MutableSet<Int>) {
            if (depth > maxDepth) return
            node.children.forEach { child ->
                if (child.isDir) {
                    SeasonParser.parse(child.name)?.let { out += it }
                    collectSeasonNumbers(child, depth + 1, out)
                }
            }
        }

        private fun countDirs(node: ShareNode): Int =
            node.children.sumOf { child -> if (child.isDir) 1 + countDirs(child) else 0 }

        /**
         * 递归收集视频文件。
         * @param seasonNo >=0 时（0 = 特别版），遇到「看起来是别的季」的目录整棵跳过；
         *                 负值（平铺 / 多季）不做季过滤。
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
                    if (seasonNo >= 0 && season != null && season != seasonNo) return@forEach
                    collectVideos(child, out, seasonNo, depth + 1)
                } else if (child.isVideo) {
                    out += child
                }
            }
        }

        /** 目标侧季目录名（含特别版 Season 0） */
        private fun seasonFolderName(no: Int): String = "Season $no"

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
            val siblings = indexOf(destParentFid)
            val existing = siblings[wantedName]?.takeIf { it.isDir }

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
            val newDir = saved.firstOrNull()?.file
            if (newDir == null || newDir.fid.isBlank()) {
                failures += "「${node.name}」转存后未定位到新目录"
                return
            }
            restoredDirs++
            siblings[newDir.name] = newDir

            if (wantedName != node.name) {
                if (adapter.renameFile(newDir.fid, wantedName).isSuccess) {
                    renamedItems++
                    siblings.remove(newDir.name)
                    siblings[wantedName] = newDir.copy(name = wantedName)
                } else {
                    failures += "目录改名失败：${node.name} -> $wantedName"
                }
            }

            // 深层改名：内部文件按重命名计划处理
            renameInside(newDir.fid, node, depth)
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
            val index = indexOf(destFid)
            val missing = mutableListOf<ShareNode>()
            val inPlace = mutableListOf<RenameItem>()
            // 需要改名的文件数：分母只统计「最终名 ≠ 原名」的，避免「改名 0/0」
            renameTotal += files.count { expectedName(it) != it.name }

            // 本地已有文件按「集号」建一份索引：用户手动把某集改成 001.mp4 这类不规范名字时，
            // 转存 diff 按文件名找不到它，会误判缺集又转一份回来。先按集号兜住这种情况。
            val localByEpisode = index.values.asSequence()
                .filter { !it.isDir && isVideoName(it.name) }
                .mapNotNull { f -> episodeOf(f.name)?.let { it to f } }
                .toList()

            files.sortedWith(NATURAL).forEach { node ->
                val expected = expectedName(node)
                val current = index[node.name]
                when {
                    index[expected]?.isDir == false -> skippedFiles++
                    // 本地已有原名文件但还没改名 → 就地改名，不重复转存
                    current != null && !current.isDir -> {
                        if (expected == node.name) {
                            skippedFiles++
                        } else {
                            inPlace += RenameItem(current.fid, node.name, expected, current)
                        }
                    }
                    else -> {
                        // 目标名没有，但盘里可能已经有「同一集、只是名字不规范」的文件。
                        // 分工必须守住：检测转存只做「核对 + 补缺」，**不改名**；
                        // 改名统一交给长按菜单里的「统一重命名」。
                        // 这里只判定「这一集已经有了」，跳过转存，避免越补越多。
                        val ep = episodeOf(node.name)
                        val same = if (ep == null) emptyList() else localByEpisode.filter { it.first == ep }.map { it.second }
                        when {
                            same.size == 1 -> {
                                skippedFiles++
                                notes += "第 $ep 集盘里已有「${same.first().name}」，未重复转存（要规范名请用长按菜单「统一重命名」）"
                            }
                            same.size > 1 -> {
                                skippedFiles++
                                notes += "第 $ep 集盘里已有 ${same.size} 个文件，未重复转存（可用「统一重命名」查看冲突）"
                            }
                            else -> missing += node
                        }
                    }
                }
            }

            // 「已有原名、只差改名」的一批一起发，别一个一个排队
            if (inPlace.isNotEmpty()) {
                val results = renameMany(inPlace)
                results.forEach { outcome ->
                    val item = outcome.item
                    if (outcome.ok) {
                        renamedItems++
                        index.remove(item.oldName)
                        index[item.newName] = item.file.copy(name = item.newName)
                    } else {
                        skippedFiles++
                        failures += "改名失败：${item.oldName} -> ${item.newName}（${outcome.reason}）"
                    }
                }
                reportRename()
            }

            if (missing.isEmpty()) return
            missing.chunked(RESTORE_BATCH).forEach { chunk ->
                if (aborted) return@forEach
                restoreChunk(chunk, destFid, index)
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

        /** 一批转存 + 并发改名；批次的 diff 对不上时退回逐个转存 */
        private suspend fun restoreChunk(
            chunk: List<ShareNode>,
            destFid: String,
            index: MutableMap<String, DriveFile>,
        ) {
            val attempt = restoreWithRetry(chunk, destFid)
            val saved = attempt.getOrNull()
            if (attempt.isFailure || saved == null || saved.size != chunk.size) {
                // 断网/域名解析失败：整轮没意义了，记一条就中止，别把 20 个文件各刷一条一样的错
                val msg = attempt.exceptionOrNull()?.message.orEmpty()
                if (attempt.isFailure && isNetworkError(msg)) {
                    aborted = true
                    failures += "网络中断，本轮中止：$msg"
                    return
                }
                chunk.forEach { node -> syncFile(node, destFid, index, expectedName(node)) }
                return
            }
            val byShareFid = chunk.associateBy { it.fid }
            restoredFiles += saved.size
            // 转存落的是原名：先按原名记账，改完名再改键，全程不用重新列目录
            saved.forEach { restored ->
                byShareFid[restored.shareFileId]?.let { index[it.name] = restored.file }
            }
            reportRestore(restoredFiles)

            val items = saved.mapNotNull { restored ->
                val node = byShareFid[restored.shareFileId] ?: return@mapNotNull null
                val expected = expectedName(node)
                if (expected == node.name) null
                else RenameItem(restored.file.fid, node.name, expected, restored.file)
            }
            if (items.isEmpty()) return

            renameMany(items).forEach { outcome ->
                val item = outcome.item
                index.remove(item.oldName)
                if (outcome.ok) {
                    renamedItems++
                    index[item.newName] = item.file.copy(name = item.newName)
                } else {
                    failures += "改名失败：${item.oldName} -> ${item.newName}（${outcome.reason}）"
                }
            }
            reportRename()
        }

        /** 分享文件按命名规则应有的最终文件名（没配规则就是原名） */
        private fun expectedName(node: ShareNode): String =
            renamePlan[node.fid]?.trim()?.takeIf { it.isNotBlank() } ?: node.name

        /** 从文件名解析集号（只用于「盘里是否已有这一集」的判断，与改名规则同源） */
        private fun episodeOf(name: String): Int? =
            FileNameContext(name).episodeRaw?.toIntOrNull()

        /** 单季：文件名集号早于起点就跳过；解析不出集号的文件保留，避免误伤 */
        private fun belowSingleStart(node: ShareNode): Boolean {
            if (startEpisode <= 1) return false
            val ep = episodeOf(node.name) ?: return false
            return ep < startEpisode
        }

        /**
         * 多季：前面的季整季跳过；起点那一季按集号过滤，后面的季全部保留。
         * 季号判不出来时，只有从第 1 季起才按集号过滤；要从后面季起就保守跳过。
         */
        private fun passesMultiStart(node: ShareNode, season: Int?): Boolean {
            // 特别版（Season 0）不参与正片集序，起点设置不筛它
            if (season != null && season <= 0) return true
            val ep = episodeOf(node.name)
            if (season == null) {
                if (startSeason > 1) return false
                return ep == null || ep >= startEpisode
            }
            if (season < startSeason) return false
            if (season == startSeason && ep != null && ep < startEpisode) return false
            return true
        }

        private fun singleStartMessage(): String =
            "开始保存设为第 $startEpisode 集，但分享里没有这一集之后的视频"

        private fun multiStartMessage(): String =
            "开始保存设为第 $startSeason 季第 $startEpisode 集，但分享里没有这之后的视频"

        private fun isVideoName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf(
                "mp4", "mkv", "mov", "m4v", "avi", "mpeg", "mpg", "ts", "zip", "cas", "iso", "",
            )

        /** 单个文件转存（批量失败时的兜底）；成功返回 true */
        private suspend fun syncFile(
            node: ShareNode,
            destFid: String,
            index: MutableMap<String, DriveFile>,
            expected: String,
        ): Boolean {
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
            index[node.name] = newFile
            reportRestore(restoredFiles)

            if (expected == node.name) return true
            val outcome = renameMany(listOf(RenameItem(newFile.fid, node.name, expected, newFile)))
                .firstOrNull()
            val ok = outcome?.ok == true
            index.remove(node.name)
            if (ok) {
                renamedItems++
                index[expected] = newFile.copy(name = expected)
                reportRename()
            } else {
                failures += "改名失败：${node.name} -> $expected（${outcome?.reason.orEmpty()}）"
            }
            return true
        }

        /**
         * 刚转存完的新目录里，按「原名 → 新名」把内部文件逐层改名。
         * 转存不改名，所以新目录里的文件名与分享树里一一对应，按名匹配即可。
         */
        private suspend fun renameInside(newDirFid: String, node: ShareNode, depth: Int) {
            if (depth >= maxDepth) return
            val destChildren = indexOf(newDirFid, refresh = true)
            val items = mutableListOf<RenameItem>()
            node.children.sortedWith(NATURAL).forEach { child ->
                if (child.isDir) {
                    val sub = destChildren[child.name]?.takeIf { it.isDir }
                    if (sub != null) renameInside(sub.fid, child, depth + 1)
                } else {
                    val target = destChildren[child.name]?.takeIf { !it.isDir } ?: return@forEach
                    val newName = renamePlan[child.fid]?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (newName == child.name) return@forEach
                    items += RenameItem(target.fid, child.name, newName, target)
                }
            }
            if (items.isEmpty()) return
            renameMany(items).forEach { outcome ->
                val item = outcome.item
                destChildren.remove(item.oldName)
                if (outcome.ok) {
                    renamedItems++
                    destChildren[item.newName] = item.file.copy(name = item.newName)
                } else {
                    failures += "改名失败：${item.oldName} -> ${item.newName}（${outcome.reason}）"
                }
            }
            reportRename()
        }

        // -------- 基础设施 --------

        /** 待改名的一项：fid + 旧名 + 新名 + 目录里的原始条目（改完在索引里换键用） */
        private data class RenameItem(
            val fid: String,
            val oldName: String,
            val newName: String,
            val file: DriveFile,
        )

        /** 改名结果：成功与否 + 失败原因（原因要写进运行日志，不然只看到「改名失败」没法查） */
        private data class RenameOutcome(
            val item: RenameItem,
            val ok: Boolean,
            val reason: String = "",
        )

        /**
         * 并发改名：一次最多 [RENAME_CONCURRENCY] 个在飞，失败的重试 [RENAME_ATTEMPTS] 次。
         *
         * 重试是必须的：光鸭在一轮里连续改名十几个之后会开始拒绝（限流），
         * 隔 1s / 2s 再发一次多半就过了；真失败时把服务器原话带出来。
         */
        private suspend fun renameMany(items: List<RenameItem>): List<RenameOutcome> {
            if (items.isEmpty()) return emptyList()
            return coroutineScope {
                val slots = Semaphore(RENAME_CONCURRENCY)
                items.map { item ->
                    async(Dispatchers.IO) {
                        slots.withPermit { renameWithRetry(item) }
                    }
                }.awaitAll()
            }
        }

        private suspend fun renameWithRetry(item: RenameItem): RenameOutcome {
            var reason = ""
            var attempt = 0
            while (attempt < RENAME_ATTEMPTS) {
                val result = runCatching { adapter.renameFile(item.fid, item.newName) }.getOrNull()
                if (result?.isSuccess == true) return RenameOutcome(item, true)
                reason = result?.exceptionOrNull()?.message
                    ?: "改名请求异常（${item.newName}）"
                attempt++
                if (attempt < RENAME_ATTEMPTS) {
                    // 光鸭限流（「操作过于频繁」）要给更长的冷却，普通错误短暂退避即可
                    val wait = if (isThrottled(reason)) {
                        THROTTLE_DELAY_MS * attempt
                    } else {
                        RENAME_RETRY_DELAY_MS * attempt
                    }
                    kotlinx.coroutines.delay(wait)
                }
            }
            return RenameOutcome(item, false, reason)
        }

        /** 转存落盘：网络类错误退避重试，别一断网就逐个文件刷错 */
        private suspend fun restoreWithRetry(
            chunk: List<ShareNode>,
            destFid: String,
        ): Result<List<RestoredFile>> {
            var attempt = 1
            var result = adapter.restoreShare(share, chunk.map { it.file }, destFid)
            while (result.isFailure &&
                attempt < RESTORE_ATTEMPTS &&
                isNetworkError(result.exceptionOrNull()?.message.orEmpty())
            ) {
                kotlinx.coroutines.delay(RESTORE_RETRY_DELAY_MS * attempt)
                attempt++
                result = adapter.restoreShare(share, chunk.map { it.file }, destFid)
            }
            return result
        }

        /** 目标目录索引（文件名 → 条目），同一目录只列一次；`refresh` 用于刚转存完的新目录 */
        private suspend fun indexOf(fid: String, refresh: Boolean = false): MutableMap<String, DriveFile> {
            if (refresh) destCache.remove(fid)
            return destCache.getOrPut(fid) {
                adapter.listFiles(fid).getOrElse {
                    throw IllegalStateException("读取目标目录失败：${it.message ?: "未知错误"}")
                }.associateBy { it.name }.toMutableMap()
            }
        }

        /** 服务器在喊「操作过于频繁」——这类失败值得拉长冷却重试 */
        private fun isThrottled(message: String): Boolean {
            val m = message.lowercase()
            return "频繁" in message || "too frequent" in m || "rate limit" in m ||
                "稍后重试" in message || "later" in m
        }

        /** 断网 / DNS 解析失败 / 连接超时：重试没用就整轮中止，别逐个文件刷错 */
        private fun isNetworkError(message: String): Boolean {
            val m = message.lowercase()
            return "unable to resolve" in m || "resolve host" in m || "unknownhost" in m ||
                "无法解析" in message || "网络" in message ||
                "timeout" in m || "timed out" in m || "超时" in message ||
                "failed to connect" in m || "connection" in m || "socket" in m ||
                "unreachable" in m || "502" in m || "503" in m
        }
    }
}




