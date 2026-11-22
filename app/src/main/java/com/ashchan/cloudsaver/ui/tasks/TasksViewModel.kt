package com.ashchan.cloudsaver.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashchan.cloudsaver.data.TaskCard
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
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

    private var autoSynced = false

    fun clearError() {
        _error.value = null
    }

    fun clearToast() {
        _toast.value = null
    }

    /**
     * 新建任务。链接解析全部本地完成，不联网；保存后立刻跑一次，
     * 把「盘里已有多少集」先填上（对应弹窗里那句提示）。
     */
    fun createTask(
        name: String,
        link: String,
        weekdays: String,
        tmdbIdText: String,
        saveFid: String = "0",
        savePathLabel: String = "网盘根目录",
    ) {
        val title = name.trim()
        if (title.isBlank()) {
            _error.value = "请填写剧名"
            return
        }
        TaskDraftParser.inspect(link)
            .onFailure { _error.value = it.message ?: "链接无法识别" }
            .onSuccess { inspect ->
                viewModelScope.launch {
                    val task = TaskEntity(
                        name = title,
                        driveType = inspect.driveType.name,
                        shareUrl = link.trim(),
                        shareId = inspect.shareId,
                        passcode = inspect.passcode,
                        saveFid = saveFid,
                        savePathLabel = savePathLabel,
                        weekdays = Weekdays.format(Weekdays.parse(weekdays)),
                        tmdbId = tmdbIdText.trim().toLongOrNull(),
                        updateMode = if (weekdays.isBlank()) {
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
                _toast.value = "没有启用的任务"
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

    /** App 打开时自动同步一轮（只跑一次） */
    fun syncOnStartOnce() {
        if (autoSynced) return
        autoSynced = true
        runAll(RunRecordEntity.TRIGGER_BACKGROUND)
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
