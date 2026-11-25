package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity

/** 完整度缺口：目标集数（总数，或「从第 N 集起」到总数的集数）与盘里已有集数的差 */
data class Gap(
    val targetCount: Int,
    val haveCount: Int,
    val missingCount: Int,
    val fromEpisode: Int = 1,
)

/**
 * 快照计算（纯函数，可单测）。
 *
 * **两套口径，各管各的，不许互相顶替**：
 * 1. 追剧进度（卡片徽标 + 三段进度条）：盘里最大集号 vs TMDB 最新已播集，
 *    只看“追没追上最新一集”，中间断档不影响。见 `EpisodeSegments` 与卡片 `StatusBadge`。
 * 2. 完整度缺口（只进运行日志/详情）：TMDB 总集数 - 盘里已有集数，
 *    用 [gap] / [gapText] 算，回答“一共有多少集、实际收了多少、还差多少”。
 *
 * 两者可以同时成立：凡人修仙传 盘里最大 E201 = TMDB 最新 E201（已最新），
 * 但盘里只有 114 个文件 / 共 211 集 → 缺 97 集。
 */
object SnapshotCalc {

    /**
     * 「完整度缺口」：TMDB 总集数 - 盘里已有集数。
     *
     * 这是**独立字段**，只写进运行日志/详情，不参与卡片徽标和进度条：
     * 卡片和进度条表达的是「追剧追到第几集」，而这里表达「一共有多少集、实际收了多少」。
     * 两者可以同时成立：已追平最新 E201，但盘里只有 114 个文件 → 缺 97 集。
     *
     * [fromEpisode] 预留给「从第 N 集开始转存」：目标集数从第 N 集算到总数，
     * 已收数仍是盘里现有文件数（后续接上真实起始集过滤时再细化为只数 >= N 的集）。
     */
    fun gap(localEpisodes: Int, tmdbTotal: Int, fromEpisode: Int = 1): Gap {
        if (tmdbTotal <= 0) return Gap(targetCount = 0, haveCount = localEpisodes, missingCount = 0, fromEpisode = fromEpisode)
        val from = fromEpisode.coerceIn(1, tmdbTotal)
        val target = (tmdbTotal - from + 1).coerceAtLeast(0)
        return Gap(
            targetCount = target,
            haveCount = localEpisodes,
            missingCount = (target - localEpisodes).coerceAtLeast(0),
            fromEpisode = from,
        )
    }

    /** 只按「总数 - 已收」算缺口；查不到总集数时退回「最新已播集」当目标，避免误报 0 */
    fun missing(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int = 0): Int {
        val target = if (tmdbTotal > 0) tmdbTotal else tmdbLatest
        return gap(localEpisodes, target).missingCount
    }

    /**
     * 完整度文案（多行，一条事实一行）：
     *
     * ```
     * 盘里 198 集 · 已播 201 集 · 共 211 集
     * 已播的还有 3 集未收到
     * 还有 10 集待播
     * ```
     *
     * 口径刻意不用「缺」：总集数里有还没播出的部分，那不是缺口。
     * - 待播 = 总集数 - TMDB 最新已播集（正常等更新）
     * - 未收到 = 已播集数 - 盘里集数（真正需要补的）
     */
    fun gapText(localEpisodes: Int, tmdbLatest: Int, tmdbTotal: Int, fromEpisode: Int = 1): String {
        val g = gap(localEpisodes, tmdbTotal, fromEpisode)
        if (g.targetCount <= 0) return ""
        val prefix = if (g.fromEpisode > 1) "从第 ${g.fromEpisode} 集起 " else ""
        val head = if (tmdbLatest > 0) {
            "${prefix}盘里 ${g.haveCount} 集 · 已播 $tmdbLatest 集 · 共 ${g.targetCount} 集"
        } else {
            "${prefix}盘里 ${g.haveCount} 集 · 共 ${g.targetCount} 集"
        }
        val lines = mutableListOf(head)
        // 最新已播集未知时，「待播」也无从判断，宁可不说
        if (tmdbLatest > 0) {
            val unaired = (tmdbTotal - tmdbLatest).coerceAtLeast(0)
            val airedMissing = (tmdbLatest - localEpisodes).coerceAtLeast(0)
            if (airedMissing > 0) lines += "已播的还有 $airedMissing 集未收到"
            if (unaired > 0) lines += "还有 $unaired 集待播"
        }
        return lines.joinToString("\n")
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
