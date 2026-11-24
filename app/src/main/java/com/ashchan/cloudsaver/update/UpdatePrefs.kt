package com.ashchan.cloudsaver.update

import android.content.Context

/**
 * 更新检测的节流信息。**单独一个 prefs 文件**，不混进业务配置：
 * 备份/比对业务配置时，「上次检查时间」这种无关数据会让人无法判读。
 */
class UpdatePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 上次检测时刻；0 = 从没查过 */
    var lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_CHECK, v).apply()

    /** 用户点「取消」时记住的版本号：同版本不再反复打扰，更高版本仍然会弹 */
    var dismissedCode: Int
        get() = prefs.getInt(KEY_DISMISSED, 0)
        set(v) = prefs.edit().putInt(KEY_DISMISSED, v).apply()

    /** 距上次检测是否还不满 24 小时 */
    fun isThrottled(now: Long): Boolean {
        val last = lastCheckMs
        return last > 0 && now - last < INTERVAL_MS
    }

    companion object {
        private const val FILE = "update_prefs"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_DISMISSED = "dismissed_code"

        const val INTERVAL_MS = 24L * 60 * 60 * 1000
        /** 启动后等 5 秒再查，避开首屏高峰 */
        const val START_DELAY_MS = 5_000L
    }
}
