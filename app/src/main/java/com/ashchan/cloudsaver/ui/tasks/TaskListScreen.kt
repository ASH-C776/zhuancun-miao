package com.ashchan.cloudsaver.ui.tasks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * 剧名 / 盘里最大集号 → TMDB 最新已播集（共总集数）/ 周几更新 / 网盘类型。\n * 缺多少集不进卡片，只看运行日志（完整度口径 = 总集数 - 已收集数）。
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
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val finishNote by viewModel.finishNote.collectAsStateWithLifecycle()
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
                            runText = running[card.task.id] ?: "准备中",
                            justFinished = finished[card.task.id],
                            justFinishedText = finishNote[card.task.id],
                            onRun = { viewModel.runTask(card.task) },
                            onEdit = { onOpenEdit(card.task) },
                            onTogglePin = { viewModel.setPinned(card.task, !card.task.pinned) },
                            onRename = { viewModel.renameTask(card.task) },
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
    runText: String = "准备中",
    justFinished: Boolean? = null,
    justFinishedText: String? = null,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val task = card.task
    val snap = card.snapshot
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // 点主体进编辑；长按弹菜单（置顶 / 统一重命名 / 删除）
                .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true }),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    // 左：海报位（有图放图，没图同尺寸占位）；胶囊只在「有海报 + 已知总数」时出现
                    PosterArt(
                        name = task.name,
                        tmdbId = task.tmdbId,
                        posterPath = task.posterPath,
                        totalBadge = if (snap.tmdbTotal > 0) "共${snap.tmdbTotal}集" else null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 84.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = task.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StatusBadge(snap)
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val metaBase = metaLeft(task, card.sourceCount)
                            val errorColor = MaterialTheme.colorScheme.error
                            Text(
                                text = buildAnnotatedString {
                                    append(metaBase)
                                    if (!task.enabled) append(" · 已暂停")
                                    if (card.sourceCount > 1 && card.failedSources > 0) {
                                        withStyle(SpanStyle(color = errorColor)) {
                                            append(" · ${card.failedSources} 失效")
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = syncText(snap.lastSyncAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // 集数与刷新按钮同一行、同一高度，整组靠右（右到左：刷新 → 集数）
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val epText = if (snap.tmdbLatest > 0 || snap.tmdbTotal > 0) {
                                "${episodeText(snap.localLastEpisode)}  →  ${episodeText(snap.tmdbLatest)}"
                            } else {
                                // 没绑 TMDB：没有总数也没有对比基准，只报盘里最大集号
                                "第${snap.localLastEpisode}集"
                            }
                            Text(
                                text = epText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            if (!isRunning) {
                                if (justFinished != null) {
                                    RunPhaseText(
                                        text = justFinishedText ?: if (justFinished) "完成" else "失败",
                                        color = if (justFinished) TaskColors.ok() else MaterialTheme.colorScheme.error,
                                        animated = false,
                                    )
                                } else {
                                    IconButton(
                                        onClick = onRun,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = "检测转存",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            } else {
                                RunPhaseText(text = runText, color = TaskColors.local())
                            }
                        }
                    }
                }

                if (!snap.lastOk && snap.lastSyncAt > 0) {
                    Text(
                        text = "上次失败 · " + snap.lastMessage.ifBlank { "未知原因" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 进度条贯穿整张卡片宽度，贴卡片底边
                EpisodeBar(snap)
            }
        }
    }

    // 长按菜单：纯文字三项，置顶在上、删除在下（删除仍走二次确认）
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        modifier = Modifier.width(184.dp),
    ) {
        DropdownMenuItem(
            text = { Text(if (task.pinned) "取消置顶" else "置顶") },
            onClick = {
                menuOpen = false
                onTogglePin()
            },
        )
        DropdownMenuItem(
            text = { Text("统一重命名") },
            onClick = {
                menuOpen = false
                onRename()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("删除任务", color = MaterialTheme.colorScheme.error) },
            onClick = {
                menuOpen = false
                onRequestDelete()
            },
        )
    }
}

/**
 * 卡片语义色：成功 / 缺集 / 未同步。
 *
 * 之前这两处是硬编码 `Color(0xFF2E7D32)` 与 `Color(0xFFB26A00)`，在深色主题下压得很暗、
 * 与浅色背景对比也不一致。这里按系统深浅色各给一套，徽标底色统一取 12% 透明度。
 */
internal object TaskColors {
    @Composable
    fun ok(): Color = if (isSystemInDarkTheme()) Color(0xFF7BC67E) else Color(0xFF2E7D32)

    @Composable
    fun behind(): Color = if (isSystemInDarkTheme()) Color(0xFFE0A94F) else Color(0xFFB26A00)

    @Composable
    fun unknown(): Color = MaterialTheme.colorScheme.onSurfaceVariant

    /** 没绑 TMDB 的任务用蓝色整条进度条：不是「缺集」也不是「收齐」，只是「这一轮干过活」 */
    @Composable
    fun local(): Color = if (isSystemInDarkTheme()) Color(0xFF6FA8FF) else Color(0xFF1A6DD6)

    @Composable
    fun badgeBg(color: Color): Color = color.copy(alpha = 0.12f)
}

@Composable
private fun StatusBadge(snap: SnapshotEntity) {
    // 徽标也只看「追剧」：盘里最大集号是否追到 TMDB 最新已播集。
    // 中间缺了几集不影响这个徽标，缺集数在运行日志里单独给（避免「已最新」和「缺 97 集」打架）。
    val (text, color) = when {
        snap.tmdbTotal > 0 && snap.localLastEpisode >= snap.tmdbTotal -> "全剧收齐" to TaskColors.ok()
        snap.tmdbLatest > 0 && snap.localLastEpisode >= snap.tmdbLatest -> "已最新" to TaskColors.ok()
        snap.tmdbLatest > 0 -> "待更新" to TaskColors.behind()
        // 没绑 TMDB：不讲「缺几集」，只讲这一轮的结果
        snap.status == SnapshotEntity.STATUS_LOCAL_ADDED -> "已转存" to TaskColors.ok()
        snap.status == SnapshotEntity.STATUS_LOCAL_LATEST -> "已是最新" to TaskColors.ok()
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
private fun RunPhaseText(text: String, color: Color, animated: Boolean = true) {
    // 省略号单独一个 Text 只做透明度呼吸（0.35 ↔ 1，1.2s 来回）：
    // 字数不变 → 宽度不变，右边「周二 · 光鸭 · 自动」不会被推来推去。
    val transition = rememberInfiniteTransition(label = "runPhase")
    val dotsAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dots",
    )
    val alpha = if (animated) dotsAlpha else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
        if (animated) {
            Text(
                text = "…",
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun Dot() {    Text(
        text = " · ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun statusColor(snap: SnapshotEntity): Color = when (snap.status) {
    SnapshotEntity.STATUS_FINISHED,
    SnapshotEntity.STATUS_UP_TO_DATE -> TaskColors.ok()
    SnapshotEntity.STATUS_LOCAL_ADDED,
    SnapshotEntity.STATUS_LOCAL_LATEST -> TaskColors.local()
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

/**
 * 三段进度条，分母统一为 TMDB 总集数，铺满整条：
 *  绿 = 盘里最大集号追到的进度；黄 = 已播到最新但最大集号还没追上；浅橘粉 = 还没播出。\n * 中间断了几集不会让绿条变短——进度条只回答「追到第几集」。
 * 没绑 TMDB（拿不到总集数）时退回单段绿条，分母用最新集号，避免画出一条「假的三段」。
 */
@Composable
private fun EpisodeBar(snap: SnapshotEntity) {
    val total = snap.tmdbTotal
    if (total <= 0) {
        // 没绑 TMDB：没有总集数当分母，画一条蓝色整条表示「跑过、正常」；
        // 从未跑过（UNKNOWN）才留空条，避免把空白伪装成「已同步」。
        val done = snap.status == SnapshotEntity.STATUS_LOCAL_ADDED ||
            snap.status == SnapshotEntity.STATUS_LOCAL_LATEST
        LinearProgressIndicator(
            progress = { if (done) 1f else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (done) TaskColors.local() else MaterialTheme.colorScheme.outline,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        return
    }
    val seg = com.ashchan.cloudsaver.data.episodeSegments(
        localLastEpisode = snap.localLastEpisode,
        tmdbLatest = snap.tmdbLatest,
        tmdbTotal = total,
    )
    val parts = listOf(
        seg.have to TaskColors.ok(),
        seg.missing to TaskColors.behind(),
        seg.unaired to MaterialTheme.colorScheme.primaryContainer,
    ).filter { it.first > 0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        parts.forEach { (count, color) ->
            Box(
                modifier = Modifier
                    .weight(count.toFloat())
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

private fun episodeText(ep: Int): String = if (ep > 0) "E$ep" else "E—"

/** 卡片底行：「周几 · 网盘」，多源再加「· 2 源」，时间已挪到上一行右端 */
private fun metaLeft(task: TaskEntity, sourceCount: Int = 1): String {
    val parts = mutableListOf(
        Weekdays.label(task.weekdays),
        driveLabel(task.driveType),
    )
    // 多源：源只是多几个链接入口，规则仍是同一套，所以这里只报个数
    if (sourceCount > 1) parts += "$sourceCount 源"
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






