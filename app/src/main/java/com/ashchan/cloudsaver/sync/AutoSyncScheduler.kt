package com.ashchan.cloudsaver.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 自动刷新的唯一调度入口：只有「每天固定时刻」这一种触发源。
 *
 * 红线：任何组件都不许自己 `enqueue`，否则互斥与开关判断会被绕过。
 *
 * 三条约束（都是踩过坑换来的）：
 * 1. Worker 续排必须用 [scheduleNextAfterRun]（名字带目标日期 + KEEP），
 *    不能拿 REPLACE 去替换「正在跑的自己」，否则新任务会被这次取消连带清掉。
 * 2. 所有排程都要带 [slotTag]，这样 [ensureScheduled] 与状态卡能反查
 *    「这个时段到底有没有排上」，而不是只靠算出来的「下次几点」自我安慰。
 * 3. 时段被用户删掉后，Worker 不能在 finally 里把它复活。
 */
object AutoSyncScheduler {

    /** 时段 Work 的统一 tag，取消时按它一并清掉，不用猜下标 */
    const val SLOT_TAG: String = "auto-sync-slot"

    const val REASON_SLOT: String = "slot"

    /** 闸门冲突后的补跑（只跑一次，不滚成周期） */
    const val REASON_RETRY: String = "slot_retry"

    const val KEY_REASON: String = "reason"
    const val KEY_SLOT: String = "slot"

    /** 撞上「已有一轮在跑」时，隔多久补跑一次 */
    const val RETRY_DELAY_MS: Long = 10 * 60_000L

    /**
     * 冷启动 / 用户重排时的宽限：正好过点几十秒也把今天这一趟排上（delay 0 立即跑），
     * 否则「08:25:00.4 打开 App」会算出明天 08:25，当天这一趟直接消失。
     * Worker 续排**不传**这个值，保持严格晚于 now，避免自己排自己。
     */
    const val SCHEDULE_GRACE_MS: Long = 30_000L

    fun slotWorkName(slot: String): String = "auto-sync-slot-" + slot.replace(":", "")

    /** 每个时段一个独立 tag，用于从 WorkInfo 反查排程状态 */
    fun slotTag(slot: String): String = slotWorkName(slot)

    /** 名字里带目标日期：同一时段每天一个唯一名，续排时不会和自己打架 */
    private fun datedWorkName(slot: String, triggerAt: Long): String =
        slotWorkName(slot) + "-" + Instant.ofEpochMilli(triggerAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    private fun enqueue(
        context: Context,
        uniqueName: String,
        slot: String,
        reason: String,
        delayMs: Long,
        policy: ExistingWorkPolicy,
    ) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(SLOT_TAG)
            .addTag(slotTag(slot))
            .setInputData(workDataOf(KEY_REASON to reason, KEY_SLOT to slot))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, policy, request)
    }

    /**
     * 为单个时段排下一次（UI 用：改名/新增时段后重排，允许 REPLACE 旧的那个）。
     */
    fun scheduleSlot(context: Context, slot: String) {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return
        val now = System.currentTimeMillis()
        val next = AutoSyncConfig.nextTriggerAt(slot, now, graceMillis = SCHEDULE_GRACE_MS)
        enqueue(context, slotWorkName(slot), slot, REASON_SLOT, next - now, ExistingWorkPolicy.REPLACE)
    }

    /**
     * Worker 跑完自己续下一次：唯一名带日期 + KEEP。
     *
     * 用 KEEP 而不是 REPLACE 是刻意的：`doWork` 还没返回，同名 REPLACE 会把「正在跑的这一轮」
     * 标成 CANCELLED，新排的任务有概率被这次取消一起带走 —— 断了就再没人接。
     * 时段已经被删除时直接不排，避免 Worker 的 finally 把删掉的时间点复活。
     */
    fun scheduleNextAfterRun(context: Context, slot: String) {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return
        if (slot !in prefs.slots) return
        val now = System.currentTimeMillis()
        val next = AutoSyncConfig.nextTriggerAt(slot, now)
        enqueue(context, datedWorkName(slot, next), slot, REASON_SLOT, next - now, ExistingWorkPolicy.KEEP)
    }

    /**
     * 闸门冲突的补跑：上一轮还没跑完，本轮不要「静默丢掉」，
     * 隔 [RETRY_DELAY_MS] 再试一次（如果那时已经跑完，补跑就会真的执行）。
     */
    fun scheduleCatchUp(context: Context, slot: String, missedAt: Long) {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return
        if (slot !in prefs.slots) return
        enqueue(
            context,
            slotWorkName(slot) + "-retry-" + missedAt,
            slot,
            REASON_RETRY,
            RETRY_DELAY_MS,
            ExistingWorkPolicy.KEEP,
        )
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

    /**
     * App 启动自愈：只补「当前没有任何活着的 Work」的时段。
     *
     * 为什么必须要有：时段 Work 是一次性链，一旦被系统清掉（应用被停用/WorkManager 被剪枝/
     * 用户手动取消），没有任何东西会再把它排回来，界面上却还显示「下次 09:00」。
     * 在 IO 线程调用（内部 `get()` 会阻塞）。
     */
    fun ensureScheduled(context: Context) {
        val prefs = AutoSyncPrefs(context)
        if (!prefs.enabled) return
        val wm = WorkManager.getInstance(context.applicationContext)
        val aliveTags: Set<String> = runCatching {
            wm.getWorkInfosByTag(SLOT_TAG).get()
                .filter {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
                .flatMap { it.tags }
                .toSet()
        }.getOrElse { emptySet() }
        prefs.slots.forEach { slot ->
            if (slotTag(slot) !in aliveTags) scheduleSlot(context, slot)
        }
    }

    /**
     * 读真实排程状态（状态卡用）：时段 → 实际 Work 状态。
     * 不在返回值里的时段就是「一条都没排上」，界面要明确提示，而不是继续算「下次几点」。
     * 在 IO 线程调用。
     */
    fun liveSlotStates(context: Context): Map<String, WorkInfo.State> {
        val slots = AutoSyncPrefs(context).slots
        val infos = runCatching {
            WorkManager.getInstance(context.applicationContext).getWorkInfosByTag(SLOT_TAG).get()
        }.getOrElse { return emptyMap() }
        val best = mutableMapOf<String, WorkInfo.State>()
        val rank = mapOf(
            WorkInfo.State.RUNNING to 4,
            WorkInfo.State.ENQUEUED to 3,
            WorkInfo.State.BLOCKED to 2,
            WorkInfo.State.FAILED to 1,
            WorkInfo.State.CANCELLED to 0,
        )
        infos.forEach { info ->
            slots.forEach { slot ->
                if (slotTag(slot) in info.tags) {
                    val old = best[slot]
                    if (old == null || (rank[info.state] ?: -1) >= (rank[old] ?: -1)) best[slot] = info.state
                }
            }
        }
        return best
    }
}
