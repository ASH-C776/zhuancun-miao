package com.ashchan.cloudsaver.transfer

import android.util.Log
import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.adapter.RestoredFile
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.ItemResult
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.TransferResult
import com.ashchan.cloudsaver.rename.RenameEngine
import kotlinx.coroutines.delay

/**
 * 转存编排器：串起「转存 → 拿新 fileId → 本地重命名 → 结果」全流程。
 * 单次、同步、用户主动触发；重命名计算全部本地完成。
 */
class TransferManager(
    private val adapter: CloudDriveAdapter,
    private val engine: RenameEngine = RenameEngine(),
) {

    /**
     * 执行一次转存 + 重命名任务。
     *
     * @param share 已解析且拿到 accessToken 的分享信息
     * @param files 用户选择的待转存文件（分享内）
     * @param targetFolderId 目标文件夹 fid（"0" 或空 = 根目录）
     * @param taskName 用户填写的剧名/任务名（用于文件夹改名与 {TASKNAME}）
     * @param renamePlan 分享 fid -> 新文件名（预览阶段用户确认/手改后的结果）
     * @param onProgress 进度回调：stage=0 转存中，stage=1 重命名中
     */
    suspend fun execute(
        share: ShareInfo,
        files: List<DriveFile>,
        targetFolderId: String,
        taskName: String,
        renamePlan: Map<String, String>,
        onProgress: (stage: Int, percent: Int, message: String) -> Unit = { _, _, _ -> },
    ): TransferResult {
        val folderShare = files.size == 1 && files[0].isDir

        // 阶段一：转存，拿新 fileId 列表
        onProgress(0, 0, "正在转存 ${files.size} 个文件…")
        val savedResult = adapter.restoreShare(share, files, targetFolderId)
        val savedFiles = savedResult.getOrElse {
            val message = it.message ?: "转存失败"
            return TransferResult(
                driveType = adapter.driveType,
                taskName = taskName,
                items = files.map { f -> ItemResult(f.name, renamePlan[f.fid], ok = false, message = message) },
            )
        }

        // 阶段 1.5：文件夹分享 → 先把新文件夹改名为剧名（用户需求：文件夹名 = 填写的剧名）
        if (folderShare && taskName.isNotBlank()) {
            savedFiles.firstOrNull()?.let { top ->
                if (top.file.isDir && top.file.name != taskName) {
                    val r = adapter.renameFile(top.file.fid, taskName)
                    if (r.isSuccess) {
                        onProgress(1, 0, "文件夹已改名为「$taskName」")
                    }
                }
            }
        }

        // 阶段二：批量重命名（按返回的 shareFileId + originName 与重命名计划映射）
        val results = mutableListOf<ItemResult>()
        val usedNames = mutableSetOf<String>()
        savedFiles.forEachIndexed { index, restored ->
            val savedFile = restored.file
            val originName = restored.originName.ifBlank { savedFile.name }

            // 文件夹分享时，列表首位是新文件夹（已在阶段 1.5 改名），跳过文件重命名流程
            if (folderShare && index == 0) {
                results += ItemResult(originName, if (taskName.isBlank()) originName else taskName, ok = true, message = "文件夹")
                return@forEachIndexed
            }

            val newName = renamePlan[restored.shareFileId] ?: originName

            var ok = true
            var failMsg = ""
            if (newName != originName) {
                // 同名冲突保护：同一批次里已用过的新名 → 保持原名，避免「文件名已存在」连环失败
                if (!usedNames.add(newName)) {
                    failMsg = "与批内其他文件重名，已保持原名"
                    Log.d(TAG, "SKIP dup name: $originName -> $newName")
                } else {
                    Log.d(TAG, "RENAME $originName -> $newName (fid=${savedFile.fid})")
                    val r = adapter.renameFile(savedFile.fid, newName)
                    ok = r.isSuccess
                    if (!ok) failMsg = r.exceptionOrNull()?.message ?: "重命名失败"
                }
            } else {
                Log.d(TAG, "KEEP name: $originName")
            }
            results += ItemResult(originName, if (ok) newName else originName, ok = ok, message = failMsg)
            onProgress(1, (index + 1) * 100 / savedFiles.size.coerceAtLeast(1), "正在重命名 $originName")
            // 风控限流：批量操作加 200-500ms 间隔
            delay(300)
        }

        return TransferResult(
            driveType = adapter.driveType,
            taskName = taskName,
            items = results,
        )
    }

    companion object {
        private const val TAG = "TransferDebug"
    }
}
