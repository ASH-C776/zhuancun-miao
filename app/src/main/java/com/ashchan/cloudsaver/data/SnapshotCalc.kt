package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity

/**
 * 快照计算（纯函数，可单测）：把「盘里已有 / TMDB 最新」折成卡片要的三项信息。
 *
 * 规则：
 * - 没绑定 TMDB（tmdbLatest <= 0）→ UNKNOWN，不显示"缺几集"，避免误报
 * - 盘里集号 >= TMDB 最新 → UP_TO_DATE，缺 0
 * - 否则 BEHIND，缺集数 = 差值
 */
object SnapshotCalc {

    fun missing(localLastEpisode: Int, tmdbLatest: Int): Int {
        if (tmdbLatest <= 0) return 0
        return (tmdbLatest - localLastEpisode).coerceAtLeast(0)
    }

    fun status(localLastEpisode: Int, tmdbLatest: Int): String = when {
        tmdbLatest <= 0 -> SnapshotEntity.STATUS_UNKNOWN
        localLastEpisode >= tmdbLatest -> SnapshotEntity.STATUS_UP_TO_DATE
        else -> SnapshotEntity.STATUS_BEHIND
    }

    /** 一次性生成整条快照（执行完成后调用） */
    fun build(
        taskId: Long,
        localEpisodes: Int,
        localLastEpisode: Int,
        tmdbLatest: Int,
        now: Long = System.currentTimeMillis(),
        message: String = "",
    ): SnapshotEntity = SnapshotEntity(
        taskId = taskId,
        localEpisodes = localEpisodes,
        localLastEpisode = localLastEpisode,
        tmdbLatest = tmdbLatest,
        missing = missing(localLastEpisode, tmdbLatest),
        status = status(localLastEpisode, tmdbLatest),
        lastSyncAt = now,
        lastMessage = message,
    )
}
