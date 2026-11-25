package com.ashchan.cloudsaver.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ashchan.cloudsaver.MainActivity
import com.ashchan.cloudsaver.sync.TaskRunService

/**
 * 任务运行的前台通知（常驻进度 + 结案提示），与「追剧更新」完全分开：
 * 渠道 [Channels.TASK_RUN] 是低优先级、不弹横幅、不占角标。
 */
object TaskRunNotifier {

    private const val ID_RUNNING = 2101
    private const val ID_DONE = 2102

    /** 启动/更新常驻通知：同 id 覆盖，不新建第二条 */
    fun showProgress(context: Context, phaseText: String) {
        Channels.ensureAll(context)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_RUNNING, progress(context, phaseText))
        }
    }

    /** 本轮结束：先记一条“完成/有失败”，再让服务自己撤掉常驻那条 */
    fun showFinished(context: Context, ok: Boolean) {
        Channels.ensureAll(context)
        val n = NotificationCompat.Builder(context, Channels.TASK_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (ok) "转存喵 · 任务完成" else "转存喵 · 任务有失败")
            .setContentText(if (ok) "本轮执行结束，可回 App 查看结果" else "本轮有失败项，回 App 查看原因")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_DONE, n) }
    }

    /** 前台服务 `startForeground` 要一条现成的 Notification */
    fun progress(context: Context, phaseText: String): Notification {
        val cancel = PendingIntent.getService(
            context,
            1,
            Intent(context, TaskRunService::class.java).setAction(TaskRunService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, Channels.TASK_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("转存喵 · 任务进行中")
            .setContentText(phaseText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(phaseText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, "取消", cancel)
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
