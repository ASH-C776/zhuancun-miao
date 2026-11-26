package com.ashchan.cloudsaver.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 自动刷新的唯一调度入口：只有「每天固定时刻」这一种触发源。
 *
 * 红线：任何组件都不许自己 `enqueue`，否则互斥与开关判断会被绕过。
 */
object AutoSyncScheduler {

    /** 时段 Work 的统一 tag，取消时按它一并清掉，不用猜下标 */
    const val SLOT_TAG: String = "auto-sync-slot"

    const val REASON_SLOT: String = "slot"

    const val KEY_REASON: String = "reason"
    const val KEY_SLOT: String = "slot"

    fun slotWorkName(slot: String): String = "auto-sync-slot-" + slot.replace(":", "")

    /** 为单个时段排下一次：延迟到下一个 `HH:mm`，跑完由 Worker 自己再续 */
    fun scheduleSlot(context: Context, slot: String) {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return
        val now = System.currentTimeMillis()
        val delay = (AutoSyncConfig.nextTriggerAt(slot, now) - now).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(SLOT_TAG)
            .setInputData(workDataOf(KEY_REASON to REASON_SLOT, KEY_SLOT to slot))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(slotWorkName(slot), ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 按当前配置重排：先清掉旧时段，再逐条排新的。
     * 开关关闭时等价于 [cancelAll]。
     */
    fun rescheduleAll(context: Context) {
        val prefs = AutoSyncPrefs(context)
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(SLOT_TAG)
        if (!prefs.enabled) return
        prefs.slots.forEach { scheduleSlot(context, it) }
    }

    /** 关总开关 / 卸载功能时调用：时段 Work 一起取消 */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(SLOT_TAG)
    }
}
