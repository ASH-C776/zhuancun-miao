package com.ashchan.cloudsaver.notify

import com.ashchan.cloudsaver.core.model.EpisodeTag
import com.ashchan.cloudsaver.data.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知文案口径：剧名去尾巴、季集格式、范围与集数 */
class AlertTextTest {

    @Test
    fun `剧名去掉年份括号`() {
        assertEquals("斗破苍穹", AlertText.stripYear("斗破苍穹 (2017)"))
        assertEquals("剑来", AlertText.stripYear("剑来（2024）"))
        assertEquals("凡人修仙传", AlertText.stripYear("凡人修仙传 2020"))
        assertEquals("斗破苍穹", AlertText.stripYear("斗破苍穹 tmdb-79481".replace("tmdb-79481", "(tmdb 79481)")))
    }

    @Test
    fun `剧名里的非结尾括号保留`() {
        assertEquals("某剧 (高清)", AlertText.stripYear("某剧 (高清)"))
        assertEquals("某剧 (2024) 特别篇", AlertText.stripYear("某剧 (2024) 特别篇"))
    }

    @Test
    fun `单集文案`() {
        val alert = AlertSource.build(
            taskId = 3,
            taskName = "斗破苍穹 (2017)",
            seasonNo = 4,
            items = listOf(EpisodeTag(season = 5, episode = 207)),
            restoredFiles = 1,
        )!!
        assertEquals("斗破苍穹", alert.title)
        assertEquals("S05E207", alert.episodeLabel)
        assertEquals("斗破苍穹更新 S05E207", alert.notificationTitle)
        assertEquals("共 1 集", alert.notificationText)
    }

    @Test
    fun `连更多集是一条范围`() {
        val alert = AlertSource.build(
            taskId = 3,
            taskName = "斗破苍穹 (2017)",
            seasonNo = 4,
            items = listOf(
                EpisodeTag(5, 209),
                EpisodeTag(5, 207),
                EpisodeTag(5, 208),
            ),
            restoredFiles = 3,
        )!!
        assertEquals("S05E207-E209", alert.episodeLabel)
        assertEquals("斗破苍穹更新 S05E207-E209", alert.notificationTitle)
        assertEquals("共 3 集", alert.notificationText)
    }

    @Test
    fun `同一集多版本不重复计数`() {
        val alert = AlertSource.build(
            taskId = 3,
            taskName = "仙逆 (2023)",
            seasonNo = TaskEntity.SEASON_MULTI,
            items = listOf(EpisodeTag(1, 168), EpisodeTag(1, 168)),
            restoredFiles = 2,
        )!!
        assertEquals(1, alert.count)
        assertEquals("S01E168", alert.episodeLabel)
    }

    @Test
    fun `文件名没写季号时按任务季补齐`() {
        val alert = AlertSource.build(
            taskId = 7,
            taskName = "剑来 (2024)",
            seasonNo = 2,
            items = listOf(EpisodeTag(null, 53)),
            restoredFiles = 1,
        )!!
        assertEquals("S02E053", alert.episodeLabel)
    }

    @Test
    fun `平铺任务按第一季补齐`() {
        val alert = AlertSource.build(
            taskId = 9,
            taskName = "凡人修仙传 (2020)",
            seasonNo = TaskEntity.SEASON_FLAT,
            items = listOf(EpisodeTag(null, 201)),
            restoredFiles = 1,
        )!!
        assertEquals("S01E201", alert.episodeLabel)
    }

    @Test
    fun `多季任务且文件没季号时不猜`() {
        val alert = AlertSource.build(
            taskId = 11,
            taskName = "凡人修仙传 (2020)",
            seasonNo = TaskEntity.SEASON_MULTI,
            items = listOf(EpisodeTag(null, 201)),
            restoredFiles = 1,
        )!!
        assertEquals("E201", alert.episodeLabel)
    }

    @Test
    fun `一条集号都解析不出时只报集数`() {
        val alert = AlertSource.build(
            taskId = 13,
            taskName = "玫瑰战争 (2026)",
            seasonNo = TaskEntity.SEASON_FLAT,
            items = listOf(EpisodeTag(null, null), EpisodeTag(null, null)),
            restoredFiles = 2,
        )!!
        assertNull(alert.episodeLabel)
        assertEquals("玫瑰战争更新", alert.notificationTitle)
        assertEquals("共 2 集", alert.notificationText)
    }

    @Test
    fun `没有落盘文件就不出提醒`() {
        assertNull(
            AlertSource.build(
                taskId = 1,
                taskName = "斗破苍穹 (2017)",
                seasonNo = 4,
                items = emptyList(),
                restoredFiles = 0,
            )
        )
    }

    @Test
    fun `跨季范围两头都写全`() {
        val alert = AlertSource.build(
            taskId = 21,
            taskName = "某剧",
            seasonNo = TaskEntity.SEASON_MULTI,
            items = listOf(EpisodeTag(1, 12), EpisodeTag(2, 1)),
            restoredFiles = 2,
        )!!
        assertEquals("S01E012-S02E001", alert.episodeLabel)
    }
}
