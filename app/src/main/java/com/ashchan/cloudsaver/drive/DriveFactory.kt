package com.ashchan.cloudsaver.drive

import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.drive.quark.QuarkAdapter

/**
 * 网盘适配器工厂。
 *
 * 光鸭凭证格式："access_token||refresh_token"（双竖线分隔）
 * 夸克凭证格式：cookie 原文
 */
object DriveFactory {

    fun create(type: DriveType, credential: String): CloudDriveAdapter = when (type) {
        DriveType.GUANGYA -> {
            val (access, refresh) = splitCredential(credential)
            GuangYaAdapter(accessToken = access, refreshToken = refresh)
        }
        DriveType.QUARK -> QuarkAdapter(cookie = credential)
    }

    /** 光鸭：组装最新凭证（刷新后可能变化） */
    fun guangYaCredential(adapter: CloudDriveAdapter): String? =
        (adapter as? GuangYaAdapter)?.currentCredential()

    private fun splitCredential(credential: String): Pair<String, String> {
        val parts = credential.split("||", limit = 2)
        return parts.getOrElse(0) { "" }.trim() to parts.getOrElse(1) { "" }.trim()
    }
}
