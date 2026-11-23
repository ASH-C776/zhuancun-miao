package com.ashchan.cloudsaver.ui.tasks

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashchan.cloudsaver.data.TaskCard
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.SnapshotEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页：追剧任务列表。
 *
 * 卡片只放 5 项核心信息（手机屏小，其余全部下沉）：
 * 剧名 / 盘里集数 → TMDB 最新集 / 是否最新（缺几集）/ 周几更新 / 网盘类型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TasksViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNewTask: () -> Unit = {},
    onOpenEdit: (TaskEntity) -> Unit = {},
    startWithNewTaskDialog: Boolean = false,
    onSelectTab: (com.ashchan.cloudsaver.ui.MainTab) -> Unit = {},
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    // 打开 App 自动同步一轮（每个 ViewModel 生命周期一次）

    // 调试/外部分享入口仍可直达新建页（openNewTask extra）
    LaunchedEffect(startWithNewTaskDialog) {
        if (startWithNewTaskDialog) onOpenNewTask()
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("转存喵", fontWeight = FontWeight.SemiBold) },
                // 顶栏不再放高频操作：刷新/日志/设置统一挪到右下拇指区（见下方 floatingActionButton）
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        // 主页只保留一个动作：「全部检测」的圆形按钮，悬在底栏上方靠右下
        floatingActionButton = {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
            ) {
                IconButton(
                    onClick = { viewModel.runAll() },
                    enabled = cards.isNotEmpty() && running.isEmpty(),
                    modifier = Modifier.size(48.dp),
                ) {
                    if (running.isEmpty()) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "全部检测",
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        },
        bottomBar = {
            com.ashchan.cloudsaver.ui.AppTabBar(
                selected = com.ashchan.cloudsaver.ui.MainTab.TASKS,
                onSelect = onSelectTab,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (cards.isNotEmpty()) {
                progress?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (cards.isEmpty()) {
                EmptyTasks(modifier = Modifier.fillMaxSize(), onAdd = onOpenNewTask)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(cards, key = { it.task.id }) { card ->
                        TaskRow(
                            card = card,
                            isRunning = card.task.id in running,
                            onRun = { viewModel.runTask(card.task) },
                            onEdit = { onOpenEdit(card.task) },
                            onRequestDelete = { pendingDelete = card.task },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
            text = { Text("「${task.name}」将从列表移除，网盘里已转存的文件不受影响。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ---------------- 卡片 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    card: TaskCard,
    isRunning: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val task = card.task
    val snap = card.snapshot

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 点主体进编辑；长按弹删除确认（删除不可撤销，故意藏长按里）
            .combinedClickable(onClick = onEdit, onLongClick = onRequestDelete),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 第一行：剧名 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(snap)
            }

            Spacer(Modifier.height(8.dp))

            // 第二行：盘里集数 → 最新集；右侧放上次同步时间（时间独占右端，永远不被压缩）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = episodeText(snap.localLastEpisode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  →  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = episodeText(snap.tmdbLatest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = syncText(snap.lastSyncAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 进度：盘里集数 / 目标集数（有总集数就按全集算，否则退回按最新集算）
            val denominator = if (snap.tmdbTotal > 0) snap.tmdbTotal else snap.tmdbLatest
            val numerator = if (snap.tmdbTotal > 0) snap.localEpisodes else snap.localLastEpisode
            val fraction = if (denominator > 0) {
                (numerator.toFloat() / denominator).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = statusColor(snap),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // 上次执行失败：卡片上直接给红字，不用点进运行日志才知道
            if (!snap.lastOk && snap.lastSyncAt > 0) {
                Text(
                    text = "上次失败 · " + snap.lastMessage.ifBlank { "未知原因" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
            }

            // 第三行：左边刷新（手动检测），右边「更新日 · 网盘 · 时间」右对齐
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isRunning) {
                    IconButton(
                        onClick = onRun,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "检测转存",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左段可能变长（如「周二 · 光鸭 · 自动」），过长只压缩自己（时间已挪到上一行右端）
                    Text(
                        text = if (task.enabled) metaLeft(task) else "${metaLeft(task)} · 已暂停",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 卡片语义色：成功 / 缺集 / 未同步。
 *
 * 之前这两处是硬编码 `Color(0xFF2E7D32)` 与 `Color(0xFFB26A00)`，在深色主题下压得很暗、
 * 与浅色背景对比也不一致。这里按系统深浅色各给一套，徽标底色统一取 12% 透明度。
 */
private object TaskColors {
    @Composable
    fun ok(): Color = if (isSystemInDarkTheme()) Color(0xFF7BC67E) else Color(0xFF2E7D32)

    @Composable
    fun behind(): Color = if (isSystemInDarkTheme()) Color(0xFFE0A94F) else Color(0xFFB26A00)

    @Composable
    fun unknown(): Color = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun badgeBg(color: Color): Color = color.copy(alpha = 0.12f)
}

@Composable
private fun StatusBadge(snap: SnapshotEntity) {
    val (text, color) = when (snap.status) {
        // 全集都到齐：这部剧没得补了，用户看到这个标识自己决定删不删任务
        SnapshotEntity.STATUS_FINISHED -> "转存完" to TaskColors.ok()
        SnapshotEntity.STATUS_UP_TO_DATE -> "最新了" to TaskColors.ok()
        SnapshotEntity.STATUS_BEHIND -> "缺 ${snap.missing} 集" to TaskColors.behind()
        else -> "未同步" to TaskColors.unknown()
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TaskColors.badgeBg(color),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun Dot() {
    Text(
        text = " · ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun statusColor(snap: SnapshotEntity): Color = when (snap.status) {
    SnapshotEntity.STATUS_FINISHED,
    SnapshotEntity.STATUS_UP_TO_DATE -> TaskColors.ok()
    SnapshotEntity.STATUS_BEHIND -> TaskColors.behind()
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "还没有追剧任务",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点「+」粘贴夸克或光鸭分享链接即可新建",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAdd) { Text("新建任务") }
    }
}

// ---------------- 小工具 ----------------

private fun episodeText(ep: Int): String = if (ep > 0) "E$ep" else "E—"

/** 卡片底行：「周几 · 网盘 · 自动 / 待推断 / 未绑定 TMDB」，时间已挪到上一行右端 */
private fun metaLeft(task: TaskEntity): String {
    val parts = mutableListOf(
        Weekdays.label(task.weekdays),
        driveLabel(task.driveType),
    )
    if (task.tmdbId == null) {
        parts += "未绑定 TMDB"
    } else if (task.updateMode == TaskEntity.UPDATE_MODE_TMDB) {
        parts += if (task.weekdays.isBlank()) "待推断" else "自动"
    }
    return parts.joinToString(" · ")
}

private fun driveLabel(driveType: String): String = when (driveType) {
    "QUARK" -> "夸克"
    "GUANGYA" -> "光鸭"
    else -> driveType
}

private fun syncText(millis: Long): String {
    if (millis <= 0) return "未同步"
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}


