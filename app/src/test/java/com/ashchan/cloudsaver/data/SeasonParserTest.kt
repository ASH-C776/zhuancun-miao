package com.ashchan.cloudsaver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 季号解析：实际网盘季目录命名 + 中文数字 */
class SeasonParserTest {

    @Test
    fun `解析 第N季 带后缀`() {
        assertEquals(2, SeasonParser.parse("第 2 季 - 2160p WEB-DL HEVC AAC"))
    }

    @Test
    fun `解析 Season 英文`() {
        assertEquals(3, SeasonParser.parse("Season 3"))
    }

    @Test
    fun `解析 S02 短格式`() {
        assertEquals(2, SeasonParser.parse("S02"))
    }

    @Test
    fun `解析中文数字季`() {
        assertEquals(2, SeasonParser.parse("第二季"))
        assertEquals(10, SeasonParser.parse("第十季"))
        assertEquals(12, SeasonParser.parse("第十二季"))
        assertEquals(21, SeasonParser.parse("第二十一季"))
    }

    @Test
    fun `纯数字目录名 当作季号`() {
        assertEquals(1, SeasonParser.parse("1"))
    }

    @Test
    fun `空与无季号文本返回 null`() {
        assertNull(SeasonParser.parse(null))
        assertNull(SeasonParser.parse(""))
        assertNull(SeasonParser.parse("斗破苍穹 (2017)"))
        // 分辨率不能被误判成季号
        assertNull(SeasonParser.parse("1080p WEB-DL"))
    }
}
