package com.ashchan.cloudsaver.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.tmdb.TmdbSearchItem
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.Weekdays
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 新建追剧任务（全屏页）。
 *
 * 为什么从弹窗改成整页：字段多（含目录浏览、TMDB 搜索），窄屏 AlertDialog 里既放不下也滚不顺，
 * 而且「保存路径 / TMDB 搜索」都要二级弹层，弹窗套弹窗在手机上很难用。
 *
 * 文案策略：能省的都省，输入框说明只放 `placeholder`，不单独占 `label` 行，也不写解释性副标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(
    viewModel: TasksViewModel,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var drive by remember { mutableStateOf<DriveType?>(null) }
    // 用户手动点过网盘 chip 之后，就不再让链接识别覆盖它
    var drivePickedByUser by remember { mutableStateOf(false) }
    var saveFid by remember { mutableStateOf("0") }
    var savePathLabel by remember { mutableStateOf("网盘根目录") }
    var seasonNo by remember { mutableStateOf(0) }
    var folderWithTmdbId by remember { mutableStateOf(false) }
    var ruleKey by remember { mutableStateOf("") }
    var tmdbId by remember { mutableStateOf("") }
    var tmdbTitle by remember { mutableStateOf("") }
    var airTime by remember { mutableStateOf("") }
    var weekdays by remember { mutableStateOf(emptySet<Int>()) }

    var showPathPicker by remember { mutableStateOf(false) }
    var showTmdbSearch by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showTmdbHint by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }

    // 命名规则只留两项：不改名 / 通用视频命名；后者在设置页被禁用时不出现
    val magic = remember { viewModel.renameRules().firstOrNull { it.key == "\$TV_MAGIC" && it.enabled } }

    // 链接变化时自动识别网盘（用户手动选过则不覆盖）
    LaunchedEffect(link) {
        if (drivePickedByUser) return@LaunchedEffect
        if (link.isBlank()) return@LaunchedEffect
        TaskDraftParser.inspect(link).onSuccess { drive = it.driveType }
    }

    BackHandler { onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建追剧任务", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val title = name.trim()
                            val url = link.trim()
                            when {
                                title.isBlank() -> error = "请填写任务名称"
                                url.isBlank() -> error = "请粘贴分享链接"
                                TaskDraftParser.inspect(url).isFailure ->
                                    error = "链接无法识别，仅支持夸克 / 光鸭分享链接"
                                else -> {
                                    viewModel.createTask(
                                        name = title,
                                        link = url,
                                        weekdays = Weekdays.format(weekdays),
                                        tmdbIdText = tmdbId,
                                        airTime = airTime,
                                        saveFid = saveFid,
                                        savePathLabel = savePathLabel,
                                        ruleKey = ruleKey,
                                        seasonNo = seasonNo,
                                        folderWithTmdbId = folderWithTmdbId,
                                        driveOverride = drive,
                                    )
                                    onDone()
                                }
                            }
                        },
                    ) { Text("保存", fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 任务名称：右侧直接搜 TMDB（按当前输入的名字去搜，选中就绑上）
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                placeholder = { Text("任务名称") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (viewModel.hasTmdb()) showTmdbSearch = true else showTmdbHint = true
                        },
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "搜索剧集")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (tmdbId.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "已绑定：${tmdbTitle.ifBlank { tmdbId }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        tmdbId = ""
                        tmdbTitle = ""
                    }) { Text("取消绑定") }
                }
            }

            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    error = null
                },
                placeholder = { Text("分享链接") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // 网盘
            SectionLabel("网盘")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveType.entries.forEach { type ->
                    val loggedIn = viewModel.isLoggedIn(type)
                    FilterChip(
                        selected = drive == type,
                        onClick = {
                            drive = type
                            drivePickedByUser = true
                        },
                        label = {
                            Text(
                                if (loggedIn) type.displayName else "${type.displayName}（未登录）",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            // 保存路径
            SectionLabel("保存路径")
            SettingRow(
                icon = { Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary) },
                title = savePathLabel,
                onClick = { showPathPicker = true },
            )

            // 季度：不选 = 剧名/视频平铺；选第N季 = 剧名/Season N/视频平铺
            SectionLabel("季度")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChoice("不选", seasonNo == 0, Modifier.weight(1f)) { seasonNo = 0 }
                (1..4).forEach { n ->
                    SmallChoice("第$n 季", seasonNo == n, Modifier.weight(1f)) { seasonNo = n }
                }
            }

            // 命名规则
            SectionLabel("命名规则")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ruleKey.isBlank(),
                    onClick = { ruleKey = "" },
                    label = { Text("不改名", style = MaterialTheme.typography.labelMedium) },
                )
                if (magic != null) {
                    FilterChip(
                        selected = ruleKey == magic.key,
                        onClick = { ruleKey = magic.key },
                        label = { Text(magic.name, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            // 目录命名开关：勾了就是「剧名 tmdb-123」（单独一行，文字不换行）
            Row {
                FilterChip(
                    selected = folderWithTmdbId,
                    onClick = { folderWithTmdbId = !folderWithTmdbId },
                    enabled = tmdbId.isNotBlank(),
                    label = {
                        Text("文件夹加 TMDB ID", style = MaterialTheme.typography.labelMedium)
                    },
                )
            }

            // 更新日：七个排一排
            SectionLabel("更新日")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..7).forEach { day ->
                    val on = day in weekdays
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (on) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            )
                            .clickableRow {
                                weekdays = if (on) weekdays - day else weekdays + day
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            Weekdays.nameOf(day).removePrefix("周"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (on) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // 播出时刻
            SectionLabel("播出时刻")
            SettingRow(
                icon = { Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.primary) },
                title = airTime.ifBlank { "未设置" },
                onClick = { showTimePicker = true },
            )

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPathPicker) {
        PathPickerDialog(
            viewModel = viewModel,
            drive = drive,
            onDismiss = { showPathPicker = false },
            onPick = { fid, label ->
                saveFid = fid
                savePathLabel = label
                showPathPicker = false
            },
        )
    }

    if (showTmdbHint) {
        AlertDialog(
            onDismissRequest = { showTmdbHint = false },
            title = { Text("TMDB") },
            text = { Text("请先配置 TMDB") },
            confirmButton = {
                TextButton(onClick = { showTmdbHint = false }) { Text("知道了") }
            },
        )
    }

    if (showTmdbSearch) {
        TmdbSearchDialog(
            viewModel = viewModel,
            initialQuery = name.trim(),
            onDismiss = { showTmdbSearch = false },
            onPick = { item ->
                tmdbId = item.id.toString()
                tmdbTitle = item.title()
                showTmdbSearch = false
            },
        )
    }

    if (showTimePicker) {
        val initial = airTime.split(":")
        val state = rememberTimePickerState(
            initialHour = initial.getOrNull(0)?.toIntOrNull() ?: 20,
            initialMinute = initial.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("播出时刻") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    airTime = String.format(Locale.US, "%02d:%02d", state.hour, state.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }
}

// ---------------- 子块 ----------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一行可点的设置项，用来省掉输入框的 label 行 */
@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickableRow(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 目录选择弹层：从网盘根开始逐层下钻，底部确认当前目录。
 * 与旧 `FolderPickerScreen` 的区别是不占导航栈 —— 选完直接把 fid 带回表单。
 */
@Composable
private fun PathPickerDialog(
    viewModel: TasksViewModel,
    drive: DriveType?,
    onDismiss: () -> Unit,
    onPick: (fid: String, label: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dirs by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var crumb by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var currentFid by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(fid: String) {
        if (drive == null) return
        loading = true
        viewModel.listDirs(drive, fid)
            .onSuccess { dirs = it; error = null }
            .onFailure { error = it.message ?: "加载目录失败" }
        loading = false
    }

    LaunchedEffect(drive) {
        if (drive != null) load("0")
    }

    val label = if (crumb.isEmpty()) "网盘根目录" else crumb.joinToString(" / ") { it.second }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择保存路径") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (drive == null) {
                    Text("请先选择网盘")
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val parent = crumb.dropLast(1)
                            crumb = parent
                            val fid = parent.lastOrNull()?.first ?: "0"
                            currentFid = fid
                            scope.launch { load(fid) }
                        },
                        enabled = crumb.isNotEmpty(),
                    ) { Text(".. 上级") }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(dirs, key = { it.fid }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow {
                                    crumb = crumb + (dir.fid to dir.name)
                                    currentFid = dir.fid
                                    scope.launch { load(dir.fid) }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(dir.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (dirs.isEmpty() && !loading) {
                        item {
                            Text(
                                "这个目录下没有子文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(currentFid, label) },
                enabled = drive != null,
            ) { Text("选这个目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TmdbSearchDialog(
    viewModel: TasksViewModel,
    initialQuery: String,
    onDismiss: () -> Unit,
    onPick: (TmdbSearchItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<TmdbSearchItem>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 带着任务名进来自动搜一次，省一次点击
    LaunchedEffect(Unit) {
        if (query.isNotBlank()) {
            loading = true
            viewModel.searchTv(query)
                .onSuccess { results = it; error = null }
                .onFailure { error = it.message ?: "搜索失败"; results = null }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索剧集") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; error = null },
                        placeholder = { Text("剧名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            scope.launch {
                                loading = true
                                viewModel.searchTv(query)
                                    .onSuccess { results = it; error = null }
                                    .onFailure { error = it.message ?: "搜索失败"; results = null }
                                loading = false
                            }
                        },
                        enabled = query.isNotBlank() && !loading,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                results?.let { list ->
                    if (list.isEmpty()) {
                        Text(
                            "没搜到",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(list, key = { it.id }) { item ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickableRow { onPick(item) }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        item.title(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        buildString {
                                            if (item.originalName.isNotBlank() && item.originalName != item.name) {
                                                append(item.originalName)
                                            }
                                            if (item.seasons > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${item.seasons} 季")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 等宽小选项（季度/星期这种一排多个），避免 chip 挤成竖排文字 */
@Composable
private fun SmallChoice(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickableRow(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/** 行级点击（带 enabled 控制），避免每处都写 .clickable + interactionSource */
private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)
