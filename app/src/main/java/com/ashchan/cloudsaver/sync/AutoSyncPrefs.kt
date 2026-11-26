package com.ashchan.cloudsaver.sync

import android.content.Context

/**
 * 自动刷新的配置与运行状态，全部落在独立的 `auto_sync_prefs` 里。
 *
 * 为什么要独立一份 SharedPreferences：
 * - 不往 Room 加表/加字段，避免一次数据库迁移（v11 不动，零升级风险）；
 * - 主设置页的 `app_settings` 只放外观，两边互不干扰，将来摘掉自动刷新直接删这个文件。
 */
class AutoSyncPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 总开关：关着时不排任何 Work、不响应插电、不发通知 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 每天刷新时刻，恒为规范化后的 `HH:mm` 列表（最多 4 条）；用户删空时允许为空 */
    var slots: List<String>
        get() = if (sp.contains(KEY_SLOTS)) {
            AutoSyncConfig.normalize(sp.getString(KEY_SLOTS, "").orEmpty())
        } else {
            AutoSyncConfig.normalize(AutoSyncConfig.DEFAULT_SLOTS)
        }
        set(value) = sp.edit().putString(KEY_SLOTS, AutoSyncConfig.toCsv(value)).apply()

    /** 跑完发通知；默认开，但只在「有新集 / 有失败」时才真的发 */
    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY, value).apply()

    /**
     * 上一次自动运行的开始时间。用「开始」而不是「结束」：
     * 长任务（改名限流）会把结束时间推后十几分钟，用结束时间会让冷却越跑越长。
     */
    var lastRunStartAt: Long
        get() = sp.getLong(KEY_LAST_START, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_START, value).apply()

    var lastRunEndAt: Long
        get() = sp.getLong(KEY_LAST_END, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_END, value).apply()

    /** 上一次自动运行成功写入的新文件数（集数） */
    var lastRunNew: Int
        get() = sp.getInt(KEY_LAST_NEW, 0)
        set(value) = sp.edit().putInt(KEY_LAST_NEW, value).apply()

    /** 上一次自动运行参与的任务数 */
    var lastRunTotal: Int
        get() = sp.getInt(KEY_LAST_TOTAL, 0)
        set(value) = sp.edit().putInt(KEY_LAST_TOTAL, value).apply()

    /** 上一次自动运行是否全绿（失败数 = 0） */
    var lastRunOk: Boolean
        get() = sp.getBoolean(KEY_LAST_OK, true)
        set(value) = sp.edit().putBoolean(KEY_LAST_OK, value).apply()

    /** 正在跑（Worker 开始时置 true、结束时置 false）：进 App 能看出“现在就在刷新” */
    var running: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_RUNNING, value).apply()

    /** 本轮「计划触发时刻」；与开始时间对比就能看出系统把它延后了多久 */
    var lastPlanAt: Long
        get() = sp.getLong(KEY_LAST_PLAN, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_PLAN, value).apply()

    /** 最近 24 小时的运行次数（滑动窗口：窗口起点超过 24h 就归零） */
    fun runCount24h(nowMillis: Long): Int {
        val start = sp.getLong(KEY_WINDOW_START, 0L)
        if (start <= 0L || nowMillis - start >= 24L * 60 * 60 * 1000) return 0
        return sp.getInt(KEY_WINDOW_COUNT, 0)
    }

    /** 记一轮运行：时间戳 + 结果 + 24h 计数，一次 commit 落盘 */
    fun recordRun(startAt: Long, endAt: Long, total: Int, newCount: Int, ok: Boolean) {
        val start = sp.getLong(KEY_WINDOW_START, 0L)
        val expired = start <= 0L || endAt - start >= 24L * 60 * 60 * 1000
        val count = if (expired) 1 else sp.getInt(KEY_WINDOW_COUNT, 0) + 1
        sp.edit()
            .putLong(KEY_LAST_START, startAt)
            .putLong(KEY_LAST_END, endAt)
            .putInt(KEY_LAST_TOTAL, total)
            .putInt(KEY_LAST_NEW, newCount)
            .putBoolean(KEY_LAST_OK, ok)
            .putLong(KEY_WINDOW_START, if (expired) endAt else start)
            .putInt(KEY_WINDOW_COUNT, count)
            .apply()
    }

    /** 清空运行状态（关总开关时调用，状态卡不至于显示上周的数字） */
    fun clearState() {
        sp.edit()
            .remove(KEY_LAST_START)
            .remove(KEY_LAST_END)
            .remove(KEY_LAST_NEW)
            .remove(KEY_LAST_TOTAL)
            .remove(KEY_LAST_OK)
            .remove(KEY_RUNNING)
            .remove(KEY_LAST_PLAN)
            .remove(KEY_WINDOW_START)
            .remove(KEY_WINDOW_COUNT)
            .apply()
    }

    private companion object {
        const val NAME = "auto_sync_prefs"

        const val KEY_ENABLED = "enabled"
        const val KEY_SLOTS = "slots"
        const val KEY_NOTIFY = "notify_enabled"

        const val KEY_LAST_START = "last_run_start"
        const val KEY_LAST_END = "last_run_end"
        const val KEY_LAST_NEW = "last_run_new"
        const val KEY_LAST_TOTAL = "last_run_total"
        const val KEY_LAST_OK = "last_run_ok"
        const val KEY_RUNNING = "running"
        const val KEY_LAST_PLAN = "last_plan_at"

        const val KEY_WINDOW_START = "window_start"
        const val KEY_WINDOW_COUNT = "window_count"
    }
}
