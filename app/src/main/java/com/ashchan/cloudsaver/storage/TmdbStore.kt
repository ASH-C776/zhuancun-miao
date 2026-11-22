package com.ashchan.cloudsaver.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TMDB 凭证存储（可选功能）。
 *
 * 与网盘凭证一样走 EncryptedSharedPreferences：TMDB Key 也是账号资产，
 * 明文落盘等于送给别人刷配额。兼容 v3 api_key 与 v4 Read Access Token，
 * 原样保存，由 TmdbClient 判断类型。
 */
class TmdbStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cloudsaver_tmdb",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveCredential(value: String) {
        prefs.edit().putString(KEY_CREDENTIAL, value.trim()).apply()
    }

    fun loadCredential(): String? =
        prefs.getString(KEY_CREDENTIAL, null)?.takeIf { it.isNotBlank() }

    /**
     * 接口地址。默认官方 `https://api.themoviedb.org`，
     * 但国内直连会超时（实测 15 秒无响应），所以允许改成镜像 / 自建反代。
     */
    fun saveBaseUrl(value: String) {
        val v = value.trim().trimEnd('/')
        if (v.isBlank() || v == DEFAULT_BASE_URL) {
            prefs.edit().remove(KEY_BASE_URL).apply()
        } else {
            prefs.edit().putString(KEY_BASE_URL, v).apply()
        }
    }

    fun loadBaseUrl(): String =
        prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    fun clear() {
        prefs.edit().remove(KEY_CREDENTIAL).remove(KEY_BASE_URL).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org"
        private const val KEY_CREDENTIAL = "tmdb_credential"
        private const val KEY_BASE_URL = "tmdb_base_url"
    }
}
