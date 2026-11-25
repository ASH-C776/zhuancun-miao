package com.ashchan.cloudsaver.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ashchan.cloudsaver.MainActivity
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.db.AppDatabase
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.storage.TmdbStore
import com.ashchan.cloudsaver.transfer.TaskRunner
import com.ashchan.cloudsaver.transfer.RenamePass
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
 * 释放时机：任务结束 / 失败 / 用户点通知栏「取消」都会走 `finally` 释放全部锁并停服务，
 * 空闲时零唤醒、零常驻。
 */
class TaskRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        // 单飞：已有任务在跑时忽略新的启动请求，避免同一网盘并发
        if (job?.isActive == true) return START_NOT_STICKY

        val runAll = intent?.action == ACTION_RUN_ALL
        val rename = intent?.action == ACTION_RUN_RENAME
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, 0L) ?: 0L
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: RunRecordEntity.TRIGGER_MANUAL

        startForeground(NOTIFY_ID_RUNNING, ongoing("准备中"))
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
                notifyDone(okAny)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
                credentialStore = CredentialStore(applicationContext),
                ruleStore = RuleStore(applicationContext),
            ).run(task) { phase ->
                TaskRunBus.setPhase(task.id, phase)
                updateNotification("${task.name} · $phase")
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
        if (tasks.isEmpty()) return false
        var allOk = true
        tasks.forEachIndexed { index, task ->
            updateNotification("同步 ${index + 1}/${tasks.size}：${task.name}")
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
                    if (showInNotification) updateNotification("${task.name} · $phase")
                },
            )
            ok = result.isSuccess && (result.getOrNull()?.ok == true)
            return ok
        } finally {
            TaskRunBus.clearPhase(task.id)
            TaskRunBus.reportFinished(task.id, ok)
        }
    }

    private fun repo(): TaskRepository = TaskRepository(AppDatabase.get(applicationContext))

    private fun runner(repo: TaskRepository): TaskRunner = TaskRunner(
        repo = repo,
        credentialStore = CredentialStore(applicationContext),
        tmdbStore = TmdbStore(applicationContext),
        ruleStore = RuleStore(applicationContext),
    )

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

    // ---------------- 通知 --------------

    private fun ongoing(phaseText: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, TaskRunService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("转存喵 · 任务进行中")
            .setContentText(phaseText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(phaseText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "取消", cancel)
            .build()
    }

    private fun updateNotification(phaseText: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFY_ID_RUNNING, ongoing(phaseText))
        }
    }

    private fun notifyDone(ok: Boolean) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel()
            val open = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(if (ok) "转存喵 · 任务完成" else "转存喵 · 任务有失败")
                .setContentText(if (ok) "本轮执行结束，可回 App 查看结果" else "本轮有失败项，回 App 查看原因")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(NOTIFY_ID_DONE, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "任务运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "转存任务运行时的常驻进度与完成提醒"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "task_run"
        private const val NOTIFY_ID_RUNNING = 2101
        private const val NOTIFY_ID_DONE = 2102
        private const val ACTION_RUN = "com.ashchan.cloudsaver.action.RUN_TASK"
        private const val ACTION_RUN_ALL = "com.ashchan.cloudsaver.action.RUN_ALL"
        private const val ACTION_RUN_RENAME = "com.ashchan.cloudsaver.action.RUN_RENAME"
        private const val ACTION_CANCEL = "com.ashchan.cloudsaver.action.CANCEL_TASK"
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



