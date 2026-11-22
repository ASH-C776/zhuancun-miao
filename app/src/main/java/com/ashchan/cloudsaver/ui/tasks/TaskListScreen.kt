package com.ashchan.cloudsaver.ui.tasks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    startWithNewTaskDialog: Boolean = false,
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showNewDialog by remember { mutableStateOf(startWithNewTaskDialog) }
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    // 打开 App 自动同步一轮（每个 ViewModel 生命周期一次）
    LaunchedEffect(Unit) { viewModel.syncOnStartOnce() }

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
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.List, contentDescription = "运行日志")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建任务") },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${cards.size} 个任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.runAll() },
                        enabled = running.isEmpty(),
                    ) {
                        if (running.isEmpty()) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("全部检测")
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("同步中")
                        }
                    }
                }
                progress?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (cards.isEmpty()) {
                EmptyTasks(modifier = Modifier.fillMaxSize()) { showNewDialog = true }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards, key = { it.task.id }) { card ->
                        TaskRow(
                            card = card,
                            isRunning = card.task.id in running,
                            onRun = { viewModel.runTask(card.task) },
                            onDelete = { pendingDelete = card.task },
                            onToggle = { viewModel.toggleEnabled(card.task) },
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewTaskDialog(
            onDismiss = { showNewDialog = false },
            onSave = { name, link, weekdays, tmdbId, airTime ->
                viewModel.createTask(name, link, weekdays, tmdbId, airTime = airTime)
                showNewDialog = false
            },
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("「${task.name}」将从列表移除，网盘里已转存的文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ---------------- 卡片 ----------------

@Composable
private fun TaskRow(
    card: TaskCard,
    isRunning: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val task = card.task
    val snap = card.snapshot

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
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

            Spacer(Modifier.height(6.dp))

            // 第二行：盘里集数 → 最新集
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
                    text = missingText(snap),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(snap),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 进度：盘里 / 最新
            val fraction = if (snap.tmdbLatest > 0) {
                (snap.localLastEpisode.toFloat() / snap.tmdbLatest).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = statusColor(snap),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // 第三行：更新日 · 网盘 · 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = updateLabel(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Dot()
                Text(
                    text = driveLabel(task.driveType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Dot()
                Text(
                    text = syncText(snap.lastSyncAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (!task.enabled) {
                    Text(
                        text = "已暂停",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggle) {
                    Text(if (task.enabled) "暂停" else "启用")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRun, enabled = !isRunning) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("同步中")
                    } else {
                        Text("检测转存")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(snap: SnapshotEntity) {
    val (text, color) = when (snap.status) {
        SnapshotEntity.STATUS_UP_TO_DATE -> "已是最新" to Color(0xFF2E7D32)
        SnapshotEntity.STATUS_BEHIND -> "缺 ${snap.missing} 集" to Color(0xFFB26A00)
        else -> "未同步" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
    SnapshotEntity.STATUS_UP_TO_DATE -> Color(0xFF2E7D32)
    SnapshotEntity.STATUS_BEHIND -> Color(0xFFB26A00)
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有追剧任务",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点「新建任务」粘贴夸克或光鸭分享链接，",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "之后打开 App 就会自动检测并补齐集数。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAdd) { Text("新建第一个任务") }
    }
}

// ---------------- 新建任务 ----------------

@Composable
private fun NewTaskDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, link: String, weekdays: String, tmdbId: String, airTime: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var tmdbId by remember { mutableStateOf("") }
    var airTime by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建追剧任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("剧名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("分享链接") },
                    placeholder = { Text("夸克 / 光鸭分享链接，可带提取码") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "更新日",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 7 个按钮分两行排，窄屏 Dialog 里一行放不下会裁掉周六周日
                listOf(1..4, 5..7).forEach { range ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        range.forEach { day ->
                            FilterChip(
                                selected = day in selected,
                                onClick = {
                                    selected = if (day in selected) selected - day else selected + day
                                },
                                label = {
                                    Text(
                                        text = Weekdays.nameOf(day).removePrefix("周"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = Weekdays.everyDay() }) { Text("每天") }
                    TextButton(onClick = { selected = Weekdays.workdays() }) { Text("工作日 1-5") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                }

                OutlinedTextField(
                    value = tmdbId,
                    onValueChange = { tmdbId = it },
                    label = { Text("TMDB ID（可选）") },
                    supportingText = { Text("填了会自动判断最新集与更新日") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = airTime,
                    onValueChange = { airTime = it },
                    label = { Text("播出时刻（可选）") },
                    placeholder = { Text("例如 18:00，只用于提醒，暂不影响同步") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "保存后会先自动扫描一次，确认盘里已有多少集。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name, link, Weekdays.format(selected), tmdbId, airTime)
                },
                enabled = name.isNotBlank() && link.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------- 小工具 ----------------

private fun episodeText(ep: Int): String = if (ep > 0) "E$ep" else "E—"

/** 更新日文案：手动设的星期 + 来源标记（TMDB 自动推断的会注明，方便用户区分） */
private fun updateLabel(task: TaskEntity): String {
    val label = Weekdays.label(task.weekdays)
    if (task.updateMode != TaskEntity.UPDATE_MODE_TMDB) return label
    val auto = if (task.weekdays.isBlank()) "TMDB 自动待推断" else "TMDB 自动"
    return if (task.weekdays.isBlank()) auto else "$label · $auto"
}

private fun missingText(snap: SnapshotEntity): String = when (snap.status) {
    SnapshotEntity.STATUS_BEHIND -> "缺 ${snap.missing} 集"
    SnapshotEntity.STATUS_UP_TO_DATE -> ""
    else -> "未绑定 TMDB"
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
