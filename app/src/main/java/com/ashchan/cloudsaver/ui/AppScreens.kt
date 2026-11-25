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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(credentialStore, ruleStore) as T
    }
}

/** 根导航 */
@Composable
fun AppRoot(
    viewModel: AppViewModel,
    tasksViewModel: com.ashchan.cloudsaver.ui.tasks.TasksViewModel,
    themeMode: Int = THEME_AUTO,
    onThemeModeChange: (Int) -> Unit = {},
    openNewTaskOnLaunch: Boolean = false,
) {
    val screen by viewModel.screen.collectAsState()
    // 启动时后台检测一次 GitHub Releases 是否有新版本（有则红点提示）
    val appVersion = currentVersion()
    LaunchedEffect(Unit) { viewModel.checkUpdate(appVersion) }
    // 自更新：延迟 5 秒检测，24 小时最多一次；只在主页面弹，别在看图/设置时打断
    val context = LocalContext.current
    val updateManager = remember(context) { com.ashchan.cloudsaver.update.UpdateManager(context) }
    LaunchedEffect(Unit) { updateManager.autoCheck() }
    val updatePrompt by updateManager.prompt.collectAsState()
    val updateAvailable by updateManager.available.collectAsState()
    val updateProgress by updateManager.progress.collectAsState()
    val updateFailed by updateManager.failed.collectAsState()
    // 底栏四栏 → 四个页面。切换时清掉「添加」页的编辑态，避免从别的栏目回来还停在编辑。
    fun goTab(tab: MainTab) {
        when (tab) {
            MainTab.TASKS -> {
                tasksViewModel.clearEdit()
                viewModel.go(Screen.TASKS)
            }
            MainTab.ADD -> {
                tasksViewModel.clearEdit()
                viewModel.go(Screen.NEW_TASK)
            }
            MainTab.RUNLOG -> viewModel.go(Screen.RUNLOG)
            MainTab.SETTINGS -> viewModel.go(Screen.SETTINGS)
        }
    }
    when (screen) {
        Screen.TASKS -> com.ashchan.cloudsaver.ui.tasks.TaskListScreen(
            viewModel = tasksViewModel,
            onOpenSettings = { viewModel.go(Screen.SETTINGS) },
            onOpenNewTask = {
                tasksViewModel.clearEdit()
                viewModel.go(Screen.NEW_TASK)
            },
            onOpenEdit = { task ->
                tasksViewModel.startEdit(task)
                viewModel.go(Screen.NEW_TASK)
            },
            startWithNewTaskDialog = openNewTaskOnLaunch,
            onSelectTab = { goTab(it) },
        )
        Screen.NEW_TASK -> {
            val editingTask by tasksViewModel.editing.collectAsState()
            // 调试用：adb --ez openPathPicker true 进新建页就直接摊开「保存位置」选择器
            val ctx = LocalContext.current
            val autoPathPicker = remember(ctx) {
                (ctx as? android.app.Activity)
                    ?.intent?.getBooleanExtra("openPathPicker", false) == true
            }
            // 调试用：adb --es sourceRows 3 进新建页就摊开 3 行空链接（截屏验收多源排版）
            val debugRows = remember(ctx) {
                (ctx as? android.app.Activity)
                    ?.intent?.getIntExtra("sourceRows", 1) ?: 1
            }
            com.ashchan.cloudsaver.ui.tasks.NewTaskScreen(
                viewModel = tasksViewModel,
                onDone = {
                    tasksViewModel.clearEdit()
                    viewModel.go(Screen.TASKS)
                },
                editingTask = editingTask,
                openPathPickerOnStart = autoPathPicker,
                startSourceRows = debugRows,
                onSelectTab = { goTab(it) },
            )
        }
        Screen.RUNLOG -> com.ashchan.cloudsaver.ui.tasks.RunLogScreen(
            viewModel = tasksViewModel,
            onBack = { viewModel.go(Screen.TASKS) },
            onSelectTab = { goTab(it) },
        )
        Screen.SETTINGS -> SettingsScreen(viewModel, themeMode, onThemeModeChange, onSelectTab = { goTab(it) })
        Screen.ABOUT -> AboutScreen(viewModel)
        Screen.DONATE -> DonateScreen(viewModel)
    }
    // 自更新弹窗：只在任务主页面上出现（用户正在看图/设置时不打断）
    val info = updateAvailable
    if (updatePrompt && info != null && screen == Screen.TASKS) {
        com.ashchan.cloudsaver.update.UpdateDialog(
            info = info,
            currentVersionName = updateManager.currentVersionName(),
            progress = updateProgress,
            failed = updateFailed,
            onDismiss = { updateManager.dismiss() },
            onUpdate = {
                if (!updateManager.canInstallPackages()) {
                    // 首次自更新要先允许「安装未知应用」，跳到系统设置，授权后回来再点更新
                    updateManager.openInstallPermissionSettings()
                } else {
                    updateManager.downloadAndInstall()
                }
            },
            onRetry = { updateManager.retry() },
        )
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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(24.dp))
                }
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
fun SettingsScreen(
    viewModel: AppViewModel,
    themeMode: Int = THEME_AUTO,
    onThemeModeChange: (Int) -> Unit = {},
    onSelectTab: (MainTab) -> Unit = {},
) {
    val driveType by viewModel.settingsDriveType.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val account = accounts[driveType]
    val loading by viewModel.loginLoading.collectAsState()
    val error by viewModel.loginError.collectAsState()
    val update by viewModel.update.collectAsState()
    val smsState by viewModel.smsLoginState.collectAsState()

    // 系统返回键回主页（而不是退出 App）
    BackHandler { viewModel.go(Screen.TASKS) }

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

    Scaffold(
        topBar = { AppTopBar("设置", onBack = null) },
        bottomBar = { AppTabBar(selected = MainTab.SETTINGS, onSelect = onSelectTab) },
    ) { padding ->
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
                                        .padding(top = 8.dp, end = 8.dp)
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
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

            // TMDB（可选）：追剧任务靠它算「最新集」与更新日
            Spacer(Modifier.height(24.dp))
            TmdbCredentialSection()

            // 外观：主题切换从旧主页搬到设置页（旧主页下线后不能丢功能）
            Spacer(Modifier.height(24.dp))
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    THEME_AUTO to "跟随系统",
                    THEME_LIGHT to "浅色",
                    THEME_DARK to "深色",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(label) },
                    )
                }
            }

            // 自动化：后台自动刷新（独立 auto_sync_prefs，不动数据库、不动任务列表）
            AutoSyncSection()

            // 底部：关于 / 打赏作者
            Spacer(Modifier.height(24.dp))
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("光鸭验证码登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
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
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text("隐私说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
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
        Text("我已了解", maxLines = 1)
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
    Spacer(Modifier.height(8.dp))
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
                Text("重新获取", maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
        ) { Text("登录获取", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
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
        ) { Text("手动粘贴", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun SmsSuccess() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text("已获取登录凭证", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("Token 已填入输入框，点「登录」即可", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
            .padding(vertical = 16.dp),
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
            modifier = Modifier.size(16.dp),
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
                        style = MaterialTheme.typography.titleMedium,
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
                                modifier = Modifier.size(16.dp).padding(top = 4.dp),
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
                        Text("我已了解", maxLines = 1)
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
                                Spacer(Modifier.height(8.dp))
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
    BackHandler { viewModel.go(Screen.TASKS) }

    Scaffold(topBar = { AppTopBar("关于", onBack = { viewModel.go(Screen.TASKS) }) }) { padding ->
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
                    Text("转存喵", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "作者：基耶杰",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "当前版本 v${currentVersion()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
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
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(UpdateDotRed, CircleShape),
                            )
                        }
                    }
                    if (update.hasUpdate && update.latestVersion != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "新版本 v${update.latestVersion} 已发布，点击前往下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
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

            Spacer(Modifier.height(24.dp))
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
    BackHandler { viewModel.go(Screen.TASKS) }

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

    Scaffold(topBar = { AppTopBar("打赏作者", onBack = { viewModel.go(Screen.TASKS) }) }) { padding ->
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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("微信打赏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "纯粹自愿",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
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
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

