package com.ashchan.cloudsaver.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ashchan.cloudsaver.core.model.DriveType

/**
 * 登录凭证加密存储。
 * 使用 EncryptedSharedPreferences（AES-256-GCM + 主密钥存 Android Keystore），
 * Token / Cookie 不明文落盘。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cloudsaver_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 保存某网盘的登录凭证（光鸭：access_token|refresh_token；夸克：cookie） */
    fun saveCredential(driveType: DriveType, credential: String) {
        prefs.edit().putString(keyOf(driveType), credential).apply()
    }

    /** 读取某网盘的登录凭证，无则返回 null */
    fun loadCredential(driveType: DriveType): String? =
        prefs.getString(keyOf(driveType), null)

    /** 清除某网盘登录态 */
    fun clearCredential(driveType: DriveType) {
        prefs.edit().remove(keyOf(driveType)).apply()
    }

    /** 保存扩展字段（光鸭的 refresh_token / device_id 等） */
    fun saveExtra(driveType: DriveType, field: String, value: String) {
        prefs.edit().putString("${keyOf(driveType)}::$field", value).apply()
    }

    fun loadExtra(driveType: DriveType, field: String): String? =
        prefs.getString("${keyOf(driveType)}::$field", null)

    private fun keyOf(driveType: DriveType): String = "credential_${driveType.name.lowercase()}"
}
