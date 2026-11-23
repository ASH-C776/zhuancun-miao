package com.ashchan.cloudsaver.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashchan.cloudsaver.core.model.DriveFile
import com.ashchan.cloudsaver.core.model.DriveType
import com.ashchan.cloudsaver.core.model.NaturalOrder
import com.ashchan.cloudsaver.core.model.RenameRule
import com.ashchan.cloudsaver.core.tmdb.TmdbClient
import com.ashchan.cloudsaver.core.tmdb.TmdbSearchItem
import com.ashchan.cloudsaver.data.TaskCard
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.drive.DriveSession
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.storage.CredentialStore
import com.ashchan.cloudsaver.storage.RuleStore
import com.ashchan.cloudsaver.storage.TmdbStore
import com.ashchan.cloudsaver.transfer.TaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 任务列表页的 ViewModel：任务 CRUD + 单条/全部执行编排。
 *
 * 执行语义（对齐原项目）：
 * - 单条：手动点「检测转存」
 * - 全部：串行遍历启用中的任务，绝不同时打同一网盘
 * - 自动：App 打开时跑一轮（每个 ViewModel 生命周期只跑一次，避免来回切页重复打网盘）
 */
class TasksViewModel(
    private val repo: TaskRepository,
    private val runner: TaskRunner,
    private val credentials: CredentialStore? = null,
    private val ruleStore: RuleStore? = null,
    private val tmdbStore: TmdbStore? = null,
) : ViewModel() {

    val cards: StateFlow<List<TaskCard>> = repo.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRuns: StateFlow<List<RunRecordEntity>> = repo.observeRecentRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 正在执行的任务 id，卡片上显示进度圈 */
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    /** 「全部/自动」执行时的整体进度文案 */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()


    fun clearError() {
        _error.value = null
    }

    fun clearToast() {
        _toast.value = null
    }

    /** 清空运行日志（日志页右上角按钮用，只删记录，不动任务与快照） */
    fun clearRuns() {
        viewModelScope.launch {
            runCatching { repo.clearRuns() }
                .onSuccess { _toast.value = "已清空运行日志" }
                .onFailure { _toast.value = "清空失败：${it.message ?: "未知错误"}" }
        }
    }

    /** 新建任务。
     *
     * 链接解析全部本地完成，不联网；网盘类型允许被用户手选覆盖（分享链接本身有时看不出平台）。
     * 保存后立刻跑一次，把「盘里已有多少集」先填上。
     */
    fun createTask(
        name: String,
        link: String,
        weekdays: String,
        tmdbIdText: String,
        airTime: String = "",
        saveFid: String = "0",
        savePathLabel: String = "网盘根目录",
        ruleKey: String = "",
        seasonNo: Int = 0,
        folderWithTmdbId: Boolean = false,
        driveOverride: DriveType? = null,
        /** true = 更新日交给 TMDB 自动推断（用户没手动点过星期） */
        updateModeAuto: Boolean = false,
    ) {
        val title = name.trim()
        if (title.isBlank()) {
            _error.value = "请填写任务名称"
            return
        }
        TaskDraftParser.inspect(link)
            .onFailure { _error.value = it.message ?: "链接无法识别" }
            .onSuccess { inspect ->
                val drive = driveOverride ?: inspect.driveType
                viewModelScope.launch {
                    val task = TaskEntity(
                        name = title,
                        driveType = drive.name,
                        shareUrl = link.trim(),
                        shareId = inspect.shareId,
                        passcode = inspect.passcode,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        weekdays = Weekdays.format(Weekdays.parse(weekdays)),
                        tmdbId = tmdbIdText.trim().toLongOrNull(),
                        airTime = airTime.trim(),
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = folderWithTmdbId,
                        updateMode = if (updateModeAuto || weekdays.isBlank()) {
                            TaskEntity.UPDATE_MODE_TMDB
                        } else {
                            TaskEntity.UPDATE_MODE_MANUAL
                        },
                    )
                    val id = repo.create(task)
                    if (id <= 0) {
                        _error.value = "保存失败"
                        return@launch
                    }
                    _toast.value = "已添加「$title」，正在扫描…"
                    execute(task.copy(id = id), RunRecordEntity.TRIGGER_MANUAL)
                }
            }
    }

    /** 新建页下拉用的命名规则（已禁用的也列出来，UI 上置灰说明） */
    fun renameRules(): List<RenameRule> = ruleStore?.loadRules() ?: emptyList()

    /** 当前正在编辑的任务（null = 新建模式） */
    val editing = MutableStateFlow<TaskEntity?>(null)

    fun startEdit(task: TaskEntity) {
        editing.value = task
    }

    fun clearEdit() {
        editing.value = null
    }

    /**
     * 保存编辑：只落库，不自动同步（用户自己点刷新才会跑）。
     * 链接被改过时重新解析 shareId/passcode；链接没改则保留原值。
     */
    fun saveEdit(
        name: String,
        link: String,
        weekdays: String,
        tmdbIdText: String,
        airTime: String,
        saveFid: String,
        savePathLabel: String,
        ruleKey: String,
        seasonNo: Int,
        folderWithTmdbId: Boolean,
        driveOverride: DriveType?,
        /** true = 更新日交给 TMDB 自动推断（用户没手动点过星期） */
        updateModeAuto: Boolean = false,
    ) {
        val base = editing.value ?: return
        val title = name.trim()
        if (title.isBlank()) {
            _error.value = "请填写任务名称"
            return
        }
        TaskDraftParser.inspect(link)
            .onFailure { _error.value = it.message ?: "链接无法识别" }
            .onSuccess { inspect ->
                val drive = driveOverride ?: inspect.driveType
                viewModelScope.launch {
                    repo.update(
                        base.copy(
                            name = title,
                            driveType = drive.name,
                            shareUrl = link.trim(),
                            shareId = inspect.shareId,
                            passcode = inspect.passcode,
                            saveFid = saveFid,
                            savePathLabel = savePathLabel,
                            weekdays = Weekdays.format(Weekdays.parse(weekdays)),
                            tmdbId = tmdbIdText.trim().toLongOrNull(),
                            airTime = airTime.trim(),
                            ruleKey = ruleKey,
                            seasonNo = seasonNo,
                            folderWithTmdbId = folderWithTmdbId,
                            updateMode = if (updateModeAuto || weekdays.isBlank()) {
                                TaskEntity.UPDATE_MODE_TMDB
                            } else {
                                TaskEntity.UPDATE_MODE_MANUAL
                            },
                        )
                    )
                    _toast.value = "已保存"
                    clearEdit()
                }
            }
    }

    /**
     * 「运行一次」：拿当前表单内容直接转存一轮，**不保存任务**。
     * 仍写一条运行日志，方便回看这次试跑结果；任务卡片上的快照不动。
     */
    fun runDraft(
        name: String,
        link: String,
        weekdays: String,
        tmdbIdText: String,
        airTime: String,
        saveFid: String,
        savePathLabel: String,
        ruleKey: String,
        seasonNo: Int,
        folderWithTmdbId: Boolean,
        driveOverride: DriveType?,
    ) {
        val title = name.trim()
        if (title.isBlank()) {
            _error.value = "请填写任务名称"
            return
        }
        TaskDraftParser.inspect(link)
            .onFailure { _error.value = it.message ?: "链接无法识别" }
            .onSuccess { inspect ->
                val drive = driveOverride ?: inspect.driveType
                viewModelScope.launch {
                    val draft = TaskEntity(
                        id = editing.value?.id ?: 0,
                        name = title,
                        driveType = drive.name,
                        shareUrl = link.trim(),
                        shareId = inspect.shareId,
                        passcode = inspect.passcode,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        weekdays = Weekdays.format(Weekdays.parse(weekdays)),
                        tmdbId = tmdbIdText.trim().toLongOrNull(),
                        airTime = airTime.trim(),
                        ruleKey = ruleKey,
                        seasonNo = seasonNo,
                        folderWithTmdbId = folderWithTmdbId,
                    )
                    _toast.value = "正在跑一次「$title」…"
                    val result = runner.run(draft, RunRecordEntity.TRIGGER_MANUAL, saveSnapshot = false)
                    _toast.value = if (result.getOrNull()?.ok == true) {
                        "「$title」运行完成"
                    } else {
                        "「$title」运行失败"
                    }
                }
            }
    }

    /** 某个网盘是否已登录（新建页用来提示「先去设置里登录」） */
    fun isLoggedIn(drive: DriveType): Boolean =
        credentials?.loadCredential(drive)?.isNotBlank() == true

    /**
     * 列出某个网盘下的一级目录（新建页选保存路径用）。
     * 结果按名称自然序，过滤掉非目录项。
     */
    suspend fun listDirs(drive: DriveType, parentFid: String): Result<List<DriveFile>> = runCatching {
        val store = credentials ?: throw IllegalStateException("凭证存储不可用")
        // 先走一次「等价于点登录」的确保动作：掉线时自动恢复，不用用户回设置页
        val adapter = DriveSession.ensure(drive, store).getOrElse { throw it }

        suspend fun load(): List<DriveFile> =
            adapter.listFolders(parentFid.ifBlank { "0" }).getOrElse { throw it }
                .sortedWith(compareBy(NaturalOrder) { it.name })

        try {
            load()
        } catch (e: Throwable) {
            // 列目录撞到登录类错误：强制重新登录一次再试，仍失败才抛
            if (!DriveSession.isAuthError(e)) throw e
            DriveSession.invalidate(drive)
            DriveSession.ensure(drive, store, force = true).getOrElse { throw it }
            load()
        }
    }

    /** 取某部剧的播出日（可能多个），用于选完剧后自动点亮更新日；口径与原项目一致 */
    suspend fun tmdbWeekdays(tvId: Long): List<Int> = runCatching {
        val store = tmdbStore ?: return emptyList()
        val credential = store.loadCredential()?.takeIf { it.isNotBlank() } ?: return emptyList()
        TmdbClient(credential, store.loadBaseUrl()).inferWeekdays(tvId)
    }.getOrDefault(emptyList())

    /** TMDB 搜索（新建页选剧用）。未配置凭证时直接给出可读原因 */    suspend fun searchTv(query: String): Result<List<TmdbSearchItem>> {
        val store = tmdbStore ?: return Result.failure(IllegalStateException("TMDB 不可用"))
        val credential = store.loadCredential()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("还没配置 TMDB，请先到设置页填写凭证"))
        return TmdbClient(credential, store.loadBaseUrl()).searchTv(query)
    }

    /** 是否已配 TMDB 凭证（UI 决定搜索按钮是否可点） */
    fun hasTmdb(): Boolean = tmdbStore?.loadCredential()?.isNotBlank() == true

    fun deleteTask(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            _toast.value = "已删除"
        }
    }

    fun toggleEnabled(task: TaskEntity) {
        viewModelScope.launch { repo.setEnabled(task.id, !task.enabled) }
    }

    /** 单条检测转存 */
    fun runTask(task: TaskEntity) {
        if (task.id in _running.value) return
        viewModelScope.launch {
            execute(task, RunRecordEntity.TRIGGER_MANUAL)
        }
    }

    /** 一键全部：串行遍历启用中的任务 */
    fun runAll(trigger: String = RunRecordEntity.TRIGGER_ALL) {
        if (_running.value.isNotEmpty()) return
        viewModelScope.launch {
            val tasks = repo.tasks().filter { it.enabled }
            if (tasks.isEmpty()) {
                if (trigger != RunRecordEntity.TRIGGER_BACKGROUND) {
                    _toast.value = "没有启用的任务"
                }
                return@launch
            }
            var ok = 0
            tasks.forEachIndexed { index, task ->
                _progress.value = "同步 ${index + 1}/${tasks.size}：${task.name}"
                if (runOnce(task, trigger)) ok++
            }
            _progress.value = null
            if (trigger != RunRecordEntity.TRIGGER_BACKGROUND || ok > 0) {
                _toast.value = "同步完成：成功 $ok / ${tasks.size}"
            }
        }
    }

    // ---------------- 内部 ----------------

    private suspend fun execute(task: TaskEntity, trigger: String) {
        runOnce(task, trigger).let { ok ->
            _toast.value = if (ok) "「${task.name}」已同步" else "「${task.name}」同步失败"
        }
    }

    private suspend fun runOnce(task: TaskEntity, trigger: String): Boolean {
        _running.update { it + task.id }
        return try {
            val result = runner.run(task, trigger)
            result.isSuccess && (result.getOrNull()?.ok == true)
        } finally {
            _running.update { it - task.id }
        }
    }
}



