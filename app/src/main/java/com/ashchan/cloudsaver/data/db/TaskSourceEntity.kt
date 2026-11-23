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
)
