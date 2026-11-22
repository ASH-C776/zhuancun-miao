package com.ashchan.cloudsaver.core.model

/**
 * 解析分享链接后得到的分享信息。
 *
 * 各网盘字段语义：
 * - 光鸭：[shareId] 为 shareId，[passcode] 为提取码，[accessToken] 为分享访问令牌，
 *   [pdirFid] 为分享内初始目录；[raw] 保存原始链接。
 * - 夸克：[shareId] 为 pwd_id，[passcode] 为提取码，[stoken] 为分享令牌，
 *   [pdirFid] 为分享内初始目录（可能为 0）。
 */
data class ShareInfo(
    val driveType: DriveType,
    val shareId: String,
    val passcode: String = "",
    /** 分享访问令牌（光鸭 accessToken / 夸克 stoken），解析后惰性获取 */
    val accessToken: String = "",
    /** 分享内初始目录 ID */
    val pdirFid: String = "0",
    val raw: String = "",
) {
    val isValid: Boolean get() = shareId.isNotBlank()
}
