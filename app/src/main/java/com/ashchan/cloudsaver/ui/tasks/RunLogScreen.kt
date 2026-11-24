package com.ashchan.cloudsaver.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashchan.cloudsaver.core.AppLog
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.sync.AutoSyncPrefs
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
    onSelectTab: (com.ashchan.cloudsaver.ui.MainTab) -> Unit = {},
) {
    val runs by viewModel.recentRuns.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    var confirmClear by remember { mutableStateOf(false) }
    // 筛选：纯内存过滤，不动库、不新增路由；「自动」只在开了自动刷新时出现
    val context = LocalContext.current
    val autoEnabled = remember { AutoSyncPrefs(context).enabled }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val shown = remember(runs, filter) {
        when (filter) {
            LogFilter.ALL -> runs
            LogFilter.MANUAL -> runs.filter { it.trigger != RunRecordEntity.TRIGGER_BACKGROUND }
            LogFilter.AUTO -> runs.filter { it.trigger == RunRecordEntity.TRIGGER_BACKGROUND }
        }
    }
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontWeight = FontWeight.SemiBold) },
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
        bottomBar = {
            com.ashchan.cloudsaver.ui.AppTabBar(
                selected = com.ashchan.cloudsaver.ui.MainTab.RUNLOG,
                onSelect = onSelectTab,
            )
        },
    ) { padding ->
        if (confirmClear) {
            androidx.compose.material3.AlertDialog(
                shape = RoundedCornerShape(24.dp),
                onDismissRequest = { confirmClear = false },
                title = { Text("清除全部运行日志？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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
            // 来源筛选：横向一排 chip，默认「全部」，纯内存过滤
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { item ->
                    if (item == LogFilter.AUTO && !autoEnabled) return@forEach
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) },
                    )
                }
            }
            if (shown.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有运行记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (runs.isEmpty()) {
                            "点「检测」或一次性「全部检测」就会留下一条"
                        } else {
                            "这个分类下没有记录，切到「全部」看看"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { record ->
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
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showDialog = false },
            title = { Text("崩溃日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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

/** 来源筛选（纯内存，不落库、不加路由） */
private enum class LogFilter(val label: String) {
    ALL("全部"),
    MANUAL("手动"),
    AUTO("自动"),
}

/** 状态 → 三块颜色：强调色、胶囊底、卡片底、提示块底；深色模式换一套更亮的强调色 */
private class LogTone(
    val accent: Color,
    val badgeBg: Color,
    val cardBg: Color,
    val noteBg: Color,
)

@Composable
private fun logTone(kind: RunLogKind): LogTone {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scheme = MaterialTheme.colorScheme
    val green = if (dark) Color(0xFF7CD48B) else Color(0xFF2E7D32)
    val blue = if (dark) Color(0xFF8FCBE8) else Color(0xFF2C6E8F)
    val amber = if (dark) Color(0xFFE8B562) else Color(0xFF9A6400)
    return when (kind) {
        RunLogKind.NEW -> LogTone(
            green, green.copy(alpha = 0.16f),
            scheme.surfaceVariant.copy(alpha = 0.55f), green.copy(alpha = 0.10f),
        )
        RunLogKind.TIDY -> LogTone(
            blue, blue.copy(alpha = 0.16f),
            scheme.surfaceVariant.copy(alpha = 0.55f), blue.copy(alpha = 0.10f),
        )
        RunLogKind.IDLE -> LogTone(
            scheme.onSurfaceVariant, scheme.surfaceVariant.copy(alpha = 0.32f),
            scheme.surfaceVariant.copy(alpha = 0.26f), scheme.surfaceVariant.copy(alpha = 0.30f),
        )
        RunLogKind.PARTIAL -> LogTone(
            amber, amber.copy(alpha = 0.18f),
            scheme.surfaceVariant.copy(alpha = 0.55f), amber.copy(alpha = 0.12f),
        )
        RunLogKind.NOTE -> LogTone(
            scheme.onSurfaceVariant, scheme.surfaceVariant.copy(alpha = 0.42f),
            scheme.surfaceVariant.copy(alpha = 0.42f), scheme.surfaceVariant.copy(alpha = 0.45f),
        )
        else -> LogTone(
            scheme.error, scheme.errorContainer,
            scheme.errorContainer.copy(alpha = 0.30f), scheme.errorContainer,
        )
    }
}

@Composable
private fun RunRow(record: RunRecordEntity, formatter: SimpleDateFormat) {
    val status = remember(record) { RunLogStatusRules.classify(record) }
    val tone = logTone(status.kind)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tone.cardBg),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // 左侧 4dp 竖色条：一屏里几条红的能立刻扫出来
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(tone.accent)
            )
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 结论胶囊：直接写「新增 3 集 / 无变化 / 登录失效」，不再是笼统的「成功/失败」
                    Surface(color = tone.badgeBg, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = status.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = tone.accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatter.format(Date(record.startedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = triggerLabel(record.trigger),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = record.taskName.ifBlank { "任务 #${record.taskId}" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = mainText(record),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = tone.accent,
                )
                Text(
                    text = metaText(record),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(tone.noteBg, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = record.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.kind == RunLogKind.IDLE) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                tone.accent
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun triggerLabel(trigger: String): String = when (trigger) {
    RunRecordEntity.TRIGGER_ALL -> "全部检测"
    RunRecordEntity.TRIGGER_BACKGROUND -> "自动同步"
    else -> "手动"
}

/** 主行：这一轮到底发生了什么，用最少的字说清 */
private fun mainText(record: RunRecordEntity): String = when {
    !record.ok -> "本轮未完成"
    record.restoredFiles > 0 && record.renamedItems > 0 ->
        "新增 ${record.restoredFiles} 集 · 改名 ${record.renamedItems}"
    record.restoredFiles > 0 -> "新增 ${record.restoredFiles} 集"
    record.renamedItems > 0 -> "改名 ${record.renamedItems} 项"
    record.restoredDirs > 0 -> "新建 ${record.restoredDirs} 个目录"
    else -> "没有新内容"
}

/** 次行：耗时与跳过这类次要信息，缩到最小号 */
private fun metaText(record: RunRecordEntity): String {
    val seconds = ((record.finishedAt - record.startedAt).coerceAtLeast(0)) / 1000
    val parts = mutableListOf("耗时 ${seconds}s")
    if (record.skippedFiles > 0) parts += "跳过 ${record.skippedFiles}"
    return parts.joinToString(" · ")
}