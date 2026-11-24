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
    fun `中文季名 第一季到第三季`() {
        assertEquals(1, SeasonParser.parse("第一季"))
        assertEquals(2, SeasonParser.parse("第二季"))
        assertEquals(3, SeasonParser.parse("第三季"))
        // 带后缀也认
        assertEquals(2, SeasonParser.parse("第二季 2160p HDR"))
        assertEquals(2, SeasonParser.parse("第 二 季"))
    }

    @Test
    fun `第零季 与 第一部 也能认`() {
        assertEquals(0, SeasonParser.parse("第零季"))
        assertEquals(1, SeasonParser.parse("第一部"))
        assertEquals(2, SeasonParser.parse("第2部"))
        assertEquals(3, SeasonParser.parse("第三篇"))
    }

    @Test
    fun `番外 OVA OAD 归类到特别版`() {
        assertEquals(0, SeasonParser.parse("番外篇"))
        assertEquals(0, SeasonParser.parse("OVA"))
        assertEquals(0, SeasonParser.parse("OAD 1080p"))
    }

    @Test
    fun `小写 season 与 s02 也认`() {
        assertEquals(3, SeasonParser.parse("season 3"))
        assertEquals(2, SeasonParser.parse("s02"))
    }

    @Test
    fun `特别版目录名解析成 0`() {
        // 0 是真实存在的一季（TMDB Season 0 = 特别篇），不是「没选季度」
        assertEquals(0, SeasonParser.parse("Season 0"))
        assertEquals(0, SeasonParser.parse("Specials"))
        assertEquals(0, SeasonParser.parse("Special"))
        assertEquals(0, SeasonParser.parse("SP"))
        assertEquals(0, SeasonParser.parse("S00"))
        assertEquals(0, SeasonParser.parse("特别篇"))
        assertEquals(0, SeasonParser.parse("特别版 1080p"))
    }

    @Test
    fun `带 SP 字样的常规季不能误判成特别版`() {
        assertEquals(1, SeasonParser.parse("S01 SP04"))
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
