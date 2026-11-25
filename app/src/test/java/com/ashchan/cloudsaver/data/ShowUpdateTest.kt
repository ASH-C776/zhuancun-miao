package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.data.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知文案口径：剧名去尾巴、季集格式、范围与集数 */
class ShowUpdateTest {

    @Test
    fun `剧名去掉年份括号`() {
        assertEquals("斗破苍穹", ShowUpdate.stripYear("斗破苍穹 (2017)"))
        assertEquals("剑来", ShowUpdate.stripYear("剑来（2024）"))
        assertEquals("凡人修仙传", ShowUpdate.stripYear("凡人修仙传 2020"))
    }

    @Test
    fun `剧名里的非年份括号保留`() {
        assertEquals("某剧 (高清)", ShowUpdate.stripYear("某剧 (高清)"))
        assertEquals("某剧 (2024) 特别篇", ShowUpdate.stripYear("某剧 (2024) 特别篇"))
    }

    @Test
    fun `单集通知文案`() {
        val update = ShowUpdate.from(
            taskId = 3,
            taskName = "斗破苍穹 (2017)",
            seasonNo = 4,
            names = listOf("斗破苍穹.S05E207.1080p.mp4"),
            restoredFiles = 1,
        )!!
        assertEquals("斗破苍穹", update.title)
        assertEquals("S05E207", update.episodeLabel)
        assertEquals("斗破苍穹更新 S05E207", update.notificationTitle)
        assertEquals("共 1 集", update.notificationBody)
    }

    @Test
    fun `连更多集是一条范围`() {
        val update = ShowUpdate.from(
            taskId = 3,
            taskName = "斗破苍穹 (2017)",
            seasonNo = 4,
            names = listOf(
                "斗破苍穹.S05E209.mp4",
                "斗破苍穹.S05E207.mp4",
                "斗破苍穹.S05E208.mp4",
            ),
            restoredFiles = 3,
        )!!
        assertEquals("S05E207-E209", update.episodeLabel)
        assertEquals("斗破苍穹更新 S05E207-E209", update.notificationTitle)
        assertEquals("共 3 集", update.notificationBody)
    }

    @Test
    fun `同一集多版本不重复计数`() {
        val update = ShowUpdate.from(
            taskId = 3,
            taskName = "仙逆 (2023)",
            seasonNo = TaskEntity.SEASON_MULTI,
            names = listOf("仙逆.S01E168.mp4", "仙逆.S01E168.2160p.mkv"),
            restoredFiles = 2,
        )!!
        assertEquals(1, update.count)
        assertEquals("S01E168", update.episodeLabel)
    }

    @Test
    fun `文件名没写季号时按任务季补齐`() {
        val fixed = ShowUpdate.from(
            taskId = 7,
            taskName = "剑来 (2024)",
            seasonNo = 2,
            names = listOf("剑来.第53集.mp4"),
            restoredFiles = 1,
        )!!
        assertEquals("S02E053", fixed.episodeLabel)
    }

    @Test
    fun `平铺任务按第一季补齐`() {
        val flat = ShowUpdate.from(
            taskId = 9,
            taskName = "凡人修仙传 (2020)",
            seasonNo = TaskEntity.SEASON_FLAT,
            names = listOf("凡人修仙传.201.mp4"),
            restoredFiles = 1,
        )!!
        assertEquals("S01E201", flat.episodeLabel)
    }

    @Test
    fun `多季任务且文件没季号时不猜`() {
        val multi = ShowUpdate.from(
            taskId = 11,
            taskName = "凡人修仙传 (2020)",
            seasonNo = TaskEntity.SEASON_MULTI,
            names = listOf("凡人修仙传.201.mp4"),
            restoredFiles = 1,
        )!!
        assertEquals("E201", multi.episodeLabel)
    }

    @Test
    fun `名字都解析不出集号时只报集数`() {
        val update = ShowUpdate.from(
            taskId = 13,
            taskName = "玫瑰战争 (2026)",
            seasonNo = TaskEntity.SEASON_FLAT,
            names = listOf("开播预告.mkv", "花絮.mp4"),
            restoredFiles = 2,
        )!!
        assertNull(update.episodeLabel)
        assertEquals("玫瑰战争更新", update.notificationTitle)
        assertEquals("共 2 集", update.notificationBody)
    }

    @Test
    fun `没有落盘文件就不出通知`() {
        assertNull(
            ShowUpdate.from(
                taskId = 1,
                taskName = "斗破苍穹 (2017)",
                seasonNo = 4,
                names = emptyList(),
                restoredFiles = 0,
            )
        )
    }
}
