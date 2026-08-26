package com.taskmind.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.capture.ResidencyService
import com.taskmind.di.AppContainer
import com.taskmind.diagnostics.PermissionState
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec 18 - onboarding. Not a footnote: with zero permissions the app does
 * nothing, so this is a first-class flow with a screen per step, each showing
 * live granted/not-granted state and each skippable.
 */
enum class OnboardingStep(val title: String) {
    WELCOME("What TaskMind does"),
    PROVIDERS("AI provider setup"),
    NOTIFICATION_ACCESS("Notification access"),
    APPS("Which apps to watch"),
    BATTERY("Battery optimisation"),
    AUTOSTART("Xiaomi Autostart"),
    CALLS("Call capture"),
    DONE("You are set up"),
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val consent: Boolean = false,
    val permissions: List<PermissionState.Item> = emptyList(),
    val isXiaomi: Boolean = PermissionState.isXiaomi(),
    val callRecordingSeen: Boolean? = null,
    val checkingRecordings: Boolean = false,
    val finished: Boolean = false,
) {
    val steps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter { it != OnboardingStep.AUTOSTART || isXiaomi }
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            _state.value = _state.value.copy(consent = container.settingsRepository.current().cloudConsent)
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(permissions = PermissionState.all(container.context))
    }

    fun setConsent(value: Boolean) {
        _state.value = _state.value.copy(consent = value)
        viewModelScope.launch {
            // Spec 18.1: capture stays disabled until this is ticked. Nothing
            // is captured, and nothing leaves the device, before then.
            container.settingsRepository.setCloudConsent(value)
        }
    }

    fun next() {
        val steps = _state.value.steps
        val index = steps.indexOf(_state.value.step)
        if (index < steps.size - 1) {
            _state.value = _state.value.copy(step = steps[index + 1])
            refresh()
        }
    }

    fun back() {
        val steps = _state.value.steps
        val index = steps.indexOf(_state.value.step)
        if (index > 0) _state.value = _state.value.copy(step = steps[index - 1])
    }

    fun goTo(step: OnboardingStep) {
        _state.value = _state.value.copy(step = step)
        refresh()
    }

    /**
     * Spec 18.7: check whether call recording appears to be enabled by looking
     * for any file in the known paths, and say so plainly if none is found.
     */
    fun checkCallRecording() {
        _state.value = _state.value.copy(checkingRecordings = true)
        viewModelScope.launch {
            val dirUri = container.settingsRepository.current().callRecordingDirUri
            val seen = container.recordingFinder.anyRecordingExists(dirUri)
            _state.value = _state.value.copy(checkingRecordings = false, callRecordingSeen = seen)
        }
    }

    fun finish() {
        viewModelScope.launch {
            container.settingsRepository.setOnboardingComplete(true)
            Scheduler.ensurePeriodicWork(container.context)
            Scheduler.ensureWatchdog(container.context)
            val settings = container.settingsRepository.current()
            if (settings.cloudConsent && settings.captureCalls) {
                ResidencyService.start(container.context)
            }
            _state.value = _state.value.copy(finished = true)
        }
    }
}
