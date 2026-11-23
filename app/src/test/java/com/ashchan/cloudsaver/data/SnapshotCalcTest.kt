package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.SnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快照计算测试：卡片标识的唯一口径。
 *
 * 三态优先级（用户口径）：
 * 1. 盘里集数 >= 总集数 → FINISHED「转存完」
 * 2. 盘里集数 >= 最新已播集 → UP_TO_DATE「最新了」
 * 3. 否则 → BEHIND「缺 N 集」
 */
class SnapshotCalcTest {

    @Test
    fun `盘里落后最新集时 缺集数等于差值`() {
        // 总 30 集、播到 20、盘里 19 → 缺 1 集
        assertEquals(1, SnapshotCalc.missing(localEpisodes = 19, tmdbLatest = 20))
        assertEquals(
            SnapshotEntity.STATUS_BEHIND,
            SnapshotCalc.status(localEpisodes = 19, tmdbLatest = 20, tmdbTotal = 30)
        )
    }

    @Test
    fun `追平最新集但没到总集数 显示最新了`() {
        assertEquals(0, SnapshotCalc.missing(20, 20))
        assertEquals(
            SnapshotEntity.STATUS_UP_TO_DATE,
            SnapshotCalc.status(localEpisodes = 20, tmdbLatest = 20, tmdbTotal = 30)
        )
    }

    @Test
    fun `集数达到总集数 显示转存完`() {
        assertEquals(
            SnapshotEntity.STATUS_FINISHED,
            SnapshotCalc.status(localEpisodes = 30, tmdbLatest = 30, tmdbTotal = 30)
        )
        // 已完结剧：最新集 = 总集数，同样归到「转存完」
        assertEquals(
            SnapshotEntity.STATUS_FINISHED,
            SnapshotCalc.status(localEpisodes = 12, tmdbLatest = 12, tmdbTotal = 12)
        )
        // 盘里比总数还多（重复文件/特辑）也算收齐
        assertEquals(
            SnapshotEntity.STATUS_FINISHED,
            SnapshotCalc.status(localEpisodes = 31, tmdbLatest = 30, tmdbTotal = 30)
        )
    }

    @Test
    fun `盘里超前最新集 仍算最新了`() {
        assertEquals(0, SnapshotCalc.missing(12, 10))
        assertEquals(
            SnapshotEntity.STATUS_UP_TO_DATE,
            SnapshotCalc.status(localEpisodes = 12, tmdbLatest = 10, tmdbTotal = 30)
        )
    }

    @Test
    fun `未绑定 TMDB 时 跑过且有集数算已是最新`() {
        assertEquals(0, SnapshotCalc.missing(5, 0))
        // 以前这里一律判 UNKNOWN → 卡片永远显示「未同步」，即使盘里已经是最新的
        assertEquals(
            SnapshotEntity.STATUS_LOCAL_LATEST,
            SnapshotCalc.status(localEpisodes = 5, tmdbLatest = 0, tmdbTotal = 0)
        )
    }

    @Test
    fun `未绑定 TMDB 但本轮转进新集 算已转存`() {
        assertEquals(
            SnapshotEntity.STATUS_LOCAL_ADDED,
            SnapshotCalc.status(localEpisodes = 8, tmdbLatest = 0, tmdbTotal = 0, addedEpisodes = 3)
        )
    }

    @Test
    fun `未绑定 TMDB 且盘里一集都没有 仍是未同步`() {
        assertEquals(
            SnapshotEntity.STATUS_UNKNOWN,
            SnapshotCalc.status(localEpisodes = 0, tmdbLatest = 0, tmdbTotal = 0, addedEpisodes = 0)
        )
    }

    @Test
    fun `只有总集数没有最新集时 按总集数判断`() {
        assertEquals(
            SnapshotEntity.STATUS_FINISHED,
            SnapshotCalc.status(localEpisodes = 8, tmdbLatest = 0, tmdbTotal = 8)
        )
        assertEquals(
            SnapshotEntity.STATUS_BEHIND,
            SnapshotCalc.status(localEpisodes = 7, tmdbLatest = 0, tmdbTotal = 8)
        )
    }

    @Test
    fun `构建整条快照`() {
        val snap = SnapshotCalc.build(
            taskId = 7,
            localEpisodes = 168,
            localLastEpisode = 168,
            tmdbLatest = 170,
            tmdbTotal = 200,
            now = 1000,
        )
        assertEquals(7, snap.taskId)
        assertEquals(168, snap.localEpisodes)
        assertEquals(2, snap.missing)
        assertEquals(200, snap.tmdbTotal)
        assertEquals(SnapshotEntity.STATUS_BEHIND, snap.status)
        assertEquals(1000, snap.lastSyncAt)
    }
}
