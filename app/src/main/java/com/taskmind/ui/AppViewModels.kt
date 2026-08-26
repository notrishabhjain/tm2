package com.taskmind.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taskmind.di.AppContainer
import com.taskmind.ui.calls.CallsViewModel
import com.taskmind.ui.importer.ImportViewModel
import com.taskmind.ui.log.ActivityLogViewModel
import com.taskmind.ui.onboarding.OnboardingViewModel
import com.taskmind.ui.review.ReviewViewModel
import com.taskmind.ui.settings.SettingsViewModel
import com.taskmind.ui.status.StatusViewModel
import com.taskmind.ui.tasks.TaskDetailViewModel
import com.taskmind.ui.tasks.TaskListViewModel

/**
 * Spec 2: no DI framework. ViewModels are built by hand from [AppContainer],
 * which is one factory rather than one annotation processor.
 */
object AppViewModels {

    private fun CreationExtras.container(): AppContainer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        return AppContainer.get(app)
    }

    val factory = viewModelFactory {
        initializer { TaskListViewModel(container()) }
        initializer { TaskDetailViewModel(container()) }
        initializer { ReviewViewModel(container()) }
        initializer { StatusViewModel(container()) }
        initializer { SettingsViewModel(container()) }
        initializer { OnboardingViewModel(container()) }
        initializer { ActivityLogViewModel(container()) }
        initializer { CallsViewModel(container()) }
        initializer { ImportViewModel(container()) }
    }
}
