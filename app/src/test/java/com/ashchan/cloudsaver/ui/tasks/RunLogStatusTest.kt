package com.ashchan.cloudsaver.ui.tasks

import com.ashchan.cloudsaver.data.db.RunRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 日志状态归类：只依赖 RunRecord 的既有字段，不碰数据库 */
class RunLogStatusTest {

    private fun record(
        ok: Boolean = true,
        restoredFiles: Int = 0,
        restoredDirs: Int = 0,
        renamed: Int = 0,
        skipped: Int = 0,
        message: String = "",
    ) = RunRecordEntity(
        taskId = 1,
        taskName = "测试任务",
        trigger = RunRecordEntity.TRIGGER_BACKGROUND,
        restoredFiles = restoredFiles,
        restoredDirs = restoredDirs,
        renamedItems = renamed,
        skippedFiles = skipped,
        ok = ok,
        message = message,
    )

    @Test
    fun `有新增文件归为新增并带数量`() {
        val s = RunLogStatusRules.classify(record(restoredFiles = 3, skipped = 9))
        assertEquals(RunLogKind.NEW, s.kind)
        assertEquals("转存 3 集", s.label)
    }

    @Test
    fun `只改名归为整理`() {
        val s = RunLogStatusRules.classify(record(renamed = 5))
        assertEquals(RunLogKind.TIDY, s.kind)
        assertEquals("改名 5 项", s.label)
    }

    @Test
    fun `只建目录也归为整理`() {
        val s = RunLogStatusRules.classify(record(restoredDirs = 2))
        assertEquals(RunLogKind.TIDY, s.kind)
        assertEquals("新建 2 个目录", s.label)
    }

    @Test
    fun `全零且无消息归为无变化`() {
        val s = RunLogStatusRules.classify(record(skipped = 25))
        assertEquals(RunLogKind.IDLE, s.kind)
        assertEquals("无变化", s.label)
    }

    @Test
    fun `成功但消息里有失败字样归为部分失败`() {
        val s = RunLogStatusRules.classify(
            record(restoredFiles = 2, message = "源2 失败：登录已失效")
        )
        assertEquals(RunLogKind.PARTIAL, s.kind)
        assertEquals("部分失败", s.label)
    }

    @Test
    fun `成功且只有季判定提示归为提示`() {
        val s = RunLogStatusRules.classify(record(message = "季判定：按文件名走第 2 季"))
        assertEquals(RunLogKind.NOTE, s.kind)
    }

    @Test
    fun `失败里登录失效单独归类`() {
        assertEquals(RunLogKind.FAIL_LOGIN, RunLogStatusRules.classify(record(ok = false, message = "登录已失效")).kind)
        assertEquals(RunLogKind.FAIL_LOGIN, RunLogStatusRules.classify(record(ok = false, message = "cookie 过期")).kind)
    }

    @Test
    fun `失败里限流单独归类`() {
        assertEquals(RunLogKind.FAIL_RATE, RunLogStatusRules.classify(record(ok = false, message = "操作过于频繁，请稍后重试")).kind)
        assertEquals(RunLogKind.FAIL_RATE, RunLogStatusRules.classify(record(ok = false, message = "HTTP 429")).kind)
    }

    @Test
    fun `失败里网络异常单独归类`() {
        assertEquals(RunLogKind.FAIL_NET, RunLogStatusRules.classify(record(ok = false, message = "Unable to resolve host \"pan.quark.cn\"")).kind)
        assertEquals(RunLogKind.FAIL_NET, RunLogStatusRules.classify(record(ok = false, message = "连接超时")).kind)
    }

    @Test
    fun `失败里分享无内容单独归类`() {
        assertEquals(
            RunLogKind.FAIL_EMPTY,
            RunLogStatusRules.classify(record(ok = false, message = "分享中没有找到可转存的视频文件")).kind,
        )
    }

    @Test
    fun `其它失败落到通用失败`() {
        val s = RunLogStatusRules.classify(record(ok = false, message = "未知异常"))
        assertEquals(RunLogKind.FAILED, s.kind)
        assertEquals("失败", s.label)
    }

    @Test
    fun `新增优先于整理`() {
        val s = RunLogStatusRules.classify(record(restoredFiles = 1, renamed = 7))
        assertEquals(RunLogKind.NEW, s.kind)
    }

    @Test
    fun `失败里即使有计数也按失败呈现`() {
        val s = RunLogStatusRules.classify(record(ok = false, restoredFiles = 5, message = "同步失败"))
        assertEquals(RunLogKind.FAILED, s.kind)
    }
}
