package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.AppDatabase
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.SnapshotEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.data.db.TaskSourceEntity
import com.ashchan.cloudsaver.transfer.TreeSyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 卡片数据：任务 + 最新快照（无快照时给默认值，UI 不用判空） */
data class TaskCard(
    val task: TaskEntity,
    val snapshot: SnapshotEntity,
    /** 这个任务配了几个分享链接；> 1 时卡片上标「· N 源」 */
    val sourceCount: Int = 1,
    /** 已判定失效（连续两次没连上）的源数量；> 0 时卡片上标「· N 失效」 */
    val failedSources: Int = 0,
)

/** 一条待落库的源（UI 层解析好链接再交下来，仓库不重复做正则） */
data class SourceDraft(
    val shareUrl: String,
    val shareId: String,
    val passcode: String = "",
)

/**
 * 数据仓库：UI 与 Room 之间唯一的一层。
 *
 * 说明：
 * - 单账号，不存在账号维度，执行时按 task.driveType 取唯一凭证。
 * - 所有写操作都在 IO 线程（Room suspend 自带）。
 */
class TaskRepository(private val db: AppDatabase) {

    private val taskDao = db.taskDao()
    private val snapshotDao = db.snapshotDao()
    private val runDao = db.runRecordDao()
    private val sourceDao = db.taskSourceDao()

    /** 首页卡片流：任务 / 快照 / 源数三路合并，按任务 id 升序 */
    fun observeCards(): Flow<List<TaskCard>> =
        combine(
            taskDao.observeAll(),
            snapshotDao.observeAll(),
            sourceDao.observeAll(),
        ) { tasks, snaps, sources ->
            val byTask = snaps.associateBy { it.taskId }
            val countByTask = sources.groupingBy { it.taskId }.eachCount()
            val failedByTask = sources
                .filter { it.lastStatus == TaskSourceEntity.STATUS_FAIL }
                .groupingBy { it.taskId }
                .eachCount()
            tasks.map { task ->
                TaskCard(
                    task = task,
                    snapshot = byTask[task.id] ?: SnapshotEntity(taskId = task.id),
                    sourceCount = countByTask[task.id] ?: 1,
                    failedSources = failedByTask[task.id] ?: 0,
                )
            }
        }

    fun observeRecentRuns(limit: Int = 30): Flow<List<RunRecordEntity>> = runDao.observeRecent(limit)

    /** 清空运行日志 */
    suspend fun clearRuns() = runDao.clearAll()

    suspend fun tasks(): List<TaskEntity> = taskDao.getAll()

    suspend fun enabledTasks(): List<TaskEntity> = taskDao.getEnabled()

    suspend fun task(id: Long): TaskEntity? = taskDao.getById(id)

    suspend fun create(task: TaskEntity): Long = taskDao.insert(task)

    /** 一个任务的全部源（按 position 升序）；老任务恰好只有一行 */
    suspend fun sourcesOf(taskId: Long): List<TaskSourceEntity> = sourceDao.listByTask(taskId)

    /** 执行完一个源回写结论（状态只影响提示，不参与转存判断） */
    suspend fun markSourceStatus(
        taskId: Long,
        position: Int,
        status: String,
        error: String,
    ) = sourceDao.updateStatus(taskId, position, status, error.trim().take(200))

    /**
     * 重写某个任务的源列表（先删后插，顺序即行序）。
     * 同时把第一行镜像回 tasks.shareUrl / shareId / passcode：老代码任何漏改的地方仍能读到主链接。
     */
    suspend fun saveSources(taskId: Long, drafts: List<SourceDraft>, task: TaskEntity) {
        sourceDao.deleteByTask(taskId)
        if (drafts.isEmpty()) return
        sourceDao.insertAll(
            drafts.mapIndexed { index, d ->
                TaskSourceEntity(
                    taskId = taskId,
                    position = index,
                    shareUrl = d.shareUrl,
                    shareId = d.shareId,
                    passcode = d.passcode,
                )
            }
        )
        val first = drafts.first()
        taskDao.update(
            task.copy(
                shareUrl = first.shareUrl,
                shareId = first.shareId,
                passcode = first.passcode,
            )
        )
    }

    suspend fun update(task: TaskEntity) =
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) {
        snapshotDao.deleteByTask(id)
        sourceDao.deleteByTask(id)
        taskDao.delete(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = taskDao.setEnabled(id, enabled)

    suspend fun snapshotOf(taskId: Long): SnapshotEntity? = snapshotDao.getByTask(taskId)

    suspend fun saveSnapshot(snapshot: SnapshotEntity) = snapshotDao.upsert(snapshot)

    /**
     * 一次执行结束后统一落库：快照 + 执行记录 + 记录裁剪。
     * [report] 为 null 表示执行前就失败（如未登录），此时只写失败记录不动快照。
     */
    suspend fun finishRun(
        task: TaskEntity,
        trigger: String,
        startedAt: Long,
        report: TreeSyncEngine.Report?,
        localEpisodes: Int,
        localLastEpisode: Int,
        tmdbLatest: Int,
        tmdbTotal: Int = 0,
        errorMessage: String = "",
        /** false = 「运行一次」的试跑：只写运行日志，不动任务快照 */
        saveSnapshot: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val ok = report?.ok == true
        val msg = when {
            report == null -> errorMessage
            report.failures.isNotEmpty() -> report.failures.joinToString("；")
            else -> ""
        }

        if (!saveSnapshot) {
            // 试跑只记日志，卡片保持不动
        } else if (report != null && ok) {
            // 没绑 TMDB（两个集数都是 0）时，「这一轮转进来多少集」靠跟上一份快照比
            val old = snapshotDao.getByTask(task.id)
            val added = if (tmdbLatest <= 0 && tmdbTotal <= 0 && old != null) {
                (localEpisodes - old.localEpisodes).coerceAtLeast(0)
            } else {
                0
            }
            saveSnapshot(
                SnapshotCalc.build(
                    taskId = task.id,
                    localEpisodes = localEpisodes,
                    localLastEpisode = localLastEpisode,
                    tmdbLatest = tmdbLatest,
                    tmdbTotal = tmdbTotal,
                    addedEpisodes = added,
                    now = now,
                    message = msg,
                ).copy(lastOk = true)
            )
        } else {
            // 失败时保留上次成功快照的集数，只更新说明与时间，避免卡片突然变空；
            // lastOk 标记为 false，卡片据此显示红点，而不是当成普通提醒
            val old = snapshotDao.getByTask(task.id)
            snapshotDao.upsert(
                (old ?: SnapshotEntity(taskId = task.id)).copy(
                    lastSyncAt = now,
                    lastMessage = msg.ifBlank { "本次同步失败" },
                    lastOk = false,
                )
            )
        }

        runDao.insert(
            RunRecordEntity(
                taskId = task.id,
                taskName = task.name,
                trigger = trigger,
                startedAt = startedAt,
                finishedAt = now,
                restoredDirs = report?.restoredDirs ?: 0,
                restoredFiles = report?.restoredFiles ?: 0,
                renamedItems = report?.renamedItems ?: 0,
                skippedFiles = report?.skippedFiles ?: 0,
                ok = ok,
                message = msg,
            )
        )
        runDao.trimTo(200)
    }

    /** 最近一次成功快照（用于判断「是否最新」） */
    fun observeStatusOf(taskId: Long): Flow<SnapshotEntity?> =
        snapshotDao.observeAll().map { list -> list.firstOrNull { it.taskId == taskId } }
}
