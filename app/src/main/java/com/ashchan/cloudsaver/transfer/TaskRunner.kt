package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.tmdb.TmdbClient
import com.ashchan.cloudsaver.data.EpisodeCounter
import com.ashchan.cloudsaver.data.SeasonParser
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.drive.quark.QuarkAdapter
import com.ashchan.cloudsaver.storage.CredentialStore
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
    ): Result<TreeSyncEngine.Report> {
        val startedAt = System.currentTimeMillis()
        val drive = DriveType.fromName(task.driveType)
        if (drive == null) {
            val msg = "未知的网盘类型：${task.driveType}"
            repo.finishRun(task, trigger, startedAt, null, 0, 0, 0, msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val credential = credentialStore.loadCredential(drive)
        if (credential.isNullOrBlank()) {
            val msg = "请先在设置里登录${drive.displayName}"
            repo.finishRun(task, trigger, startedAt, null, 0, 0, 0, msg)
            return Result.failure(IllegalStateException(msg))
        }

        val baseFid = task.saveFid.ifBlank { "0" }
        return try {
            runLock.withLock { executeLocked(task, trigger, startedAt, drive, credential, baseFid) }
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

    /** [run] 的实际主体，调用时已持有 [runLock] */
    private suspend fun executeLocked(
        task: TaskEntity,
        trigger: String,
        startedAt: Long,
        drive: DriveType,
        credential: String,
        baseFid: String,
    ): Result<TreeSyncEngine.Report> {
        val adapter = buildAdapter(drive, credential)
        val share = retryOnce("解析分享链接") { adapter.parseShareLink(task.shareUrl) }.getOrThrow()
        val resolved = retryOnce("获取分享访问令牌") { adapter.resolveShareAccess(share) }.getOrThrow()

        // 每个任务在自己的保存位置下建一个「剧名」目录：
        // 多剧混在同一层会让集数统计把整盘视频算作一个任务，必须隔离。
        val destFid = retryOnce("创建任务目录") {
            adapter.ensureFolder(baseFid, task.name.trim())
        }.getOrElse { baseFid }

        val rootId = task.seasonFid.ifBlank { task.startFid }.ifBlank { resolved.pdirFid }
        val tree = retryOnce("拉取分享目录树") {
            adapter.fetchShareTree(
                share = resolved,
                rootId = rootId.takeIf { it != "0" },
                maxDepth = TreeSyncEngine.DEFAULT_MAX_DEPTH,
            )
        }.getOrThrow()

        val scope = resolveScope(task, tree)
        val report = TreeSyncEngine(adapter).sync(
            share = resolved,
            tree = tree,
            destRootFid = destFid,
            // destFid 已经是「剧名」目录，这里不再用任务名二次建层，
            // 让引擎沿用分享里的真实目录名（如「第 1 季 - 1080p」）
            taskName = "",
            renamePlan = emptyMap(),
            scope = scope,
        ).getOrThrow()

        // 光鸭的 access_token 会在刷新后轮换，必须回写，否则下次登录失效
        if (adapter is GuangYaAdapter) {
            credentialStore.saveCredential(DriveType.GUANGYA, adapter.currentCredential())
        }

        val counted = countLocal(adapter, destFid)
        val tmdb = resolveTmdb(task)

        // 更新日按 TMDB 自动推断：只在「任务设成自动」且还没手动设过更新日时写入，
        // 不覆盖用户手动点选的星期，避免下次打开 App 被悄改。
        if (task.updateMode == TaskEntity.UPDATE_MODE_TMDB && task.weekdays.isBlank()) {
            tmdb.weekday?.let { day ->
                repo.update(task.copy(weekdays = Weekdays.format(setOf(day))))
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
    private data class TmdbLatest(val latestEpisode: Int, val weekday: Int?)

    /**
     * 查 TMDB：最新集号 + 推断更新日。
     *
     * 口径：
     * - 没绑 TMDB ID 或没填凭证 → 直接返回 0（卡片显示「未绑定 TMDB」）
     * - 任务指定了季（季名里能解析出季号）→ 该季最新集号；否则整部剧最新集号
     * - 任何网络/解析异常都吞掉，只当没绑定，绝不让 TMDB 挂掉整个同步
     */
    private suspend fun resolveTmdb(task: TaskEntity): TmdbLatest {
        val tvId = task.tmdbId ?: return TmdbLatest(0, null)
        val credential = tmdbStore?.loadCredential()?.takeIf { it.isNotBlank() }
            ?: return TmdbLatest(0, null)
        val info = TmdbClient(credential, tmdbStore.loadBaseUrl()).fetchTv(tvId).getOrNull()
            ?: return TmdbLatest(0, null)
        val season = SeasonParser.parse(task.seasonName)
        return TmdbLatest(
            latestEpisode = info.latestEpisodeNumber(season),
            weekday = info.weekdayHint(),
        )
    }

    private fun buildAdapter(drive: DriveType, credential: String): CloudDriveAdapter = when (drive) {        DriveType.QUARK -> QuarkAdapter(credential)
        DriveType.GUANGYA -> {
            // 光鸭凭证格式 "access||refresh"
            val parts = credential.split("||")
            GuangYaAdapter(
                accessToken = parts.getOrElse(0) { "" },
                refreshToken = parts.getOrElse(1) { "" },
            )
        }
    }
}
