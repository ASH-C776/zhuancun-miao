package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页排序守卫：口径是「置顶 > 最近真有新集 > id」，且时间相同不能抖。
 * 这几个用例都是线上真出事过的形态（一键同步把时间刷成同一秒 / 改名被当成更新）。
 */
class TaskOrderTest {

    private fun card(id: Long, lastNewAt: Long = 0L, pinned: Boolean = false): TaskCard =
        TaskCard(
            task = TaskEntity(
                id = id,
                name = "剧$id",
                pinned = pinned,
                driveType = "QUARK",
                shareUrl = "https://pan.quark.cn/s/test$id",
                shareId = "test$id",
            ),
            snapshot = SnapshotEntity(taskId = id, lastNewAt = lastNewAt),
        )

    @Test
    fun `最近有新集的排在最前`() {
        val cards = listOf(
            card(id = 1, lastNewAt = 100L),
            card(id = 2, lastNewAt = 900L),
            card(id = 3, lastNewAt = 500L),
        )
        assertEquals(listOf(2L, 3L, 1L), TaskOrder.sort(cards).map { it.task.id })
    }

    @Test
    fun `置顶优先：即使置顶那部很久没更新`() {
        val cards = listOf(
            card(id = 1, lastNewAt = 999L),
            card(id = 2, lastNewAt = 0L, pinned = true),
        )
        assertEquals(listOf(2L, 1L), TaskOrder.sort(cards).map { it.task.id })
    }

    @Test
    fun `从没更新过的排最后并按 id 稳定`() {
        val cards = listOf(
            card(id = 3),
            card(id = 1, lastNewAt = 10L),
            card(id = 2),
        )
        assertEquals(listOf(1L, 2L, 3L), TaskOrder.sort(cards).map { it.task.id })
    }

    @Test
    fun `同一轮一起更新的时间相同：按 id 决定，不会来回抖`() {
        val batchAt = 1_700_000_000_000L
        val a = listOf(card(1, batchAt), card(2, batchAt), card(3, batchAt))
        val b = a.reversed()
        assertEquals(listOf(1L, 2L, 3L), TaskOrder.sort(a).map { it.task.id })
        assertEquals(listOf(1L, 2L, 3L), TaskOrder.sort(b).map { it.task.id })
    }
}
