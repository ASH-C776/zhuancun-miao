package com.ashchan.cloudsaver.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 一条转存记录（任务历史）。
 * 记录本次转存的关键参数，用于「记录页」回看与「再转一次」一键回填。
 */
data class TransferRecord(
    val id: Long,          // 完成时间戳（ms），同时作唯一 id
    val drive: String,     // DriveType.name（"GUANGYA" / "QUARK"）
    val taskName: String,  // 剧名（可为空）
    val link: String,      // 分享链接原文
    val path: String,      // 目标路径显示文案（如 "电视剧 / 庆余年"）
    val ruleName: String,  // 命名规则显示文案（如 "通用剧集命名"）
    val successCount: Int,
    val failCount: Int,
    val total: Int,
) {
    val allSuccess: Boolean get() = failCount == 0 && successCount > 0
}

/**
 * 转存记录持久化（SharedPreferences + Gson，最多保留 50 条）。
 * 与 RuleStore 同套路：非机密数据，普通 SP 存储。
 */
class RecordStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloudsaver_records", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 读取全部记录（新→旧排序） */
    fun load(): List<TransferRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TransferRecord>>(json, RECORD_LIST_TYPE)
        }.getOrNull() ?: emptyList()
    }

    /** 追加一条记录，返回新列表（新→旧）。
     *  同一条分享链接（归一化后相等）视为同一条记录：原地替换而非新增，
     *  这样历史里始终只有一条，展示名取最后一次转存的命名。 */
    fun add(record: TransferRecord): List<TransferRecord> {
        val list = load().toMutableList()
        val key = normalizeLink(record.link)
        if (key.isNotBlank()) {
            val idx = list.indexOfFirst { normalizeLink(it.link) == key }
            if (idx >= 0) list[idx] = record else list.add(record)
        } else {
            list.add(record)
        }
        val sorted = list.sortedByDescending { it.id }.take(MAX)
        prefs.edit().putString(KEY_RECORDS, gson.toJson(sorted)).apply()
        return sorted
    }

    /** 链接归一化：去首尾空白、去结尾斜杠、转小写，避免同链接因格式差异被当成多条 */
    private fun normalizeLink(link: String): String =
        link.trim().trimEnd('/').lowercase()

    /** 删除单条，返回新列表 */
    fun remove(id: Long): List<TransferRecord> {
        val list = load().filterNot { it.id == id }
        prefs.edit().putString(KEY_RECORDS, gson.toJson(list)).apply()
        return list
    }

    /** 清空全部，返回空列表 */
    fun clear(): List<TransferRecord> {
        prefs.edit().remove(KEY_RECORDS).apply()
        return emptyList()
    }

    companion object {
        private const val KEY_RECORDS = "records"
        private const val MAX = 50
        private val RECORD_LIST_TYPE = object : TypeToken<List<TransferRecord>>() {}.type
    }
}
