package com.ashchan.cloudsaver.data

import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter
import com.ashchan.cloudsaver.drive.quark.QuarkAdapter

/** 从粘贴的链接里识别出来的分享信息（建任务第一步用，不联网） */
data class ShareInspect(
    val driveType: DriveType,
    val shareId: String,
    val passcode: String,
)

/**
 * 新建任务时解析用户粘贴的链接。
 *
 * 只做本地识别（正则），不发任何请求：网盘类型靠域名判断，
 * 分享 id / 提取码复用两个适配器已有的解析逻辑，避免两套正则各写一遍。
 */
object TaskDraftParser {

    fun detectDriveType(link: String): DriveType? {
        val lower = link.lowercase()
        return when {
            "quark.cn" in lower -> DriveType.QUARK
            "guangyapan.com" in lower -> DriveType.GUANGYA
            else -> null
        }
    }

    fun inspect(link: String): Result<ShareInspect> = runCatching {
        val trimmed = link.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("请先粘贴分享链接")
        when (detectDriveType(trimmed)) {
            DriveType.QUARK -> {
                val info = QuarkAdapter().parseShareLink(trimmed).getOrThrow()
                ShareInspect(DriveType.QUARK, info.shareId, info.passcode)
            }
            DriveType.GUANGYA -> {
                val info = GuangYaAdapter().parseShareLink(trimmed).getOrThrow()
                ShareInspect(DriveType.GUANGYA, info.shareId, info.passcode)
            }
            else -> throw IllegalArgumentException("只支持夸克 / 光鸭云盘链接")
        }
    }
}
