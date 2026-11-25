package com.ashchan.cloudsaver.sync

import android.Manifest
import android.app.NotificationChannel
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

/**
 * 自动刷新的通知：**一部剧一条**，独立渠道 `auto_sync_v2`。
 *
 * 渠道为什么换了 id：Android 8+ 的规定是渠道建好之后，重要性只能由用户在系统设置里改，
 * 代码里再 `createNotificationChannel` 传新重要性也不会生效。旧渠道 `auto_sync` 是
 * `IMPORTANCE_DEFAULT`（不悬浮），想升级到「高」（悬浮 + 横幅）只能换一个新的渠道 id。
 * 代价：以前手动关过「自动刷新」通知的人会重新收到，需要再关一次。
 */
object AutoSyncNotifier {

    const val CHANNEL_ID: String = "auto_sync_v2"

    /** 旧渠道：只保留常量做迁移说明，不再往里发 */
    const val LEGACY_CHANNEL_ID: String = "auto_sync"

    private const val NOTIFY_BASE: Int = 2000
    private const val FALLBACK_ID: Int = NOTIFY_BASE - 1

    /**
     * 每部有新集的剧发一条通知。
     * 没有逐剧明细（理论上不该发生）时退回一条汇总，保证「有新增就一定看得到」。
     */
    fun notifyDone(context: Context, summary: AutoSyncSummary) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val manager = NotificationManagerCompat.from(context)

        if (summary.updates.isEmpty()) {
            val body = "新增 ${summary.newFiles} 集"
            runCatching {
                manager.notify(FALLBACK_ID, base(context, "转存喵 · 新增 $body")
                    .setContentText(body)
                    .build())
            }
            return
        }

        summary.updates.forEach { update ->
            val notification = base(context, update.notificationTitle)
                .setContentText(update.notificationBody)
                .setWhen(System.currentTimeMillis())
                .build()
            runCatching { manager.notify(notifyIdOf(update.taskId), notification) }
        }
    }

    /** 同一条剧重复发时覆盖上一条，不越堆越多 */
    private fun notifyIdOf(taskId: Long): Int = NOTIFY_BASE + (taskId % 10000L).toInt()

    private fun base(context: Context, title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 点通知回到 App 的任务列表；用 SINGLE_TOP 避免又开一个新页面
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setAutoCancel(true)

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

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "追剧更新",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "有剧集更新时提醒，一部剧一条；想静音可以单独关掉这个渠道"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
