package com.ashchan.cloudsaver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每次「检测转存」的执行记录（含手动单条 / 一键全部）。
 * 与 [SnapshotEntity] 分工：快照只留最新状态，这里留历史，便于排查「为什么昨天没补上」。
 */
@Entity(tableName = "run_records")
data class RunRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 任务名快照：任务删掉/改名后，历史记录仍能看出是哪部剧 */
    val taskName: String = "",
    /** 手动单条 / 一键全部 / 后台定时 */
    val trigger: String = TRIGGER_MANUAL,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val restoredDirs: Int = 0,
    val restoredFiles: Int = 0,
    val renamedItems: Int = 0,
    val skippedFiles: Int = 0,
    val ok: Boolean = true,
    val message: String = "",
) {
    companion object {
        const val TRIGGER_MANUAL: String = "manual"
        const val TRIGGER_ALL: String = "all"
        const val TRIGGER_BACKGROUND: String = "background"
    }
}
