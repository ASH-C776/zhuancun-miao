package com.ashchan.cloudsaver.notify

/** 这条提醒该以什么形态出现 */
enum class AlertLevel {
    /** 高优先级：系统会弹横幅/悬浮（前提是渠道级别够高、用户没关横幅） */
    BANNER,

    /** 只进通知栏，不打扰（免打扰、锁屏、App 在前台时） */
    NORMAL,

    /** 不发 */
    SILENT,
}

/** 为什么是这个形态 —— 写进日志，答“为什么没弹” */
enum class AlertReason {
    NEW_EPISODES,
    APP_FOREGROUND,
    SCREEN_LOCKED,
    DND_ON,
    ALREADY_NOTIFIED,
    NO_NEW_EPISODES,
}

data class AlertDecision(val level: AlertLevel, val reason: AlertReason) {
    val publish: Boolean get() = level != AlertLevel.SILENT
}

/** 判定需要的环境信息（纯数据，方便单测；Android 侧由 [AlertPublisher] 采集） */
data class AlertEnv(
    /** App 是否在前台：前台时系统会压掉横幅，我们改成只进通知栏 */
    val appForeground: Boolean = false,
    val screenLocked: Boolean = false,
    /** 免打扰（zen）是否生效 */
    val dndOn: Boolean = false,
)

/**
 * 「发不发、发哪一档」的唯一判定点。
 *
 * 顺序（先命中先返回，越靠前优先级越高）：
 * 没有新集 → 已提醒过 → 免打扰 → 锁屏 → App 在前台 → 横幅
 */
object AlertPolicy {

    fun decide(alert: ShowAlert?, env: AlertEnv, alreadyNotified: Boolean): AlertDecision = when {
        alert == null || alert.count <= 0 -> AlertDecision(AlertLevel.SILENT, AlertReason.NO_NEW_EPISODES)
        alreadyNotified -> AlertDecision(AlertLevel.SILENT, AlertReason.ALREADY_NOTIFIED)
        env.dndOn -> AlertDecision(AlertLevel.NORMAL, AlertReason.DND_ON)
        env.screenLocked -> AlertDecision(AlertLevel.NORMAL, AlertReason.SCREEN_LOCKED)
        env.appForeground -> AlertDecision(AlertLevel.NORMAL, AlertReason.APP_FOREGROUND)
        else -> AlertDecision(AlertLevel.BANNER, AlertReason.NEW_EPISODES)
    }
}
