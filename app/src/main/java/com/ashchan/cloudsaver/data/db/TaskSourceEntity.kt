package com.ashchan.cloudsaver.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个任务的一条分享链接（多源）。
 *
 * 口径（用户明确）：
 * - 一个任务只属于一个网盘，所以这里**不存** driveType：网盘由 [TaskEntity.driveType] 统一决定，
 *   从根上杜绝「一个光鸭任务里掺了夸克链接」。
 * - `position = 0` 是默认链接（表单第一行，右端是「+」）；1..4 是附加源（右端是「−」，可删）。
 * - 规则只有一套：保存位置 / 季度 / 命名规则 / tmdb id / 更新日 / 播出时刻全任务共用，
 *   源只是多几个链接入口。
 */
@Entity(
    tableName = "task_sources",
    indices = [Index("taskId")],
)
data class TaskSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 0 = 默认链接（第一行）；1..4 = 附加源 */
    val position: Int = 0,
    /** 分享链接原文（用户粘贴的那份，含提取码文案） */
    val shareUrl: String,
    val shareId: String = "",
    val passcode: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 最近一次执行结果：OK / WARN（一次没连上）/ FAIL（连续两次）/ UNKNOWN（还没检测过） */
    val lastStatus: String = STATUS_UNKNOWN,
    /** 失败原因一句话，只给 UI 提示用，不影响转存判断 */
    val lastError: String = "",
    /** 最近一次检测时间，0 = 从没检测过 */
    val lastCheckedAt: Long = 0,
) {
    companion object {
        const val STATUS_OK: String = "OK"
        const val STATUS_WARN: String = "WARN"
        const val STATUS_FAIL: String = "FAIL"
        const val STATUS_UNKNOWN: String = "UNKNOWN"

        /** 这一轮之后的新状态：连续两次失败才升级成 FAIL，避免网络抖动把源误标成失效 */
        fun statusAfterRun(previous: String, ok: Boolean): String = when {
            ok -> STATUS_OK
            previous == STATUS_WARN || previous == STATUS_FAIL -> STATUS_FAIL
            else -> STATUS_WARN
        }
    }
}
