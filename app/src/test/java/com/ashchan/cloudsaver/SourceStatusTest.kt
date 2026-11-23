package com.ashchan.cloudsaver

import com.ashchan.cloudsaver.data.db.TaskSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 源状态的两级判定：一次失败 WARN、连续两次才 FAIL，成功一律回到 OK */
class SourceStatusTest {

    @Test
    fun `第一次失败降为 WARN`() {
        assertEquals(
            TaskSourceEntity.STATUS_WARN,
            TaskSourceEntity.statusAfterRun(TaskSourceEntity.STATUS_UNKNOWN, ok = false),
        )
    }

    @Test
    fun `WARN 再失败升级 FAIL`() {
        assertEquals(
            TaskSourceEntity.STATUS_FAIL,
            TaskSourceEntity.statusAfterRun(TaskSourceEntity.STATUS_WARN, ok = false),
        )
    }

    @Test
    fun `FAIL 继续失败仍是 FAIL`() {
        assertEquals(
            TaskSourceEntity.STATUS_FAIL,
            TaskSourceEntity.statusAfterRun(TaskSourceEntity.STATUS_FAIL, ok = false),
        )
    }

    @Test
    fun `成功从任何状态回到 OK`() {
        listOf(
            TaskSourceEntity.STATUS_UNKNOWN,
            TaskSourceEntity.STATUS_WARN,
            TaskSourceEntity.STATUS_FAIL,
            TaskSourceEntity.STATUS_OK,
        ).forEach { previous ->
            assertEquals(
                TaskSourceEntity.STATUS_OK,
                TaskSourceEntity.statusAfterRun(previous, ok = true),
            )
        }
    }

    @Test
    fun `单源任务第一次失败只算 WARN 不算失效`() {
        // 卡片上「N 失效」只看 FAIL，所以抖动一次不会让用户以为链接坏了
        assertEquals(
            TaskSourceEntity.STATUS_WARN,
            TaskSourceEntity.statusAfterRun(TaskSourceEntity.STATUS_OK, ok = false),
        )
    }
}
