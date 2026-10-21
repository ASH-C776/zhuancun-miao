package com.ashchan.cloudsaver.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashchan.cloudsaver.core.adapter.AccountInfo
import com.ashchan.cloudsaver.core.adapter.CloudDriveAdapter
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.RenamePreview
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.model.RuleMode
import com.ashchan.cloudsaver.core.model.ShareInfo
import com.ashchan.cloudsaver.core.model.TransferResult
import com.ashchan.cloudsaver.drive.DriveFactory
import com.ashchan.cloudsaver.drive.guangya.GuangYaSmsLogin
import com.ashchan.cloudsaver.rename.RenameEngine
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.transfer.TransferManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 页面导航状态 */
enum class Screen { HOME, SETTINGS, FOLDER_PICKER, ABOUT, DONATE }

/** 项目仓库（更新检测与跳转均基于此） */
const val REPO_URL = "https://github.com/ASH-C776/zhuancun-miao"

private const val REPO_API_LATEST = "https://api.github.com/repos/ASH-C776/zhuancun-miao/releases/latest"
private val REGEX_TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

/** 更新检测结果：是否有比当前版本新的 Release */
data class UpdateState(
    val hasUpdate: Boolean = false,
    val latestVersion: String? = null,
)

/** 转存进度弹窗状态 */
data class TransferDialogState(
    val running: Boolean,
    val stage: Int,          // 0=解析/转存中，1=重命名中
    val percent: Int,
    val message: String,
    val result: TransferResult? = null,
)

/** 预命名预览条目（原名 → 新名，新名可手改） */
data class PreviewItem(
    val file: DriveFile,
    val originName: String,
    val newName: String,
    val filtered: Boolean,
    val hasUnresolved: Boolean,
)

/** 预命名预览弹窗状态 */
data class PreviewDialogState(
    val items: List<PreviewItem>,
)

/**
 * 全局业务状态机：
 * 主页（链接+任务名+网盘选择+路径+规则） → 右上角设置（登录） → 目录选择器 → 转存弹窗（进度/结果）。
 * 每个网盘独立登录、独立 adapter，可同时登录；主页切换当前网盘。
 * 一次转存 = 解析链接 → 转存分享全部内容 → 本地计算新名 → 批量改名 → 结果。
 */
class AppViewModel(
    private val credentialStore: CredentialStore,
    private val ruleStore: RuleStore,
) : ViewModel() {

    // ---------- 状态 ----------
    /** 从外部（分享菜单/链接）带进来的链接，主页自动填入 */
    var pendingExternalLink: String? = null

    // 主页输入框内容（放 ViewModel，跨页面导航不丢失）
    private val _linkInput = MutableStateFlow("")
    val linkInput: StateFlow<String> = _linkInput.asStateFlow()
    private val _taskNameInput = MutableStateFlow("")
    val taskNameInput: StateFlow<String> = _taskNameInput.asStateFlow()

    fun setLinkInput(v: String) { _linkInput.value = v }
    fun setTaskNameInput(v: String) { _taskNameInput.value = v }

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // 各网盘已登录账号（仅放登录成功的）
    private val _accounts = MutableStateFlow<Map<DriveType, AccountInfo>>(emptyMap())
    val accounts: StateFlow<Map<DriveType, AccountInfo>> = _accounts.asStateFlow()

    // 主页当前选中的网盘
    private val _driveType = MutableStateFlow<DriveType?>(null)
    val driveType: StateFlow<DriveType?> = _driveType.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    // 设置页当前正在配置的网盘（独立于主页选中，避免互相污染）
    private val _settingsDriveType = MutableStateFlow(DriveType.GUANGYA)
    val settingsDriveType: StateFlow<DriveType> = _settingsDriveType.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _loginLoading = MutableStateFlow(false)
    val loginLoading: StateFlow<Boolean> = _loginLoading.asStateFlow()

    // ---------- 版本更新检测 ----------
    private val _update = MutableStateFlow(UpdateState())
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /**
     * 查询 GitHub Releases 最新版本并与当前版本比较。
     * 无网络 / 仓库还没有 Release 时静默忽略，不打扰用户。
     */
    fun checkUpdate(currentVersion: String) {
        viewModelScope.launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(REPO_API_LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "zhuancun-miao-app")
                if (conn.responseCode != 200) return@launch
                val latest = conn.inputStream.bufferedReader().use { reader ->
                    REGEX_TAG_NAME.find(reader.readText())?.groupValues?.get(1)
                }?.trimStart('v', 'V') ?: return@launch
                if (isNewerVersion(latest, currentVersion)) {
                    _update.value = UpdateState(hasUpdate = true, latestVersion = latest)
                }
            } catch (_: Exception) {
                // 静默：离线或接口异常时不提示
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 语义化版本比较：remote 是否比 current 新（逐段数字比较） */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        // 剥离 -debug 等后缀，只取开头的「数字.数字...」部分（如 "0.4.1-debug" → "0.4.1"），
        // 否则 "1-debug" 会被解析成 0，导致调试版恒误报「有更新」
        val r = sanitizeVersion(remote).split('.').map { it.toIntOrNull() ?: 0 }
        val c = sanitizeVersion(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** 只保留版本号开头的「数字.数字...」部分，去掉 -debug 等后缀与前置 v/V */
    private fun sanitizeVersion(v: String): String =
        v.trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }

    private val _rules = MutableStateFlow<List<RenameRule>>(emptyList())
    val rules: StateFlow<List<RenameRule>> = _rules.asStateFlow()

    private val _folders = MutableStateFlow<List<DriveFile>>(emptyList())
    val folders: StateFlow<List<DriveFile>> = _folders.asStateFlow()

    private val _targetFolder = MutableStateFlow("0")
    val targetFolder: StateFlow<String> = _targetFolder.asStateFlow()

    /** 目标文件夹显示路径（"根目录" 或 面包屑） */
    private val _targetPath = MutableStateFlow("根目录")
    val targetPath: StateFlow<String> = _targetPath.asStateFlow()

    /** 目标目录面包屑（fid to name，从根到当前） */
    private val _folderCrumb = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val folderCrumb: StateFlow<List<Pair<String, String>>> = _folderCrumb.asStateFlow()

    /** 转存进度弹窗 */
    private val _transferDialog = MutableStateFlow<TransferDialogState?>(null)
    val transferDialog: StateFlow<TransferDialogState?> = _transferDialog.asStateFlow()

    /** 预命名预览弹窗（转存前确认） */
    private val _previewDialog = MutableStateFlow<PreviewDialogState?>(null)
    val previewDialog: StateFlow<PreviewDialogState?> = _previewDialog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---------- 转存流程内部状态 ----------
    private val _share = MutableStateFlow<ShareInfo?>(null)
    private val _shareFiles = MutableStateFlow<List<DriveFile>>(emptyList())
    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    private val _taskName = MutableStateFlow("")
    val taskName: StateFlow<String> = _taskName.asStateFlow()

    private val _previews = MutableStateFlow<List<RenamePreview>>(emptyList())

    /** 分享 fid -> 最终新名（规则计算后） */
    private val _renamePlan = MutableStateFlow<Map<String, String>>(emptyMap())

    // ---------- 依赖 ----------
    private val adapters = mutableMapOf<DriveType, CloudDriveAdapter>()
    private val adapter: CloudDriveAdapter? get() = _driveType.value?.let { adapters[it] }
    private val engine = RenameEngine()

    init {
        _rules.value = ruleStore.loadRules()
        autoLogin()
    }

    /**
     * 启动时自动恢复登录态：逐个网盘用已保存凭证静默验证（不弹错误），
     * 成功的放入登录表；默认选中第一个已登录网盘。
     */
    private fun autoLogin() {
        viewModelScope.launch {
            for (type in DriveType.entries) {
                val credential = credentialStore.loadCredential(type) ?: continue
                if (credential.isBlank()) continue
                runCatching {
                    val instance = DriveFactory.create(type, credential)
                    val r = instance.validateLogin(credential).getOrThrow()
                    // 光鸭刷新后 token 可能变化，回写最新凭证
                    DriveFactory.guangYaCredential(instance)?.let {
                        credentialStore.saveCredential(type, it)
                    }
                    adapters[type] = instance
                    _accounts.update { m -> m + (type to r) }
                }
            }
            if (_driveType.value == null) {
                DriveType.entries.firstOrNull { _accounts.value[it] != null }?.let {
                    selectCurrentDrive(it)
                }
            }
        }
    }

    // ---------- 导航 ----------
    fun go(screen: Screen) {
        _screen.value = screen
        _error.value = null
    }

    // ---------- 登录（设置页） ----------
    /** 切换设置页正在配置的网盘（只影响表单） */
    fun selectDrive(type: DriveType) {
        _settingsDriveType.value = type
        _loginError.value = null
    }

    fun login(credential: String) {
        val type = _settingsDriveType.value
        if (credential.isBlank()) {
            _loginError.value = "请先填写登录凭证"
            return
        }
        viewModelScope.launch {
            _loginLoading.value = true
            _loginError.value = null
            runCatching {
                val instance = DriveFactory.create(type, credential)
                val r = instance.validateLogin(credential).getOrThrow()
                credentialStore.saveCredential(type, credential)
                // 光鸭刷新后 token 可能变化，回写最新凭证
                DriveFactory.guangYaCredential(instance)?.let {
                    credentialStore.saveCredential(type, it)
                }
                adapters[type] = instance
                _accounts.update { m -> m + (type to r) }
                // 登录成功后主页切到该网盘
                selectCurrentDrive(type)
            }.onFailure {
                _loginError.value = it.message ?: "登录失败"
            }
            _loginLoading.value = false
        }
    }

    /** 退出设置页当前配置网盘的登录 */
    fun logout() {
        val type = _settingsDriveType.value
        credentialStore.clearCredential(type)
        adapters.remove(type)
        _accounts.update { m -> m - type }
        if (_driveType.value == type) {
            val next = DriveType.entries.firstOrNull { _accounts.value[it] != null }
            if (next != null) selectCurrentDrive(next) else {
                _driveType.value = null
                _account.value = null
                _resetPath()
            }
        }
    }

    /** 设置页读取当前配置网盘的已保存凭证（用于回填） */
    fun loadSavedCredential(): String? =
        credentialStore.loadCredential(_settingsDriveType.value)

    // ---------- 光鸭短信验证码登录（设置页弹框） ----------
    /** 短信登录弹框各阶段 */
    enum class SmsPhase { DECLARATION, PHONE, CODE, HUMAN_VERIFY, SUCCESS }

    /** 短信登录弹框状态（声明 → 手机号 → 验证码 → 人机验证 → 成功回填） */
    data class SmsLoginState(
        val open: Boolean = false,
        val phase: SmsPhase = SmsPhase.DECLARATION,
        val phone: String = "",
        val code: String = "",
        val captchaUrl: String? = null,           // 人机验证页 url（需要时）
        val countdown: Int = 0,                    // 重新获取验证码倒计时（秒）
        val loading: Boolean = false,
        val error: String? = null,
        val resultRefreshToken: String? = null,    // 成功后产出，UI 取走后清空
    )

    private val _smsLogin = MutableStateFlow(SmsLoginState())
    val smsLoginState: StateFlow<SmsLoginState> = _smsLogin.asStateFlow()

    // 跨步骤的中间令牌（不进 UI 状态，仅内部流转）
    private var smsCaptchaToken: String? = null
    private var smsVerificationId: String? = null

    /** 打开短信登录弹框（进入声明页） */
    fun openSmsLogin() {
        _smsLogin.value = SmsLoginState(open = true, phase = SmsPhase.DECLARATION)
    }

    /** 关闭弹框并复位 */
    fun closeSmsLogin() {
        _smsLogin.value = SmsLoginState()
        smsCaptchaToken = null
        smsVerificationId = null
    }

    /** 声明页「我已了解」→ 进入手机号输入 */
    fun smsAgreeDeclaration() {
        _smsLogin.update { it.copy(phase = SmsPhase.PHONE, error = null) }
    }

    /** 手机号页「获取验证码」：初始化验证并发送短信 */
    fun smsRequestCode(phone: String) {
        val formatted = phone.trim()
        if (formatted.isBlank()) {
            _smsLogin.update { it.copy(error = "请输入手机号") }
            return
        }
        viewModelScope.launch {
            _smsLogin.update { it.copy(loading = true, error = null, phone = formatted) }
            runCatching {
                val init = GuangYaSmsLogin.captchaInit(formatted)
                when (init) {
                    is GuangYaSmsLogin.CaptchaInitResult.Token -> {
                        smsCaptchaToken = init.captchaToken
                        val vid = GuangYaSmsLogin.sendCode(init.captchaToken, formatted)
                        smsVerificationId = vid
                        _smsLogin.update { it.copy(phase = SmsPhase.CODE, loading = false, countdown = 60) }
                    }
                    is GuangYaSmsLogin.CaptchaInitResult.HumanVerify -> {
                        // 需要人机验证：交给 WebView 兜底层处理
                        _smsLogin.update {
                            it.copy(phase = SmsPhase.HUMAN_VERIFY, loading = false, captchaUrl = init.url)
                        }
                    }
                }
            }.onFailure { e ->
                _smsLogin.update { it.copy(loading = false, error = readableSmsError(e)) }
            }
        }
    }

    /** 人机验证完成后（WebView 提取到 captcha_token）回调，继续发送短信 */
    fun smsHumanVerified(captchaToken: String) {
        val phone = _smsLogin.value.phone
        viewModelScope.launch {
            _smsLogin.update { it.copy(loading = true, error = null) }
            runCatching {
                smsCaptchaToken = captchaToken
                val vid = GuangYaSmsLogin.sendCode(captchaToken, phone)
                smsVerificationId = vid
                _smsLogin.update { it.copy(phase = SmsPhase.CODE, loading = false, captchaUrl = null, countdown = 60) }
            }.onFailure { e ->
                _smsLogin.update { it.copy(loading = false, error = readableSmsError(e)) }
            }
        }
    }

    /** 验证码页「登录并获取 Token」：校验 → 登录 → 产出 refresh_token */
    fun smsSubmitCode(code: String) {
        val phone = _smsLogin.value.phone
        val captchaToken = smsCaptchaToken
        val verificationId = smsVerificationId
        if (captchaToken.isNullOrBlank() || verificationId.isNullOrBlank()) {
            _smsLogin.update { it.copy(error = "登录状态已失效，请重新获取验证码") }
            return
        }
        if (code.trim().length < 4) {
            _smsLogin.update { it.copy(error = "请输入收到的验证码") }
            return
        }
        viewModelScope.launch {
            _smsLogin.update { it.copy(loading = true, error = null) }
            runCatching {
                val vToken = GuangYaSmsLogin.verifyCode(verificationId, code.trim(), phone)
                val refresh = GuangYaSmsLogin.signIn(captchaToken, vToken, code.trim(), phone)
                _smsLogin.update { it.copy(phase = SmsPhase.SUCCESS, loading = false, resultRefreshToken = refresh) }
            }.onFailure { e ->
                _smsLogin.update { it.copy(loading = false, error = readableSmsError(e)) }
            }
        }
    }

    /** UI 取走成功产出的 refresh_token 后调用，清空结果并关闭弹框 */
    fun smsConsumeResult() {
        _smsLogin.update { it.copy(resultRefreshToken = null, open = false) }
        smsCaptchaToken = null
        smsVerificationId = null
    }

    /** 短信登录相关错误转中文 */
    private fun readableSmsError(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            msg.isNotBlank() -> msg
            e is java.net.UnknownHostException -> "网络错误：无法连接光鸭服务器，请检查网络"
            e is java.net.ConnectException -> "网络错误：连接光鸭服务器失败，请检查网络"
            e is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
            else -> "操作失败，请重试或改用手动粘贴 Token"
        }
    }

    // ---------- 主页网盘选择 ----------
    /** 主页点击网盘卡片：已登录则切换；未登录则提示去设置 */
    fun selectDriveOnHome(type: DriveType) {
        if (_accounts.value[type] == null) {
            _error.value = "「${type.displayName}」尚未登录，请点右上角设置登录"
            _settingsDriveType.value = type
            return
        }
        selectCurrentDrive(type)
    }

    /** 切换主页当前网盘，并重置目标路径 */
    private fun selectCurrentDrive(type: DriveType) {
        _driveType.value = type
        _account.value = _accounts.value[type]
        _resetPath()
    }

    private fun _resetPath() {
        _folders.value = emptyList()
        _folderCrumb.value = emptyList()
        _targetFolder.value = "0"
        _targetPath.value = "根目录"
    }

    // ---------- 目标文件夹（目录选择器） ----------
    fun goFolderPicker() {
        val instance = adapter ?: run {
            _error.value = "请先在右上角设置中登录网盘"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val dirs = instance.listFolders("0").getOrThrow()
                _folders.value = dirs
                _folderCrumb.value = emptyList()
                _targetFolder.value = "0"
                _targetPath.value = "根目录"
                _screen.value = Screen.FOLDER_PICKER
            }.onFailure { _error.value = it.message ?: "加载目录失败" }
            _busy.value = false
        }
    }

    fun enterFolder(fid: String, name: String) {
        val instance = adapter ?: return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val dirs = instance.listFolders(fid).getOrThrow()
                _folders.value = dirs
                _folderCrumb.value = _folderCrumb.value + (fid to name)
                _targetFolder.value = fid
                _targetPath.value = buildTargetPath(_folderCrumb.value)
            }.onFailure { _error.value = it.message ?: "进入目录失败" }
            _busy.value = false
        }
    }

    /** 直达网盘根目录 */
    fun goRootFolder() {
        val instance = adapter ?: return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val dirs = instance.listFolders("0").getOrThrow()
                _folders.value = dirs
                _folderCrumb.value = emptyList()
                _targetFolder.value = "0"
                _targetPath.value = "根目录"
            }.onFailure { _error.value = it.message ?: "加载根目录失败" }
            _busy.value = false
        }
    }

    fun backFolder() {
        val crumb = _folderCrumb.value
        if (crumb.isEmpty()) return
        val instance = adapter ?: return
        viewModelScope.launch {
            _busy.value = true
            val newCrumb = crumb.dropLast(1)
            val parent = newCrumb.lastOrNull()?.first ?: "0"
            runCatching {
                val dirs = instance.listFolders(parent).getOrThrow()
                _folders.value = dirs
                _folderCrumb.value = newCrumb
                _targetFolder.value = parent
                _targetPath.value = buildTargetPath(newCrumb)
            }.onFailure { _error.value = it.message ?: "返回目录失败" }
            _busy.value = false
        }
    }

    /** 确认选择目标文件夹，回到主页 */
    fun confirmFolder() {
        _screen.value = Screen.HOME
    }

    private fun buildTargetPath(crumb: List<Pair<String, String>>): String =
        if (crumb.isEmpty()) "根目录" else crumb.joinToString(" / ") { it.second }

    // ---------- 规则 ----------
    /** 单选一条规则（其余关闭） */
    fun selectRule(key: String) {
        _rules.update { list ->
            list.map { it.copy(enabled = it.key == key) }
        }
        persistRuleStates()
    }

    /** 不使用任何规则（保持原名） */
    fun selectNoRule() {
        _rules.update { list ->
            list.map { it.copy(enabled = false) }
        }
        persistRuleStates()
    }

    /** 自定义拼装规则：勾选剧名 + 固定季度（如 S01），生成并启用（每次现拼，不持久化） */
    fun applyCustomRule(useTaskName: Boolean, season: String?) {
        val custom = RenameRule(
            key = CUSTOM_RULE_KEY,
            name = "自定义",
            enabled = true,
            pattern = ".*\\.(mp4|mkv|mov|m4v|avi|mpeg|ts|zip|cas|iso)$",
            replace = buildCustomTemplate(useTaskName, season),
            mode = RuleMode.RENAME,
            builtin = false,
        )
        _rules.update { list ->
            list.filterNot { it.key == CUSTOM_RULE_KEY }.map { it.copy(enabled = false) } + custom
        }
        persistRuleStates()
    }

    /** 拼装 replace 模板：剧名.{SXX}E{E}.{EXT}（季度固定写死） */
    private fun buildCustomTemplate(useTaskName: Boolean, season: String?): String {
        val sb = StringBuilder()
        if (useTaskName) sb.append("{TASKNAME}.")
        sb.append(season ?: "").append("E{E}.")
        sb.append("{EXT}")
        return sb.toString()
    }

    private fun persistRuleStates() {
        _rules.value.forEach { ruleStore.setBuiltinEnabled(it.key, it.enabled) }
    }

    /** 根据当前规则生成重命名计划（转存前调用） */
    private fun refreshPreview() {
        val name = _taskName.value
        val prev = engine.preview(selectedShareFiles(), _rules.value, name)
        _previews.value = prev
        _renamePlan.value = prev.filter { !it.filtered && it.newName != null }
            .associate { it.file.fid to (it.newName ?: it.originName) }
    }

    private fun selectedShareFiles(): List<DriveFile> {
        val selectedIds = _selected.value
        return _shareFiles.value.filter { it.fid in selectedIds && !it.isDir }
    }

    // ---------- 转存 + 重命名（两步：预命名预览 → 确认转存） ----------
    /**
     * 第一步：解析分享链接 + 拉取文件列表 + 本地计算预命名 → 弹出「原名→新名」预览（可手改）。
     * 不发起转存。
     */
    fun startTransfer(link: String, taskName: String) {
        val instance = adapter
        if (instance == null) {
            _error.value = "请先在右上角设置中登录网盘"
            return
        }
        if (link.isBlank()) {
            _error.value = "请先粘贴分享链接"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _taskName.value = taskName.trim()
            _transferDialog.value = TransferDialogState(
                running = true, stage = 0, percent = 0, message = "正在解析分享链接…",
            )
            runCatching {
                // 1. 解析分享链接 + 获取访问令牌
                val parsed = instance.parseShareLink(link).getOrThrow()
                val resolved = instance.resolveShareAccess(parsed).getOrThrow()
                _share.value = resolved
                // 2. 加载分享根目录全部内容（文件 + 文件夹，整份转存）
                val root = resolved.pdirFid.takeIf { it != "0" }
                val all = instance.fetchShareChildren(resolved, root).getOrThrow()
                if (all.isEmpty()) throw IllegalStateException("分享中没有可转存的内容")
                _shareFiles.value = all
                _selected.value = all.map { it.fid }.toSet()
                // 2.5 若分享根是单个文件夹，把其内部文件也纳入重命名计划（转存后对其内部文件改名）
                val renameTargets: List<DriveFile> =
                    if (all.size == 1 && all[0].isDir) {
                        runCatching { instance.fetchShareChildren(resolved, all[0].fid).getOrThrow() }
                            .getOrElse { emptyList() }
                    } else all
                // 3. 本地计算预命名
                val prev = engine.preview(renameTargets.filter { !it.isDir }, _rules.value, _taskName.value)
                _previews.value = prev
                Log.d("TransferDebug", "PREVIEW task=$taskName rules=${_rules.value.filter{it.enabled}.map{it.key}} " +
                    "targets=${renameTargets.size} files=${prev.size}")
                // 4. 显示预命名预览弹窗（解析失败/过滤的条目保留原名或标灰，可手改）
                _transferDialog.value = null
                _previewDialog.value = PreviewDialogState(
                    items = prev.map { p ->
                        PreviewItem(
                            file = p.file,
                            originName = p.originName,
                            newName = p.newName ?: p.originName,
                            filtered = p.filtered,
                            hasUnresolved = p.hasUnresolved,
                        )
                    },
                )
            }.onFailure { e ->
                _transferDialog.value = null
                _error.value = e.message ?: "解析分享失败"
            }
            _busy.value = false
        }
    }

    /** 预命名预览中手改某个文件的新名 */
    fun updatePreviewName(fid: String, newName: String) {
        _previewDialog.update { st ->
            st?.copy(items = st.items.map {
                if (it.file.fid == fid) it.copy(newName = newName) else it
            })
        }
    }

    /** 关闭预览弹窗（不转存） */
    fun cancelPreview() {
        _previewDialog.value = null
    }

    /** 第二步：确认预览 → 生成重命名计划 → 转存 + 批量改名 */
    fun confirmTransfer() {
        val st = _previewDialog.value ?: return
        val instance = adapter ?: return
        val resolved = _share.value ?: return
        val all = _shareFiles.value
        val taskName = _taskName.value
        // 生成计划：未过滤 且 新名与原名不同
        _renamePlan.value = st.items
            .filter { !it.filtered && it.newName.isNotBlank() && it.newName != it.originName }
            .associate { it.file.fid to it.newName.trim() }
        val plan = _renamePlan.value
        _previewDialog.value = null
        Log.d("TransferDebug", "CONFIRM plan=${plan.entries.take(8)}")
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _transferDialog.value = TransferDialogState(
                running = true, stage = 0, percent = 0, message = "正在转存…",
            )
            runCatching {
                val manager = TransferManager(instance)
                val r = manager.execute(
                    share = resolved,
                    files = all,
                    targetFolderId = _targetFolder.value,
                    taskName = taskName,
                    renamePlan = plan,
                    onProgress = { stage, percent, msg ->
                        _transferDialog.value = TransferDialogState(
                            running = true, stage = stage, percent = percent, message = msg,
                        )
                    },
                )
                _transferDialog.value = TransferDialogState(
                    running = false, stage = 1, percent = 100,
                    message = if (r.failCount == 0) "全部完成" else "部分失败",
                    result = r,
                )
            }.onFailure { e ->
                _transferDialog.value = TransferDialogState(
                    running = false, stage = 0, percent = 0,
                    message = e.message ?: "转存失败",
                    result = null,
                )
                _error.value = e.message ?: "转存失败"
            }
            _busy.value = false
        }
    }

    fun dismissDialog() {
        _transferDialog.value = null
    }

    companion object {
        /** 自定义拼装规则的 key（每次现拼，不落盘） */
        const val CUSTOM_RULE_KEY = "\$CUSTOM_BUILT"
    }
}
