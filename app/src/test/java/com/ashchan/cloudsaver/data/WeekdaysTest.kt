package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.x 更新星期工具测试（纯逻辑）。 */
class WeekdaysTest {

    @Test
    fun `解析时忽略越界值与空段`() {
        assertEquals(setOf(1, 3, 5), Weekdays.parse("3, 1 ,5,"))
        assertEquals(setOf(6), Weekdays.parse("6"))
        assertEquals(emptySet<Int>(), Weekdays.parse("0,8,abc"))
        assertEquals(emptySet<Int>(), Weekdays.parse(""))
    }

    @Test
    fun `格式化去重升序`() {
        assertEquals("1,3,5", Weekdays.format(setOf(5, 3, 1, 1)))
        assertEquals("", Weekdays.format(emptySet()))
    }

    @Test
    fun `卡片文案 每日 周末 空`() {
        assertEquals("每日更新", Weekdays.label("1,2,3,4,5,6,7"))
        assertEquals("周六、周日", Weekdays.label("6,7"))
        assertEquals("周一、周三", Weekdays.label("3,1"))
        assertEquals("未设更新日", Weekdays.label(""))
    }

    @Test
    fun `天内名与越界`() {
        assertEquals("周一", Weekdays.nameOf(1))
        assertEquals("周日", Weekdays.nameOf(7))
        assertEquals("", Weekdays.nameOf(0))
        assertEquals("", Weekdays.nameOf(8))
    }

    @Test
    fun `预设`() {
        assertEquals(setOf(1, 2, 3, 4, 5), Weekdays.workdays())
        assertEquals(7, Weekdays.everyDay().size)
        assertTrue(Weekdays.isEveryDay("1,2,3,4,5,6,7"))
        assertFalse(Weekdays.isEveryDay("1,2,3"))
    }
}
