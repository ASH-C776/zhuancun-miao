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
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RecordStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.ui.AppRoot
import com.ashchan.cloudsaver.ui.AppViewModel
import com.ashchan.cloudsaver.ui.CloudSaverTheme
import com.ashchan.cloudsaver.ui.ScreenViewModelFactory
import com.ashchan.cloudsaver.ui.THEME_AUTO
import com.ashchan.cloudsaver.ui.THEME_DARK
import com.ashchan.cloudsaver.ui.THEME_LIGHT

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        ScreenViewModelFactory(
            credentialStore = CredentialStore(applicationContext),
            ruleStore = RuleStore(applicationContext),
            recordStore = RecordStore(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // 处理从外部打开/分享的链接
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            viewModel.pendingExternalLink = sharedText
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
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
