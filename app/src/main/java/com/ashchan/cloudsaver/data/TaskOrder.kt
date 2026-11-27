package com.ashchan.cloudsaver.data

/**
 * 首页卡片排序（纯函数，便于单测）。
 *
 * 规则，优先级从高到低：
 * 1. [com.ashchan.cloudsaver.data.db.TaskEntity.pinned]：用户手动置顶永远在最前，不受更新时间影响；
 * 2. [com.ashchan.cloudsaver.data.db.SnapshotEntity.lastNewAt]：最近一次「真的转进新集」的时间，
 *    越新越靠前；0 = 从没更新过，自然落到最后；
 * 3. `id` 升序：并发更新时间相同（同一轮一起更新）时按创建顺序，保证顺序稳定不抖。
 *
 * 为什么不用 lastSyncAt：一键同步 / 定时刷新会把所有任务的快照刷新成同一时刻，
 * 排序会变成「这轮最后跑的那部在第一个」，每轮都洗牌。
 */
object TaskOrder {

    val CARDS: Comparator<TaskCard> =
        compareByDescending<TaskCard> { it.task.pinned }
            .thenByDescending { it.snapshot.lastNewAt }
            .thenBy { it.task.id }

    fun sort(cards: List<TaskCard>): List<TaskCard> = cards.sortedWith(CARDS)
}
