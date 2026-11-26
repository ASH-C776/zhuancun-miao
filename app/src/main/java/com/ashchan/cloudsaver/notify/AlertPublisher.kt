package com.ashchan.cloudsaver.notify

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ashchan.cloudsaver.MainActivity

/** App 是否在前台：由 MainActivity 的 onResume/onPause 维护（前台时不硬弹横幅） */
object AppForeground {
    @Volatile
    var value: Boolean = false
}

/**
 * 剧集提醒的唯一投递点。
 *
 * 流程固定：判定（[AlertPolicy]）→ 去重（[AlertStore]）→ 投递。
 * 日志里会打 `decision/reason`，拿 logcat 就能回答“为什么没弹”。
 *
 * 角标：渠道 `setShowBadge(true)` + 每条通知 `setNumber(1)`，
 * 桌面会把「有几条通知」当成数字显示（而不是某部剧一次更新了多少集）；
 * 进入 App（[clearEpisodeAlerts]）后清掉。
 */
object AlertPublisher {

    private const val NOTIFY_BASE = 3000
    private const val TAG = "Notify"
    private const val PREFS = "notify_publisher"
    private const val KEY_IDS = "posted_ids"

    /** 两条横幅之间隔多久：太密系统只弹第一条 */
    private const val STAGGER_MS = 1200L

    /**
     * 逐条投递。**故意一条一条隔开发**：一次同时 notify 多条时，系统只弹第一条横幅，
     * 后面的会被压成静默（用户看到的现象就是“有的剧更新了却没弹”）。
     * 间隔 [STAGGER_MS] 让每条都有机会成为独立的横幅。
     */
    suspend fun publish(context: Context, alerts: List<ShowAlert>) {
        if (alerts.isEmpty()) return
        if (!canNotify(context)) {
            android.util.Log.i(TAG, "跳过提醒：通知权限未授予或 App 通知被关")
            return
        }
        Channels.ensureAll(context)
        val env = readEnv(context)
        val store = AlertStore.of(context)
        val manager = NotificationManagerCompat.from(context)

        alerts.forEachIndexed { index, alert ->
            val decision = AlertPolicy.decide(alert, env, alreadyNotified = !store.isNew(alert))
            android.util.Log.i(
                TAG,
                "task=${alert.taskId} ${alert.notificationTitle} level=${decision.level} reason=${decision.reason}",
            )
            if (!decision.publish) {
                AlertLog.record(context, alert, decision, "未投递")
                return@forEachIndexed
            }
            // 用户可能把「追剧更新」渠道单独关掉了：这种情况系统会静默吞掉通知，
            // 不能再把指纹记为「已提醒」，否则开了渠道也永远不弹
            if (isChannelBlocked(context)) {
                AlertLog.record(context, alert, decision, "渠道被关闭")
                android.util.Log.i(TAG, "跳过提醒：追剧更新渠道被关闭")
                return@forEachIndexed
            }
            val id = notifyIdOf(alert.taskId)
            val posted = runCatching { manager.notify(id, build(context, alert, decision)) }
            posted
                .onSuccess {
                    // 只有真的投递成功才记指纹；否则下次同步再也弹不出来
                    store.mark(alert)
                    savePostedIds(context, postedIds(context) + id.toString())
                    AlertLog.record(context, alert, decision, "已发出")
                }
                .onFailure {
                    android.util.Log.w(TAG, "投递失败", it)
                    AlertLog.record(context, alert, decision, "投递失败：${it.message ?: "未知"}")
                }
            // 最后一条不用等
            if (index < alerts.lastIndex) kotlinx.coroutines.delay(STAGGER_MS)
        }
    }

    /** 追剧更新渠道是否存在但重要度为 NONE（用户手动关掉） */
    private fun isChannelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val channel = nm.getNotificationChannel(Channels.EPISODE_ALERTS) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** 进 App 时调用：清掉追剧通知与角标（去重指纹保留，避免下次同步又弹一遍） */
    fun clearEpisodeAlerts(context: Context) {
        // 只清自己发的那几条：不能用 cancelAll，否则会把正在跑的前台服务通知一起清掉
        val manager = NotificationManagerCompat.from(context)
        postedIds(context).forEach { raw ->
            raw.toIntOrNull()?.let { id -> runCatching { manager.cancel(id) } }
        }
        savePostedIds(context, emptySet())
    }

    /** 记下当前还挂在通知栏的追剧提醒 id，供进 App 时精确清理 */
    private fun postedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet()).orEmpty()

    private fun savePostedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_IDS, ids).apply()
    }

    private fun build(
        context: Context,
        alert: ShowAlert,
        decision: AlertDecision,
    ): android.app.Notification {
        val priority = when (decision.level) {
            AlertLevel.BANNER -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        return NotificationCompat.Builder(context, Channels.EPISODE_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(alert.notificationTitle)
            .setContentText(alert.notificationText)
            // 角标按「通知条数」累加，所以每条固定 1；集数已经在标题里写清楚了
            .setNumber(1)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            // 同一批集重发时仍要出声/弹横幅，而不是被系统当成“重复更新”压掉
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 同一部剧固定一个 id：下次更新覆盖上一条，不越堆越多 */
    private fun notifyIdOf(taskId: Long): Int = NOTIFY_BASE + (taskId % 1000L).toInt()

    private fun readEnv(context: Context): AlertEnv {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL } == true
        } else {
            false
        }
        return AlertEnv(
            appForeground = AppForeground.value,
            screenLocked = keyguard?.isKeyguardLocked == true,
            dndOn = dnd,
        )
    }

    /** Android 13+ 需要运行时授权；未授权时静默不发，避免抛 SecurityException */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
