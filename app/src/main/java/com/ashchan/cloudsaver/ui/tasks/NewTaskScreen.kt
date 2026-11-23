package com.ashchan.cloudsaver.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.painterResource
import com.ashchan.cloudsaver.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.tmdb.TmdbSearchItem
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.TaskEntity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 新增 / 编辑任务（同一个全屏页，编辑时预填已有任务）。
 *
 * 文案策略：能省的都省，输入框说明只放 `placeholder`，不单独占 `label` 行。
 * 顶栏两个动作：保存（落库）/ 运行（不保存，只把当前内容转存一次）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(
    viewModel: TasksViewModel,
    onDone: () -> Unit,
    editingTask: TaskEntity? = null,
    /** 调试用：进页直接弹「保存位置」全屏选择（截屏验收沉浸效果） */
    openPathPickerOnStart: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val editing = editingTask != null

    var name by remember { mutableStateOf(editingTask?.name ?: "") }
    var link by remember { mutableStateOf(editingTask?.shareUrl ?: "") }
    // 链接框单行显示，用光标位置控制「露哪一段」：失焦回到开头，长链接只显示前面
    var linkSelection by remember { mutableStateOf(TextRange(0)) }
    var drive by remember {
        mutableStateOf(editingTask?.let { DriveType.fromName(it.driveType) })
    }
    var drivePickedByUser by remember { mutableStateOf(editingTask != null) }
    var saveFid by remember { mutableStateOf(editingTask?.saveFid ?: "0") }
    var savePathLabel by remember {
        mutableStateOf(editingTask?.savePathLabel?.ifBlank { "网盘根目录" } ?: "网盘根目录")
    }
    var seasonNo by remember { mutableStateOf(editingTask?.seasonNo ?: 0) }
    var folderWithTmdbId by remember { mutableStateOf(editingTask?.folderWithTmdbId ?: false) }
    var ruleKey by remember { mutableStateOf(editingTask?.ruleKey ?: "") }
    var tmdbId by remember { mutableStateOf(editingTask?.tmdbId?.toString() ?: "") }
    var tmdbTitle by remember { mutableStateOf("") }
    var airTime by remember { mutableStateOf(editingTask?.airTime ?: "") }
    var weekdays by remember {
        mutableStateOf(editingTask?.let { Weekdays.parse(it.weekdays) } ?: emptySet())
    }
    // 用户手动点过星期后，就不再让 TMDB 的播出日覆盖
    var weekdaysManual by remember { mutableStateOf(!editingTask?.weekdays.isNullOrBlank()) }
    // 更新日是否交给 TMDB 自动（新建默认自动；编辑时看原任务模式）
    var updateModeAuto by remember {
        mutableStateOf(editingTask?.updateMode != TaskEntity.UPDATE_MODE_MANUAL)
    }

    var showPathPicker by remember { mutableStateOf(openPathPickerOnStart) }
    var showTmdbSearch by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showTmdbHint by remember { mutableStateOf(false) }
    var showCustomSeason by remember { mutableStateOf(false) }
    var seasonInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 命名规则只留「默认 / 通用命名」两项，后者被禁用则不出现
    val magic = remember { viewModel.renameRules().firstOrNull { it.key == "\$TV_MAGIC" && it.enabled } }
    val canUseTmdbFolder = magic != null && ruleKey == magic.key && tmdbId.isNotBlank()

    LaunchedEffect(link) {
        if (drivePickedByUser) return@LaunchedEffect
        if (link.isBlank()) return@LaunchedEffect
        TaskDraftParser.inspect(link).onSuccess { drive = it.driveType }
    }

    BackHandler { onDone() }

    fun submit(mode: SubmitMode) {
        val title = name.trim()
        val url = link.trim()
        when {
            title.isBlank() -> error = "请填写任务名称"
            url.isBlank() -> error = "请粘贴分享链接"
            TaskDraftParser.inspect(url).isFailure ->
                error = "链接无法识别，仅支持夸克 / 光鸭分享链接"
            else -> {
                val effectiveFolder = folderWithTmdbId && canUseTmdbFolder
                when {
                    mode == SubmitMode.RUN -> viewModel.runDraft(
                        name = title,
                        link = url,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
                        airTime = airTime,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = effectiveFolder,
                        driveOverride = drive,
                    )

                    editing -> viewModel.saveEdit(
                        name = title,
                        link = url,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
                        airTime = airTime,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = effectiveFolder,
                        driveOverride = drive,
                        updateModeAuto = updateModeAuto,
                    )

                    else -> viewModel.createTask(
                        name = title,
                        link = url,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
                        airTime = airTime,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = effectiveFolder,
                        driveOverride = drive,
                        updateModeAuto = updateModeAuto,
                    )
                }
                if (mode == SubmitMode.SAVE) onDone()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "编辑任务" else "新增任务", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 保存 / 运行一次已挪到右下拇指区（bottomBar），顶栏只留返回
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        // 右下角主按钮：保存（拇指区）；左侧「运行一次」图标（不保存只转存一轮）
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 两个按钮同一规格：48dp 高、并排等宽；「运行一次」描边、「保存」实心
                    OutlinedButton(
                        onClick = { submit(SubmitMode.RUN) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("运行一次", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { submit(SubmitMode.SAVE) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("保存", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        text = "已绑定：${tmdbTitle.ifBlank { "tmdb-$tmdbId" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        tmdbId = ""
                        tmdbTitle = ""
                        folderWithTmdbId = false
                    }) { Text("取消绑定") }
                }
            }

            // 分享链接：固定一行高，链接再长也只露前面，末尾顺着输入框裁掉/横滚
            OutlinedTextField(
                value = TextFieldValue(link, selection = linkSelection),
                onValueChange = {
                    link = it.text
                    linkSelection = it.selection
                    error = null
                },
                placeholder = { Text("分享链接") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // 失焦后光标回到开头，这样长链接默认显示的是前段，而不是尾部
                    .onFocusChanged { if (!it.isFocused) linkSelection = TextRange(0) },
            )

            // 保存位置：网盘 + 目录合成一行，点进去从网盘根开始逐层下钻
            SectionLabel("保存位置")
            SettingRow(
                icon = { Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary) },
                title = buildString {
                    val type = drive
                    if (type == null) {
                        append("选择网盘 / 目录")
                    } else {
                        append(type.displayName)
                        if (!viewModel.isLoggedIn(type)) append("（未登录）")
                        append(" / ")
                        append(savePathLabel)
                    }
                },
                onClick = { showPathPicker = true },
            )

            // 季度：不选 = 剧名/视频平铺；选第N季 = 剧名/Season N/视频平铺
            SectionLabel("季度")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChoice("不选", seasonNo == 0, Modifier.weight(1f)) { seasonNo = 0 }
                (1..3).forEach { n ->
                    SmallChoice("第$n 季", seasonNo == n, Modifier.weight(1f)) { seasonNo = n }
                }
                SmallChoice(
                    text = if (seasonNo > 3) "第${seasonNo}季" else "自定义",
                    selected = seasonNo > 3,
                    modifier = Modifier.weight(1f),
                ) {
                    seasonInput = if (seasonNo > 3) seasonNo.toString() else ""
                    showCustomSeason = true
                }
            }

            // 命名规则：默认 / 通用命名 / tmdb id，三项并排一行
            SectionLabel("命名规则")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChoice(
                    text = "默认",
                    selected = ruleKey.isBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    ruleKey = ""
                    folderWithTmdbId = false
                }
                SmallChoice(
                    text = "通用命名",
                    selected = ruleKey == magic?.key,
                    modifier = Modifier.weight(1f),
                ) { if (magic != null) ruleKey = magic.key }
                // 只有选了「通用命名」、且绑定了 TMDB 才能用：文件夹名变成 剧名 + tmdb-ID
                SmallChoice(
                    text = "tmdb id",
                    selected = folderWithTmdbId,
                    modifier = Modifier.weight(1f),
                    enabled = canUseTmdbFolder,
                ) { folderWithTmdbId = !folderWithTmdbId }
            }
            if (!canUseTmdbFolder) {
                Text(
                    text = if (ruleKey.isBlank()) {
                        "选「通用命名」后才能开启 tmdb id"
                    } else {
                        "先在名称框右侧搜索绑定剧集，才能开启 tmdb id"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 更新日：七个排一排；绑了 TMDB 可以一键重算
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("更新日")
                Spacer(Modifier.weight(1f))
                if (tmdbId.isNotBlank()) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val days = viewModel.tmdbWeekdays(tmdbId.toLong())
                                if (days.isNotEmpty()) {
                                    weekdays = days.toSet()
                                    weekdaysManual = false
                                    updateModeAuto = true
                                    error = null
                                } else {
                                    error = "没能推断更新日，检查 TMDB 凭证或网络后再试"
                                }
                            }
                        },
                    ) { Text("重算", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..7).forEach { day ->
                    val on = day in weekdays
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (on) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            )
                            .clickableRow {
                                weekdaysManual = true
                                updateModeAuto = false
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
        SaveLocationDialog(
            viewModel = viewModel,
            initialDrive = drive,
            onDismiss = { showPathPicker = false },
            onPick = { pickedDrive, fid, label ->
                drive = pickedDrive
                drivePickedByUser = true
                saveFid = fid
                savePathLabel = label
                showPathPicker = false
            },
        )
    }
    }

    if (showTmdbHint) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showTmdbHint = false },
            title = { Text("TMDB", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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
                // 选完剧就按 TMDB 推断的播出日点亮更新日（可能多天）；用户手动点过就不覆盖
                scope.launch {
                    val days = viewModel.tmdbWeekdays(item.id)
                    if (days.isNotEmpty() && !weekdaysManual) weekdays = days.toSet()
                }
            },
        )
    }

    if (showCustomSeason) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showCustomSeason = false },
            title = { Text("季度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = seasonInput,
                    onValueChange = { seasonInput = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("季度数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    seasonInput.toIntOrNull()?.takeIf { it > 0 }?.let { seasonNo = it }
                    showCustomSeason = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSeason = false }) { Text("取消") }
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
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showTimePicker = false },
            title = { Text("播出时刻", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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

private enum class SubmitMode { SAVE, RUN }

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
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

/** 等宽小选项（季度/星期这种一排多个），避免 chip 挤成竖排文字 */
@Composable
private fun SmallChoice(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            // 规范：可点元素最小 48dp 热区；圆角统一 12
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickableRow { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * 保存位置选择器（全屏）：顶部切网盘，面包屑逐级跳转，中间列子目录。
 * 确认后把「网盘 + 目录 fid + 可读路径」一起带回表单。
 */
@Composable
private fun SaveLocationDialog(
    viewModel: TasksViewModel,
    initialDrive: DriveType?,
    onDismiss: () -> Unit,
    onPick: (drive: DriveType, fid: String, label: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var drive by remember {
        mutableStateOf(initialDrive ?: DriveType.entries.firstOrNull { viewModel.isLoggedIn(it) })
    }
    var dirs by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var crumb by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var currentFid by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(type: DriveType?, fid: String) {
        if (type == null) return
        loading = true
        viewModel.listDirs(type, fid)
            .onSuccess { dirs = it; error = null }
            .onFailure { error = it.message ?: "加载目录失败" }
        loading = false
    }

    suspend fun jumpTo(target: List<Pair<String, String>>) {
        crumb = target
        val fid = target.lastOrNull()?.first ?: "0"
        currentFid = fid
        load(drive, fid)
    }

    LaunchedEffect(drive) {
        dirs = emptyList()
        if (drive != null) load(drive, "0")
    }

    val label = if (crumb.isEmpty()) "网盘根目录" else crumb.joinToString(" / ") { it.second }
    val type = drive

    // 不再起独立 Dialog 窗口：直接画在 Activity 同一个窗口里，与主页/设置页共用
    // enableEdgeToEdge，状态栏图标和底部小白条都是同一套沉浸效果（不会再冒出灰带）。
    // 返回即确认：点左上箭头或用手势返回，都把当前所在目录写回表单。
    // 但如果进来看了一眼就返回、没切过网盘也没下钻任何目录，则保持原有选择不动。
    fun commitOrDismiss() {
        val moved = type != initialDrive || crumb.isNotEmpty()
        if (type != null && moved) onPick(type, currentFid, label) else onDismiss()
    }
    BackHandler { commitOrDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                // 底色铺满整屏（含系统栏区域），正文让开两端的系统栏
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
                // 顶栏：返回即保存，不再单独放「选这里」
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { commitOrDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回并保存")
                    }
                    Text(
                        "保存位置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 网盘切换：未登录的置灰，点了给提示
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DriveType.entries.forEach { candidate ->
                        val loggedIn = viewModel.isLoggedIn(candidate)
                        FilterChip(
                            selected = drive == candidate,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { if (loggedIn) drive = candidate },
                            enabled = loggedIn,
                            label = {
                                Text(
                                    if (loggedIn) candidate.displayName else "${candidate.displayName}（未登录）",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }

                // 面包屑：每段都能点，点第一段回根目录
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (type != null) {
                        CrumbText("${type.displayName}") {
                            scope.launch { jumpTo(emptyList()) }
                        }
                        crumb.forEachIndexed { index, (_, name) ->
                            Text(
                                "›",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            CrumbText(name, selected = index == crumb.lastIndex) {
                                scope.launch { jumpTo(crumb.take(index + 1)) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 明确告知：返回就等于选中当前目录
                        Text(
                            "返回即保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            "没有已登录的网盘，请先在设置里登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider()

                LazyColumn(Modifier.weight(1f)) {
                    items(dirs, key = { it.fid }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow {
                                    scope.launch { jumpTo(crumb + (dir.fid to dir.name)) }
                                }
                                .padding(vertical = 16.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                dir.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (dirs.isEmpty() && !loading && error == null) {
                        item {
                            Text(
                                "这个目录下没有子文件夹，可以直接用当前目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
}

/** 面包屑里的一段，可点跳转 */
@Composable
private fun CrumbText(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickableRow(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("搜索剧集", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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
                    Spacer(Modifier.width(8.dp))
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
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                                        .padding(vertical = 12.dp),
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

/** 行级点击（带 enabled 控制），避免每处都写 .clickable + interactionSource */
private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)