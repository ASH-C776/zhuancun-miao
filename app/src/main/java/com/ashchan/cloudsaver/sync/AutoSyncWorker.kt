package com.ashchan.cloudsaver.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.notify.AlertPublisher

/**
 * 后台执行一轮「全部检测」。
 *
 * 不复用 TasksViewModel：那边绑在 viewModelScope 上，进程被杀就一起没了；
 * 这里自建仓库与引擎（构造方式与 MainActivity 里一致），跑完把汇总写进独立 prefs。
 */
class AutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AutoSyncPrefs(applicationContext)
        val reason = inputData.getString(AutoSyncScheduler.KEY_REASON) ?: AutoSyncScheduler.REASON_SLOT
        val slot = inputData.getString(AutoSyncScheduler.KEY_SLOT)

        try {
            // 总开关在入队后被关掉：直接退出，不留下半轮记录
            if (!prefs.enabled) return Result.success()

            if (reason == AutoSyncScheduler.REASON_CHARGING) {
                if (!prefs.chargeEnabled) return Result.success()
                val now = System.currentTimeMillis()
                // 入队到真正执行可能隔了很久，这里再判一次冷却
                if (AutoSyncConfig.shouldSkipCharge(prefs.lastRunStartAt, now)) {
                    prefs.lastSkipAt = now
                    prefs.lastSkipReason = "charge_cooldown"
                    return Result.success()
                }
            }

            val startAt = System.currentTimeMillis()
            val summary = AutoSyncEngine(applicationContext).runAllOnce()
            prefs.recordRun(
                startAt = startAt,
                endAt = System.currentTimeMillis(),
                total = summary.total,
                newCount = summary.newFiles,
                ok = summary.failed == 0,
            )
            Log.i(
                TAG,
                "自动刷新完成 reason=$reason 任务=${summary.total} 新增=${summary.newFiles} 失败=${summary.failed}",
            )
            // 只在「有新集」时提醒（用户口径：一部剧一条；没有新增就不打扰，失败只在日志页看）
            if (prefs.notifyEnabled && summary.newFiles > 0) {
                AlertPublisher.publish(applicationContext, summary.updates)
            }
            return Result.success()
        } catch (t: Throwable) {
            // 单轮失败不该让系统无限重试（重试会反复拉目录树）；记录后交给下一次定时
            Log.w(TAG, "自动刷新异常", t)
            return Result.success()
        } finally {
            // 时段 Work 是一次性链：不管这轮成功与否都要排下一次，否则第二天就断了
            if (reason == AutoSyncScheduler.REASON_SLOT && !slot.isNullOrBlank()) {
                AutoSyncScheduler.scheduleSlot(applicationContext, slot)
            }
        }
    }

    private companion object {
        const val TAG = "AutoSyncWorker"
    }
}

/** 单轮汇总：给通知与状态卡用，不进入数据库 */
data class AutoSyncSummary(
    val total: Int,
    val newFiles: Int,
    val renamed: Int,
    val failed: Int,
    val firstError: String?,
    /** 本轮有新集的剧，按任务拆开（一部剧一条通知） */
    val updates: List<com.ashchan.cloudsaver.notify.ShowAlert> = emptyList(),
)

/**
 * 无 UI 执行体：只调用既有引擎的公开入口，不改 TaskRunner 一行。
 * `runAll(..., TRIGGER_BACKGROUND)` 是 v0.7 就存在的路径，日志页会显示成「自动同步」。
 */
class AutoSyncEngine(private val context: Context) {

    suspend fun runAllOnce(): AutoSyncSummary {
        val repo = com.ashchan.cloudsaver.data.TaskRepository(
            com.ashchan.cloudsaver.data.db.AppDatabase.get(context)
        )
        val tasks = repo.enabledTasks()
        if (tasks.isEmpty()) return AutoSyncSummary(0, 0, 0, 0, null)

        val runner = com.ashchan.cloudsaver.transfer.TaskRunner.create(context, repo)

        var newFiles = 0
        var renamed = 0
        var failed = 0
        var firstError: String? = null
        val updates = mutableListOf<com.ashchan.cloudsaver.notify.ShowAlert>()

        val results = runner.runAll(tasks, RunRecordEntity.TRIGGER_BACKGROUND)
        results.forEach { (task, result) ->
            result.onSuccess { report ->
                newFiles += report.restoredFiles
                renamed += report.renamedItems
                if (report.restoredFiles > 0) {
                    com.ashchan.cloudsaver.notify.AlertSource.build(
                        taskId = task.id,
                        taskName = task.name,
                        seasonNo = task.seasonNo,
                        items = report.restoredEpisodes,
                        restoredFiles = report.restoredFiles,
                    )?.let { updates += it }
                }
                if (!report.ok) {
                    failed++
                    if (firstError == null) {
                        firstError = report.failures.firstOrNull()?.let { "「${task.name}」$it" }
                            ?: "「${task.name}」本轮有失败项"
                    }
                }
            }.onFailure { e ->
                failed++
                if (firstError == null) firstError = "「${task.name}」${e.message ?: "执行失败"}"
            }
        }
        return AutoSyncSummary(tasks.size, newFiles, renamed, failed, firstError, updates)
    }
}
