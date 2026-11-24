package com.ashchan.cloudsaver.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本判据单测。这两个函数判反了，客户端就会出现「提示降级」或「永远提示有新版」，
 * 而这两种 bug 都很难在真机上察觉 —— 必须先让判据跑绿，再接线。
 */
class UpdateRulesTest {

    @Test
    fun `build_gradle 公式一致`() {
        // build.gradle.kts: major*10000 + minor*100 + patch
        assertEquals(800, UpdateRules.parseVersionCode("0.8.0"))
        assertEquals(700, UpdateRules.parseVersionCode("0.7.0"))
        assertEquals(1_004_000, UpdateRules.parseVersionCode("100.40.0"))
    }

    @Test
    fun `两位版本号按 patch 补零`() {
        assertEquals(1_200, UpdateRules.parseVersionCode("0.12"))
        assertEquals(1_000, UpdateRules.parseVersionCode("0.10.0"))
    }

    @Test
    fun `剥离 v 前缀与后缀`() {
        assertEquals(800, UpdateRules.parseVersionCode("v0.8.0"))
        assertEquals(800, UpdateRules.parseVersionCode("V0.8.0"))
        assertEquals(801, UpdateRules.parseVersionCode(" v0.8.1-debug "))
        assertEquals(800, UpdateRules.parseVersionCode("0.8.0+4"))
    }

    @Test
    fun `脏数据一律拒绝`() {
        assertNull(UpdateRules.parseVersionCode(null))
        assertNull(UpdateRules.parseVersionCode(""))
        assertNull(UpdateRules.parseVersionCode("latest"))
        assertNull(UpdateRules.parseVersionCode("v0.8.0.1"))
        assertNull(UpdateRules.parseVersionCode("0.-1.0"))
        assertNull(UpdateRules.parseVersionCode("1.2.3.4"))
    }

    @Test
    fun `没有更高版本就不提示`() {
        val cand = listOf(
            UpdateInfo("0.7.0", 700, "u", "github"),
            UpdateInfo("0.6.0", 600, "u", "gitee"),
        )
        assertNull(UpdateRules.pickLatest(cand, 700))
        assertNull(UpdateRules.pickLatest(emptyList(), 700))
    }

    @Test
    fun `跨端取最高版本`() {
        val cand = listOf(
            UpdateInfo("0.8.0", 800, "gitee", "gitee"),
            UpdateInfo("0.9.0", 900, "github", "github"),
        )
        assertEquals(900, UpdateRules.pickLatest(cand, 700)?.versionCode)
    }

    @Test
    fun `同版本优先 Gitee`() {
        val cand = listOf(
            UpdateInfo("0.8.0", 800, "https://api.github.com/x.apk", "github"),
            UpdateInfo("0.8.0", 800, "https://gitee.com/x.apk", "gitee"),
        )
        assertEquals("gitee", UpdateRules.pickLatest(cand, 700)?.source)
    }
}
