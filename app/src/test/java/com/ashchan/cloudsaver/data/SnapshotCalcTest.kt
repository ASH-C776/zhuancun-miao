package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.x 快照计算测试：卡片「是否最新 / 缺几集」的唯一口径。 */
class SnapshotCalcTest {

    @Test
    fun `盘里落后时 缺集数等于差值`() {
        assertEquals(3, SnapshotCalc.missing(localLastEpisode = 10, tmdbLatest = 13))
        assertEquals(
            SnapshotEntity.STATUS_BEHIND,
            SnapshotCalc.status(localLastEpisode = 10, tmdbLatest = 13)
        )
    }

    @Test
    fun `盘里追平或超前时 缺 0 集且为最新`() {
        assertEquals(0, SnapshotCalc.missing(10, 10))
        assertEquals(
            SnapshotEntity.STATUS_UP_TO_DATE,
            SnapshotCalc.status(10, 10)
        )
        // 网盘里有超前集（抢先版）也不能算负数
        assertEquals(0, SnapshotCalc.missing(12, 10))
        assertEquals(
            SnapshotEntity.STATUS_UP_TO_DATE,
            SnapshotCalc.status(12, 10)
        )
    }

    @Test
    fun `未绑定 TMDB 时状态未知 不报缺集`() {
        assertEquals(0, SnapshotCalc.missing(5, 0))
        assertEquals(
            SnapshotEntity.STATUS_UNKNOWN,
            SnapshotCalc.status(5, 0)
        )
    }

    @Test
    fun `构建整条快照`() {
        val snap = SnapshotCalc.build(
            taskId = 7, localEpisodes = 168, localLastEpisode = 168, tmdbLatest = 170, now = 1000
        )
        assertEquals(7, snap.taskId)
        assertEquals(168, snap.localEpisodes)
        assertEquals(2, snap.missing)
        assertEquals(SnapshotEntity.STATUS_BEHIND, snap.status)
        assertEquals(1000, snap.lastSyncAt)
    }
}
