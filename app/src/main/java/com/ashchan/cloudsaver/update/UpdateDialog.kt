package com.ashchan.cloudsaver.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 更新弹窗三态：确认 / 下载中 / 下载失败。
 *
 * 默认动作给「取消」——按错一次的代价是白下载十几 MB，宁可让用户主动去点「更新」。
 * 下载中不提供任何按钮，避免重复点击。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersionName: String,
    progress: Int?,
    failed: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        // 下载中不允许点外部关闭：中途关掉只会留下半截文件
        onDismissRequest = { if (progress == null) onDismiss() },
        title = {
            Text(
                when {
                    progress != null -> "正在下载"
                    failed -> "下载失败"
                    else -> "发现新版本"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                when {
                    progress != null -> {
                        Text(
                            "正在下载… ${progress}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    failed -> Text(
                        "下载失败，检查网络后再试",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            "软件最新版本为 v${info.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "当前版本 $currentVersionName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                progress != null -> {}
                failed -> TextButton(onClick = onRetry) { Text("重试") }
                else -> TextButton(onClick = onUpdate) { Text("更新") }
            }
        },
        dismissButton = {
            when {
                progress != null -> {}
                failed -> TextButton(onClick = onDismiss) { Text("取消") }
                else -> TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
