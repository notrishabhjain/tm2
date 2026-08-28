package com.taskmind

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.taskmind.di.AppContainer
import com.taskmind.ui.AppViewModels
import com.taskmind.ui.calls.CallsScreen
import com.taskmind.ui.calls.CallsViewModel
import com.taskmind.ui.importer.ImportTranscriptScreen
import com.taskmind.ui.importer.ImportViewModel
import com.taskmind.ui.diagnostics.DiagnosticsScreen
import com.taskmind.ui.diagnostics.DiagnosticsViewModel
import com.taskmind.ui.recordings.RecordingsScreen
import com.taskmind.ui.recordings.RecordingsViewModel
import com.taskmind.ui.inspector.ModelCallsScreen
import com.taskmind.ui.inspector.ModelCallsViewModel
import com.taskmind.ui.log.ActivityLogScreen
import com.taskmind.ui.log.ActivityLogViewModel
import com.taskmind.ui.onboarding.OnboardingScreen
import com.taskmind.ui.onboarding.OnboardingViewModel
import com.taskmind.ui.prompts.PromptScreen
import com.taskmind.ui.prompts.PromptViewModel
import com.taskmind.ui.review.ReviewScreen
import com.taskmind.ui.review.ReviewViewModel
import com.taskmind.ui.settings.SettingsScreen
import com.taskmind.ui.settings.SettingsViewModel
import com.taskmind.ui.status.StatusScreen
import com.taskmind.ui.status.StatusViewModel
import com.taskmind.ui.tasks.TaskDetailScreen
import com.taskmind.ui.tasks.TaskDetailViewModel
import com.taskmind.ui.tasks.TaskListScreen
import com.taskmind.ui.tasks.TaskListViewModel
import com.taskmind.ui.transparency.HowItWorksScreen
import com.taskmind.ui.transparency.HowItWorksViewModel
import com.taskmind.ui.theme.TaskMindTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.get(this)
        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            TaskMindTheme {
                var onboardingChecked by remember { mutableStateOf(false) }
                var needsOnboarding by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val settings = container.settingsRepository.current()
                    needsOnboarding = !settings.onboardingComplete
                    onboardingChecked = true
                }

                if (!onboardingChecked) return@TaskMindTheme

                TaskMindNavHost(
                    startOnboarding = needsOnboarding,
                    startRoute = startRoute,
                    onShareText = ::shareText,
                    onShareFile = ::shareFile,
                )
            }
        }

        // Spec 17.4: re-check permissions on every start, so a revocation shows
        // up on the status screen rather than silently disabling a feature.
        lifecycleScope.launch {
            container.logger.write(
                com.taskmind.core.Stage.SYSTEM,
                com.taskmind.core.LogLevel.DEBUG,
                "app opened",
            )
        }
    }

    /** Log and data exports leave through the share sheet, not a file path. */
    private fun shareText(title: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        runCatching { startActivity(Intent.createChooser(intent, title)) }
    }

    /**
     * A diagnostic report goes out as a file, not as EXTRA_TEXT.
     *
     * The report runs to tens of thousands of characters and most share targets
     * silently truncate a large text extra - which would produce a report that
     * looks complete and is missing the end, the worst possible outcome for a
     * diagnostic.
     */
    private fun shareFile(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "TaskMind diagnostics")) }
    }

    companion object {
        const val EXTRA_ROUTE = "com.taskmind.extra.ROUTE"
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val TASKS = "tasks"
    const val TASK_DETAIL = "task/{taskId}"
    const val REVIEW = "review"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val LOG = "log"
    const val CALLS = "calls"
    const val IMPORT = "import"
    const val PROMPTS = "prompts"
    const val MODEL_CALLS = "model_calls"
    const val HOW_IT_WORKS = "how_it_works"
    const val DIAGNOSTICS = "diagnostics"
    const val RECORDINGS = "recordings"

    fun taskDetail(id: String) = "task/$id"
}

@Composable
fun TaskMindNavHost(
    startOnboarding: Boolean,
    startRoute: String?,
    onShareText: (String, String) -> Unit,
    onShareFile: (File) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val start = when {
        startOnboarding -> Routes.ONBOARDING
        startRoute == "status" -> Routes.STATUS
        startRoute == "review" -> Routes.REVIEW
        else -> Routes.TASKS
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            val vm: OnboardingViewModel = viewModel(factory = AppViewModels.factory)
            OnboardingScreen(
                viewModel = vm,
                onFinished = {
                    navController.navigate(Routes.TASKS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.TASKS) {
            val vm: TaskListViewModel = viewModel(factory = AppViewModels.factory)
            TaskListScreen(
                viewModel = vm,
                onOpenTask = { id -> navController.navigate(Routes.taskDetail(id)) },
                onOpenStatus = { navController.navigate(Routes.STATUS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                onOpenCalls = { navController.navigate(Routes.CALLS) },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
            )
        }

        composable(Routes.TASK_DETAIL) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId").orEmpty()
            val vm: TaskDetailViewModel = viewModel(factory = AppViewModels.factory)
            TaskDetailScreen(taskId = taskId, viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.REVIEW) {
            val vm: ReviewViewModel = viewModel(factory = AppViewModels.factory)
            ReviewScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.STATUS) {
            val vm: StatusViewModel = viewModel(factory = AppViewModels.factory)
            StatusScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onShareText = onShareText,
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = AppViewModels.factory)
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onShareText = onShareText,
                onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenHowItWorks = { navController.navigate(Routes.HOW_IT_WORKS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
            )
        }

        composable(Routes.RECORDINGS) {
            val vm: RecordingsViewModel = viewModel(factory = AppViewModels.factory)
            RecordingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.DIAGNOSTICS) {
            val vm: DiagnosticsViewModel = viewModel(factory = AppViewModels.factory)
            DiagnosticsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onShareFile = onShareFile,
            )
        }

        composable(Routes.PROMPTS) {
            val vm: PromptViewModel = viewModel(factory = AppViewModels.factory)
            PromptScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onShareText = onShareText,
            )
        }

        composable(Routes.MODEL_CALLS) {
            val vm: ModelCallsViewModel = viewModel(factory = AppViewModels.factory)
            ModelCallsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onShareText = onShareText,
            )
        }

        composable(Routes.HOW_IT_WORKS) {
            val vm: HowItWorksViewModel = viewModel(factory = AppViewModels.factory)
            HowItWorksScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenLog = { navController.navigate(Routes.LOG) },
            )
        }

        composable(Routes.LOG) {
            val vm: ActivityLogViewModel = viewModel(factory = AppViewModels.factory)
            ActivityLogScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onShareText = onShareText,
            )
        }

        composable(Routes.CALLS) {
            val vm: CallsViewModel = viewModel(factory = AppViewModels.factory)
            CallsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
            )
        }

        composable(Routes.IMPORT) {
            val vm: ImportViewModel = viewModel(factory = AppViewModels.factory)
            ImportTranscriptScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
