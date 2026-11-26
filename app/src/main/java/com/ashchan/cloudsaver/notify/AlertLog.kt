package com.ashchan.cloudsaver.notify

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近 20 条提醒的判定记录（时间 / 剧名 / 档位 / 原因）。
 *
 * 目的：用户说“这条没弹”时，不用连电脑抓 logcat —— App 设置页里就能看到
 * 「几点、哪部剧、决定了什么档位、为什么」。
 */
object AlertLog {

    private const val PREFS = "notify_log"
    private const val KEY = "recent"
    private const val MAX = 20
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun record(context: Context, alert: ShowAlert, decision: AlertDecision, outcome: String = "") {
        val line = buildString {
            append(fmt.format(Date())).append(" · ").append(alert.notificationTitle)
            append(" · ").append(labelOf(decision.level)).append("（").append(reasonOf(decision.reason)).append("）")
            if (outcome.isNotBlank()) append(" · ").append(outcome)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, "").orEmpty()
        val merged = (listOf(line) + old.lines().filter { it.isNotBlank() })
            .take(MAX)
            .joinToString("\n")
        prefs.edit().putString(KEY, merged).apply()
    }

    fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .lines().filter { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun labelOf(level: AlertLevel): String = when (level) {
        AlertLevel.BANNER -> "横幅"
        AlertLevel.NORMAL -> "只进通知栏"
        AlertLevel.SILENT -> "不发"
    }

    fun reasonOf(reason: AlertReason): String = when (reason) {
        AlertReason.NEW_EPISODES -> "有新集"
        AlertReason.APP_FOREGROUND -> "App 在前台"
        AlertReason.SCREEN_LOCKED -> "锁屏中"
        AlertReason.DND_ON -> "免打扰开启"
        AlertReason.ALREADY_NOTIFIED -> "这一批已提醒过"
        AlertReason.NO_NEW_EPISODES -> "没有新集"
    }
}
