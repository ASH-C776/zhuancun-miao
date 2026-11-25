package com.ashchan.cloudsaver.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import com.ashchan.cloudsaver.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.BasicTextField
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
    /** 调试用：进页就摊开 N 行空链接（截屏验收多源排版），1 = 单行 */
    startSourceRows: Int = 1,
    onSelectTab: (com.ashchan.cloudsaver.ui.MainTab) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val editing = editingTask != null
    // 从「添加」切到别的栏目再回来时，用快照把新建表单回填（编辑已有任务不参与）
    val stash = remember { if (editingTask == null) viewModel.takeDraftForm() else null }
    // 「运行一次」的三段反馈：跑的时候按钮置灰 + 按钮上方显示阶段文字；跑完在同一位置留一行结果
    val draftRunning by viewModel.draftRunning.collectAsState()
    val draftPhase by viewModel.draftPhase.collectAsState()
    val draftResult by viewModel.draftResult.collectAsState()
    // 换表单内容（改了链接/端口/规则）就清掉上一次的结果行，避免旧结论挂在新的参数上
    LaunchedEffect(draftRunning) { if (draftRunning) viewModel.clearDraftResult() }

    var name by remember { mutableStateOf(stash?.name ?: editingTask?.name ?: "") }
    // 多源：第 1 行是默认链接（右端「+」追加），第 2~5 行是附加源（右端「−」删除）
    // 每行带稳定 id，删中间行时不会串内容
    var nextSlotId by remember { mutableStateOf(maxOf(startSourceRows.coerceIn(1, MAX_SOURCES).toLong(), (stash?.links?.size ?: 0).toLong())) }
    val slots = remember {
        mutableStateListOf<LinkSlot>().apply {
            val restored = stash?.links.orEmpty()
            if (restored.isNotEmpty()) {
                restored.take(MAX_SOURCES).forEachIndexed { i, text -> add(LinkSlot(id = i.toLong(), text = text)) }
            } else {
                // 新建默认 1 行；编辑时先用任务里的主链接占位，多源由下面的 LaunchedEffect 回填
                add(LinkSlot(id = 0L, text = editingTask?.shareUrl ?: ""))
                repeat((startSourceRows - 1).coerceIn(0, MAX_SOURCES - 1)) { i ->
                    add(LinkSlot(id = i + 1L, text = ""))
                }
            }
        }
    }
    var drive by remember {
        mutableStateOf(stash?.drive?.let { DriveType.fromName(it) } ?: editingTask?.let { DriveType.fromName(it.driveType) })
    }
    var drivePickedByUser by remember { mutableStateOf(stash != null || editingTask != null) }
    var saveFid by remember { mutableStateOf(stash?.saveFid ?: editingTask?.saveFid ?: "0") }
    var savePathLabel by remember {
        mutableStateOf(stash?.savePathLabel ?: editingTask?.savePathLabel?.ifBlank { "网盘根目录" } ?: "网盘根目录")
    }
    var seasonNo by remember {
        mutableStateOf(stash?.seasonNo ?: editingTask?.seasonNo ?: TaskEntity.SEASON_FLAT)
    }
    var folderWithTmdbId by remember { mutableStateOf(stash?.folderWithTmdbId ?: editingTask?.folderWithTmdbId ?: false) }
    var ruleKey by remember { mutableStateOf(stash?.ruleKey ?: editingTask?.ruleKey ?: "") }
    var tmdbId by remember { mutableStateOf(stash?.tmdbId ?: editingTask?.tmdbId?.toString() ?: "") }
    var tmdbTitle by remember { mutableStateOf(stash?.tmdbTitle ?: "") }
    // 播出时刻字段已删除（不参与任何执行逻辑）；TaskEntity 里的列保留但不再读写
    var weekdays by remember {
        mutableStateOf(
            stash?.weekdays?.let { Weekdays.parse(it) }
                ?: editingTask?.let { Weekdays.parse(it.weekdays) }
                ?: emptySet()
        )
    }
    // 用户手动点过星期后，就不再让 TMDB 的播出日覆盖
    var weekdaysManual by remember { mutableStateOf(stash?.weekdaysManual ?: !editingTask?.weekdays.isNullOrBlank()) }
    // 更新日是否交给 TMDB 自动（新建默认自动；编辑时看原任务模式）
    var updateModeAuto by remember {
        mutableStateOf(stash?.updateModeAuto ?: (editingTask?.updateMode != TaskEntity.UPDATE_MODE_MANUAL))
    }

    // 新建任务页的表单随改随存一份快照（离开页面时组合被销毁，切 tab 回来靠它回填）
    LaunchedEffect(editing, name, slots.map { it.text }, drive, saveFid, savePathLabel, seasonNo, folderWithTmdbId, ruleKey, tmdbId, tmdbTitle, weekdays, weekdaysManual, updateModeAuto) {
        if (editing) return@LaunchedEffect
        viewModel.stashDraftForm(
            TasksViewModel.DraftForm(
                name = name,
                links = slots.map { it.text.trim() },
                drive = drive?.name,
                saveFid = saveFid,
                savePathLabel = savePathLabel,
                seasonNo = seasonNo,
                folderWithTmdbId = folderWithTmdbId,
                ruleKey = ruleKey,
                tmdbId = tmdbId,
                tmdbTitle = tmdbTitle,
                weekdays = Weekdays.format(weekdays),
                weekdaysManual = weekdaysManual,
                updateModeAuto = updateModeAuto,
            )
        )
    }

    var showPathPicker by remember { mutableStateOf(openPathPickerOnStart) }
    var showTmdbSearch by remember { mutableStateOf(false) }
    var showTmdbHint by remember { mutableStateOf(false) }
    var showCustomSeason by remember { mutableStateOf(false) }
    var seasonInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 命名规则只留「默认 / 通用命名」两项，后者被禁用则不出现
    val magic = remember { viewModel.renameRules().firstOrNull { it.key == "\$TV_MAGIC" && it.enabled } }
    val canUseTmdbFolder = magic != null && ruleKey == magic.key && tmdbId.isNotBlank()

    // 每个源的状态（与 slots 一一对应）：OK / WARN / FAIL / UNKNOWN，只用于右侧小圆点与一行提示
    val slotStates = remember {
        mutableStateListOf<SlotState>().apply {
            repeat(slots.size) { add(SlotState()) }
        }
    }
    // 编辑任务：把已保存的多行链接与各自检测状态带回来（老任务只有 1 行、状态 UNKNOWN）
    LaunchedEffect(editingTask?.id) {
        val id = editingTask?.id ?: return@LaunchedEffect
        if (id <= 0) return@LaunchedEffect
        val rows = viewModel.sourceRows(id)
        if (rows.isEmpty()) return@LaunchedEffect
        slots.clear()
        slotStates.clear()
        rows.take(MAX_SOURCES).forEach { row ->
            slots += LinkSlot(id = nextSlotId++, text = row.shareUrl)
            slotStates += SlotState(row.lastStatus, row.lastError)
        }
    }

    // 网盘按第一行自动识别：用户没手动选过保存位置时，跟着第一行链接走
    val firstLink = slots.firstOrNull()?.text.orEmpty()
    LaunchedEffect(firstLink) {
        if (drivePickedByUser) return@LaunchedEffect
        if (firstLink.isBlank()) return@LaunchedEffect
        TaskDraftParser.inspect(firstLink).onSuccess { drive = it.driveType }
    }

    BackHandler { onDone() }

    // 每行的就地校验：无法识别 / 与前面某行重复 / 不是本任务的网盘
    fun slotError(index: Int, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parsed = TaskDraftParser.inspect(trimmed)
        if (parsed.isFailure) return "链接无法识别"
        val type = parsed.getOrThrow().driveType
        val taskDrive = drive
        if (taskDrive != null && type != taskDrive) {
            return "不是${taskDrive.displayName}链接，一个任务只能用一个网盘"
        }
        val dupIndex = slots.take(index).indexOfFirst { it.text.trim() == trimmed }
        if (dupIndex >= 0) return "与第 ${dupIndex + 1} 行相同"
        return null
    }

    fun submit(mode: SubmitMode) {
        val title = name.trim()
        val urls = slots.map { it.text.trim() }.filter { it.isNotEmpty() }
        when {
            title.isBlank() -> error = "请填写任务名称"
            urls.isEmpty() -> error = "请粘贴分享链接"
            else -> {
                val badIndex = slots.indices.firstOrNull { slotError(it, slots[it].text) != null } ?: -1
                if (badIndex >= 0) {
                    error = slotError(badIndex, slots[badIndex].text)
                    return
                }
                val effectiveFolder = folderWithTmdbId && canUseTmdbFolder
                when {
                    mode == SubmitMode.RUN -> viewModel.runDraft(
                        name = title,
                        links = urls,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = effectiveFolder,
                        driveOverride = drive,
                    )

                    editing -> viewModel.saveEdit(
                        name = title,
                        links = urls,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
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
                        links = urls,
                        weekdays = Weekdays.format(weekdays),
                        tmdbIdText = tmdbId,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = effectiveFolder,
                        driveOverride = drive,
                        updateModeAuto = updateModeAuto,
                    )
                }
                if (mode == SubmitMode.SAVE) {
                    // 落库成功就不再需要草稿快照，下次进「添加」应是空白新表单
                    viewModel.clearDraftForm()
                    onDone()
                }
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
        // 底栏 = 动作行（运行一次 / 保存）+ 四栏底栏；系统手势条只在最底部避让一次
        bottomBar = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // 跑的过程中这里是阶段文字（蓝色、不带胶囊），跑完换成结果行（绿 / 红），常驻到下次再跑
                        val phase = draftPhase
                        val result = draftResult
                        if (draftRunning && phase != null) {
                            DraftRunText(text = phase, color = TaskColors.local(), animated = !phase.any { it.isDigit() })
                        } else if (result != null) {
                            DraftRunText(
                                text = result.text,
                                color = if (result.ok) TaskColors.ok() else MaterialTheme.colorScheme.error,
                                animated = false,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 两个按钮同一规格：48dp 高、并排等宽；「运行一次」描边、「保存」实心
                            OutlinedButton(
                                onClick = { submit(SubmitMode.RUN) },
                                enabled = !draftRunning,
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
                                enabled = !draftRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("保存", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                com.ashchan.cloudsaver.ui.AppTabBar(
                    selected = com.ashchan.cloudsaver.ui.MainTab.ADD,
                    onSelect = onSelectTab,
                )
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
            // 任务名称 + 分享链接属于同一块，给它一个「任务」分区标题，与下面「保存位置」等分区同一层级
            SectionLabel("任务")
            // 任务名称：左边输入，右边一个独立方块按钮（放大镜）——与下面链接行的按钮同一条竖线
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactStringField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    placeholder = "任务名称",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SquareActionButton(
                    contentDescription = "搜索剧集",
                    onClick = {
                        if (viewModel.hasTmdb()) showTmdbSearch = true else showTmdbHint = true
                    },
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
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

            // 多源链接：所有行左边缘/右边缘/等宽，右侧一列独立方块按钮（第 1 行「+」，其余行「−」）
            // 行与行靠 placeholder 区分，不再放「源N」序号；一个任务只有一套规则
            slots.forEachIndexed { index, slot ->
                val rowError = slotError(index, slot.text)
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactValueField(
                            value = TextFieldValue(slot.text, selection = slot.selection),
                            onValueChange = { value ->
                                slots[index] = slot.copy(text = value.text, selection = value.selection)
                                error = null
                            },
                            placeholder = if (index == 0) "分享链接" else "附加源链接",
                            isError = rowError != null,
                            status = slotStates.getOrNull(index)?.status ?: "UNKNOWN",
                            modifier = Modifier.weight(1f),
                            // 失焦后光标回到开头，这样长链接默认显示的是前段，而不是尾部
                            onBlur = {
                                slots[index] = slots[index].copy(selection = TextRange(0))
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        if (index == 0) {
                            SquareActionButton(
                                contentDescription = "添加源",
                                enabled = slots.size < MAX_SOURCES,
                                onClick = {
                                    if (slots.size < MAX_SOURCES) {
                                        slots += LinkSlot(id = nextSlotId++, text = "")
                                        slotStates += SlotState()
                                    }
                                    error = null
                                },
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            SquareActionButton(
                                contentDescription = "删除这一行",
                                onClick = {
                                    slots.removeAt(index)
                                    if (index < slotStates.size) slotStates.removeAt(index)
                                    error = null
                                },
                            ) {
                                // 没有 Material 的减号图标（core 包只有 Add/Delete），用等宽的半角减号字符，
                                // 与「+」的线宽、粗细看起来一致
                                Text(
                                    "−",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    rowError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // 这个源上次没连上（WARN/FAIL）：只给一行原因，不刷屏；正常源不出现任何字样
                    val state = slotStates.getOrNull(index)
                    if (state != null && state.isTrouble && state.error.isNotBlank() && rowError == null) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.status == "FAIL") {
                                MaterialTheme.colorScheme.error
                            } else {
                                warnColor()
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // 保存位置：网盘 + 目录合成一行，点进去从网盘根开始逐层下钻
            SectionLabel("保存位置")
            SettingRow(
                icon = {
                    Icon(
                        Icons.Default.Home,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
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

            // 季度三选一：平铺 / 输入季数（含 0 = 特别版）/ 多季；按钮下用一行小黑字说明落盘结构
            SectionLabel("季度")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChoice(
                    "平铺",
                    seasonNo == TaskEntity.SEASON_FLAT,
                    Modifier.weight(1f),
                ) { seasonNo = TaskEntity.SEASON_FLAT }
                SmallChoice(
                    text = "输入季数",
                    selected = seasonNo >= TaskEntity.SEASON_SPECIALS,
                    modifier = Modifier.weight(1f),
                ) {
                    // 已经填过季数就把旧值带出来，省得每次重敲
                    seasonInput = if (seasonNo >= TaskEntity.SEASON_SPECIALS) seasonNo.toString() else ""
                    showCustomSeason = true
                }
                SmallChoice(
                    "多季",
                    seasonNo == TaskEntity.SEASON_MULTI,
                    Modifier.weight(1f),
                ) { seasonNo = TaskEntity.SEASON_MULTI }
            }
            FieldHint(
                when {
                    seasonNo == TaskEntity.SEASON_MULTI -> "剧名/Season 1/…、Season 2/…"
                    seasonNo >= TaskEntity.SEASON_SPECIALS -> "剧名/Season $seasonNo/视频文件"
                    else -> "剧名/视频文件"
                }
            )

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
            FieldHint(
                when {
                    folderWithTmdbId -> "剧名 tmdb-12345/……"
                    ruleKey == magic?.key -> "剧名/……/剧名.S01E01.扩展名"
                    else -> "原名原目录"
                }
            )

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
                            .height(ROW_HEIGHT)
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
                // 绑定 TMDB 后任务名直接用「官方名 (年份)」：目录名、文件名都跟着带年份，
                // 同名翻拍（征途 2019 / 征途 2026）不会在同一个保存位置里混成一堆
                name = item.title()
                error = null
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
                Column {
                    OutlinedTextField(
                        value = seasonInput,
                        onValueChange = { seasonInput = it.filter { c -> c.isDigit() } },
                        placeholder = { Text("季度数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 0 是真季（TMDB Season 0 = 特别篇），跟「平铺」不是一回事，这里必须写清
                    Text(
                        "0 = 特别版（Season 0）；填 1、2 也行；不选季度请用「平铺」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    seasonInput.toIntOrNull()?.takeIf { it in 0..99 }?.let { seasonNo = it }
                    showCustomSeason = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSeason = false }) { Text("取消") }
            },
        )
    }

    // 多季试跑的季号冲突确认：只在这种「文件名说 S02、文件夹写 Season 1」的真矛盾时出现
    val seasonConflict by viewModel.draftConflict.collectAsState()
    seasonConflict?.let { info ->
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { viewModel.dismissDraftSeasons() },
            title = { Text("季号对不上，需要你确认", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "共 ${info.totalVideos} 个视频，其中 ${info.count} 个文件的季号和所在目录不一致：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    info.examples.forEach { example ->
                        Text(
                            "· $example",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "文件名里的 S02E01 通常比文件夹名可信，确认后按文件名分季保存；" +
                            "想把这次跑完再改也不会动已转存的文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDraftSeasonsByFile() }) { Text("按文件名") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDraftSeasons() }) { Text("取消") }
            },
        )
    }
}

private enum class SubmitMode { SAVE, RUN }

/** 一个任务最多几个分享链接（用户口径：5 个以内，硬限制） */
private const val MAX_SOURCES = 5

/**
 * 输入框与右侧方块按钮的统一高度。
 *
 * 设计逻辑：文字是 16sp（bodyLarge），56dp 的默认框高会让文字上下各空 20dp，看起来「框大、字小」；
 * 收到 48dp 后上下各 16dp，文字既不挤又有呼吸感，且正好和 48dp 的按钮同高，
 * 一行是「框 + 按钮」而不是「大框挂小扣子」。48dp 同时是规范里的最小可点尺寸。
 */
private val ROW_HEIGHT = 48.dp

/** 链接行：带稳定 id，删中间行不会串内容；selection 用来让长链接默认露前段 */
private data class LinkSlot(
    val id: Long,
    val text: String = "",
    val selection: TextRange = TextRange(0),
)

// ---------------- 子块 ----------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 紧凑输入框（文字版）：48dp 高、12dp 圆角、1dp 描边，文字垂直居中。
 *
 * 为什么不用 OutlinedTextField：它内部有 56dp 的最小高度和上下各 16dp 的内边距，
 * 硬压到 48dp 会把文字上下切掉（真机截图上「任务名称」顶部被裁）。
 * 这里改用 BasicTextField + 自绘边框，高度完全由我们说了算，文字自然居中不裁。
 */
@Composable
private fun CompactStringField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(ROW_HEIGHT)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * 紧凑输入框（TextFieldValue 版）：链接行要用光标位置控制「露哪一段」，所以保留 selection。
 *
 * status 只影响文字观感（不动框宽）：失效（FAIL）文字转红并划一道红线，
 * 「上次没连上」（WARN）文字转琥珀并划琥珀线，其余状态不划。
 * 早期版本在这里放了一个状态小圆点，但它会占掉框的宽度使各行不等宽，已废弃。
 */
@Composable
private fun CompactValueField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    status: String = "UNKNOWN",
    onBlur: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    }
    val struck = status == "FAIL" || status == "WARN"
    val textColor = when (status) {
        "FAIL" -> MaterialTheme.colorScheme.error
        "WARN" -> warnColor()
        else -> MaterialTheme.colorScheme.onSurface
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = textColor,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(ROW_HEIGHT)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) onBlur()
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = if (focused && !isError) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** 链接行的检测状态（只有提示作用，不影响转存） */
private data class SlotState(
    val status: String = "UNKNOWN",
    val error: String = "",
) {
    val isTrouble: Boolean get() = status == "WARN" || status == "FAIL"
}

/** 琥珀色（与卡片「缺 N 集」同一档），用于「上次没连上」这种不至于判死的提示 */
@Composable
private fun warnColor(): androidx.compose.ui.graphics.Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) {
        androidx.compose.ui.graphics.Color(0xFFE0A94F)
    } else {
        androidx.compose.ui.graphics.Color(0xFFB26A00)
    }

/**
 * 输入框右侧的独立方块按钮（48dp，圆角 12，1dp 描边，图标 20dp）。
 *
 * 口径：从输入框里拆出来单独成一列，任务名「搜索」与链接行「加 / 减」用同一规格，
 * 这样右侧是一列齐整的方块，而不是有的挂在框里、有的是实心图标。
 */
@Composable
private fun SquareActionButton(
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(ROW_HEIGHT)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
    }
}

/** 一行可点的设置项，用来省掉输入框的 label 行；高度与输入框统一 48dp */
@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickableRow(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 选择行下方的一行说明（如 `剧名/Season 1/视频文件`）。
 * 统一小黑字：bodySmall + onSurfaceVariant，左对齐，不常驻弹窗、不淡出。
 */
@Composable
private fun FieldHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
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
            // 规范：可点元素最小 48dp 热区；圆角统一 12；高度与输入框 / 设置行一致
            .height(ROW_HEIGHT)
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
        title = { Text("匹配 TMDB 剧集", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
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

/**
 * 动作行上方那句状态字：跑的过程中是阶段文字（准备中… / 转存 6/26…），跑完是结果行（完成 / 失败）。
 * 不带胶囊、不带底色，同一个位置直接换字；没有数字的步骤让省略号做透明度呼吸。
 */
@Composable
private fun DraftRunText(text: String, color: Color, animated: Boolean) {
    val alpha = if (animated) {
        val transition = rememberInfiniteTransition(label = "draftRunDots")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 600), RepeatMode.Reverse),
            label = "draftRunDotsAlpha",
        ).value
    } else {
        1f
    }
    Text(
        text = text,
        color = color.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    )
}




