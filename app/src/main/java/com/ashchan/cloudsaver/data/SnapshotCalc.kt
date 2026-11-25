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

    /**
     * 缺集数 = TMDB 总集数 - 盘里已有集数（负数归零）。
     * 用户口径：卡片上「共 N 集」和「已收 M 集」两个数直接相减，不做其它折算。
     * 只有查不到总集数（TMDB 没给）时才退回「最新已播集 - 已有集数」，避免显示成 0 缺口。
     */
    fun missing(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int = 0): Int {
        val target = if (tmdbTotal > 0) tmdbTotal else tmdbLatest
        if (target <= 0) return 0
        return (target - localEpisodes).coerceAtLeast(0)
    }

    /**
     * 状态：有总集数就只按总集数判断（收齐 = FINISHED，否则 BEHIND），
     * 没有总集数时才用「最新已播集」判断是否最新。
     */
    fun status(
        localEpisodes: Int,
        tmdbLatest: Int,
        tmdbTotal: Int,
        addedEpisodes: Int = 0,
    ): String = when {
        tmdbTotal > 0 && localEpisodes >= tmdbTotal -> SnapshotEntity.STATUS_FINISHED
        tmdbTotal > 0 -> SnapshotEntity.STATUS_BEHIND
        tmdbLatest > 0 && localEpisodes >= tmdbLatest -> SnapshotEntity.STATUS_UP_TO_DATE
        tmdbLatest > 0 -> SnapshotEntity.STATUS_BEHIND
        // 没绑 TMDB：没有「最新集」这个尺子，只讲这一轮的结果，不再一律判「未同步」
        addedEpisodes > 0 -> SnapshotEntity.STATUS_LOCAL_ADDED
        localEpisodes > 0 -> SnapshotEntity.STATUS_LOCAL_LATEST
        else -> SnapshotEntity.STATUS_UNKNOWN
    }

    /** 一次性生成整条快照（执行完成后调用） */
    fun build(
        taskId: Long,
        localEpisodes: Int,
        localLastEpisode: Int,
        tmdbLatest: Int,
        tmdbTotal: Int = 0,
        addedEpisodes: Int = 0,
        now: Long = System.currentTimeMillis(),
        message: String = "",
    ): SnapshotEntity = SnapshotEntity(
        taskId = taskId,
        localEpisodes = localEpisodes,
        localLastEpisode = localLastEpisode,
        tmdbLatest = tmdbLatest,
        missing = missing(localEpisodes, tmdbLatest, tmdbTotal),
        tmdbTotal = tmdbTotal,
        addedEpisodes = addedEpisodes,
        status = status(localEpisodes, tmdbLatest, tmdbTotal, addedEpisodes),
        lastSyncAt = now,
        lastMessage = message,
    )
}
