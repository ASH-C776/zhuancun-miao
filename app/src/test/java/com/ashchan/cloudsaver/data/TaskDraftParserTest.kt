package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.core.model.DriveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 建任务时链接识别测试（纯本地，不联网）。 */
class TaskDraftParserTest {

    @Test
    fun `夸克链接识别`() {
        val r = TaskDraftParser.inspect("https://pan.quark.cn/s/225348afbc93#/list/share").getOrThrow()
        assertEquals(DriveType.QUARK, r.driveType)
        assertEquals("225348afbc93", r.shareId)
    }

    @Test
    fun `夸克链接带提取码`() {
        val r = TaskDraftParser.inspect("https://pan.quark.cn/s/abc123def456?pwd=k9m2").getOrThrow()
        assertEquals("abc123def456", r.shareId)
        assertEquals("k9m2", r.passcode)
    }

    @Test
    fun `光鸭链接识别`() {
        val r = TaskDraftParser
            .inspect("https://www.guangyapan.com/s/1962143988912746595_aeWw8Ru726g3wcPx#/share/")
            .getOrThrow()
        assertEquals(DriveType.GUANGYA, r.driveType)
        assertEquals("1962143988912746595_aeWw8Ru726g3wcPx", r.shareId)
    }

    @Test
    fun `不支持的链接给出提示`() {
        val r = TaskDraftParser.inspect("https://www.aliyundrive.com/s/xxx")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("夸克") == true)
    }

    @Test
    fun `空链接`() {
        assertTrue(TaskDraftParser.inspect("   ").isFailure)
    }

    @Test
    fun `类型判断`() {
        assertEquals(DriveType.QUARK, TaskDraftParser.detectDriveType("https://pan.quark.cn/s/1"))
        assertEquals(DriveType.GUANGYA, TaskDraftParser.detectDriveType("https://www.guangyapan.com/s/1"))
        assertNull(TaskDraftParser.detectDriveType("https://example.com/x"))
    }
}
