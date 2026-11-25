package com.ashchan.cloudsaver.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 通知渠道的唯一出口。
 *
 * 三个渠道：
 * - [EPISODE_ALERTS] 追剧更新（高，可横幅/悬浮，带角标）
 * - [TASK_RUN]       任务运行中/完成（低，常驻 silently）
 * - 版本更新目前只在 App 内提示，不建渠道；将来要做下载进度通知再加 `app_update`。
 *
 * 迁移：旧实现为了让通知能悬浮，另开过 `auto_sync_v2`；重构后统一到 `episode_alerts`，
 * 并把旧渠道 `auto_sync`、`auto_sync_v2` 删除（删前把「是否被静音」记到 prefs 作为依据）。
 * Android 不允许改已有渠道的级别，所以只能换 id；旧渠道留着只会多一个看不懂的开关。
 */
object Channels {

    const val EPISODE_ALERTS: String = "episode_alerts"
    const val TASK_RUN: String = "task_run"

    /** 已废弃渠道：只用于迁移清理，不再投递 */
    private val LEGACY_IDS = listOf("auto_sync", "auto_sync_v2")

    private const val PREFS = "notify_migration"
    private const val KEY_LEGACY_MUTED = "legacy_auto_sync_muted"
    private const val KEY_MIGRATED = "legacy_channels_removed"

    /** App 启动 / 服务启动时调用，幂等 */
    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createEpisodeChannel(context, nm)
        createTaskRunChannel(nm)
        migrateLegacy(context, nm)
    }

    private fun createEpisodeChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(EPISODE_ALERTS) != null) return
        val muted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_MUTED, false)
        // 旧渠道被静音过：新渠道降成「默认」（有声音、不横幅），尊重用户当初的选择；
        // 否则「高」，系统才会弹横幅/悬浮。
        val importance = if (muted) NotificationManager.IMPORTANCE_DEFAULT
        else NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(EPISODE_ALERTS, "追剧更新", importance).apply {
            description = "有剧集更新时提醒，一部剧一条；想安静可以单独关掉这个渠道"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun createTaskRunChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(TASK_RUN) != null) return
        nm.createNotificationChannel(
            NotificationChannel(TASK_RUN, "任务运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "转存任务运行时的常驻进度与完成提醒"
                setShowBadge(false)
            },
        )
    }

    private fun migrateLegacy(context: Context, nm: NotificationManager) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val muted = LEGACY_IDS.any { id ->
            nm.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        prefs.edit().putBoolean(KEY_LEGACY_MUTED, muted).apply()
        LEGACY_IDS.forEach { id -> runCatching { nm.deleteNotificationChannel(id) } }
        // 旧渠道里的残留通知一起清掉，避免升级后还挂着旧样式的“自动刷新完成”
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    /** 旧渠道是否被静音过（迁移记录，设置页可用来解释“为什么这次只有声音没横幅”） */
    fun legacyWasMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LEGACY_MUTED, false)
}
