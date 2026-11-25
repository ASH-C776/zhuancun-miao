package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonCustomMergeTest {

    @Test
    fun `没传值就沿用旧值`() {
        assertEquals(166 to 46, SeasonCustomMerge.merge(166, 46, null, null))
    }

    @Test
    fun `UI 默认传 0 时不清空老任务`() {
        assertEquals(166 to 46, SeasonCustomMerge.merge(166, 46, 0, 0))
    }

    @Test
    fun `显式传正数才覆盖`() {
        assertEquals(181 to 35, SeasonCustomMerge.merge(166, 46, 181, 35))
    }

    @Test
    fun `只覆盖其中一个字段`() {
        assertEquals(166 to 35, SeasonCustomMerge.merge(166, 46, null, 35))
        assertEquals(181 to 46, SeasonCustomMerge.merge(166, 46, 181, null))
    }

    @Test
    fun `新任务没有旧值时保持 0`() {
        assertEquals(0 to 0, SeasonCustomMerge.merge(0, 0, null, null))
    }
}
