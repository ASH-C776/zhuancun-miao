package com.ashchan.cloudsaver.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自更新的状态机：启动后延迟检测 → 发现新版本 → 弹窗 → 下载 → 交给系统安装器。
 *
 * 全部失败静默（不弹报错、不影响正常功能），只有「下载失败」会切到重试态。
 */
class UpdateManager(private val context: Context) {

    private val prefs = UpdatePrefs(context)
    private val source: UpdateSource = DefaultUpdateSource(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** 可更新的版本（null = 没有 / 已取消过） */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _prompt = MutableStateFlow(false)
    /** 是否该弹「发现新版本」弹窗（等主页面 + 未取消过） */
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    private val _progress = MutableStateFlow<Int?>(null)
    /** 下载进度 0..100；null = 不在下载 */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** 当前已装版本号（与 build.gradle 同一算法；debug 的 `-debug` 后缀由 PackageManager 给出，不影响比较） */
    fun currentVersionCode(): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 启动检测：延迟 5 秒避开首屏高峰；24 小时最多查一次。
     * 只在主页面弹（由 UI 判断当前 screen），别在看图/设置时打断。
     */
    fun autoCheck() {
        scope.launch {
            delay(UpdatePrefs.START_DELAY_MS)
            val now = System.currentTimeMillis()
            if (prefs.isThrottled(now)) return@launch
            val info = source.check(currentVersionCode(), prefs.dismissedCode) ?: run {
                prefs.lastCheckMs = now
                return@launch
            }
            prefs.lastCheckMs = now
            _available.value = info
            _prompt.value = true
        }
    }

    /** 手动检查（设置/关于页用），不受 24 小时节流 */
    fun checkNow() {
        scope.launch {
            val info = source.check(currentVersionCode(), prefs.dismissedCode) ?: return@launch
            prefs.lastCheckMs = System.currentTimeMillis()
            _available.value = info
            _prompt.value = true
        }
    }

    fun dismiss() {
        _available.value?.let { prefs.dismissedCode = it.versionCode }
        _prompt.value = false
        _failed.value = false
    }

    /** 下载并在完成后拉起系统安装器 */
    fun downloadAndInstall() {
        val info = _available.value ?: return
        if (_progress.value != null) return
        _failed.value = false
        _progress.value = 0
        scope.launch {
            val file = source.download(info) { percent -> _progress.value = percent }
            _progress.value = null
            if (file == null) {
                _failed.value = true
            } else {
                _prompt.value = false
                install(file)
            }
        }
    }

    /** 下载失败后重试 */
    fun retry() {
        _failed.value = false
        downloadAndInstall()
    }

    /**
     * 拉起系统安装器。
     * targetSdk ≥ 30 必须走 FileProvider 的 `content://`，传 file:// 会 FileUriExposedException，
     * 用户看到的是「解析包错误」，很容易误判成 APK 损坏。
     */
    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 系统是否允许本应用「安装未知来源应用」（Android 8+ 需要用户手动打开一次） */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 打开「允许安装未知应用」的系统设置页 */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /** 下载后清理旧包，避免 filesDir 堆一堆历史 APK */
    suspend fun cleanupOldApks(keep: File? = null) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "update")
        dir.listFiles()?.forEach { file -> if (file != keep) runCatching { file.delete() } }
    }
}
