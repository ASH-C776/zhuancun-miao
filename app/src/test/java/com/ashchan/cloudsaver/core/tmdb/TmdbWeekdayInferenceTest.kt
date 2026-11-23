package com.ashchan.cloudsaver.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日推断（对齐 CASX 原项目 `_infer_weekdays_from_weekday_samples`）。
 *
 * 样本顺序 = 时间顺序（旧 → 新）；星期口径 1=周一 … 7=周日。
 */
class TmdbWeekdayInferenceTest {

    @Test
    fun `单一每周更新日不会被拆开`() {
        // 连续 5 集都周六（6）→ 单日
        assertEquals(listOf(6), inferWeekdaysFromSamples(List(5) { 6 }))
    }

    @Test
    fun `一周播两天时返回两天`() {
        // 周一+周二交替 5 轮：判定不该只剩一天
        val samples = (0 until 10).map { if (it % 2 == 0) 1 else 2 }
        val result = inferWeekdaysFromSamples(samples)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `一周播三天的综艺返回三天`() {
        val samples = (0 until 12).map { listOf(5, 6, 7)[it % 3] }
        val result = inferWeekdaysFromSamples(samples)
        assertEquals(listOf(5, 6, 7), result)
    }

    @Test
    fun `天天播的节目返回多个日子`() {
        // 7 天轮一遍，没有明显主日 → 走兜底返回所有出现过的日子
        val samples = (1..7).toList()
        val result = inferWeekdaysFromSamples(samples)
        assertEquals(7, result.size)
        assertTrue(result.containsAll(listOf(1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun `样本太少时不会强行猜单日`() {
        // 只有两天样本，且不同 → 两个都留着
        val result = inferWeekdaysFromSamples(listOf(2, 4))
        assertEquals(listOf(2, 4), result)
    }

    @Test
    fun `最近几集改了播出日时以新日期为主`() {
        // 前面老集在周日（7），最近三集改到周五（5）
        val samples = listOf(7, 7, 5, 5, 5)
        assertEquals(listOf(5), inferWeekdaysFromSamples(samples))
    }

    @Test
    fun `空样本返回空`() {
        assertEquals(emptyList<Int>(), inferWeekdaysFromSamples(emptyList()))
        assertEquals(emptyList<Int>(), inferWeekdaysFromSamples(listOf(0, 9)))
    }
}
