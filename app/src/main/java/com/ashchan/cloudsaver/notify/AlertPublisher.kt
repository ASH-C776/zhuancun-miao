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
 * 角标：渠道 `setShowBadge(true)` + 每条 `setNumber(集数)`，桌面支持时图标显示未读数；
 * 进入 App（[clearEpisodeAlerts]）后清掉。
 */
object AlertPublisher {

    private const val NOTIFY_BASE = 3000
    private const val TAG = "Notify"

    fun publish(context: Context, alerts: List<ShowAlert>) {
        if (alerts.isEmpty()) return
        if (!canNotify(context)) {
            android.util.Log.i(TAG, "跳过提醒：通知权限未授予或 App 通知被关")
            return
        }
        Channels.ensureAll(context)
        val env = readEnv(context)
        val store = AlertStore.of(context)
        val manager = NotificationManagerCompat.from(context)

        alerts.forEach { alert ->
            val decision = AlertPolicy.decide(alert, env, alreadyNotified = !store.isNew(alert))
            android.util.Log.i(
                TAG,
                "task=${alert.taskId} ${alert.notificationTitle} level=${decision.level} reason=${decision.reason}",
            )
            if (!decision.publish) return@forEach
            store.mark(alert)
            runCatching { manager.notify(notifyIdOf(alert.taskId), build(context, alert, decision)) }
                .onFailure { android.util.Log.w(TAG, "投递失败", it) }
        }
    }

    /** 进 App 时调用：清掉追剧通知与角标（去重指纹保留，避免下次同步又弹一遍） */
    fun clearEpisodeAlerts(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
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
            // 角标数字 = 本轮新增集数
            .setNumber(alert.count)
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
