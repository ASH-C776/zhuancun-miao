package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.AppDatabase
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.SnapshotEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.transfer.TreeSyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 卡片数据：任务 + 最新快照（无快照时给默认值，UI 不用判空） */
data class TaskCard(
    val task: TaskEntity,
    val snapshot: SnapshotEntity,
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

    /** 首页卡片流：任务与快照合并，按任务 id 升序 */
    fun observeCards(): Flow<List<TaskCard>> =
        combine(taskDao.observeAll(), snapshotDao.observeAll()) { tasks, snaps ->
            val byTask = snaps.associateBy { it.taskId }
            tasks.map { task ->
                TaskCard(task, byTask[task.id] ?: SnapshotEntity(taskId = task.id))
            }
        }

    fun observeRecentRuns(limit: Int = 30): Flow<List<RunRecordEntity>> = runDao.observeRecent(limit)

    suspend fun tasks(): List<TaskEntity> = taskDao.getAll()

    suspend fun enabledTasks(): List<TaskEntity> = taskDao.getEnabled()

    suspend fun task(id: Long): TaskEntity? = taskDao.getById(id)

    suspend fun create(task: TaskEntity): Long = taskDao.insert(task)

    suspend fun update(task: TaskEntity) =
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) {
        snapshotDao.deleteByTask(id)
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
        errorMessage: String = "",
    ) {
        val now = System.currentTimeMillis()
        val ok = report?.ok == true
        val msg = when {
            report == null -> errorMessage
            report.failures.isNotEmpty() -> report.failures.joinToString("；")
            else -> ""
        }

        if (report != null && ok) {
            saveSnapshot(
                SnapshotCalc.build(
                    taskId = task.id,
                    localEpisodes = localEpisodes,
                    localLastEpisode = localLastEpisode,
                    tmdbLatest = tmdbLatest,
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
