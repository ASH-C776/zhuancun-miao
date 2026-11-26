package com.ashchan.cloudsaver.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.db.AppDatabase
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.notify.Channels
import com.ashchan.cloudsaver.notify.TaskRunNotifier
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.transfer.RenamePass
import com.ashchan.cloudsaver.transfer.TaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 界面侧只读：服务在跑哪些任务、跑到哪一步 */
object TaskRunBus {
    /** 任务 id → 阶段文案（「准备中 / 转存 3/20 …」） */
    val phases = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 跑完一个任务时的结果短句（如「已改名 87 项」）；没有则卡片按 ok 显示「完成 / 失败」 */
    val finishNote = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 跑完一个任务：id + 是否成功（卡片闪「完成 / 失败」用） */
    val finished = MutableSharedFlow<Pair<Long, Boolean>>(extraBufferCapacity = 8)

    fun setPhase(taskId: Long, text: String) {
        phases.update { it + (taskId to text) }
    }

    fun clearPhase(taskId: Long) {
        phases.update { it - taskId }
    }

    fun setFinishNote(taskId: Long, note: String) {
        finishNote.update { it + (taskId to note) }
    }

    fun clearFinishNote(taskId: Long) {
        finishNote.update { it - taskId }
    }

    fun reportFinished(taskId: Long, ok: Boolean) {
        finished.tryEmit(taskId to ok)
    }

    fun clearAll() {
        phases.value = emptyMap()
    }
}

/**
 * 任务执行前台服务：把原本绑在 `viewModelScope` 上的长任务搬到服务里，
 * 这样切后台 / 熄屏 / 划掉最近任务都不会掐断任务本体。
 *
 * 保活手段（按功耗从小到大叠加）：
 * 1. `startForeground` + `dataSync` 类型：进程不进 cached，网络可用；
 * 2. `PARTIAL_WAKE_LOCK`：熄屏时 CPU 不休眠（60 分钟超时兜底，正常结束即释放）；
 * 3. 仅 Wi-Fi 下额外持 `WIFI_MODE_FULL_HIGH_PERF` 锁，避免射频休眠导致请求超时。
 *
 * 通知统一走 [TaskRunNotifier]，渠道在 [Channels] 里定义（低优先级、不进角标）。
 */
class TaskRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 排队中的启动请求：正在跑时来的新请求不丢弃，跑完接着执行 */
    private val pending = ArrayDeque<Intent>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            pending.clear()
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) return START_NOT_STICKY
        // 单飞：同一时刻只跑一条链（避免同一网盘并发触发风控）。
        // 但请求不能丢：后来的排队，当前这条跑完自动接着执行。
        if (job?.isActive == true) {
            pending.addLast(intent)
            return START_NOT_STICKY
        }
        dispatch(intent)
        return START_NOT_STICKY
    }

    private fun dispatch(intent: Intent) {
        val runAll = intent.action == ACTION_RUN_ALL
        val rename = intent.action == ACTION_RUN_RENAME
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: RunRecordEntity.TRIGGER_MANUAL

        Channels.ensureAll(this)
        startForeground(NOTIFY_ID_RUNNING, TaskRunNotifier.progress(this, "准备中"))
        acquireLocks()

        job = scope.launch {
            var okAny = true
            try {
                okAny = when {
                    rename -> runRenameTask(taskId)
                    runAll -> runAllTasks(trigger)
                    else -> runSingle(taskId, trigger)
                }
            } catch (e: Throwable) {
                android.util.Log.e("TaskRunService", "任务执行异常：${e.message}", e)
                okAny = false
            } finally {
                releaseLocks()
                TaskRunBus.clearAll()
                TaskRunNotifier.showFinished(this@TaskRunService, okAny)
                stopForeground(STOP_FOREGROUND_REMOVE)
                val next = pending.removeFirstOrNull()
                if (next == null) stopSelf() else dispatch(next)
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        releaseLocks()
        TaskRunBus.clearAll()
        super.onDestroy()
    }

    // ---------------- 执行 --------------

    private suspend fun runSingle(taskId: Long, trigger: String): Boolean {
        val repo = repo()
        val task = repo.tasks().firstOrNull { it.id == taskId } ?: return false
        return runTask(repo, task, trigger, showInNotification = true)
    }

    /** 统一重命名：只改文件名，不动目录结构；失败不写快照集数 */
    private suspend fun runRenameTask(taskId: Long): Boolean {
        val repo = repo()
        val task = repo.tasks().firstOrNull { it.id == taskId } ?: return false
        TaskRunBus.setPhase(task.id, "扫描目录")
        var ok = false
        try {
            val result = RenamePass(
                repo = repo,
                credentialStore = CredentialStore.get(applicationContext),
                ruleStore = RuleStore(applicationContext),
            ).run(task) { phase ->
                TaskRunBus.setPhase(task.id, phase)
                if (true) TaskRunNotifier.showProgress(this, "${task.name} · $phase")
            }
            ok = result.ok
            TaskRunBus.setFinishNote(
                task.id,
                if (result.renamed > 0) "已改名 ${result.renamed} 项" else "无需改名",
            )
            return ok
        } catch (e: Throwable) {
            android.util.Log.e("TaskRunService", "统一重命名失败：${e.message}", e)
            return false
        } finally {
            TaskRunBus.clearPhase(task.id)
            TaskRunBus.reportFinished(task.id, ok)
        }
    }

    private suspend fun runAllTasks(trigger: String): Boolean {
        val repo = repo()
        val tasks = repo.enabledTasks()
        // 没有启用的任务不是失败：只是没活干，别让用户看到「同步失败」的通知
        if (tasks.isEmpty()) return true
        var allOk = true
        tasks.forEachIndexed { index, task ->
            TaskRunNotifier.showProgress(this, "同步 ${index + 1}/${tasks.size}：${task.name}")
            if (!runTask(repo, task, trigger, showInNotification = false)) allOk = false
        }
        return allOk
    }

    private suspend fun runTask(
        repo: TaskRepository,
        task: TaskEntity,
        trigger: String,
        showInNotification: Boolean,
    ): Boolean {
        TaskRunBus.setPhase(task.id, "准备中")
        var ok = false
        try {
            val result = runner(repo).run(
                task = task,
                trigger = trigger,
                onPhase = { phase ->
                    TaskRunBus.setPhase(task.id, phase)
                    if (showInNotification) {
                        TaskRunNotifier.showProgress(this, "${task.name} · $phase")
                    }
                },
            )
            ok = result.isSuccess && (result.getOrNull()?.ok == true)
            // 手动刷新/一键同步也要提醒：以前只有后台定时同步发通知，手动跑出来的新集静默
            result.getOrNull()?.let { report ->
                if (report.restoredFiles > 0) {
                    com.ashchan.cloudsaver.notify.AlertSource.build(
                        taskId = task.id,
                        taskName = task.name,
                        seasonNo = task.seasonNo,
                        items = report.restoredEpisodes,
                        restoredFiles = report.restoredFiles,
                    )?.let { alert ->
                        com.ashchan.cloudsaver.notify.AlertPublisher.publish(
                            this,
                            listOf(alert),
                        )
                    }
                }
            }
            return ok
        } finally {
            TaskRunBus.clearPhase(task.id)
            TaskRunBus.reportFinished(task.id, ok)
        }
    }

    private fun repo(): TaskRepository = TaskRepository(AppDatabase.get(applicationContext))

    private fun runner(repo: TaskRepository): TaskRunner = TaskRunner.create(applicationContext, repo)

    // ---------------- 锁 --------------

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhuancunmiao:task").apply {
            setReferenceCounted(false)
            // 60 分钟兜底：正常结束会主动释放，这里防异常路径永久占锁
            acquire(60 * 60 * 1000L)
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onWifi = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!onWifi) return
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zhuancunmiao:task").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    companion object {
        const val ACTION_CANCEL = "com.ashchan.cloudsaver.action.CANCEL_TASK"
        private const val NOTIFY_ID_RUNNING = 2101
        private const val ACTION_RUN = "com.ashchan.cloudsaver.action.RUN_TASK"
        private const val ACTION_RUN_ALL = "com.ashchan.cloudsaver.action.RUN_ALL"
        private const val ACTION_RUN_RENAME = "com.ashchan.cloudsaver.action.RUN_RENAME"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TRIGGER = "trigger"

        /** 单条任务：切后台/熄屏后仍继续执行 */
        fun startTask(context: Context, taskId: Long) {
            val intent = Intent(context, TaskRunService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TRIGGER, RunRecordEntity.TRIGGER_MANUAL)
            }
            startCompat(context, intent)
        }

        /** 一键全部：串行跑所有启用任务 */
        fun startAll(context: Context, trigger: String = RunRecordEntity.TRIGGER_ALL) {
            val intent = Intent(context, TaskRunService::class.java).apply {
                action = ACTION_RUN_ALL
                putExtra(EXTRA_TRIGGER, trigger)
            }
            startCompat(context, intent)
        }

        /** 统一重命名：只改任务目录里的文件名，不动目录结构 */
        fun startRename(context: Context, taskId: Long) {
            val intent = Intent(context, TaskRunService::class.java).apply {
                action = ACTION_RUN_RENAME
                putExtra(EXTRA_TASK_ID, taskId)
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
