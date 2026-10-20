package com.ashchan.cloudsaver.core.adapter

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.ShareInfo

/**
 * 网盘统一适配器接口。
 *
 * 每个网盘一个实现类：光鸭 [com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter]、
 * 夸克 [com.ashchan.cloudsaver.drive.quark.QuarkAdapter]。
 *
 * 调用顺序（由 TransferManager 编排）：
 * 1. [validateLogin] 校验登录态
 * 2. [parseShareLink] 解析分享链接
 * 3. [fetchShareChildren] 展示分享内文件列表（可下钻子目录）
 * 4. [listFolders] 选择目标文件夹
 * 5. [restoreShare] 转存，返回转存后新文件（含 fid）
 * 6. [renameFile] 批量重命名
 */
interface CloudDriveAdapter {

    val driveType: DriveType

    /** 校验登录凭证是否有效（光鸭：access_token/refresh_token；夸克：cookie） */
    suspend fun validateLogin(credential: String): Result<AccountInfo>

    /** 从分享链接解析出 [ShareInfo]，不访问网络 */
    fun parseShareLink(link: String): Result<ShareInfo>

    /** 获取分享内 accessToken/stoken（网络请求；需要提取码时用户已提供） */
    suspend fun resolveShareAccess(share: ShareInfo): Result<ShareInfo>

    /** 列出分享内某目录下的文件列表（parentId 为 null 表示分享根目录） */
    suspend fun fetchShareChildren(share: ShareInfo, parentId: String?): Result<List<DriveFile>>

    /** 列出我的网盘中某目录下的子文件夹（parentId 为 null 表示根目录） */
    suspend fun listFolders(parentId: String?): Result<List<DriveFile>>

    /**
     * 转存分享内文件到目标目录。
     * [files] 为待转存文件（含 fid / fidToken）。
     * 返回转存后新文件的 [RestoredFile] 列表（source=SAVED），每项携带对应的分享文件 fid。
     * 实现内部处理：异步任务轮询 + 目录 diff 对齐新 fid；若转存的是文件夹，会展开其内部文件便于重命名。
     */
    suspend fun restoreShare(share: ShareInfo, files: List<DriveFile>, parentId: String): Result<List<RestoredFile>>

    /** 重命名单个文件 */
    suspend fun renameFile(fileId: String, newName: String): Result<Unit>
}

/** 登录后的账号信息（用于展示昵称/容量） */
data class AccountInfo(
    val nickname: String,
    val raw: String = "",
)

/** 转存后的新文件及其对应的分享内文件 fid（用于重命名映射） */
data class RestoredFile(
    val file: DriveFile,
    val shareFileId: String = "",
    val originName: String = "",
)
