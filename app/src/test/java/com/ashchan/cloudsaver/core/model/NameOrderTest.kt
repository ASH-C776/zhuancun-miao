package com.ashchan.cloudsaver.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件名排序口径：数字 → 汉字 → 字母；数字按数值大小。
 * 这条序同时决定「转存/改名的先后」，网盘里落在目录里的创建顺序会跟着它走。
 */
class NameOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun `数字开头的排最前 且按数值大小`() {
        assertEquals(
            listOf("1-200.mp4", "2-4K.mp4", "19-4K.mp4", "219-4K.mp4"),
            sorted("219-4K.mp4", "2-4K.mp4", "19-4K.mp4", "1-200.mp4"),
        )
    }

    @Test
    fun `集数不会排反 EP2 在 EP10 前`() {
        assertEquals(
            listOf("EP1.mkv", "EP2.mkv", "EP10.mkv", "EP20.mkv"),
            sorted("EP10.mkv", "EP20.mkv", "EP1.mkv", "EP2.mkv"),
        )
    }

    @Test
    fun `数字 汉字 字母 三类顺序`() {
        assertEquals(
            listOf("01.mkv", "第二集.mkv", "EP01.mkv", "Sword.and.Fairy.mp4"),
            sorted("Sword.and.Fairy.mp4", "EP01.mkv", "第二集.mkv", "01.mkv"),
        )
    }

    @Test
    fun `中文剧名之间按字典序 集号按数值`() {
        assertEquals(
            listOf("仙剑奇侠传三.S01E01.mp4", "仙剑奇侠传三.S01E02.mp4", "仙剑奇侠传三.S01E11.mp4"),
            sorted(
                "仙剑奇侠传三.S01E11.mp4",
                "仙剑奇侠传三.S01E01.mp4",
                "仙剑奇侠传三.S01E02.mp4",
            ),
        )
    }

    @Test
    fun `季目录名按季号 第1季 在 第10季 前`() {
        assertEquals(listOf("第1季", "第2季", "第10季"), sorted("第10季", "第2季", "第1季"))
    }

    @Test
    fun `前导零不影响数值序`() {
        assertEquals(listOf("01.mkv", "2.mkv", "010.mkv"), sorted("010.mkv", "2.mkv", "01.mkv"))
    }
}
