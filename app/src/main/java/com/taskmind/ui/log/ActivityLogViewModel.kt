package com.taskmind.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.LogLevel
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Spec 15 - the activity log with a level filter and a share action.
 *
 * This screen is the app's debugger. When something does not work, the log has
 * to say why, because there is no other instrument.
 */
class ActivityLogViewModel(private val container: AppContainer) : ViewModel() {

    private val _level = MutableStateFlow(LogLevel.DEBUG)
    val level: StateFlow<LogLevel> = _level.asStateFlow()

    private val _stageFilter = MutableStateFlow<String?>(null)
    val stageFilter: StateFlow<String?> = _stageFilter.asStateFlow()

    val entries: StateFlow<List<ActivityLogEntity>> = combine(
        container.database.activityLogDao().observeRecent(ActivityLogger.KEEP),
        _level,
        _stageFilter,
    ) { all, level, stage ->
        all.filter { it.level.ordinal >= level.ordinal }
            .filter { stage == null || it.stage == stage }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLevel(level: LogLevel) {
        _level.value = level
    }

    fun setStage(stage: String?) {
        _stageFilter.value = stage
    }

    fun exportText(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(container.logger.exportAsText()) }
    }

    fun clear() {
        viewModelScope.launch { container.database.activityLogDao().deleteAll() }
    }
}
