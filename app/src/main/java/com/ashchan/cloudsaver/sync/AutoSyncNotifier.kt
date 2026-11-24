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
import com.ashchan.cloudsaver.R

/**
 * 自动刷新的通知：独立渠道 `auto_sync`，与自更新的通知互不影响
 * （用户嫌吵可以单独关掉这个渠道，更新提醒还在）。
 */
object AutoSyncNotifier {

    const val CHANNEL_ID: String = "auto_sync"
    private const val NOTIFY_ID: Int = 1001

    /** 只在「有新集 / 有失败」时调用；纯空转不进这里 */
    fun notifyDone(context: Context, summary: AutoSyncSummary) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        if (!canNotify(context)) return

        val title = if (summary.failed == 0) {
            "转存喵 · 自动刷新完成"
        } else {
            "转存喵 · 自动刷新有失败"
        }
        val body = buildString {
            when {
                summary.newFiles > 0 -> append("新增 ${summary.newFiles} 集")
                else -> append("没有新增")
            }
            append(" · ${summary.total} 个任务")
            summary.firstError?.let { append("\n$it") }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification) }
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

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动刷新",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "后台自动刷新完成时提醒（仅在有新集或有失败时）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
