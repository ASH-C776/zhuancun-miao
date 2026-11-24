package com.ashchan.cloudsaver.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 自动刷新的唯一调度入口。
 *
 * 红线：所有触发（定时点 / 插电 / 将来可能的其它来源）都必须经过这里，
 * 任何其它组件都不许自己 `enqueue`，否则互斥、冷却、开关判断会被绕过。
 */
object AutoSyncScheduler {

    /** 同一个运行 Work 的唯一名：保证任意时刻只有一轮在跑 */
    const val RUN_WORK: String = "auto-sync-run"

    /** 时段 Work 的统一 tag，取消时按它一并清掉，不用猜下标 */
    const val SLOT_TAG: String = "auto-sync-slot"

    const val REASON_SLOT: String = "slot"
    const val REASON_CHARGING: String = "charging"

    const val KEY_REASON: String = "reason"
    const val KEY_SLOT: String = "slot"

    fun slotWorkName(slot: String): String = "auto-sync-slot-" + slot.replace(":", "")

    /**
     * 请求跑一轮。返回是否真的入队，便于调用方（和调试）区分「跳过」与「已排队」。
     *
     * 这里做的是**入口级**判断：总开关、插电开关、冷却。Worker 里还会再判一次，
     * 因为从入队到真正执行之间可能过了很久（Doze 延后），期间可能已被别的一轮满足。
     */
    fun requestRun(context: Context, reason: String): Boolean {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return false
        if (reason == REASON_CHARGING) {
            if (!prefs.chargeEnabled) return false
            val now = System.currentTimeMillis()
            if (AutoSyncConfig.shouldSkipCharge(prefs.lastRunStartAt, now)) {
                prefs.lastSkipAt = now
                prefs.lastSkipReason = "charge_cooldown"
                return false
            }
        }
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInputData(workDataOf(KEY_REASON to reason))
            .build()
        // KEEP：已有一轮在跑时不再排第二条，避免叠着跑
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(RUN_WORK, ExistingWorkPolicy.KEEP, request)
        return true
    }

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
        if (!prefs.enabled) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RUN_WORK)
            return
        }
        prefs.slots.forEach { scheduleSlot(context, it) }
    }

    /** 关总开关 / 卸载功能时调用：时段与运行 Work 一起取消 */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelAllWorkByTag(SLOT_TAG)
        wm.cancelUniqueWork(RUN_WORK)
    }
}
