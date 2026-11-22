package com.ashchan.cloudsaver.core.adapter

import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.ShareNode

/**
 * 网盘统一适配器接口。
 *
 * 每个网盘一个实现类：光鸭 [com.ashchan.cloudsaver.drive.guangya.GuangYaAdapter]、
 * 夸克 [com.ashchan.cloudsaver.drive.quark.QuarkAdapter]。
 *
 * 调用顺序（由 TransferManager / 任务执行器编排）：
 * 1. [validateLogin] 校验登录态
 * 2. [parseShareLink] 解析分享链接
 * 3. [fetchShareTree] 递归扫描分享目录树（深层资源定位）；单层浏览仍可用 [fetchShareChildren]
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

    /** 列出我的网盘中某目录下的全部条目（文件 + 子文件夹），用于增量转存比对 */
    suspend fun listFiles(parentId: String?): Result<List<DriveFile>>

    /**
     * 递归扫描分享目录，返回可直接下钻的目录树。
     *
     * 默认实现基于 [fetchShareChildren] 逐层展开，无需各适配器重复实现：
     * - 同一目录在一次扫描内只请求一次（节点缓存），避免重复拉取；
     * - 超过 [maxDepth] 的目录保留节点但不再展开；
     * - 检测到成环（fid 重复）时停止展开，防止异常数据导致死循环；
     * - 子节点按 [NaturalOrder] 自然序排列，保证 EP2 在 EP10 之前。
     *
     * @param rootId 扫描起点 fid，null / "0" 表示分享根目录
     * @param maxDepth 最大目录层数（根的直接子级 = 1），默认 [DEFAULT_TREE_MAX_DEPTH]
     */
    suspend fun fetchShareTree(
        share: ShareInfo,
        rootId: String? = null,
        maxDepth: Int = DEFAULT_TREE_MAX_DEPTH,
    ): Result<ShareNode> = runCatching {
        val cache = mutableMapOf<String?, List<DriveFile>>()
        val visited = mutableSetOf<String>()

        suspend fun children(parentId: String?): List<DriveFile> =
            cache.getOrPut(parentId) {
                fetchShareChildren(share, parentId).getOrThrow()
            }

        suspend fun build(parentId: String?, parentDepth: Int): List<ShareNode> {
            val depth = parentDepth + 1
            return children(parentId)
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .map { item ->
                    when {
                        !item.isDir -> ShareNode(item, emptyList(), depth)
                        depth < maxDepth && visited.add(item.fid) ->
                            ShareNode(item, build(item.fid, depth), depth)
                        else -> ShareNode(item, emptyList(), depth)
                    }
                }
        }

        val rootFiles = build(rootId?.takeIf { it != "0" }, 0)
        ShareNode(
            file = DriveFile(
                fid = rootId?.takeIf { it != "0" } ?: "0",
                name = SHARE_ROOT_NAME,
                isDir = true,
            ),
            children = rootFiles,
            depth = 0,
        )
    }

    /**
     * 转存分享内文件到目标目录。
     * [files] 为待转存文件（含 fid / fidToken）。
     * 返回转存后新文件的 [RestoredFile] 列表（source=SAVED），每项携带对应的分享文件 fid。
     * 实现内部处理：异步任务轮询 + 目录 diff 对齐新 fid；若转存的是文件夹，会展开其内部文件便于重命名。
     */
    suspend fun restoreShare(share: ShareInfo, files: List<DriveFile>, parentId: String): Result<List<RestoredFile>>

    /** 重命名单个文件 */
    suspend fun renameFile(fileId: String, newName: String): Result<Unit>

    companion object {
        /** 默认扫描到第 3 层目录（根的直接子级 = 1）。绝大多数资源不超过这个深度。 */
        const val DEFAULT_TREE_MAX_DEPTH: Int = 3
        /** 扫描深度硬上限，避免误传大值导致请求爆炸 */
        const val MAX_TREE_MAX_DEPTH: Int = 5
        const val SHARE_ROOT_NAME: String = "分享根目录"
    }
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
