package com.ashchan.cloudsaver.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 插电即刷新。
 *
 * `ACTION_POWER_CONNECTED` 属于 Android 8+ 后台广播限制的白名单，
 * 静态注册仍能收到（不需要动态注册，也不需要前台服务）。
 * 收到后不直接干活，只交给统一调度入口，由它判开关与 3 小时冷却。
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
        runCatching {
            AutoSyncScheduler.requestRun(context.applicationContext, AutoSyncScheduler.REASON_CHARGING)
        }
    }
}
