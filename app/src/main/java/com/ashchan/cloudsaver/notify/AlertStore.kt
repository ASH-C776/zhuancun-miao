package com.ashchan.cloudsaver.notify

import android.content.Context

/**
 * 「这批集已经提醒过了」的持久化记录。
 *
 * 为什么需要：定时同步、开机重排、用户手点刷新都可能把同一批集跑两遍；
 * 只靠“通知 id 覆盖”不算真的去过重（清掉通知后再跑还会弹）。
 *
 * 纯逻辑部分（[AlertStore]）不依赖 Android，单测直接注入两个 lambda；
 * Android 侧用 SharedPreferences 的 StringSet 存 `指纹@时间戳`，超过 [MAX_AGE_MS] 自动清掉。
 */
class AlertStore(
    private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit,
) {

    fun isNew(alert: ShowAlert): Boolean = load().none { fingerprintOf(it) == alert.fingerprint }

    fun mark(alert: ShowAlert, now: Long = System.currentTimeMillis()) {
        save(load() + "${alert.fingerprint}@$now")
    }

    fun clear() = save(emptySet())

    companion object {
        private const val PREFS = "notify_alerts"
        private const val KEY = "seen"
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

        internal fun fingerprintOf(entry: String): String = entry.substringBeforeLast('@', entry)

        fun of(context: Context): AlertStore {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AlertStore(
                load = {
                    val raw = prefs.getStringSet(KEY, emptySet()).orEmpty()
                    prune(raw).also { kept ->
                        if (kept != raw) prefs.edit().putStringSet(KEY, kept).apply()
                    }
                },
                save = { entries -> prefs.edit().putStringSet(KEY, entries).apply() },
            )
        }

        /** 清掉过期条目；没带时间戳的老格式条目一律丢弃（重构前没有这类数据） */
        internal fun prune(raw: Set<String>, now: Long = System.currentTimeMillis()): Set<String> =
            raw.filter { entry ->
                val ts = entry.substringAfterLast('@', "").toLongOrNull() ?: return@filter false
                now - ts <= MAX_AGE_MS
            }.toSet()
    }
}
