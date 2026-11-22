package com.ashchan.cloudsaver.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashchan.cloudsaver.data.TaskCard
import com.ashchan.cloudsaver.data.TaskDraftParser
import com.ashchan.cloudsaver.data.TaskRepository
import com.ashchan.cloudsaver.data.Weekdays
import com.ashchan.cloudsaver.data.db.RunRecordEntity
import com.ashchan.cloudsaver.data.db.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 任务列表页的 ViewModel。
 *
 * 职责边界：只做「任务 CRUD + 卡片流」。
 * 真正的检测转存执行编排在 Stage 4 接进来（执行时按 task.driveType 取唯一凭证）。
 */
class TasksViewModel(private val repo: TaskRepository) : ViewModel() {

    val cards: StateFlow<List<TaskCard>> = repo.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRuns: StateFlow<List<RunRecordEntity>> = repo.observeRecentRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun clearToast() {
        _toast.value = null
    }

    /**
     * 新建任务。链接解析全部本地完成，不联网。
     * 目标季（seasonFid）与转存范围（scopeMode）在 Stage 4 的「扫描选季」里补齐，
     * 这里先落 SUBTREE，执行时由 ScopeAdvisor 按真实结构重新推断。
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
                    val id = repo.create(
                        TaskEntity(
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
                    )
                    _toast.value = "已添加「$title」"
                    if (id <= 0) _error.value = "保存失败"
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

    /** Stage 3 阶段占位：执行编排尚未接入 */
    fun runTask(task: TaskEntity) {
        _toast.value = "「${task.name}」的检测转存将在下一步接通"
    }

    fun runAll() {
        _toast.value = "一键全部转存将在下一步接通"
    }
}
