package com.ashchan.cloudsaver.storage

import android.content.Context
import android.content.SharedPreferences
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.rename.BuiltinRules
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 重命名规则持久化（含内置规则 + 用户自定义规则的启用状态与增删改）。
 * 规则非机密数据，使用普通 SharedPreferences + Gson 序列化。
 */
class RuleStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloudsaver_rules", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 读取全部规则：内置（按用户是否覆盖/启用） + 自定义 */
    fun loadRules(): List<RenameRule> {
        val customJson = prefs.getString(KEY_CUSTOM, null)
        val custom: List<RenameRule> = if (customJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                gson.fromJson<List<RenameRule>>(customJson, RULE_LIST_TYPE)
            }.getOrNull() ?: emptyList()
        }

        val overridesJson = prefs.getString(KEY_OVERRIDES, null)
        val overrides: Map<String, RenameRule> = if (overridesJson.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                gson.fromJson<Map<String, RenameRule>>(overridesJson, RULE_MAP_TYPE)
            }.getOrNull() ?: emptyMap()
        }

        val builtins = BuiltinRules.defaults.map { builtin ->
            overrides[builtin.key] ?: builtin
        }
        return builtins + custom
    }

    /** 保存自定义规则列表 */
    fun saveCustomRules(rules: List<RenameRule>) {
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(rules)).apply()
    }

    /** 更新内置规则的启用状态（不修改内置 pattern/replace） */
    fun setBuiltinEnabled(key: String, enabled: Boolean) {
        val overrides = loadOverrides().toMutableMap()
        val current = overrides[key] ?: BuiltinRules.byKey(key) ?: return
        overrides[key] = current.copy(enabled = enabled)
        prefs.edit().putString(KEY_OVERRIDES, gson.toJson(overrides)).apply()
    }

    /** 覆盖内置规则内容（自定义增删改内置规则） */
    fun upsertBuiltin(rule: RenameRule) {
        val overrides = loadOverrides().toMutableMap()
        overrides[rule.key] = rule
        prefs.edit().putString(KEY_OVERRIDES, gson.toJson(overrides)).apply()
    }

    fun resetBuiltins() {
        prefs.edit().remove(KEY_OVERRIDES).apply()
    }

    private fun loadOverrides(): Map<String, RenameRule> {
        val json = prefs.getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, RenameRule>>(json, RULE_MAP_TYPE)
        }.getOrNull() ?: emptyMap()
    }

    companion object {
        private const val KEY_CUSTOM = "custom_rules"
        private const val KEY_OVERRIDES = "builtin_overrides"
        private val RULE_LIST_TYPE = object : TypeToken<List<RenameRule>>() {}.type
        private val RULE_MAP_TYPE = object : TypeToken<Map<String, RenameRule>>() {}.type
    }
}
