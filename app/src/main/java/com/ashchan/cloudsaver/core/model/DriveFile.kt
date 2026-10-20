package com.ashchan.cloudsaver.core.model

/**
 * 网盘中的文件 / 文件夹统一模型。
 *
 * @param fid 文件 ID（分享列表与转存后列表中的 ID 语义不同，见 [source]）
 * @param name 原名
 * @param isDir 是否为文件夹
 * @param size 字节数（文件夹为 0）
 * @param fidToken 分享上下文令牌（夸克转存需要 fid_token；光鸭为 null）
 * @param parentFid 父目录 ID（分享列表内）
 * @param source 数据来源：SHARE=分享内文件，MINE=我的网盘文件，SAVED=转存后新文件
 */
data class DriveFile(
    val fid: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val fidToken: String? = null,
    val parentFid: String? = null,
    val source: FileSource = FileSource.SHARE,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

enum class FileSource { SHARE, MINE, SAVED }
