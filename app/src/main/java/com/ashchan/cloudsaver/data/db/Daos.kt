package com.ashchan.cloudsaver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tasks SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())
}

@Dao
interface TaskSourceDao {

    @Query("SELECT * FROM task_sources WHERE taskId = :taskId ORDER BY position ASC")
    suspend fun listByTask(taskId: Long): List<TaskSourceEntity>

    /** 全表：卡片要显示「· 2 源」，跟随任务列表一起观察 */
    @Query("SELECT * FROM task_sources ORDER BY taskId ASC, position ASC")
    fun observeAll(): Flow<List<TaskSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TaskSourceEntity>)

    @Query("DELETE FROM task_sources WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    /** 执行完一个源顺手回写结论；只动状态列，不动链接本身 */
    @Query(
        "UPDATE task_sources SET lastStatus = :status, lastError = :error, lastCheckedAt = :now " +
            "WHERE taskId = :taskId AND position = :position"
    )
    suspend fun updateStatus(
        taskId: Long,
        position: Int,
        status: String,
        error: String,
        now: Long = System.currentTimeMillis(),
    )
}

@Dao
interface SnapshotDao {

    @Query("SELECT * FROM snapshots")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE taskId = :taskId")
    suspend fun getByTask(taskId: Long): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: SnapshotEntity)

    @Query("DELETE FROM snapshots WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}

@Dao
interface RunRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RunRecordEntity): Long

    @Query("SELECT * FROM run_records ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<RunRecordEntity>>

    @Query("SELECT * FROM run_records WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentByTask(taskId: Long, limit: Int = 10): List<RunRecordEntity>

    /** 只保留最近 [keep] 条，避免表无限增长 */
    @Query(
        "DELETE FROM run_records WHERE id NOT IN " +
            "(SELECT id FROM run_records ORDER BY startedAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int = 200)

    /** 清空全部运行日志（用户在日志页手动清除） */
    @Query("DELETE FROM run_records")
    suspend fun clearAll()
}
