package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.core.tmdb.TmdbClient
import com.ashchan.cloudsaver.core.tmdb.totalOfProperSeasons
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.EpisodeStats
import com.ashchan.cloudsaver.data.SeasonParser
import com.ashchan.cloudsaver.data.SnapshotCalc
import com.ashchan.cloudsaver.data.SourceLinks
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.data.db.TaskSourceEntity
import com.ashchan.cloudsaver.drive.DriveSession
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.rename.RenameEngine
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.storage.TmdbStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单次任务执行编排：登录态 → 解析分享 → 扫描树 → 按范围同步 → 统计集数 → 落快照。
 *
 * 设计约束（对应「纯单机、单账号」）：
 * - 凭证按 [TaskEntity.driveType] 取唯一一份，未登录直接拦截，不静默失败。
 * - 全程串行：不做并行转存，避免同一网盘并发触发风控。
 * - 任何异常都写一条失败记录，卡片保留上次成功的集数，只更新说明与时间。
 */
class TaskRunner(
    private val repo: TaskRepository,
    private val credentialStore: CredentialStore,
    private val tmdbStore: TmdbStore? = null,
    /** 命名规则来源；为空则所有任务都不改名（与老行为一致） */
    private val ruleStore: RuleStore? = null,
) {

    companion object {
        /**
         * 统一装配入口：执行链需要的依赖只在这里创建一次。
         * 前台/后台/界面三个调用方都走这里，避免各写一套 new 造成依赖不一致。
         */
        fun create(context: android.content.Context, repo: TaskRepository): TaskRunner =
            TaskRunner(
                repo = repo,
                credentialStore = CredentialStore.get(context),
                tmdbStore = TmdbStore(context),
                ruleStore = RuleStore(context),
            )

        /** 集数统计递归层数上限（剧名 / 季 / 文件 三层足够，留一层余量） */
        private const val MAX_COUNT_DEPTH = 3

        /**
         * 全局互斥：同一台设备上无论从哪个入口（单条 / 一键全部 / 打开 App 自动 / 将来的后台定时）触发，
         * 任何时刻只有一条执行链在跑。转存本身靠目录 diff 幂等，但并发会让
         * 「列目录 → 建目录 → 比对」同时发生，出现重复建同名目录、集数统计串味。
         */
        private val runLock = Mutex()

        /** 网络步骤失败后重试一次，间隔 1.2 秒（网盘接口偶发 5xx / 连接重置） */
        private const val RETRY_DELAY_MS = 1200L
    }

    /**
     * 关键网络步骤：失败重试一次。
     *
     * 只重试**只读**步骤（解析分享 / 拿访问令牌 / 拉目录树 / 建目录）。
     * 真正的转存动作不在这里重试 —— 它自带目录 diff（已存在就跳过），
     * 重复执行天然安全，没必要在超时上再加一层重试把等待拉长。
     */
    private suspend fun <T> retryOnce(step: String, block: suspend () -> Result<T>): Result<T> {
        val first = block()
        if (first.isSuccess) return first
        val err = first.exceptionOrNull()
        android.util.Log.w("TaskRunner", "$step 首次失败，重试一次：${err?.message}")
        delay(RETRY_DELAY_MS)
        val second = block()
        if (second.isFailure) {
            android.util.Log.e("TaskRunner", "$step 重试仍失败：${second.exceptionOrNull()?.message}")
        }
        return second
    }

    suspend fun run(
        task: TaskEntity,
        trigger: String = RunRecordEntity.TRIGGER_MANUAL,
        /** false = 试跑（「运行一次」）：照常转存，但不动任务快照，只写运行日志 */
        saveSnapshot: Boolean = true,
        /** 试跑时表单里的链接（含多源）；为空则按 [task] 的源列表取 */
        draftLinks: List<String> = emptyList(),
        /** 阶段回调：把「准备中 / 读取目录 / 比对文件 / 转存 3/20 …」写给卡片上的文字 */
        onPhase: (String) -> Unit = {},
    ): Result<TreeSyncEngine.Report> {
        val startedAt = System.currentTimeMillis()
        val drive = DriveType.fromName(task.driveType)
        if (drive == null) {
            val msg = "未知的网盘类型：${task.driveType}"
            repo.finishRun(task, trigger, startedAt, null, 0, 0, 0, 0, msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        // 源列表：试跑用表单里的，正式执行读 task_sources（老任务由迁移补成 1 行）
        val targets = targetsOf(task, draftLinks)
        if (targets.isEmpty()) {
            val msg = "任务没有可用的分享链接"
            repo.finishRun(task, trigger, startedAt, null, 0, 0, 0, 0, msg)
            return Result.failure(IllegalStateException(msg))
        }

        val baseFid = task.saveFid.ifBlank { "0" }
        return try {
            runLock.withLock {
                // 转存前先「等价于点一次设置页登录」：掉线时自动恢复，别等用户回来手点
                val adapter = DriveSession.ensure(drive, credentialStore).getOrElse { err ->
                    val msg = err.message ?: "登录已失效"
                    repo.finishRun(task, trigger, startedAt, null, 0, 0, 0, 0, msg)
                    return@withLock Result.failure<TreeSyncEngine.Report>(err)
                }
                try {
                    executeLocked(task, trigger, startedAt, adapter, baseFid, saveSnapshot, targets, onPhase)
                } catch (e: Throwable) {
                    // 跑到一半掉线：强制重新登录一次，再整轮重来（目录 diff 幂等，重跑不重复转存）
                    if (!DriveSession.isAuthError(e)) throw e
                    android.util.Log.w("TaskRunner", "执行中登录失效，重新登录后重试：${e.message}")
                    DriveSession.invalidate(drive)
                    val retryAdapter = DriveSession.ensure(drive, credentialStore, force = true)
                        .getOrElse { throw e }
                    executeLocked(task, trigger, startedAt, retryAdapter, baseFid, saveSnapshot, targets, onPhase)
                }
            }
        } catch (e: Throwable) {
            repo.finishRun(
                task = task,
                trigger = trigger,
                startedAt = startedAt,
                report = null,
                localEpisodes = 0,
                localLastEpisode = 0,
                tmdbLatest = 0,
                errorMessage = e.message ?: "执行失败",
            )
            Result.failure(e)
        }
    }

    /** 一个要跑的源：行号（用于回写状态）+ 链接 + 上一轮结论 */
    internal data class RunTarget(
        val position: Int,
        val url: String,
        val previousStatus: String = TaskSourceEntity.STATUS_UNKNOWN,
    )

    /**
     * 预扫描结果：一个源解析好的分享信息 + 目录树 + 命名质量分。
     * 多源按 [score] 排序后再转存；目录树只拉一次，后面直接复用。
     */
    private data class PreparedSource(
        val target: RunTarget,
        val label: String,
        val share: ShareInfo,
        val tree: ShareNode,
        val score: SourceScore?,
    )

    /**
     * 要跑的源清单。
     *
     * - 「运行一次」的试跑：用表单里的链接，不落状态（任务还没保存）
     * - 正式执行：读 task_sources，按行序，带上一轮结论（用来判断「一次 WARN、两次 FAIL」）
     * - 兜底：老数据 source 表为空时退回 task.shareUrl
     */
    private suspend fun targetsOf(task: TaskEntity, draftLinks: List<String>): List<RunTarget> {
        if (draftLinks.isNotEmpty()) {
            return SourceLinks.normalize(draftLinks)
                .mapIndexed { index, url -> RunTarget(index, url) }
        }
        val saved = if (task.id > 0) repo.sourcesOf(task.id) else emptyList()
        val fromDb = saved
            .map { RunTarget(it.position, it.shareUrl.trim(), it.lastStatus) }
            .filter { it.url.isNotEmpty() }
        if (fromDb.isNotEmpty()) return fromDb
        val fallback = task.shareUrl.trim()
        return if (fallback.isEmpty()) emptyList() else listOf(RunTarget(0, fallback))
    }

    /**
     * 转存前预检（只读）：多季任务先扫一遍分享树，看有没有「文件名季号和目录季号对不上」。
     *
     * 调用方（新建页/编辑页）拿到非 null 结果就弹一次确认；正式跑卡片的「运行」不预检，
     * 直接按文件名处理并把结论写进日志——那边没有弹窗时机。
     *
     * 任何一步失败都返回 null（预检是增强，不是前置条件，不能挡住正常转存）。
     */
    suspend fun precheckSeasons(task: TaskEntity, draftLinks: List<String>): SeasonConflictInfo? {
        if (task.seasonNo != TaskEntity.SEASON_MULTI) return null
        val drive = DriveType.fromName(task.driveType) ?: return null
        return runCatching {
            val targets = targetsOf(task, draftLinks)
            if (targets.isEmpty()) return@runCatching null
            val adapter = DriveSession.ensure(drive, credentialStore).getOrElse { return@runCatching null }
            val share = adapter.parseShareLink(targets.first().url).getOrThrow()
            val resolved = adapter.resolveShareAccess(share).getOrThrow()
            val tree = adapter.fetchShareTree(
                share = resolved,
                maxDepth = TreeSyncEngine.DEFAULT_MAX_DEPTH,
            ).getOrThrow()
            val plan = SeasonResolver.resolve(tree, TreeSyncEngine.DEFAULT_MAX_DEPTH)
            if (plan.conflictCount == 0) {
                null
            } else {
                SeasonConflictInfo(
                    count = plan.conflictCount,
                    totalVideos = plan.items.size,
                    examples = plan.conflictExamples(3),
                    summary = plan.summary(SeasonPrefer.FILE),
                )
            }
        }.getOrNull()
    }

    /** [run] 的实际主体，调用时已持有 [runLock] */
    /**
     * 多源时给阶段文案加个「源K 」前缀（`源2 转存 3/20`），单源不加。
     * 计数按源各自从头算，前缀正好说明「现在是第几个源」。
     */
    private fun withSource(label: String, total: Int, message: String): String =
        if (total > 1) "$label $message" else message

    private suspend fun executeLocked(
        task: TaskEntity,
        trigger: String,
        startedAt: Long,
        adapter: CloudDriveAdapter,
        baseFid: String,
        saveSnapshot: Boolean,
        links: List<RunTarget>,
        onPhase: (String) -> Unit = {},
    ): Result<TreeSyncEngine.Report> {
        onPhase("准备中")
        // 每个任务在自己的保存位置下建一个「剧名」目录：
        // 多剧混在同一层会让集数统计把整盘视频算作一个任务，必须隔离。
        // 勾了「文件夹加 TMDB ID」时目录名变成「剧名 tmdb-12345」。
        // 目录只建一次：所有源共用同一个保存位置（一个任务 = 一套规则）。
        val folderName = taskNameOf(task)
        val nameFid = retryOnce("创建任务目录") {
            adapter.ensureFolder(baseFid, folderName)
        }.getOrElse { baseFid }

        // 选了季度（含 0 = 特别版）就再多一层 `Season N`；平铺（-2）不建；多季交给引擎按文件季号各自建
        val multiSeason = task.seasonNo == TaskEntity.SEASON_MULTI
        val destFid = if (task.seasonNo >= TaskEntity.SEASON_SPECIALS) {
            retryOnce("创建季目录") {
                adapter.ensureFolder(nameFid, seasonFolderName(task.seasonNo))
            }.getOrElse { nameFid }
        } else {
            nameFid
        }

        // 逐源跑：某源失败只记一行，不影响其它源（用户口径：源只是多几个链接入口）
        var restoredDirs = 0
        var restoredFiles = 0
        var renamedItems = 0
        var skippedFiles = 0
        val failures = mutableListOf<String>()
        /** 季判定等「不是错」的结论，最后写进运行日志说明 */
        val notes = mutableListOf<String>()
        var anyOk = false

        // TMDB 先查一次：多源时要靠它判断「缺口是否已补齐」，写快照也复用这一次结果
        val tmdb = resolveTmdb(task)

        // 「命中才转存」只在选了命名规则时生效：没选规则本来就不改名，沿用老行为全部转存
        val filterEnabled = task.ruleKey.trim().isNotEmpty()
        val multiSource = links.size > 1

        // 统计口径：指定季按该季、多季按 TMDB 对应那一季、平铺按第一季（与 S01 命名一致）
        val statMode = when {
            task.seasonNo == TaskEntity.SEASON_MULTI -> EpisodeStats.Mode.MULTI
            task.seasonNo == TaskEntity.SEASON_FLAT -> EpisodeStats.Mode.FLAT
            else -> EpisodeStats.Mode.FIXED
        }
        val fixedSeason = task.seasonNo.coerceAtLeast(0)
        val targetSeason = tmdb.countSeason?.takeIf { it >= 0 } ?: 1
        // 多季的「盘里有多少集」要逐季相加；其它口径仍只看被指定的那一季
        fun haveEpisodes(r: EpisodeStats.Result): Int =
            if (statMode == EpisodeStats.Mode.MULTI) r.episodesAll else r.episodes

        /** 回写单个源的结论：一次失败算 WARN，连续两次才升级 FAIL（不误报失效） */
        suspend fun markSource(target: RunTarget, ok: Boolean, error: String) {
            if (task.id <= 0) return
            val newStatus = TaskSourceEntity.statusAfterRun(target.previousStatus, ok)
            runCatching {
                repo.markSourceStatus(
                    taskId = task.id,
                    position = target.position,
                    status = newStatus,
                    error = if (newStatus == TaskSourceEntity.STATUS_OK) "" else error,
                )
            }
        }

        // ① 预扫描：读目录树 + 给每个源打命名质量分（只列目录，不下载任何文件）
        val prepared = mutableListOf<PreparedSource>()
        val preErrors = mutableListOf<Pair<RunTarget, String>>()
        links.forEachIndexed { index, target ->
            val label = if (links.size > 1) "源${index + 1}" else "源"
            try {
                onPhase(withSource(label, links.size, "预检命名"))
                val share = retryOnce("$label 解析分享链接") {
                    adapter.parseShareLink(target.url)
                }.getOrThrow()
                val resolved = retryOnce("$label 获取分享访问令牌") {
                    adapter.resolveShareAccess(share)
                }.getOrThrow()

                val rootId = task.seasonFid.ifBlank { task.startFid }.ifBlank { resolved.pdirFid }
                val tree = retryOnce("$label 拉取分享目录树") {
                    adapter.fetchShareTree(
                        share = resolved,
                        rootId = rootId.takeIf { it != "0" },
                        maxDepth = TreeSyncEngine.DEFAULT_MAX_DEPTH,
                    )
                }.getOrThrow()

                prepared += PreparedSource(
                    target = target,
                    label = label,
                    share = resolved,
                    tree = tree,
                    score = if (multiSource) SourceQuality.score(tree) else null,
                )
            } catch (e: Throwable) {
                if (DriveSession.isAuthError(e)) throw e
                preErrors += target to (e.message ?: "未知错误")
            }
        }

        // ② 多源按命名质量排序：名字更规范、歧义更少的源先跑（同分保持原链接顺序）
        val ordered = prepared.sortedWith(
            compareByDescending<PreparedSource> { it.score?.score ?: 0 }
                .thenBy { it.target.position }
        )
        if (multiSource && ordered.size > 1) {
            notes += "命名质量优先：" + ordered.joinToString(" > ") { p ->
                "${p.label} ${p.score?.score ?: 0}分（${p.score?.hits ?: 0}/${p.score?.videos ?: 0} 命中）"
            }
        }

        // 预扫描就失败的源：记失败 + 回写状态，不参与排序
        preErrors.forEach { (target, message) ->
            val prefix = if (links.size > 1) "源${target.position + 1} " else ""
            failures += "${prefix}失败：$message"
            markSource(target, ok = false, error = message)
        }

        // ③ 按顺序转存；多源时每跑完一个就重算缺口，补齐了就停
        var stoppedForGap = false
        var lastCount: EpisodeStats.Result? = null

        for (p in ordered) {
            if (stoppedForGap) {
                notes += "${p.label} 未执行（前面的源已补齐）"
                continue
            }
            val label = p.label
            onPhase(withSource(label, links.size, "比对文件"))

            // 命中裁剪：剧场版、解析不出集号、有歧义的一律不转；同集多版本只留一个
            val plan = TransferClassifier.plan(p.tree, filterEnabled)
            // 第二道：规则没匹配上（占位符解析不了）的文件也不转 —— 转存数必须等于改名数
            val renameOutcome = if (filterEnabled) buildRenameOutcome(task, plan.tree) else null
            val workTree = if (filterEnabled && renameOutcome != null) {
                TransferClassifier.prune(plan.tree, renameOutcome.matchedFids)
                    ?: plan.tree.copy(children = emptyList())
            } else {
                plan.tree
            }
            val droppedByRule = if (filterEnabled && renameOutcome != null) {
                plan.kept.count { it.node.fid !in renameOutcome.matchedFids }
            } else {
                0
            }
            val hasWork = workTree.flattenFiles().any { it.isVideo }
            android.util.Log.i(
                "TransferPlan",
                "$label 树内视频=${p.tree.flattenFiles().count { it.isVideo }} " +
                    "命中=${plan.kept.size} 未命中=${plan.unmatched.size} 剧场版=${plan.movies.size} " +
                    "多版本=${plan.duplicates.size} 规则未匹配=$droppedByRule 实际转存=${workTree.flattenFiles().count { it.isVideo }} " +
                    "未命中样例=${plan.unmatched.take(5).map { it.name }}",
            )

            var errorNote = ""
            val outcome: Result<TreeSyncEngine.Report> = try {
                if (filterEnabled && !hasWork) {
                    // 一个都没命中：这一源没有可转存内容，不算失败
                    Result.success(
                        TreeSyncEngine.Report(
                            restoredDirs = 0,
                            restoredFiles = 0,
                            renamedItems = 0,
                            skippedFiles = 0,
                            failures = emptyList(),
                            explicitOk = true,
                            notes = emptyList(),
                        )
                    )
                } else {
                    TreeSyncEngine(adapter).syncFlat(
                        share = p.share,
                        tree = workTree,
                        destRootFid = destFid,
                        renamePlan = renameOutcome?.plan ?: emptyMap(),
                        seasonNo = if (multiSeason) TaskEntity.SEASON_FLAT else task.seasonNo,
                        multiSeason = multiSeason,
                        startSeason = task.saveStartSeason,
                        startEpisode = task.saveStartEpisode,
                        // 引擎按「比对文件 / 转存 3/20 / 改名 5/12」逐条上报，直接翻到卡片上
                        onProgress = { _, _, message ->
                            onPhase(withSource(label, links.size, message))
                        },
                    )
                }
            } catch (e: Throwable) {
                // 掉线类的异常交给外层重登整轮重跑；其余按「本源失败」吞掉，继续下一个源
                if (DriveSession.isAuthError(e)) throw e
                errorNote = e.message ?: "未知错误"
                Result.failure(e)
            }

            markSource(p.target, outcome.getOrNull()?.ok == true, errorNote)

            outcome
                .onSuccess { r ->
                    anyOk = anyOk || r.ok
                    restoredDirs += r.restoredDirs
                    restoredFiles += r.restoredFiles
                    renamedItems += r.renamedItems
                    skippedFiles += r.skippedFiles
                    // 同一批文件可能两个源都有：第二个源靠目标目录 diff 判定已存在 → 计入跳过
                    r.failures.forEach { failures += if (links.size > 1) "$label $it" else it }
                    // 季判定这类「不是错但要让人看到」的结论，多源时带上源前缀
                    r.notes.forEach { notes += if (links.size > 1) "$label $it" else it }
                }
                .onFailure { e ->
                    failures += if (links.size > 1) {
                        "$label 失败：${e.message ?: "未知错误"}"
                    } else {
                        e.message ?: "同步失败"
                    }
                }

            // 「命中才转存」的结论写进日志：命中几集、几个多版本被合并、几个未命中没转
            val summary = plan.summary() + if (droppedByRule > 0) {
                "\n规则未匹配 $droppedByRule 个未转存"
            } else {
                ""
            }
            notes += if (links.size > 1) "$label $summary" else summary

            if (multiSource) {
                val current = countLocal(adapter, destFid, statMode, fixedSeason, targetSeason)
                lastCount = current
                if (tmdb.bound &&
                    SnapshotCalc.missing(haveEpisodes(current), tmdb.latestEpisode, tmdb.totalEpisodes) == 0
                ) {
                    stoppedForGap = true
                    notes += "$label 之后缺口已补齐，后面的源不再执行"
                }
            }
        }

        val report = TreeSyncEngine.Report(
            restoredDirs = restoredDirs,
            restoredFiles = restoredFiles,
            renamedItems = renamedItems,
            skippedFiles = skippedFiles,
            failures = failures.toList().ifEmpty {
                if (anyOk) emptyList() else listOf("所有源都没有可转存的内容")
            },
            // 多源：只要有一个源成功，这一轮就算成功（失败的源原因仍留在 failures 里提示）
            explicitOk = anyOk,
            notes = notes.toList(),
        )

        // 光鸭的 access_token 会在刷新后轮换，必须回写，否则下次登录失效
        if (adapter is GuangYaAdapter) {
            credentialStore.saveCredential(DriveType.GUANGYA, adapter.currentCredential())
        }

        onPhase("统计中")
        val counted = lastCount ?: countLocal(adapter, destFid, statMode, fixedSeason, targetSeason)

        // 完整度缺口（总数 - 已收）只进运行日志：卡片与进度条走「追剧」口径，
        // 这里补一句「盘里 114 集 / 共 211 集，缺 97 集」，两者互不顶替。
        val finalReport = if (tmdb.bound || tmdb.scopeIssue != null) {
            val extra = buildList {
                if (tmdb.bound && tmdb.totalEpisodes > 0) {
                    add(SnapshotCalc.gapText(haveEpisodes(counted), tmdb.latestEpisode, tmdb.totalEpisodes))
                }
                tmdb.scopeIssue?.let { add(it) }
                // 多季：逐季列一行，整季目录缺失也逃不掉
                if (statMode == EpisodeStats.Mode.MULTI && tmdb.seasonTargets.isNotEmpty()) {
                    tmdb.seasonTargets.entries.sortedBy { it.key }.forEach { (season, target) ->
                        val have = counted.bySeason[season] ?: 0
                        add("第${season}季 已收 ${have} 集 / 共 ${target} 集")
                    }
                }
            }
            if (extra.isEmpty()) report else report.copy(notes = report.notes + extra)
        } else {
            report
        }

        // 更新日按 TMDB 自动推断：只在「任务设成自动」且还没手动设过更新日时写入，
        // 不覆盖用户手动点选的星期，避免下次打开 App 被悄改。
        // 更新日按 TMDB 自动推断：`updateMode == TMDB` 表示「用户从没手动点过更新日」，
        // 这种情况每次都用最新推断结果覆盖（原项目也是每次现算），
        // 修掉老数据里因「只取下一集」而被压成单日的错误值；用户手点过就会切成 MANUAL，不再被覆盖。
        if (task.updateMode == TaskEntity.UPDATE_MODE_TMDB && tmdb.weekdays.isNotEmpty()) {
            val formatted = Weekdays.format(tmdb.weekdays.toSet())
            if (formatted != task.weekdays) {
                repo.update(task.copy(weekdays = formatted))
            }
        }

        repo.finishRun(
            task = task,
            trigger = trigger,
            startedAt = startedAt,
            report = finalReport,
            localEpisodes = haveEpisodes(counted),
            localLastEpisode = if (statMode == EpisodeStats.Mode.MULTI) haveEpisodes(counted) else counted.lastEpisode,
            tmdbLatest = tmdb.latestEpisode,
            tmdbTotal = tmdb.totalEpisodes,
            saveSnapshot = saveSnapshot,
        )
        return Result.success(finalReport)
    }

    /** 串行执行多个任务（「一键全部」与「打开 App 自动同步」共用） */
    suspend fun runAll(
        tasks: List<TaskEntity>,
        trigger: String,
        onEach: (TaskEntity, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Pair<TaskEntity, Result<TreeSyncEngine.Report>>> {
        val enabled = tasks.filter { it.enabled }
        val results = mutableListOf<Pair<TaskEntity, Result<TreeSyncEngine.Report>>>()
        enabled.forEachIndexed { index, task ->
            onEach(task, index + 1, enabled.size)
            results += task to run(task, trigger)
        }
        return results
    }

    /**
     * 统计任务目标目录下已有的集数（**按季分区**，不再是各季混算）。
     *
     * 递归到 [MAX_COUNT_DEPTH] 层：真实资源常见「剧名 / 第 N 季 - 分辨率 / 集.mp4」三层，
     * 只扫两层会把盘里集数算成 0。跳过的深度不再下钻，避免深层大目录把请求打爆。
     *
     * 分区口径见 [EpisodeStats]：指定季只数该季；多季只数 TMDB 对应那一季；
     * 平铺按第一季（与 `剧名.S01E01.后缀` 的命名口径一致）。Season 0 的特别篇不会混进正片统计。
     */
    private suspend fun countLocal(
        adapter: CloudDriveAdapter,
        destFid: String,
        mode: EpisodeStats.Mode,
        fixedSeason: Int = 1,
        targetSeason: Int = 1,
    ): EpisodeStats.Result {
        val entries = mutableListOf<EpisodeStats.Entry>()

        suspend fun walk(fid: String, dirs: List<String>, depth: Int) {
            val items = adapter.listFiles(fid).getOrNull() ?: return
            items.filter { !it.isDir }.forEach { entries += EpisodeStats.Entry(dirs, it.name) }
            if (depth >= MAX_COUNT_DEPTH) return
            items.filter { it.isDir }.forEach { walk(it.fid, dirs + it.name, depth + 1) }
        }

        walk(destFid, emptyList(), 0)
        // 目录分布：盘里 114 个还是 201 个，一眼看出是「只扫到一个子目录」还是「目录确实只有这些」
        val byDir = entries.groupBy { it.dirs.joinToString("/").ifEmpty { "<根>" } }
            .mapValues { (_, list) -> list.size }
        android.util.Log.i("EpisodeStats", "dest=$destFid 目录分布=$byDir")
        val counted = EpisodeStats.count(entries, mode, fixedSeason, targetSeason)
        // 诊断打点：卡片数字对不上时，直接看 logcat 就知道是漏扫、重复、还是解析失败
        android.util.Log.i(
            "EpisodeStats",
            "dest=$destFid mode=$mode fixed=$fixedSeason target=$targetSeason " +
                "→ 集数=${counted.episodes} 末集=${counted.lastEpisode} " +
                "扫到=${counted.scanned} 入桶=${counted.counted} 重复=${counted.duplicates} " +
                "其它季=${counted.otherSeason} 解析失败=${counted.unparsedCount}(歧义${counted.ambiguous}) " +
                "剧场版=${counted.movies} 季分布=${counted.bySeason} " +
                "样例失败=${counted.unparsed.take(5)}",
        )
        return counted
    }

    /** TMDB 解析结果（失败一律回 0/null，不影响转存主流程） */
    private data class TmdbLatest(
        val latestEpisode: Int,
        val weekdays: List<Int>,
        val totalEpisodes: Int = 0,
        /** 这次统计对应的季（多季任务按这一季分区数集）；查不到为 null */
        val countSeason: Int? = null,
        /** 多季口径：每季的目标集数（S1 起，不含特别版），用于逐季对账 */
        val seasonTargets: Map<Int, Int> = emptyMap(),
        /** 口径本身有问题（例如任务选了 TMDB 上不存在的季），写进运行日志提醒用户 */
        val scopeIssue: String? = null,
    ) {
        /** 是否绑上了 TMDB（没绑就没有「缺几集」这把尺子，多源也就无法判断补齐） */
        val bound: Boolean get() = latestEpisode > 0 || totalEpisodes > 0
    }

    /**
     * 生成改名映射（分享文件 fid → 转存时要用的新文件名）+ 规则真正匹配到的文件集合。
     *
     * 口径：
     * - 任务没选规则（`ruleKey` 为空）→ 原样转存，一个文件都不改
     * - 规则被全局禁用了 → 同样不改名（尊重设置页里的开关）
     * - 只对视频文件算，目录名一律不动（目录名是层级结构，改了会和「保留原层级」冲突）
     * - 新名与原名相同时不进映射（减少无意义的重命名请求），但仍在 [RenameOutcome.matchedFids] 里
     *   —— 用户口径是「转存了多少个，就都应该是改过名的」，名字本来就对的文件也算命中
     */
    private data class RenameOutcome(
        val plan: Map<String, String>,
        /** 规则匹配到、占位符也都能解析的文件（含「新名 == 原名」的） */
        val matchedFids: Set<String>,
    )

    private fun buildRenameOutcome(task: TaskEntity, tree: ShareNode): RenameOutcome? {
        val key = task.ruleKey.trim()
        if (key.isEmpty()) return null
        val store = ruleStore ?: return null
        val rule = store.loadRules().firstOrNull { it.key == key && it.enabled } ?: return null

        val files = tree.flattenFiles().filter { it.isVideo }.map { it.file }
        if (files.isEmpty()) return RenameOutcome(emptyMap(), emptySet())

        val previews = RenameEngine().preview(
            files,
            listOf(rule),
            task.name.trim(),
            seasonOverride = task.seasonNo.takeIf { it > 0 },
        )
        // 同一轮里「两个文件算出同一个新名」是解析出错的信号：宁可保留原名，
        // 也不要把一整季都往同一个名字上撞（服务器会逐个拒绝，日志刷一屏「改名失败」）。
        val used = mutableSetOf<String>()
        val map = mutableMapOf<String, String>()
        val matched = mutableSetOf<String>()
        previews.forEach { p ->
            if (p.matchedRule == null || p.filtered) return@forEach
            val newName = p.newName?.trim() ?: return@forEach
            matched += p.file.fid
            if (newName.isEmpty() || newName == p.originName) return@forEach
            if (!used.add(newName)) return@forEach
            map[p.file.fid] = newName
        }
        return RenameOutcome(map, matched)
    }

    /**
     * 查 TMDB：最新集号 + 推断更新日。
     *
     * 口径：
     * - 没绑 TMDB ID 或没填凭证 → 直接返回 0（卡片显示「未绑定 TMDB」）
     * - 任务指定了季（季名里能解析出季号）→ 该季最新集号；否则整部剧最新集号
     * - 任何网络/解析异常都吞掉，只当没绑定，绝不让 TMDB 挂掉整个同步
     */
    private suspend fun resolveTmdb(task: TaskEntity): TmdbLatest {
        val tvId = task.tmdbId ?: return TmdbLatest(0, emptyList()).also {
            android.util.Log.i("TmdbResolve", "任务「${task.name}」没有 tmdbId，卡片按 UNKNOWN")
        }
        val credential = tmdbStore?.loadCredential()?.takeIf { it.isNotBlank() }
            ?: return TmdbLatest(0, emptyList()).also {
                android.util.Log.i("TmdbResolve", "任务「${task.name}」tmdbId=$tvId 但读不到凭证")
            }
        val client = TmdbClient(credential, tmdbStore.loadBaseUrl())
        val info = client.fetchTv(tvId).getOrElse { e ->
            android.util.Log.w("TmdbResolve", "fetchTv($tvId) 失败：${e.message}")
            return TmdbLatest(0, emptyList())
        }
        // 三种口径，各算各的（唯一不按季算的是「多季」）：
        // - 指定季（含 0 特别版）→ 只用这一季的集数和这一季的已播集号
        // - 平铺 → 等同「第 1 季」，不再偷偷换成「最后一集所在的那一季」
        // - 多季 → 每季集数相加当总数，已播集也用累计口径，盘里同样累计
        val weekdays = client.inferWeekdays(tvId, info)
        val multi = task.seasonNo == TaskEntity.SEASON_MULTI
        // 自定义分季：TMDB 把多篇并成一季（如《一念永恒》S1 200 集），
        // 但资源按篇分目录、集号从 1 起；用「本季起点 + 本季集数」换算，不查 TMDB 季号。
        val customSeason = !multi && task.seasonNo >= 0 && task.seasonEpCount > 0
        val scopeSeason = when {
            multi -> null
            customSeason -> null
            task.seasonNo == TaskEntity.SEASON_FLAT -> 1
            else -> task.seasonNo.coerceAtLeast(0)
        }
        val total = when {
            multi -> info.totalOfProperSeasons()
            customSeason -> task.seasonEpCount
            else -> client.totalEpisodes(tvId, scopeSeason, info)
        }
        val latest = when {
            multi -> cumulativeAired(info)
            customSeason -> {
                val globalLatest = info.lastEpisode?.episode ?: client.latestAiredEpisode(tvId, 1, info)
                val start = task.seasonStartEp
                if (start > 0) {
                    (globalLatest - start + 1).coerceIn(0, task.seasonEpCount)
                } else {
                    globalLatest.coerceIn(0, task.seasonEpCount)
                }
            }
            else -> client.latestAiredEpisode(tvId, scopeSeason ?: 1, info)
        }
        val scopeIssue = if (!multi && !customSeason && total == 0 && task.seasonNo >= 0) {
            val label = if (task.seasonNo == 0) "特别版" else "第${task.seasonNo}季"
            "TMDB 上没有${label}（这部剧共 ${info.numberOfSeasons} 季），请检查任务的季度设置"
        } else null
        return TmdbLatest(
            latestEpisode = latest,
            weekdays = weekdays,
            totalEpisodes = total,
            countSeason = if (multi) info.lastEpisode?.season else scopeSeason,
            seasonTargets = if (multi) {
                info.seasons.filter { it.seasonNumber >= 1 }
                    .associate { it.seasonNumber to it.episodeCount }
            } else {
                emptyMap()
            },
            scopeIssue = scopeIssue,
        ).also {
            android.util.Log.i(
                "TmdbResolve",
                "tv=$tvId seasonNo=${task.seasonNo} scope=$scopeSeason multi=$multi " +
                    "latest=${it.latestEpisode} total=${it.totalEpisodes} weekdays=${it.weekdays}",
            )
        }
    }

    /** 多季：把各正季的已播集数加起来（季内集号各从 1 起，只能按集数累加） */
    private fun cumulativeAired(info: com.ashchan.cloudsaver.core.tmdb.TmdbTvInfo): Int {
        val seasons = info.seasons.filter { it.seasonNumber >= 1 }.sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) return info.lastEpisode?.episode ?: 0
        val lastSeason = info.lastEpisode?.season ?: seasons.last().seasonNumber
        val lastEp = info.lastEpisode?.episode ?: 0
        return seasons.sumOf { s ->
            when {
                s.seasonNumber < lastSeason -> s.episodeCount
                s.seasonNumber == lastSeason -> lastEp.coerceIn(0, s.episodeCount)
                else -> 0
            }
        }
    }

    /**
     * 任务在网盘里的文件夹名。
     * 勾了「文件夹加 TMDB ID」且绑定了 TMDB → 「剧名 tmdb-123」；否则就是剧名本身。
     */
    private fun taskNameOf(task: TaskEntity): String {
        val base = task.name.trim()
        val id = task.tmdbId
        return if (task.folderWithTmdbId && id != null) "$base tmdb-$id" else base
    }

    /** 目标侧季目录名（选了季度才建） */
    private fun seasonFolderName(no: Int): String = "Season $no"
}






