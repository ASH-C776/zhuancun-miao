package com.ashchan.cloudsaver

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.db.AppDatabase
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.storage.TmdbStore
import com.ashchan.cloudsaver.transfer.TaskRunner
import com.ashchan.cloudsaver.ui.AppRoot
import com.ashchan.cloudsaver.ui.AppViewModel
import com.ashchan.cloudsaver.ui.CloudSaverTheme
import com.ashchan.cloudsaver.ui.ScreenViewModelFactory
import com.ashchan.cloudsaver.ui.THEME_AUTO
import com.ashchan.cloudsaver.ui.THEME_DARK
import com.ashchan.cloudsaver.ui.THEME_LIGHT
import com.ashchan.cloudsaver.ui.tasks.TasksViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        ScreenViewModelFactory(
            credentialStore = CredentialStore(applicationContext),
            ruleStore = RuleStore(applicationContext),
        )
    }

    private val tasksViewModel: TasksViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = TaskRepository(AppDatabase.get(applicationContext))
                val credentials = CredentialStore(applicationContext)
                val rules = RuleStore(applicationContext)
                val tmdb = TmdbStore(applicationContext)
                return TasksViewModel(
                    appContext = applicationContext,
                    repo = repo,
                    runner = TaskRunner(repo, credentials, tmdb, rules),
                    credentials = credentials,
                    ruleStore = rules,
                    tmdbStore = tmdb,
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局未捕获异常落盘（崩溃后可在文件里查堆栈，纯单机没有服务端日志）
        com.ashchan.cloudsaver.core.AppLog.install(this)
        // 调试/联调用：--ez wakeScreen true 让本页把屏幕点亮（HyperOS 拒绝 adb input 注入，
        // 真机回归截屏前只能靠 App 自己 setTurnScreenOn，否则截出来是一片黑）
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("wakeScreen", false) == true) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 顺手把锁屏让开（无密码锁屏可直接进入本页；有密码时仍需用户解锁）
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            (getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
                .requestDismissKeyguard(this, null)
            // HyperOS 上 setTurnScreenOn 只置「可点亮」标记，不会真的唤醒正在 doze 的屏幕；
            // 带 ACQUIRE_CAUSES_WAKEUP 的旧式 wakelock 才会直接把屏点亮（仅调试入口用）
            @Suppress("DEPRECATION")
            (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "zhuancunmiao:debug-wake",
                )
                .acquire(60_000L)
        }
        // 沉浸式：状态栏与底部导航条（小白条）完全透明，内容铺到系统栏底下；
        // 显式传透明 scrim，避免默认的导航栏白色对比度遮罩（底栏发白的带子）。
        // 注意强制亮/暗主题时，SideEffect 里会按当前主题重新设置状态栏图标风格。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关闭系统自动加的导航栏对比度遮罩，彻底透明
            window.isNavigationBarContrastEnforced = false
        }
        // 处理从外部打开/分享的链接（只在首次创建时处理；旋转重建不会重复回填）
        if (savedInstanceState == null) handleShareIntent(intent)
        // 调试/联调用入口：只在 debug 包生效；release 包里这些 extra 全部失效
        val openNewTask = BuildConfig.DEBUG && (intent?.getBooleanExtra("openNewTask", false) ?: false)
        if (BuildConfig.DEBUG) {
            // --ez debugTaskName 剧名 --es debugShareUrl 链接
            // 绕开 HyperOS 拒绝 input 事件、无法在弹窗里打字的限制，直接建任务并同步
            val debugName = intent?.getStringExtra("debugTaskName")
            val debugUrl = intent?.getStringExtra("debugShareUrl")
            // 多源调试：--es debugShareUrl2 再加一个附加源
            val debugUrl2 = intent?.getStringExtra("debugShareUrl2")
            if (!debugName.isNullOrBlank() && !debugUrl.isNullOrBlank()) {
                tasksViewModel.createTask(
                    debugName,
                    listOfNotNull(debugUrl, debugUrl2),
                    "",
                    "",
                )
            }
            // --ez debugRunAll true 直接跑一轮「全部检测」
            if (intent?.getBooleanExtra("debugRunAll", false) == true) {
                tasksViewModel.runAll()
            }
            // --ez openRunLog true 直接进「运行日志」页
            if (intent?.getBooleanExtra("openRunLog", false) == true) {
                viewModel.go(com.ashchan.cloudsaver.ui.Screen.RUNLOG)
            }
            // --ez openSettings true 直接进「设置」页
            if (intent?.getBooleanExtra("openSettings", false) == true) {
                viewModel.go(com.ashchan.cloudsaver.ui.Screen.SETTINGS)
            }
            // --es editTaskName 剧名 直接进该任务的编辑页
            intent?.getStringExtra("editTaskName")?.takeIf { it.isNotBlank() }?.let { n ->
                tasksViewModel.editByName(n) {
                    viewModel.go(com.ashchan.cloudsaver.ui.Screen.NEW_TASK)
                }
            }
            // --es debugTmdbPing KEY [--es debugTmdbBase 地址] 只做一次 TMDB 探活
            intent?.getStringExtra("debugTmdbPing")?.takeIf { it.isNotBlank() }?.let { key ->
                val base = intent?.getStringExtra("debugTmdbBase") ?: "https://api.themoviedb.org"
                lifecycleScope.launch {
                    val r = com.ashchan.cloudsaver.core.tmdb.TmdbClient(key, base).ping()
                    android.util.Log.i(
                        "TmdbDebug",
                        "base=$base ok=${r.isSuccess} value=${r.getOrNull() ?: r.exceptionOrNull()?.message}"
                    )
                }
            }
        }
        setContent {
            val prefs = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, THEME_AUTO)) }
            val darkTheme = when (themeMode) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // 主题（含强制亮/暗）变化时，同步状态栏图标的深浅，保证对比度
            SideEffect {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
            }
            CloudSaverTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        viewModel = viewModel,
                        tasksViewModel = tasksViewModel,
                        themeMode = themeMode,
                        openNewTaskOnLaunch = openNewTask,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                        },
                    )
                }
            }
        }
    }

    /**
     * 外部分享/点链接进来：先记下链接，再直接进「新建任务」页由表单回填。
     * 只接受能识别网盘的文本（夸克/光鸭），避免把普通分享的正文塞进输入框。
     */
    private fun handleShareIntent(intent: Intent?) {
        val text = (intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (com.ashchan.cloudsaver.data.TaskDraftParser.detectDriveType(text) == null) return
        // 分享进来意味着开一个新任务：丢掉可能还停在编辑态的任务
        tasksViewModel.clearEdit()
        tasksViewModel.offerShareLink(text)
        viewModel.go(com.ashchan.cloudsaver.ui.Screen.NEW_TASK)
    }

    /** singleTask：App 已在前台时再分享一次，走这里（不会重建页面） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
