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
    /** 缺集数 = tmdbLatest - localEpisodes，负数归零 */
    val missing: Int = 0,
    /**
     * 本轮新转进来的集数（跟上一份快照的集数相比）。
     * 只给「没绑 TMDB」的卡片用：那种任务没有「最新集」可比，只能讲这一轮干了多少活。
     */
    val addedEpisodes: Int = 0,
    /** TMDB 的总集数（指定了季度就取该季集数，否则全剧）；0 = 未知 */
    val tmdbTotal: Int = 0,
    /** UP_TO_DATE / BEHIND / FINISHED / UNKNOWN */
    val status: String = STATUS_UNKNOWN,
    /** 快照时间（卡片「11/21 22:02」的来源） */
    val lastSyncAt: Long = 0,
    /** 最近一次执行说明（失败原因 / 跳过原因） */
    val lastMessage: String = "",
    /**
     * 最近一次执行是否成功。
     *
     * 失败时集数快照保留上一次成功的结果（避免卡片突然变空），只靠 [lastMessage] 无法区分
     * 「这行字是提醒」还是「这行字是报错」，所以单独存一个布尔给卡片上红点用。
     */
    val lastOk: Boolean = true,
    /**
     * 最近一次「真的转进来新集」的时间（毫秒，0 = 从没更新过）。
     *
     * 只认真正的新增集数：本轮 [run_records.restoredFiles] > 0 时才写，
     * 「只是改了名」不算更新（与通知口径一致）。首页卡片排序读它，
     * 不能用 [lastSyncAt]——一键同步会把所有快照刷新成同一时刻，排序就变成了执行顺序。
     */
    val lastNewAt: Long = 0,
) {
    companion object {
        const val STATUS_UP_TO_DATE: String = "UP_TO_DATE"
        const val STATUS_BEHIND: String = "BEHIND"
        /** 全集都到齐了（总集数已满）：这部剧没得补，用户可自行删任务 */
        const val STATUS_FINISHED: String = "FINISHED"
        const val STATUS_UNKNOWN: String = "UNKNOWN"
        /** 没绑 TMDB 但跑过、且这一轮转进来了新集 → 卡片显示「已转存」 */
        const val STATUS_LOCAL_ADDED: String = "LOCAL_ADDED"
        /** 没绑 TMDB 但跑过、这一轮没有新集 → 卡片显示「已是最新」 */
        const val STATUS_LOCAL_LATEST: String = "LOCAL_LATEST"
    }
}
