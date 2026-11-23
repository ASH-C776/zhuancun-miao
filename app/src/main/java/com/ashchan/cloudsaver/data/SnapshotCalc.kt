package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity

/**
 * 快照计算（纯函数，可单测）：把「盘里已有 / TMDB 最新 / TMDB 总集数」折成卡片要的结论。
 *
 * 两个目标、一个优先级（用户口径）：
 * 1. 盘里集数 >= TMDB **总集数** → FINISHED（转存完）：整部剧收齐了，没得补
 * 2. 盘里集数 >= TMDB **当前最新已播集** → UP_TO_DATE（最新了）：眼下没缺集，等下次更新
 * 3. 否则 → BEHIND（缺 N 集），N = 最新已播集 - 盘里集数
 *
 * 没绑定/查不到 TMDB（latest 与 total 都为 0）→ UNKNOWN，不显示「缺几集」，避免误报。
 * 集数一律用「去重后的集号数量」，不用最大集号：中间缺一集、最大号已到 30 也不算收齐。
 */
object SnapshotCalc {

    /** 缺集数 = 目标集数 - 盘里集数（按数量比，负数归零）；目标优先用最新已播集，没有则退回总集数 */
    fun missing(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int = 0): Int {
        val target = if (tmdbLatest > 0) tmdbLatest else tmdbTotal
        if (target <= 0) return 0
        return (target - localEpisodes).coerceAtLeast(0)
    }

    fun status(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int): String = when {
        tmdbTotal > 0 && localEpisodes >= tmdbTotal -> SnapshotEntity.STATUS_FINISHED
        tmdbLatest > 0 && localEpisodes >= tmdbLatest -> SnapshotEntity.STATUS_UP_TO_DATE
        tmdbLatest > 0 || tmdbTotal > 0 -> SnapshotEntity.STATUS_BEHIND
        else -> SnapshotEntity.STATUS_UNKNOWN
    }

    /** 一次性生成整条快照（执行完成后调用） */
    fun build(
        taskId: Long,
        localEpisodes: Int,
        localLastEpisode: Int,
        tmdbLatest: Int,
        tmdbTotal: Int = 0,
        now: Long = System.currentTimeMillis(),
        message: String = "",
    ): SnapshotEntity = SnapshotEntity(
        taskId = taskId,
        localEpisodes = localEpisodes,
        localLastEpisode = localLastEpisode,
        tmdbLatest = tmdbLatest,
        missing = missing(localEpisodes, tmdbLatest, tmdbTotal),
        tmdbTotal = tmdbTotal,
        status = status(localEpisodes, tmdbLatest, tmdbTotal),
        lastSyncAt = now,
        lastMessage = message,
    )
}
