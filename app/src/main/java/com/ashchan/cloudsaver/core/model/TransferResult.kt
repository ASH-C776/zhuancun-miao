package com.ashchan.cloudsaver.core.model

/** 转存 + 重命名单条结果 */
data class ItemResult(
    val originName: String,
    val newName: String?,
    val ok: Boolean,
    val message: String = "",
)

/** 一次转存任务的总体结果 */
data class TransferResult(
    val driveType: DriveType,
    val taskName: String,
    val items: List<ItemResult>,
) {
    val successCount: Int get() = items.count { it.ok }
    val failCount: Int get() = items.size - successCount
}
