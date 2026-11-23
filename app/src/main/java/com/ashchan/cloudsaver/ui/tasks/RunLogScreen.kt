package com.ashchan.cloudsaver.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashchan.cloudsaver.core.AppLog
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：每次「检测转存」都有一条，按时间倒序。
 *
 * 存在的意义是回答「昨天为什么没补上 / 这条任务为什么失败了」：
 * 卡片快照只留最新一态，这里能看到连续几轮的变化与错误原文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogScreen(
    viewModel: TasksViewModel,
    onBack: () -> Unit,
) {
    val runs by viewModel.recentRuns.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (runs.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清除日志")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (confirmClear) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除全部运行日志？") },
                text = { Text("共 ${runs.size} 条记录会被删除，任务和集数进度不受影响。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        viewModel.clearRuns()
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CrashLogBar(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (runs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有运行记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点一次「检测转存」或「全部检测」就会留下一条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(runs, key = { it.id }) { record ->
                        RunRow(record, formatter)
                    }
                }
            }
        }
    }
}

/**
 * 崩溃日志入口：只在真的写出来过崩溃时才显示，平时不占地方。
 * 点开是纯文本堆栈，可清除 —— 用户自己也能判断「是不是我手机杀后台杀的」。
 */
@Composable
private fun CrashLogBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    val hasLog = remember { AppLog.read(context).isNotBlank() }
    if (!hasLog) return

    androidx.compose.material3.OutlinedButton(
        onClick = {
            content = AppLog.read(context)
            showDialog = true
        },
        modifier = modifier,
    ) {
        Text("崩溃日志", color = MaterialTheme.colorScheme.error)
    }

    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("崩溃日志") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        content.takeLast(4000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear(context)
                    showDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun RunRow(record: RunRecordEntity, formatter: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatter.format(Date(record.startedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = triggerLabel(record.trigger),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (record.ok) "成功" else "失败",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (record.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = record.taskName.ifBlank { "任务 #${record.taskId}" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary(record),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (record.message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = record.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

private fun triggerLabel(trigger: String): String = when (trigger) {
    RunRecordEntity.TRIGGER_ALL -> "全部检测"
    RunRecordEntity.TRIGGER_BACKGROUND -> "自动同步"
    else -> "手动"
}

/** 一行摘要：耗时 + 新增/跳过/失败数量，全 0 的不显示 */
private fun summary(record: RunRecordEntity): String {
    val seconds = ((record.finishedAt - record.startedAt).coerceAtLeast(0)) / 1000
    val parts = mutableListOf<String>()
    parts += "耗时 ${seconds}s"
    if (record.restoredDirs > 0) parts += "新建目录 ${record.restoredDirs}"
    if (record.restoredFiles > 0) parts += "新增 ${record.restoredFiles}"
    if (record.renamedItems > 0) parts += "改名 ${record.renamedItems}"
    if (record.skippedFiles > 0) parts += "跳过 ${record.skippedFiles}"
    return parts.joinToString(" · ")
}