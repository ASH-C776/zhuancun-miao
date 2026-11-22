@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ashchan.cloudsaver.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import com.ashchan.cloudsaver.storage.RecordStore
import com.ashchan.cloudsaver.storage.TransferRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import com.ashchan.cloudsaver.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.RuleMode
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore

/** ViewModel 工厂 */
class ScreenViewModelFactory(
    private val credentialStore: CredentialStore,
    private val ruleStore: RuleStore,
    private val recordStore: RecordStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(credentialStore, ruleStore, recordStore) as T
    }
}

/** 根导航 */
@Composable
fun AppRoot(
    viewModel: AppViewModel,
    themeMode: Int = THEME_AUTO,
    onThemeModeChange: (Int) -> Unit = {},
) {
    val screen by viewModel.screen.collectAsState()
    // 启动时后台检测一次 GitHub Releases 是否有新版本（有则红点提示）
    val appVersion = currentVersion()
    LaunchedEffect(Unit) { viewModel.checkUpdate(appVersion) }
    when (screen) {
        Screen.HOME -> HomeScreen(viewModel, themeMode, onThemeModeChange)
        Screen.HISTORY -> HistoryScreen(viewModel)
        Screen.SETTINGS -> SettingsScreen(viewModel)
        Screen.FOLDER_PICKER -> FolderPickerScreen(viewModel)
        Screen.ABOUT -> AboutScreen(viewModel)
        Screen.DONATE -> DonateScreen(viewModel)
    }
}

/** 当前 App 版本号（从 PackageManager 读取，避免依赖 BuildConfig） */
@Composable
fun currentVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}

/** 通用顶栏 */
@Composable
private fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("返回") }
            }
        },
        actions = {
            if (actions != null) actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** 按下时轻微变暗，让点击动态更明显 */
@Composable
private fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.7f),
        label = "pressAlpha",
    )
    return this.alpha(alpha)
}

/** 统一的点击反馈：水波纹（默认）+ 轻微缩放 + onClick，替代裸 .clickable */
@Composable
private fun Modifier.tappable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressScale(interactionSource, enabled)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

@Composable
private fun ErrorBanner(message: String?) {
    if (message != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ==================== 主页：链接 + 任务名 + 路径 + 规则 + 转存 ====================

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    themeMode: Int = THEME_AUTO,
    onThemeModeChange: (Int) -> Unit = {},
) {
    val account by viewModel.account.collectAsState()
    val driveType by viewModel.driveType.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val targetPath by viewModel.targetPath.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val dialog by viewModel.transferDialog.collectAsState()
    val link by viewModel.linkInput.collectAsState()
    val taskName by viewModel.taskNameInput.collectAsState()
    val previewDialog by viewModel.previewDialog.collectAsState()
    val records by viewModel.records.collectAsState()

    var showRuleDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    // 外部分享链接预填
    LaunchedEffect(Unit) {
        val external = viewModel.pendingExternalLink
        if (!external.isNullOrBlank()) {
            viewModel.setLinkInput(external)
            viewModel.pendingExternalLink = null
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "转存喵 v${currentVersion()}",
                actions = {
                    // 转存记录入口
                    IconButton(onClick = { viewModel.go(Screen.HISTORY) }) {
                        Icon(Icons.Filled.List, contentDescription = "转存记录")
                    }
                    // 主题切换：自动 → 亮 → 暗 循环，图标随模式变化
                    IconButton(onClick = { onThemeModeChange((themeMode + 1) % 3) }) {
                        Icon(
                            painter = painterResource(
                                when (themeMode) {
                                    THEME_LIGHT -> R.drawable.ic_theme_light
                                    THEME_DARK -> R.drawable.ic_theme_dark
                                    else -> R.drawable.ic_theme_auto
                                },
                            ),
                            contentDescription = "切换主题",
                        )
                    }
                    IconButton(onClick = { viewModel.go(Screen.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ErrorBanner(error)

            // 最近一次转存记录：一键回填链接/剧名/网盘，追剧不用重新粘贴
            val latestRecord = records.firstOrNull()
            if (latestRecord != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth().tappable { viewModel.refillRecord(latestRecord) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "最近转存：${latestRecord.taskName.ifBlank { "未命名任务" }}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatRecordTime(latestRecord.id)} · ${latestRecord.ruleName} · 成功 ${latestRecord.successCount}/${latestRecord.total}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "再来一次",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 第一行：分享链接（一行，超长省略）
            OutlinedTextField(
                value = link,
                onValueChange = { viewModel.setLinkInput(it) },
                label = { Text("分享链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            // 第二行：剧名
            OutlinedTextField(
                value = taskName,
                onValueChange = { viewModel.setTaskNameInput(it) },
                label = { Text("剧名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            // 网盘选择：左右两个卡片（光鸭 / 夸克），点击切换当前网盘
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DriveType.entries.forEach { type ->
                    val acc = accounts[type]
                    val selected = type == driveType
                    Card(
                        modifier = Modifier.weight(1f).tappable { viewModel.selectDriveOnHome(type) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        border = BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                type.displayName,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (acc != null) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    acc?.nickname ?: "未登录",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (acc != null) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 转存路径（一行简洁显示）
            Card(
                modifier = Modifier.fillMaxWidth().tappable { viewModel.goFolderPicker() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("转存路径", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                account == null -> "尚未登录 · 去设置"
                                else -> "${driveType?.displayName ?: "网盘"} / ${targetPath.ifEmpty { "根目录" }}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (account == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 重命名规则（点击弹框选择：保持原名 / 通用剧集命名 / 自定义）
            val selectedRule = rules.firstOrNull { it.enabled }
            val selectedRuleName = when (selectedRule?.key) {
                null -> "保持原名"
                "\$TV_MAGIC" -> "通用剧集命名"
                else -> selectedRule.name
            }
            Card(
                modifier = Modifier.fillMaxWidth().tappable { showRuleDialog = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "重命名规则",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            selectedRuleName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 开始转存（紧跟重命名规则下方；底部留白给后续其他信息）
            Button(
                onClick = { viewModel.startTransfer(link, taskName) },
                enabled = !busy && link.isNotBlank() && account != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("开始转存", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    dialog?.let { TransferDialog(it) { viewModel.dismissDialog() } }

    // 预命名预览弹窗：转存前确认「原名 → 新名」，可单条手改
    previewDialog?.let {
        PreviewDialog(
            state = it,
            onNewNameChange = { fid, name -> viewModel.updatePreviewName(fid, name) },
            onToggleIncluded = { fid -> viewModel.togglePreviewIncluded(fid) },
            onConfirm = { viewModel.confirmTransfer() },
            onCancel = { viewModel.cancelPreview() },
        )
    }

    // 重命名规则选择弹框：不使用 / 剧集集数 / 通用视频命名 / 自定义（拼装）
    if (showRuleDialog) {
        val noneSelected = rules.none { it.enabled }
        val tvMagicOn = rules.firstOrNull { it.key == "\$TV_MAGIC" }?.enabled == true
        val customOn = rules.firstOrNull { it.key == AppViewModel.CUSTOM_RULE_KEY }?.enabled == true
        AlertDialog(
            onDismissRequest = { showRuleDialog = false },
            title = { Text("选择重命名规则") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().tappable {
                            viewModel.selectNoRule(); showRuleDialog = false
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = noneSelected, onClick = {
                            viewModel.selectNoRule(); showRuleDialog = false
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("保持原名", fontWeight = FontWeight.Medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().tappable {
                            viewModel.selectRule("\$TV_MAGIC"); showRuleDialog = false
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tvMagicOn, onClick = {
                            viewModel.selectRule("\$TV_MAGIC"); showRuleDialog = false
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("通用剧集命名", fontWeight = FontWeight.Medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().tappable {
                            showRuleDialog = false
                            showCustomDialog = true
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = customOn, onClick = {
                            showRuleDialog = false
                            showCustomDialog = true
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("自定义（拼装格式）", fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRuleDialog = false }) { Text("关闭") }
            },
        )
    }

    // 自定义拼装弹框：勾选剧名 + 选择季度
    if (showCustomDialog) {
        CustomRuleDialog(
            currentTaskName = taskName,
            onDismiss = { showCustomDialog = false },
            onApply = { useTaskName, season ->
                viewModel.applyCustomRule(useTaskName, season)
                showCustomDialog = false
            },
        )
    }
}

/** 自定义规则拼装弹框 */
@Composable
private fun CustomRuleDialog(
    currentTaskName: String,
    onDismiss: () -> Unit,
    onApply: (useTaskName: Boolean, season: String?) -> Unit,
) {
    var useTaskName by remember { mutableStateOf(true) }
    var season by remember { mutableStateOf("S01") }
    var showCustomSeason by remember { mutableStateOf(false) }
    var customSeasonInput by remember { mutableStateOf("") }
    var seasonFieldFocused by remember { mutableStateOf(false) }
    val taskLabel = if (currentTaskName.isBlank()) "剧名" else currentTaskName
    val customSeasonValid = customSeasonInput.toIntOrNull()?.let { it in 1..99 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义命名格式") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 效果预览卡片
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (useTaskName) {
                            Text(
                                taskLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text("（无剧名）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(season, fontWeight = FontWeight.SemiBold)
                        Text("E01.mkv", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 组成元素
                Text(
                    "组成元素",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = useTaskName,
                        onClick = { useTaskName = !useTaskName },
                        label = { Text("剧名") },
                        leadingIcon = if (useTaskName) {
                            { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 季度选择：S01–S05 预设 + 第六个「自定义」输入任意季度；每行 3 个，共两行
                Text(
                    "季度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val seasonItems = (1..5).map { "S%02d".format(it) } + "自定义"
                seasonItems.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEachIndexed { col, item ->
                            val isCustom = item == "自定义"
                            FilterChip(
                                selected = !isCustom && season == item,
                                onClick = { if (isCustom) showCustomSeason = true else season = item },
                                label = {
                                    Text(
                                        item,
                                        maxLines = 1,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center,
                                    )
                                },
                                // 第三列稍宽（两行同宽，纵向仍对齐），保证「自定义」单行不折行
                                modifier = Modifier.weight(if (col == 2) 1.3f else 1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "集数自动识别，扩展名自动保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        dismissButton = {
            Button(onClick = { onApply(useTaskName, season) }) { Text("使用此格式") }
        },
    )

    // 自定义季度数字输入弹框
    if (showCustomSeason) {
        AlertDialog(
            onDismissRequest = { showCustomSeason = false },
            title = { Text("自定义季度") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "输入季度数字（1–99）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customSeasonInput,
                        onValueChange = { customSeasonInput = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("季度") },
                        prefix = { Text("S") },
                        placeholder = { if (!seasonFieldFocused) Text("如 3") },
                        singleLine = true,
                        isError = customSeasonInput.isNotEmpty() && !customSeasonValid,
                        supportingText = {
                            if (customSeasonInput.isNotEmpty() && !customSeasonValid) {
                                Text("请输入 1–99 之间的数字")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { seasonFieldFocused = it.isFocused },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        season = "S%02d".format(customSeasonInput.toInt())
                        showCustomSeason = false
                        customSeasonInput = ""
                    },
                    enabled = customSeasonValid,
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSeason = false }) { Text("取消") }
            },
        )
    }
}

// ==================== 设置：网盘登录配置 ====================

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val driveType by viewModel.settingsDriveType.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val account = accounts[driveType]
    val loading by viewModel.loginLoading.collectAsState()
    val error by viewModel.loginError.collectAsState()
    val update by viewModel.update.collectAsState()
    val smsState by viewModel.smsLoginState.collectAsState()

    // 系统返回键回主页（而不是退出 App）
    BackHandler { viewModel.go(Screen.HOME) }

    var accessToken by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var showQuarkCookie by remember { mutableStateOf(false) }

    // 凭证脱敏显示：前 9 位可见，其后以星号遮蔽（仅界面展示，存储保持原样）
    fun maskSecret(raw: String): String {
        if (raw.length <= 9) return raw
        val rest = raw.length - 9
        return raw.take(9) + "*".repeat(minOf(rest, 12))
    }

    // 回填已保存凭证
    LaunchedEffect(driveType) {
        val saved = viewModel.loadSavedCredential()
        if (saved != null) {
            when (driveType) {
                DriveType.GUANGYA -> {
                    val parts = saved.split("||", limit = 2)
                    refreshToken = parts.getOrElse(1) { "" }.ifBlank { parts.getOrElse(0) { "" } }
                }
                DriveType.QUARK -> cookie = saved
                null -> {}
            }
        }
    }

    Scaffold(topBar = { AppTopBar("设置", onBack = { viewModel.go(Screen.HOME) }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("网盘账号", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // 网盘选择（右上角带「已登录」绿点，不遮挡标题文字）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveType.entries.forEach { type ->
                    val loggedIn = accounts[type] != null
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .tappable { viewModel.selectDrive(type) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (driveType == type) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(
                                text = type.displayName,
                                modifier = Modifier.align(Alignment.Center),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (loggedIn) {
                                // 右上角已登录绿点（状态指示，不挡文字）
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SuccessGreen,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                                        .size(10.dp),
                                ) {}
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when (driveType) {
                DriveType.GUANGYA -> {
                    SmsLoginEntry(enabled = account == null, onClick = { viewModel.openSmsLogin() })
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = if (account != null) maskSecret(refreshToken) else refreshToken,
                        onValueChange = { refreshToken = it },
                        label = { Text("Refresh Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = account != null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (account != null) {
                                // 仅退出登录，不清空输入框里的凭证（方便再次登录）
                                viewModel.logout()
                            } else {
                                viewModel.login("||$refreshToken")
                            }
                        },
                        enabled = !loading && (account != null || refreshToken.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (account != null) "退出登录" else "登录")
                    }
                }

                DriveType.QUARK -> {
                    Text(
                        text = "获取 Quark Cookie",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (account == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tappable(enabled = account == null) { if (account == null) showQuarkCookie = true }
                            .padding(vertical = 8.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = if (account != null) maskSecret(cookie) else cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = account != null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (account != null) {
                                // 仅退出登录，不清空输入框里的凭证（方便再次登录）
                                viewModel.logout()
                            } else {
                                viewModel.login(cookie.trim())
                            }
                        },
                        enabled = !loading && (account != null || cookie.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (account != null) "退出登录" else "登录")
                    }
                }

                null -> Text("请选择要配置的网盘", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 光鸭短信验证码登录弹框
            if (smsState.open) {
                GuangYaSmsDialog(
                    state = smsState,
                    onAgree = { viewModel.smsAgreeDeclaration() },
                    onRequestCode = { viewModel.smsRequestCode(it) },
                    onHumanVerified = { viewModel.smsHumanVerified(it) },
                    onSubmitCode = { viewModel.smsSubmitCode(it) },
                    onClose = { viewModel.closeSmsLogin() },
                    onTokenObtained = { refreshToken = it },
                )
            }

            // 夸克 Cookie 获取弹框（内置 WebView 登录 → 自动读取 Cookie）
            if (showQuarkCookie) {
                QuarkCookieDialog(
                    onCookieObtained = {
                        cookie = it
                        showQuarkCookie = false
                    },
                    onClose = { showQuarkCookie = false },
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            // 底部：关于 / 打赏作者
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsEntry("关于", "版本信息与使用说明", showDot = update.hasUpdate) { viewModel.go(Screen.ABOUT) }
            SettingsEntry("打赏作者", "如果觉得好用，请作者喝杯奶茶") { viewModel.go(Screen.DONATE) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 设置页「验证码获取」入口行：光鸭登录标题下，单行、明确可点击
@Composable
private fun SmsLoginEntry(enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = "验证码获取 Refresh token",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .tappable(enabled = enabled) { if (enabled) onClick() }
            .padding(vertical = 8.dp),
    )
}

// ==================== 光鸭短信验证码登录弹框 ====================

@Composable
fun GuangYaSmsDialog(
    state: AppViewModel.SmsLoginState,
    onAgree: () -> Unit,
    onRequestCode: (String) -> Unit,
    onHumanVerified: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onClose: () -> Unit,
    onTokenObtained: (String) -> Unit,
) {
    // 成功后回填 refreshToken 到设置页输入框，并关闭弹框
    LaunchedEffect(state.resultRefreshToken) {
        if (state.resultRefreshToken != null) {
            onTokenObtained(state.resultRefreshToken!!)
            onClose()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("光鸭验证码登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(12.dp))

                when (state.phase) {
                    AppViewModel.SmsPhase.DECLARATION -> SmsDeclaration(onAgree = onAgree)
                    AppViewModel.SmsPhase.PHONE -> SmsPhoneInput(state, onRequestCode = onRequestCode, onClose = onClose)
                    AppViewModel.SmsPhase.CODE -> SmsCodeInput(state, onSubmit = onSubmitCode, onResend = onRequestCode, onClose = onClose)
                    AppViewModel.SmsPhase.HUMAN_VERIFY -> SmsHumanVerify(state, onHumanVerified = onHumanVerified, onClose = onClose)
                    AppViewModel.SmsPhase.SUCCESS -> SmsSuccess()
                }
            }
        }
    }
}

@Composable
private fun SmsDeclaration(onAgree: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(6.dp))
                Text("隐私说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "本功能仅在你的设备本地向光鸭官方服务器发送手机号与验证码，用于换取登录凭证。" +
                    "我们不会收集、上传或存储你的手机号、验证码、Token 到任何第三方服务器。" +
                    "验证码仅用于本次登录，请仅在可信设备上使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
        Text("我已了解，开始", maxLines = 1)
    }
}

@Composable
private fun SmsPhoneInput(state: AppViewModel.SmsLoginState, onRequestCode: (String) -> Unit, onClose: () -> Unit) {
    var phone by remember { mutableStateOf(state.phone) }
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("手机号") },
        placeholder = { Text("如 13800138000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) { Text("取消", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        Button(
            onClick = { onRequestCode(phone) },
            enabled = !state.loading,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("获取验证码", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun SmsCodeInput(
    state: AppViewModel.SmsLoginState,
    onSubmit: (String) -> Unit,
    onResend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
        label = { Text("验证码") },
        placeholder = { Text("6 位短信验证码") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Text("验证码 5 分钟内有效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(8.dp))
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    if (!state.loading) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { onResend(state.phone) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text("收不到验证码？重新获取", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) { Text("取消", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        Button(
            onClick = { onSubmit(code) },
            enabled = !state.loading && code.length >= 4,
            modifier = Modifier.weight(1.2f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("登录并获取", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun SmsHumanVerify(
    state: AppViewModel.SmsLoginState,
    onHumanVerified: (String) -> Unit,
    onClose: () -> Unit,
) {
    Text(
        "需要完成人机验证。请在下方完成验证，系统会自动继续。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        extractCaptchaToken(url ?: "")?.let { onHumanVerified(it) }
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val token = extractCaptchaToken(url ?: "")
                        if (token != null) {
                            onHumanVerified(token)
                            return true
                        }
                        return false
                    }
                }
                loadUrl(state.captchaUrl ?: "")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "若验证后未自动继续，请点「改用手动粘贴」返回，使用手动粘贴 Token 方式登录。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) { Text("取消", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        Button(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("改用手动粘贴", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun SmsSuccess() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("已获取登录凭证", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("Token 已填入输入框，点「登录」即可", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 从验证回调 URL / 页面地址里尽力提取 captcha_token（最佳努力，视光鸭实际返回格式而定） */
private fun extractCaptchaToken(url: String): String? {
    if (url.isBlank()) return null
    val names = listOf("captcha_token", "captchaToken", "ctoken", "token", "ticket", "validate")
    runCatching {
        val raw = url.substringAfter("?", "").substringBefore("#")
        for (pair in raw.split("&")) {
            val kv = pair.split("=", limit = 2)
            val k = kv.getOrElse(0) { "" }
            val v = kv.getOrElse(1) { "" }
            if (names.any { it.equals(k, true) } && v.isNotBlank()) return v
        }
        // fragment 也扫一遍
        val frag = url.substringAfter("#", "")
        for (pair in frag.split("&")) {
            val kv = pair.split("=", limit = 2)
            val k = kv.getOrElse(0) { "" }
            val v = kv.getOrElse(1) { "" }
            if (names.any { it.equals(k, true) } && v.isNotBlank()) return v
        }
    }
    return null
}

/** 设置页底部列表入口行：标题 + 副标题 + 右侧箭头（可选红点提示） */
@Composable
private fun SettingsEntry(title: String, subtitle: String, showDot: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tappable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDot) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(UpdateDotRed, CircleShape),
            )
        }
        Icon(
            Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ==================== 夸克 Cookie 获取页（全屏 WebView 登录 → 自动读取 Cookie） ====================

/** 夸克登录页候选入口：主域名被网络掐断时依次降级尝试 */
private val QUARK_LOGIN_ENTRY = listOf(
    "https://pan.quark.cn/#/login",
    "https://www.quark.cn/",
    "https://drive-pc.quark.cn/",
)

/** 参与自检的夸克域名（用于判断是网络问题还是 App 问题） */
private val QUARK_PROBE_HOSTS = listOf(
    "pan.quark.cn" to "https://pan.quark.cn/",
    "www.quark.cn" to "https://www.quark.cn/",
    "account.quark.cn" to "https://account.quark.cn/",
    "passport.quark.cn" to "https://passport.quark.cn/",
    "drive-pc.quark.cn" to "https://drive-pc.quark.cn/",
)

/** 把 WebView 的底层错误码翻译成人话 + 处理建议 */
private fun describeWebError(code: Int, desc: String): String {
    val d = desc.uppercase()
    return when {
        code == -6 || d.contains("ERR_CONNECTION_RESET") ->
            "网络连接被重置（${desc.take(40)}）。通常是当前网络（公司/校园网/代理/运营商）拦截或掐断了到夸克服务器的连接，与 App 本身无关。"
        code == -2 || d.contains("ERR_NAME_NOT_RESOLVED") || d.contains("ERR_NAME_RESOLUTION_FAILED") ->
            "域名解析失败（${desc.take(40)}）。当前网络的 DNS 无法解析夸克域名，可尝试换用其他网络或 DNS。"
        code == -8 || d.contains("ERR_TIMED_OUT") || d.contains("ERR_TIMEOUT") ->
            "连接超时（${desc.take(40)}）。网络太慢或被限制，可换网络后重试。"
        code == -11 || d.contains("ERR_CERT") || d.contains("ERR_SSL") ->
            "HTTPS 证书校验失败（${desc.take(40)}）。可能存在 HTTPS 中间人代理（如公司代理），请关闭代理或换网络。"
        code == -10 || d.contains("ERR_REDIRECT") ->
            "跳转被拒绝（${desc.take(40)}）。目标站点拒绝了本次跳转，可尝试更换入口。"
        d.contains("ERR_BLOCKED") || d.contains("ERR_ACCESS_DENIED") ->
            "访问被拒绝（${desc.take(40)}）。夸克可能对该网络/设备做了风控拦截，可换网络或稍后重试。"
        else -> "页面加载失败（${desc.take(40)}）。可尝试重试或用下方「网络自检」判断是网络问题还是页面问题。"
    }
}

/** 夸克 Cookie 获取页：内置 WebView 登录 → 登录成功后自动读取 Cookie 回填 */
@Composable
private fun QuarkCookieDialog(
    onCookieObtained: (String) -> Unit,
    onClose: () -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    var loadingUrl by remember { mutableStateOf("") }
    var entryIndex by remember { mutableStateOf(0) }
    var webError by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<List<String>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val got = remember { mutableStateOf(false) } // 防止重复回调
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 沉浸式：让对话框窗口延伸到状态栏/导航栏后面
    // ⚠️ view 在组合作用域取，parent 必须在 SideEffect 内部读（组合阶段 parent 还是 null）
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT)
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
        }
    }

    // 全屏页面（不再是小弹框）
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp)
            ) {
                // 顶栏：标题 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "获取 Quark Cookie",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (!agreed) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp).padding(top = 1.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "本功能通过内置浏览器打开夸克官方登录页，登录过程由夸克官方处理。我们不会收集、上传或存储你的账号密码与 Cookie。\n\nCookie 通常约 1 个月失效，届时需重新获取。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { agreed = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("我已了解，开始", maxLines = 1)
                    }
                } else {
                    if (loadingUrl.isNotBlank()) {
                        Text(
                            loadingUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    // 加载失败诊断卡
                    webError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "页面加载失败",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            webError = null
                                            reloadKey++
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("重试", maxLines = 1) }
                                    TextButton(
                                        onClick = {
                                            // 换一个入口线路（主域名不通时降级）
                                            if (entryIndex < QUARK_LOGIN_ENTRY.lastIndex) {
                                                entryIndex++
                                                webError = null
                                                reloadKey++
                                            } else {
                                                Toast.makeText(context, "已尝试所有入口，请换用其他网络", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("换个入口", maxLines = 1) }
                                    TextButton(
                                        onClick = { probing = true; probeResult = null },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("网络自检", maxLines = 1) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // 网络自检结果
                    probeResult?.let { list ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("网络自检结果", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                list.forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "若多数域名都失败，说明当前网络无法访问夸克，请换网络（Wi-Fi/流量）或关闭代理后重试；这与 App 无关。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (probing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }

                    // WebView 容器（悬浮「获取 Cookie」按钮叠在左下角）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    ) {
                        // key 变化时重建 WebView（用于「重试」「换个入口」）
                        key(entryIndex, reloadKey) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                CookieManager.getInstance().apply { setAcceptCookie(true) }
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    // 允许双指捏合缩放（不显示系统缩放按钮），方便放大看二维码
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    // 夸克手机端没有网页登录入口（会跳 UC 推广页），必须用桌面 UA 走 PC 版登录页
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            loadingUrl = url.orEmpty()
                                            if (got.value) return
                                            val cm = CookieManager.getInstance()
                                            val all = buildString {
                                                append(cm.getCookie("https://pan.quark.cn/") ?: "")
                                                append("; ")
                                                append(cm.getCookie("https://quark.cn/") ?: "")
                                            }
                                            val u = url.orEmpty()
                                            // 登录成功后 pan.quark.cn 带 kps，且不在登录页/推广落地页
                                            if (all.contains("kps=") &&
                                                !u.contains("login") && !u.contains("passport") && !u.contains("account.quark")
                                            ) {
                                                got.value = true
                                                onCookieObtained(all)
                                            }
                                        }

                                        // 主框架加载失败：翻译成中文诊断
                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?,
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame != true) return
                                            val code = error?.errorCode ?: 0
                                            val desc = error?.description?.toString().orEmpty()
                                            webError = describeWebError(code, desc)
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?,
                                        ) {
                                            super.onReceivedHttpError(view, request, errorResponse)
                                            if (request?.isForMainFrame != true) return
                                            val code = errorResponse?.statusCode ?: 0
                                            if (code >= 400) {
                                                webError = "夸克服务器返回 HTTP $code。该地址可能被当前网络拦截，" +
                                                    "可点「换个入口」重试，或用「网络自检」确认网络连通性。"
                                            }
                                        }
                                    }
                                    // 直接打开登录页，避免落到「下载 App」推广页
                                    loadUrl(QUARK_LOGIN_ENTRY[entryIndex])
                                }
                            },
                            )
                        }
                        // 悬浮手动获取按钮（自动识别失败时的兜底）
                        Button(
                            onClick = {
                                val cm = CookieManager.getInstance()
                                val all = buildString {
                                    append(cm.getCookie("https://pan.quark.cn/") ?: "")
                                    append("; ")
                                    append(cm.getCookie("https://quark.cn/") ?: "")
                                }
                                if (all.contains("kps=")) {
                                    onCookieObtained(all)
                                } else {
                                    Toast.makeText(context, "尚未检测到登录，请先在网页登录", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .height(40.dp),
                        ) {
                            Text("获取 Cookie", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    // 网络自检：并发探测各夸克域名
    LaunchedEffect(probing) {
        if (!probing) return@LaunchedEffect
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val results = QUARK_PROBE_HOSTS.map { (host, url) ->
            val desc = runCatching {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
                    .build()
                client.newCall(req).execute().use { r -> "HTTP ${r.code}" }
            }.getOrElse { e ->
                val msg = e.message.orEmpty()
                when {
                    e is java.net.UnknownHostException -> "DNS 解析失败"
                    e is java.net.SocketTimeoutException -> "连接超时"
                    e is javax.net.ssl.SSLException -> "TLS/证书错误"
                    e is java.net.ConnectException -> "连接被拒绝"
                    msg.contains("reset", true) -> "连接被重置"
                    else -> "失败：${msg.take(30)}"
                }
            }
            host to desc
        }
        probeResult = results.map { (host, r) -> "· $host → $r" }
        probing = false
    }
}

// ==================== 关于 / 打赏作者 ====================

/** 关于页：软件信息 + 简介 + 使用说明 */
@Composable
fun AboutScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val update by viewModel.update.collectAsState()
    BackHandler { viewModel.go(Screen.HOME) }

    Scaffold(topBar = { AppTopBar("关于", onBack = { viewModel.go(Screen.HOME) }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 软件信息卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("转存喵", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "作者：基耶杰",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "当前版本 v${currentVersion()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .tappable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                                } catch (_: Exception) {
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "检查更新",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (update.hasUpdate) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(UpdateDotRed, CircleShape),
                            )
                        }
                    }
                    if (update.hasUpdate && update.latestVersion != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "新版本 v${update.latestVersion} 已发布，点击前往下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "粘贴分享链接，一键转存到夸克 / 光鸭云盘，并按剧集规则批量重命名。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Text("使用说明", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GuideItem("剧名", "填写剧名会作为命名元素，文件夹名字以剧集命名开头")
                    GuideItem("命名规则", "通用剧集格式为 剧名.S01E01.mkv")
                    GuideItem("自定义命名", "可选是否显示剧名和自定义季数")
                    GuideItem("凭证获取", "光鸭 Refresh Token 和夸克 Cookie 的获取办法，请自行研究")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 使用说明条目：主题色小标题 + 灰色描述 */
@Composable
private fun GuideItem(label: String, desc: String) {
    Row {
        Text("· ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 打赏页：收款二维码 + 保存到相册（保存后用微信扫一扫从相册识别） */
@Composable
fun DonateScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    BackHandler { viewModel.go(Screen.HOME) }

    // 每次点击 +1 触发保存。不能用「完成后把布尔置回 false」的写法：
    // 复位状态会在保存挂起点取消协程，导致既不保存也不弹提示
    var saveTrigger by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveTrigger++
    }

    // 触发保存并提示结果
    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) {
            saving = true
            val ok = saveDonateQr(context)
            saving = false
            Toast.makeText(
                context,
                if (ok) "已保存到相册，微信「扫一扫」→ 相册选择即可打赏" else "保存失败，请重试",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(topBar = { AppTopBar("打赏作者", onBack = { viewModel.go(Screen.HOME) }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // 收款码：圆角方形描边卡片，与全 App 风格一致
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(R.drawable.donate_qr),
                    contentDescription = "微信打赞赏助二维码",
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("微信打赏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                "纯粹自愿",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 29 ||
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        saveTrigger++
                    } else {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("保存图片", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 把打赏二维码保存到系统相册（Pictures/转存喵）。API 29+ 走 MediaStore 免权限，低版本需存储权限。 */
private suspend fun saveDonateQr(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.donate_qr)
            ?: return@withContext false
        val fileName = "donate_qr_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/转存喵")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: return@withContext false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "转存喵")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        true
    } catch (_: Exception) {
        false
    }
}

// ==================== 目标文件夹选择 ====================

@Composable
fun FolderPickerScreen(viewModel: AppViewModel) {
    val folders by viewModel.folders.collectAsState()
    val target by viewModel.targetFolder.collectAsState()
    val crumb by viewModel.folderCrumb.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()

    // 系统返回键回主页（而不是退出 App）
    BackHandler { viewModel.go(Screen.HOME) }

    Scaffold(
        topBar = { AppTopBar("选择转存路径", onBack = { viewModel.go(Screen.HOME) }) },
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                ErrorBanner(error)
                Button(
                    onClick = { viewModel.confirmFolder() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认选择") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.backFolder() }, enabled = crumb.isNotEmpty()) {
                    Text(".. 上级")
                }
                Text(
                    if (crumb.isEmpty()) "当前：根目录" else "当前：" + crumb.joinToString(" / ") { it.second },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn {
                item {
                    FolderRow(name = "网盘根目录", selected = target == "0") {
                        viewModel.goRootFolder()
                    }
                }
                items(folders, key = { it.fid }) { folder ->
                    FolderRow(name = folder.name, selected = target == folder.fid) {
                        viewModel.enterFolder(folder.fid, folder.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, selected: Boolean, onClick: () -> Unit) {
    // 紧凑行：约 44dp 高，选中行带圆角底色；自绘小圆点替代默认 RadioButton（默认控件最小触摸尺寸会把行撑得很高）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .tappable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 紧凑单选圆点（20dp）
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = if (selected) 2.dp else 1.5.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ==================== 转存进度弹窗 ====================

@Composable
private fun TransferDialog(state: TransferDialogState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = { Text(if (state.running) "正在转存" else "转存完成") },
        text = {
            Column {
                if (state.running) {
                    LinearProgressIndicator(progress = state.percent / 100f, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (state.stage) {
                            0 -> "转存中…"
                            1 -> "重命名中…"
                            else -> "处理中…"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("${state.percent}%", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请勿退出应用（转存为异步任务，后台服务持续运行）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val r = state.result
                    if (r != null) {
                        Text(
                            "成功 ${r.successCount} · 失败 ${r.failCount} · 共 ${r.items.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.failCount == 0) SuccessGreen else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        val fails = r.items.filter { !it.ok }
                        if (fails.isEmpty()) {
                            Text("全部转存并重命名完成 ✓")
                        } else {
                            Text("以下项目失败：", style = MaterialTheme.typography.bodySmall)
                            fails.take(5).forEach { item ->
                                Text(
                                    "✗ ${item.originName}" + if (item.message.isNotBlank()) "（${item.message}）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (fails.size > 5) {
                                Text("… 其余 ${fails.size - 5} 项", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "若个别失败：请先在网盘 App 确认文件是否已转存，再手动重命名；直接重跑会生成重复文件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text("转存失败", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text(state.message.ifBlank { "未知错误" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (!state.running) {
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
    )
}

/** 预览弹窗（全屏）：单列「旧名 → 新名」卡片，新名可单条手改；确认后转存 */
@Composable
private fun PreviewDialog(
    state: PreviewDialogState,
    onNewNameChange: (fid: String, newName: String) -> Unit,
    onToggleIncluded: (fid: String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val renamedCount = state.items.count { !it.filtered && it.newName != it.originName }
    val existingCount = state.items.count { it.alreadyExists }
    val skippedCount = state.items.count { it.alreadyExists && !it.included }
    val includedCount = state.items.count { !it.filtered && it.included }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏：标题 + 取消
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "预览",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                Text(
                    "共 ${state.items.size} 个文件，${renamedCount} 个将被重命名。可修改下方新名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (existingCount > 0) {
                    Text(
                        "目标剧文件夹里已存在 ${existingCount} 集，已跳过 ${skippedCount} 集（勾选可强制重转）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 单列列表：每个文件一张卡片（旧名 → 新名）
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.file.fid }) { item ->
                        PreviewRow(item, onNewNameChange, onToggleIncluded)
                    }
                }

                // 底部：确认转存
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = includedCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (existingCount > 0) "确认转存（${includedCount} 个）" else "确认转存")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    item: PreviewItem,
    onNewNameChange: (fid: String, newName: String) -> Unit,
    onToggleIncluded: (fid: String) -> Unit,
) {
    val mono = FontFamily.Monospace
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (item.alreadyExists) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.originName,
                            fontFamily = mono,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (item.included) "已存在 · 仍要转存" else "已存在 · 跳过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = item.included,
                        onCheckedChange = { onToggleIncluded(item.file.fid) },
                    )
                }
            } else if (item.filtered) {
                Text(
                    item.originName,
                    fontFamily = mono,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "已过滤（不转存）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 原名 / 新名：与主页输入框统一的浮动标签样式，标签嵌入边框缺口
                OutlinedTextField(
                    value = item.originName,
                    onValueChange = {},
                    label = { Text("原名") },
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono),
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (item.hasUnresolved) {
                    Text(
                        "未能解析集数，已保持原名",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 2.dp)
                        .size(16.dp),
                )
                OutlinedTextField(
                    value = item.newName,
                    onValueChange = { onNewNameChange(item.file.fid, it) },
                    label = { Text("新名") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 记录时间戳 → "MM-dd HH:mm" */
private fun formatRecordTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

// ==================== 转存记录（任务历史） ====================

@Composable
fun HistoryScreen(viewModel: AppViewModel) {
    val records by viewModel.records.collectAsState()
    BackHandler { viewModel.go(Screen.HOME) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "转存记录",
                onBack = { viewModel.go(Screen.HOME) },
                actions = {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "清空记录",
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("清空", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            // 空状态：给指引而不是白屏
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有转存记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "回主页粘贴分享链接，完成一次转存后就会出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        onRefill = { viewModel.refillRecord(record) },
                        onDelete = { viewModel.removeRecord(record.id) },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部记录？") },
            text = { Text("将删除全部 ${records.size} 条转存记录，不影响网盘里已转存的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRecords()
                    showClearConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 单条转存记录卡：剧名 + 时间/网盘 + 结果 + 再转一次 / 删除 */
@Composable
private fun RecordCard(
    record: TransferRecord,
    onRefill: () -> Unit,
    onDelete: () -> Unit,
) {
    val driveName = runCatching {
        com.ashchan.cloudsaver.core.model.DriveType.valueOf(record.drive).displayName
    }.getOrDefault(record.drive)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.taskName.ifBlank { "未命名任务" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 结果小圆点：全成功绿，部分失败红
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (record.allSuccess) SuccessGreen else UpdateDotRed,
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatRecordTime(record.id)} · $driveName · ${record.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${record.ruleName} · 成功 ${record.successCount} / 共 ${record.total}" +
                    if (record.failCount > 0) " · 失败 ${record.failCount}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (record.failCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRefill) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("再转一次", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
