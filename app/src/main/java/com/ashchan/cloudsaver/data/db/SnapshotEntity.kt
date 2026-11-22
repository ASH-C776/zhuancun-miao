package com.ashchan.cloudsaver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次同步后的快照：卡片上「盘里多少集 / TMDB 最新多少集 / 缺几集」的唯一数据源。
 * 一个任务一条，执行完成后覆盖写。
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val taskId: Long,
    /** 网盘里已有的集数（按集号去重计数） */
    val localEpisodes: Int = 0,
    /** 网盘里最大的集号（用于「S01E201」这种显示） */
    val localLastEpisode: Int = 0,
    /** TMDB 最新已播集号；未绑定 TMDB 时为 0 */
    val tmdbLatest: Int = 0,
    /** 缺集数 = tmdbLatest - localLastEpisode，负数归零 */
    val missing: Int = 0,
    /** UP_TO_DATE / BEHIND / UNKNOWN */
    val status: String = STATUS_UNKNOWN,
    /** 快照时间（卡片「11/21 22:02」的来源） */
    val lastSyncAt: Long = 0,
    /** 最近一次执行说明（失败原因 / 跳过原因） */
    val lastMessage: String = "",
) {
    companion object {
        const val STATUS_UP_TO_DATE: String = "UP_TO_DATE"
        const val STATUS_BEHIND: String = "BEHIND"
        const val STATUS_UNKNOWN: String = "UNKNOWN"
    }
}
