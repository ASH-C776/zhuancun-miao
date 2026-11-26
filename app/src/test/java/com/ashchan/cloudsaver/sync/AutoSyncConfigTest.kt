package com.ashchan.cloudsaver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** 自动刷新纯逻辑：时段规范化 + 下一跳计算 + 充电冷却判定 */
class AutoSyncConfigTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    // ---------- 格式校验 ----------

    @Test
    fun `合法时刻通过校验`() {
        listOf("00:00", "09:00", "23:59", " 20:30 ").forEach {
            assertTrue("$it 应合法", AutoSyncConfig.isValidSlot(it))
        }
    }

    @Test
    fun `非法时刻被拒`() {
        listOf("9:00", "24:00", "09:60", "0900", "", "abc", "09:00:00").forEach {
            assertFalse("$it 应非法", AutoSyncConfig.isValidSlot(it))
        }
    }

    @Test
    fun `分钟换算正确`() {
        assertEquals(0, AutoSyncConfig.toMinutes("00:00"))
        assertEquals(540, AutoSyncConfig.toMinutes("09:00"))
        assertEquals(1439, AutoSyncConfig.toMinutes("23:59"))
    }

    // ---------- 规范化 ----------

    @Test
    fun `规范化会排序去重并去空白`() {
        val out = AutoSyncConfig.normalize(" 20:00 , 09:00,20:00 ,9:00")
        assertEquals(listOf("09:00", "20:00"), out)
    }

    @Test
    fun `规范化最多保留四条`() {
        val out = AutoSyncConfig.normalize("01:00,02:00,03:00,04:00,05:00,06:00")
        assertEquals(4, out.size)
        assertEquals(listOf("01:00", "02:00", "03:00", "04:00"), out)
    }

    @Test
    fun `全非法时得到空表`() {
        assertEquals(emptyList<String>(), AutoSyncConfig.normalize("abc,,99:99"))
    }

    @Test
    fun `默认时刻规范化后是九点与二十点`() {
        assertEquals(listOf("09:00", "20:00"), AutoSyncConfig.normalize(AutoSyncConfig.DEFAULT_SLOTS))
    }

    @Test
    fun `csv 往返不变`() {
        val slots = listOf("20:00", "09:00", "20:00")
        assertEquals("09:00,20:00", AutoSyncConfig.toCsv(slots))
    }

    // ---------- 下一跳 ----------

    @Test
    fun `今天还没到点则取今天`() {
        val now = at(2026, 11, 24, 8, 0)
        val next = AutoSyncConfig.nextTriggerAt("09:00", now, zone)
        assertEquals(at(2026, 11, 24, 9, 0), next)
    }

    @Test
    fun `过了点则取明天`() {
        val now = at(2026, 11, 24, 10, 0)
        val next = AutoSyncConfig.nextTriggerAt("09:00", now, zone)
        assertEquals(at(2026, 11, 25, 9, 0), next)
    }

    @Test
    fun `正好到点算已过取明天避免自触发`() {
        val now = at(2026, 11, 24, 9, 0)
        val next = AutoSyncConfig.nextTriggerAt("09:00", now, zone)
        assertEquals(at(2026, 11, 25, 9, 0), next)
    }

    @Test
    fun `跨零点时段取次日`() {
        val now = at(2026, 11, 24, 23, 30)
        val next = AutoSyncConfig.nextTriggerAt("00:10", now, zone)
        assertEquals(at(2026, 11, 25, 0, 10), next)
    }

    @Test
    fun `月末跨天正确`() {
        val now = at(2026, 11, 30, 22, 0)
        val next = AutoSyncConfig.nextTriggerAt("08:00", now, zone)
        assertEquals(at(2026, 12, 1, 8, 0), next)
    }

    @Test
    fun `多时段取最近的一条`() {
        val now = at(2026, 11, 24, 12, 0)
        val next = AutoSyncConfig.nextTriggerAt(listOf("09:00", "20:00"), now, zone)
        assertEquals(at(2026, 11, 24, 20, 0), next!!)
    }

    @Test
    fun `空时段表没有下一跳`() {
        assertNull(AutoSyncConfig.nextTriggerAt(emptyList(), at(2026, 11, 24, 12, 0), zone))
    }

}
