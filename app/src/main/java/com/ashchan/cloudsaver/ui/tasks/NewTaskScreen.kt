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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.tmdb.TmdbSearchItem
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.Weekdays
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 新建追剧任务（全屏页）。
 *
 * 为什么从弹窗改成整页：字段从 5 个涨到 8 个（含目录浏览、规则下拉、TMDB 搜索），
 * 窄屏 AlertDialog 里既放不下也滚不顺；而且「保存路径 / TMDB 搜索」都要二级弹层，
 * 弹窗套弹窗在手机上很难用。
 *
 * 所有输入框的说明文案都放进 `placeholder`，不再单独占一行 `label`，一屏能多放两个字段。
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
    var ruleKey by remember { mutableStateOf("") }
    var tmdbId by remember { mutableStateOf("") }
    var tmdbTitle by remember { mutableStateOf("") }
    var airTime by remember { mutableStateOf("") }
    var weekdays by remember { mutableStateOf(emptySet<Int>()) }

    var showPathPicker by remember { mutableStateOf(false) }
    var showRulePicker by remember { mutableStateOf(false) }
    var showTmdbSearch by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var manualTmdbId by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }

    val rules = remember { viewModel.renameRules() }
    val ruleLabel = rules.firstOrNull { it.key == ruleKey }?.let { it.name }
        ?: "不改名（保持原文件名）"

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
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                placeholder = { Text("剧名（同时作为网盘文件夹名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    error = null
                },
                placeholder = { Text("夸克 / 光鸭分享链接，可带提取码") },
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
                subtitle = "任务会在该目录下建「剧名」文件夹",
                onClick = { showPathPicker = true },
            )

            // 命名规则
            SectionLabel("命名规则")
            SettingRow(
                icon = { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                title = ruleLabel,
                subtitle = if (ruleKey.isBlank()) {
                    "分享里叫什么就存什么，一个文件都不改"
                } else {
                    "只重命名视频文件，目录层级保持不变"
                },
                onClick = { showRulePicker = true },
            )

            // TMDB
            SectionLabel("TMDB（用于算最新集与更新日）")
            if (!manualTmdbId) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = if (showTmdbSearch) "" else tmdbTitle,
                        onValueChange = {},
                        readOnly = true,
                        placeholder = { Text("点右侧搜索，按剧名选剧") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showTmdbSearch = true },
                        enabled = viewModel.hasTmdb(),
                    ) { Text("搜索") }
                }
                if (!viewModel.hasTmdb()) {
                    Text(
                        text = "还没配置 TMDB：到「设置 → TMDB」填凭证（国内需自建反代），或改用下面的手填 ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { manualTmdbId = true }) { Text("改为手填 TMDB ID") }
                } else if (tmdbId.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "已绑定：$tmdbTitle（ID $tmdbId）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            tmdbId = ""
                            tmdbTitle = ""
                        }) { Text("清除") }
                    }
                }
            } else {
                OutlinedTextField(
                    value = tmdbId,
                    onValueChange = { tmdbId = it },
                    placeholder = { Text("手填 TMDB ID（纯数字，如 1399）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { manualTmdbId = false }) { Text("改用搜索") }
            }

            // 更新日
            SectionLabel("更新日")
            listOf(1..4, 5..7).forEach { range ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    range.forEach { day ->
                        FilterChip(
                            selected = day in weekdays,
                            onClick = {
                                weekdays = if (day in weekdays) weekdays - day else weekdays + day
                            },
                            label = {
                                Text(
                                    Weekdays.nameOf(day).removePrefix("周"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { weekdays = Weekdays.everyDay() }) { Text("每天") }
                TextButton(onClick = { weekdays = Weekdays.workdays() }) { Text("工作日 1-5") }
                TextButton(onClick = { weekdays = emptySet() }) { Text("清空（按 TMDB 推断）") }
            }

            // 播出时刻
            SectionLabel("播出时刻")
            SettingRow(
                icon = { Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.primary) },
                title = airTime.ifBlank { "未设置" },
                subtitle = "点一下选几点几分（仅记录，提醒逻辑在二期）",
                onClick = { showTimePicker = true },
            )
            if (airTime.isNotBlank()) {
                TextButton(onClick = { airTime = "" }) { Text("清除播出时刻") }
            }

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

            Spacer(Modifier.height(8.dp))
            Text(
                text = "保存后会先扫一遍分享，确认盘里已有多少集。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

    if (showRulePicker) {
        RulePickerDialog(
            rules = rules,
            selected = ruleKey,
            onDismiss = { showRulePicker = false },
            onPick = {
                ruleKey = it
                showRulePicker = false
            },
        )
    }

    if (showTmdbSearch) {
        TmdbSearchDialog(
            viewModel = viewModel,
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

/** 一行可点的设置项（标题 + 说明），用来省掉输入框的 label 行 */
@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    Text("请先选择网盘（或先粘贴链接让它自动识别）")
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
private fun RulePickerDialog(
    rules: List<RenameRule>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("命名规则") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OptionRow(
                    title = "不改名（保持原文件名）",
                    subtitle = "转存进来是什么名字就是什么名字",
                    selected = selected.isBlank(),
                    onClick = { onPick("") },
                )
                HorizontalDivider()
                rules.forEach { rule ->
                    OptionRow(
                        title = rule.name,
                        subtitle = if (rule.enabled) {
                            "替换模板：${rule.replace}"
                        } else {
                            "已在设置页禁用，选了也不会生效"
                        },
                        selected = selected == rule.key,
                        enabled = rule.enabled,
                        onClick = { onPick(rule.key) },
                    )
                    HorizontalDivider()
                }
                if (rules.isEmpty()) {
                    Text(
                        "还没有可用规则，可在设置页添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableRow(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TmdbSearchDialog(
    viewModel: TasksViewModel,
    onDismiss: () -> Unit,
    onPick: (TmdbSearchItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TmdbSearchItem>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索剧集") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; error = null },
                        placeholder = { Text("输入剧名，如 斗破苍穹") },
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
                            "没搜到，换个名字试试（英文名往往更准）",
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
                                            append(" · TMDB ${item.id}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (item.overview.isNotBlank()) {
                                        Text(
                                            item.overview.take(60),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
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

/** 行级点击（带 enabled 控制），避免每处都写 .clickable + interactionSource */
private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)
