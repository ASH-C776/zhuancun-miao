package com.ashchan.cloudsaver.transfer

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.data.EpisodeCounter
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.drive.quark.QuarkAdapter
import com.ashchan.cloudsaver.storage.CredentialStore

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
) {

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

        val destFid = task.saveFid.ifBlank { "0" }
        return try {
            val adapter = buildAdapter(drive, credential)
            val share = adapter.parseShareLink(task.shareUrl).getOrThrow()
            val resolved = adapter.resolveShareAccess(share).getOrThrow()

            val rootId = task.seasonFid.ifBlank { task.startFid }.ifBlank { resolved.pdirFid }
            val tree = adapter.fetchShareTree(
                share = resolved,
                rootId = rootId.takeIf { it != "0" },
                maxDepth = TreeSyncEngine.DEFAULT_MAX_DEPTH,
            ).getOrThrow()

            val scope = resolveScope(task, tree)
            val report = TreeSyncEngine(adapter).sync(
                share = resolved,
                tree = tree,
                destRootFid = destFid,
                taskName = task.name,
                renamePlan = emptyMap(),
                scope = scope,
            ).getOrThrow()

            // 光鸭的 access_token 会在刷新后轮换，必须回写，否则下次登录失效
            if (adapter is GuangYaAdapter) {
                credentialStore.saveCredential(DriveType.GUANGYA, adapter.currentCredential())
            }

            val counted = countLocal(adapter, destFid)
            repo.finishRun(
                task = task,
                trigger = trigger,
                startedAt = startedAt,
                report = report,
                localEpisodes = counted.episodes,
                localLastEpisode = counted.lastEpisode,
                // TMDB 最新集在 Stage 5 接入，先留 0（卡片会显示「未绑定 TMDB」）
                tmdbLatest = 0,
            )
            Result.success(report)
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
     * 统计网盘里已有的集数：目标目录 + 其直接子目录。
     * 平铺新集在根、旧集在下一级目录，只看一层会严重少算。
     */
    private suspend fun countLocal(adapter: CloudDriveAdapter, destFid: String): EpisodeCounter.Result {
        val direct = adapter.listFiles(destFid).getOrElse { return EpisodeCounter.Result(0, 0) }
        val names = mutableListOf<String>()
        names += direct.filter { !it.isDir }.map { it.name }
        direct.filter { it.isDir }.forEach { dir ->
            adapter.listFiles(dir.fid).getOrNull()
                ?.filter { !it.isDir }
                ?.map { it.name }
                ?.let { names += it }
        }
        return EpisodeCounter.count(names)
    }

    private fun buildAdapter(drive: DriveType, credential: String): CloudDriveAdapter = when (drive) {
        DriveType.QUARK -> QuarkAdapter(credential)
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
