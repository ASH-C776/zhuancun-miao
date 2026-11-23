package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.ShareNode
import com.ashchan.cloudsaver.core.tmdb.TmdbClient
import com.ashchan.cloudsaver.data.EpisodeCounter
import com.ashchan.cloudsaver.data.SeasonParser
import com.ashchan.cloudsaver.data.SourceLinks
import com.ashchan.cloudsaver.data.TaskRepository
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

    private companion object {
        /** 集数统计递归层数上限（剧名 / 季 / 文件 三层足够，留一层余量） */
        const val MAX_COUNT_DEPTH = 3

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

    /** 一个要跑的源：行号（用于回写状态）+ 链接 + 上一轮结论 */    internal data class RunTarget(
        val position: Int,
        val url: String,
        val previousStatus: String = TaskSourceEntity.STATUS_UNKNOWN,
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

        // 选了季度就再多一层 `Season N`；不选则视频直接平铺在剧名目录下
        val destFid = if (task.seasonNo > 0) {
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
        var anyOk = false

        links.forEachIndexed { index, target ->
            val label = if (links.size > 1) "源${index + 1}" else "源"
            var errorNote = ""
            val outcome: Result<TreeSyncEngine.Report> = try {
                val share = retryOnce("$label 解析分享链接") {
                    adapter.parseShareLink(target.url)
                }.getOrThrow()
                val resolved = retryOnce("$label 获取分享访问令牌") {
                    adapter.resolveShareAccess(share)
                }.getOrThrow()

                val rootId = task.seasonFid.ifBlank { task.startFid }.ifBlank { resolved.pdirFid }
                onPhase(withSource(label, links.size, "读取目录"))
                val tree = retryOnce("$label 拉取分享目录树") {
                    adapter.fetchShareTree(
                        share = resolved,
                        rootId = rootId.takeIf { it != "0" },
                        maxDepth = TreeSyncEngine.DEFAULT_MAX_DEPTH,
                    )
                }.getOrThrow()

                val plan = buildRenamePlan(task, tree)
                TreeSyncEngine(adapter).syncFlat(
                    share = resolved,
                    tree = tree,
                    destRootFid = destFid,
                    renamePlan = plan,
                    seasonNo = task.seasonNo,
                    // 引擎按「比对文件 / 转存 3/20 / 改名 5/12」逐条上报，直接翻到卡片上
                    onProgress = { _, _, message ->
                        onPhase(withSource(label, links.size, message))
                    },
                )
            } catch (e: Throwable) {
                // 掉线类的异常交给外层重登整轮重跑；其余按「本源失败」吞掉，继续下一个源
                if (DriveSession.isAuthError(e)) throw e
                errorNote = e.message ?: "未知错误"
                Result.failure(e)
            }

            // 回写这个源这一轮的结论：一次失败算 WARN，连续两次才升级 FAIL（不误报失效）
            if (task.id > 0) {
                val newStatus = TaskSourceEntity.statusAfterRun(
                    previous = target.previousStatus,
                    ok = outcome.getOrNull()?.ok == true,
                )
                runCatching {
                    repo.markSourceStatus(
                        taskId = task.id,
                        position = target.position,
                        status = newStatus,
                        error = if (newStatus == TaskSourceEntity.STATUS_OK) "" else errorNote,
                    )
                }
            }

            outcome
                .onSuccess { r ->
                    anyOk = anyOk || r.ok
                    restoredDirs += r.restoredDirs
                    restoredFiles += r.restoredFiles
                    renamedItems += r.renamedItems
                    skippedFiles += r.skippedFiles
                    // 同一批文件可能两个源都有：第二个源靠目标目录 diff 判定已存在 → 计入跳过
                    r.failures.forEach { failures += if (links.size > 1) "$label $it" else it }
                }
                .onFailure { e ->
                    failures += if (links.size > 1) {
                        "$label 失败：${e.message ?: "未知错误"}"
                    } else {
                        e.message ?: "同步失败"
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
        )

        // 光鸭的 access_token 会在刷新后轮换，必须回写，否则下次登录失效
        if (adapter is GuangYaAdapter) {
            credentialStore.saveCredential(DriveType.GUANGYA, adapter.currentCredential())
        }

        val counted = countLocal(adapter, destFid)
        onPhase("统计中")
        val tmdb = resolveTmdb(task)

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
            report = report,
            localEpisodes = counted.episodes,
            localLastEpisode = counted.lastEpisode,
            tmdbLatest = tmdb.latestEpisode,
            tmdbTotal = tmdb.totalEpisodes,
            saveSnapshot = saveSnapshot,
        )
        return Result.success(report)
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
     * 范围决策：任务显式选了「只跟当前目录」就尊重；
     * 默认 SUBTREE 时按真实结构推断 —— 根目录直接有视频（平铺新集）→ 只跟当前目录。
     */
    private fun resolveScope(task: TaskEntity, tree: com.ashchan.cloudsaver.core.model.ShareNode): TreeSyncEngine.Scope =
        if (task.scopeMode == TaskEntity.SCOPE_DIRECT_ONLY) {
            TreeSyncEngine.Scope.DIRECT_ONLY
        } else {
            ScopeAdvisor.recommend(tree)
        }

    /**
     * 统计任务目标目录下已有的集数。
     *
     * 递归到 [MAX_COUNT_DEPTH] 层：真实资源常见「剧名 / 第 N 季 - 分辨率 / 集.mp4」三层，
     * 只扫两层会把盘里集数算成 0。跳过的深度不再下钻，避免深层大目录把请求打爆。
     */
    private suspend fun countLocal(adapter: CloudDriveAdapter, destFid: String): EpisodeCounter.Result {
        val names = mutableListOf<String>()

        suspend fun walk(fid: String, depth: Int) {
            val items = adapter.listFiles(fid).getOrNull() ?: return
            names += items.filter { !it.isDir }.map { it.name }
            if (depth >= MAX_COUNT_DEPTH) return
            items.filter { it.isDir }.forEach { walk(it.fid, depth + 1) }
        }

        walk(destFid, 0)
        return EpisodeCounter.count(names)
    }

    /** TMDB 解析结果（失败一律回 0/null，不影响转存主流程） */
    private data class TmdbLatest(val latestEpisode: Int, val weekdays: List<Int>, val totalEpisodes: Int = 0)

    /**
     * 生成改名映射（分享文件 fid → 转存时要用的新文件名）。
     *
     * 口径：
     * - 任务没选规则（`ruleKey` 为空）→ 原样转存，一个文件都不改
     * - 规则被全局禁用了 → 同样不改名（尊重设置页里的开关）
     * - 只对视频文件算，目录名一律不动（目录名是层级结构，改了会和「保留原层级」冲突）
     * - 新名与原名相同时不进映射，减少无意义的重命名请求
     */
    private fun buildRenamePlan(task: TaskEntity, tree: ShareNode): Map<String, String> {
        val key = task.ruleKey.trim()
        if (key.isEmpty()) return emptyMap()
        val store = ruleStore ?: return emptyMap()
        val rule = store.loadRules().firstOrNull { it.key == key && it.enabled } ?: return emptyMap()

        val files = tree.flattenFiles().filter { it.isVideo }.map { it.file }
        if (files.isEmpty()) return emptyMap()

        val previews = RenameEngine().preview(files, listOf(rule), task.name.trim())
        // 同一轮里「两个文件算出同一个新名」是解析出错的信号：宁可保留原名，
        // 也不要把一整季都往同一个名字上撞（服务器会逐个拒绝，日志刷一屏「改名失败」）。
        val used = mutableSetOf<String>()
        return previews
            .mapNotNull { p ->
                val newName = p.newName?.trim()
                if (newName.isNullOrEmpty() || newName == p.originName) return@mapNotNull null
                if (p.filtered) return@mapNotNull null
                if (!used.add(newName)) return@mapNotNull null
                p.file.fid to newName
            }
            .toMap()
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
        val season = SeasonParser.parse(task.seasonName)
        // 更新日走原项目同一套「多天加权投票」（会多拉一次该季集表）
        val weekdays = client.inferWeekdays(tvId, info)
        // 目标总集数：指定季度取该季集数；没指定季就用「最新集所在那一季」的集数
        // （TMDB 的 episode_number 是季内编号，拿全剧总数去比会永远对不上）
        val countSeason = season ?: info.lastEpisode?.season?.takeIf { it > 0 }
        val total = client.totalEpisodes(tvId, countSeason, info)
        return TmdbLatest(
            latestEpisode = info.latestEpisodeNumber(season),
            weekdays = weekdays,
            totalEpisodes = total,
        ).also {
            android.util.Log.i(
                "TmdbResolve",
                "tv=$tvId season=$season latest=${it.latestEpisode} total=${it.totalEpisodes} weekdays=${it.weekdays}",
            )
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


