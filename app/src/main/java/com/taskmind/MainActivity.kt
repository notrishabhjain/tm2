package com.taskmind

import android.content.Intent
import androidx.core.content.IntentCompat
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import androidx.fragment.app.FragmentActivity
import com.taskmind.di.AppContainer
import com.taskmind.ui.twin.TwinScreen
import com.taskmind.ui.twin.TwinViewModel
import com.taskmind.security.AppLock
import com.taskmind.security.LockGate
import com.taskmind.widget.TasksWidget
import com.taskmind.work.Scheduler
import com.taskmind.ui.shell.MainShell
import com.taskmind.ui.shell.MainTab
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

/**
 * A FragmentActivity rather than a ComponentActivity, and only for one reason:
 * androidx.biometric raises its prompt through a headless fragment, so the app
 * lock does not work without a fragment host. FragmentActivity extends
 * ComponentActivity, so setContent, enableEdgeToEdge and lifecycleScope all
 * behave exactly as before.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyScreenPrivacy()

        val container = AppContainer.get(this)
        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)
        // "Share to TaskMind" from any app: the text goes through the same
        // task-finding as a pasted transcript. Capped, because a share can be
        // an entire chat export.
        val sharedText = sharedTextOf(intent)
        val sharedImages = sharedImagesOf(intent)

        setContent {
            TaskMindTheme {
                // Outside the onboarding check on purpose: the lock must be the
                // first thing on screen, before anything reads the database.
                LockGate {
                    var onboardingChecked by remember { mutableStateOf(false) }
                    var needsOnboarding by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val settings = container.settingsRepository.current()
                        needsOnboarding = !settings.onboardingComplete
                        onboardingChecked = true
                    }

                    if (onboardingChecked) {
                        TaskMindNavHost(
                            startOnboarding = needsOnboarding,
                            startRoute = startRoute,
                            sharedText = sharedText,
                            sharedImages = sharedImages,
                            incoming = incoming,
                            onIncomingHandled = { incoming = null },
                            onShareText = ::shareText,
                            onShareFile = ::shareFile,
                        )
                    }
                }
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

    /**
     * A notification, the widget or a share, arriving while TaskMind is
     * already running.
     *
     * The activity is singleTask, so these never reached onCreate: tapping a
     * reminder with the app open in the background brought the app forward on
     * whatever screen it was showing, not on the task. The destination is now
     * handed to the navigation host, which goes there. A plain launcher tap
     * carries neither, and is left alone.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = intent.getStringExtra(EXTRA_ROUTE)
        val shared = sharedTextOf(intent)
        val images = sharedImagesOf(intent)
        if (route != null || shared != null || images.isNotEmpty()) incoming = Incoming(route, shared, images)
    }

    /** A destination that arrived while the app was already open. */
    private var incoming by mutableStateOf<Incoming?>(null)

    /** Pictures shared in (one or several), for reading on the phone. */
    private fun sharedImagesOf(intent: Intent?): List<Uri> {
        if (intent?.type?.startsWith("image/") != true) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }

    private fun sharedTextOf(intent: Intent?): String? = intent
        ?.takeIf { it.action == Intent.ACTION_SEND && it.type?.startsWith("text/") == true }
        ?.getStringExtra(Intent.EXTRA_TEXT)
        ?.takeIf { it.isNotBlank() }
        ?.take(MAX_SHARED_CHARS)

    /**
     * Redraw the widget when leaving the app.
     *
     * Covers everything the user just did - completing, editing, approving a
     * review item. Tasks created in the background are picked up by the
     * widget's own 30-minute update instead: making those instant would mean
     * a call into the widget from the intake funnel, and the funnel is not
     * mine to touch.
     */
    /**
     * Re-read on every resume, so turning the setting on in Settings takes
     * effect without restarting the app. FLAG_SECURE is what keeps the task
     * list out of the recents thumbnail and out of screenshots; it can only be
     * set on the window, which is why it lives here and not in Compose.
     */
    override fun onResume() {
        super.onResume()
        applyScreenPrivacy()
    }

    private fun applyScreenPrivacy() {
        if (AppLock.hideContent(this) || AppLock.enabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        TasksWidget.refresh(this)
        // Leaving the app is the moment a change has just been made and the
        // phone is most likely to still have a network. The worker is a no-op
        // when sync is off or nothing has changed.
        Scheduler.enqueueSync(this)
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

        /** Opens the task list with the new-task sheet up - the widget's "+". */
        const val ROUTE_NEW_TASK = "new"

        /** Opens the task list with the brain-dump sheet up - the launcher shortcut. */
        const val ROUTE_DUMP = "dump"

        /** Opens the twin; "twin|plan", "twin|week" and "twin|nudge:<id>" start it on something. */
        const val ROUTE_TWIN = "twin"

        private const val MAX_SHARED_CHARS = 20_000
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val TASKS = "tasks"
    const val TASK_DETAIL = "task/{taskId}"
    const val REVIEW = "review"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings?page={page}"
    const val LOG = "log"
    const val CALLS = "calls"
    const val IMPORT = "import"
    const val PROMPTS = "prompts"
    const val MODEL_CALLS = "model_calls"
    const val HOW_IT_WORKS = "how_it_works"
    const val DIAGNOSTICS = "diagnostics"
    const val RECORDINGS = "recordings"
    const val TWIN = "twin?q={q}&start={start}"

    fun taskDetail(id: String) = "task/$id"
    fun settingsPage(page: String) = "settings?page=$page"
    fun twin(question: String? = null, start: String? = null): String = buildString {
        append("twin")
        val params = listOfNotNull(
            question?.let { "q=" + android.net.Uri.encode(it) },
            start?.let { "start=" + android.net.Uri.encode(it) },
        )
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }
}

/**
 * A top-level destination: content plus the navigation bar.
 *
 * The badge is read straight from the review table rather than threaded down
 * from a screen's view model, so it is correct on every tab including the ones
 * that know nothing about reviews.
 */
@Composable
private fun TopLevel(
    tab: MainTab,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    val container = AppContainer.get(LocalContext.current)
    val pending by container.database.reviewItemDao()
        .observePendingCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val inbox by container.database.taskDao()
        .observeInboxCount()
        .collectAsStateWithLifecycle(initialValue = 0)

    MainShell(
        current = tab,
        reviewBadge = pending + inbox,
        onSelect = { target ->
            navController.navigate(target.route) {
                // Tabs are siblings, not a stack: switching between them
                // should not build a back stack four screens deep.
                popUpTo(Routes.TASKS) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) {
        content()
    }
}

@Composable
fun TaskMindNavHost(
    startOnboarding: Boolean,
    startRoute: String?,
    sharedText: String? = null,
    sharedImages: List<Uri> = emptyList(),
    incoming: Incoming? = null,
    onIncomingHandled: () -> Unit = {},
    onShareText: (String, String) -> Unit,
    onShareFile: (File) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // The task list is the bottom of every stack. A screen a notification or
    // the widget asks for opens on top of it (below), never in its place: as
    // the first screen it had nothing behind it, and back left an empty
    // window that looked exactly like the app had frozen.
    val start = when {
        startOnboarding -> Routes.ONBOARDING
        sharedText != null || sharedImages.isNotEmpty() -> Routes.IMPORT
        else -> Routes.TASKS
    }
    // Once per launch. After a rotation the navigation restores its own
    // place, and opening the screen again would stack a second copy.
    var startOpened by rememberSaveable { mutableStateOf(false) }

    // What the Import screen shows: the text shared at start-up, or later.
    var shared by remember { mutableStateOf(sharedText) }
    var images by remember { mutableStateOf(sharedImages) }
    // The widget's "+" asks for the new-task sheet; the list clears this once
    // it has opened it, so coming back to the list does not open it again.
    var wantNewTask by remember { mutableStateOf(startRoute == MainActivity.ROUTE_NEW_TASK) }
    // The launcher shortcut's "Brain dump": open the task list with the sheet up.
    var wantDump by remember { mutableStateOf(startRoute?.startsWith(MainActivity.ROUTE_DUMP) == true) }
    // Text the dump opens with: "From \"Vendor sync\": " after a meeting.
    var dumpText by remember { mutableStateOf(startRoute?.substringAfter('|', "").orEmpty()) }
    // What the twin starts with: "plan", "week" or "nudge:<id>", from a notification.
    var twinStart by remember {
        mutableStateOf(startRoute?.takeIf { it.startsWith(MainActivity.ROUTE_TWIN) }?.substringAfter('|', "")?.ifBlank { null })
    }

    // Links that arrive while the app is already open (it is singleTask, so
    // they never pass through the start destination above).
    LaunchedEffect(incoming) {
        val next = incoming ?: return@LaunchedEffect
        val route = next.route
        when {
            next.sharedText != null -> {
                shared = next.sharedText
                navController.navigate(Routes.IMPORT) { launchSingleTop = true }
            }
            next.images.isNotEmpty() -> {
                images = next.images
                navController.navigate(Routes.IMPORT) { launchSingleTop = true }
            }
            route == MainActivity.ROUTE_NEW_TASK -> {
                wantNewTask = true
                navController.navigate(Routes.TASKS) { launchSingleTop = true }
            }
            route != null && route.startsWith(MainActivity.ROUTE_DUMP) -> {
                dumpText = route.substringAfter('|', "")
                wantDump = true
                navController.navigate(Routes.TASKS) { launchSingleTop = true }
            }
            route != null && route.startsWith(MainActivity.ROUTE_TWIN) -> {
                twinStart = route.substringAfter('|', "").ifBlank { null }
                navController.navigate(Routes.twin()) { launchSingleTop = true }
            }
            route == "status" -> navController.navigate(Routes.STATUS) { launchSingleTop = true }
            route == "review" -> navController.navigate(Routes.REVIEW) { launchSingleTop = true }
            route == "tasks" -> navController.navigate(Routes.TASKS) { launchSingleTop = true }
            // Not a second copy of the task already open: tapping its
            // notification again used to stack one, and back then showed the
            // same task - the back arrow looked like it did nothing.
            route != null && route.startsWith("task/") -> if (!showingTask(navController, route)) navController.navigate(route)
        }
        onIncomingHandled()
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
            TopLevel(MainTab.TASKS, navController) {
                TaskListScreen(
                    viewModel = vm,
                    onOpenTask = { id -> navController.navigate(Routes.taskDetail(id)) },
                    onOpenStatus = { navController.navigate(Routes.STATUS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenReview = { navController.navigate(Routes.REVIEW) },
                    onOpenCalls = { navController.navigate(Routes.CALLS) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenNameSettings = { navController.navigate(Routes.settingsPage("YOU")) },
                    onAsk = { question -> navController.navigate(Routes.twin(question)) { launchSingleTop = true } },
                    startWithEditor = wantNewTask,
                    onEditorRequestHandled = { wantNewTask = false },
                    startWithDump = wantDump,
                    dumpText = dumpText,
                    onDumpRequestHandled = { wantDump = false },
                    onPlanDay = {
                        twinStart = "plan"
                        navController.navigate(Routes.twin()) { launchSingleTop = true }
                    },
                )
            }
        }

        composable(Routes.TASK_DETAIL) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId").orEmpty()
            val vm: TaskDetailViewModel = viewModel(factory = AppViewModels.factory)
            TaskDetailScreen(
                taskId = taskId,
                viewModel = vm,
                onBack = { navController.goBack(backStackEntry) },
                onOpenTask = { id -> navController.navigate(Routes.taskDetail(id)) },
                onNudge = { id ->
                    twinStart = "nudge:$id"
                    navController.navigate(Routes.twin()) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.REVIEW) { entry ->
            val vm: ReviewViewModel = viewModel(factory = AppViewModels.factory)
            TopLevel(MainTab.REVIEW, navController) {
                ReviewScreen(
                    viewModel = vm,
                    onBack = { navController.goBack(entry) },
                    onOpenTask = { id -> navController.navigate(Routes.taskDetail(id)) },
                )
            }
        }

        composable(Routes.STATUS) { entry ->
            val vm: StatusViewModel = viewModel(factory = AppViewModels.factory)
            StatusScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onShareText = onShareText,
                onOpenHowItWorks = { navController.navigate(Routes.HOW_IT_WORKS) },
                onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
            )
        }

        composable(
            Routes.SETTINGS_PAGE,
            arguments = listOf(
                navArgument("page") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val vm: SettingsViewModel = viewModel(factory = AppViewModels.factory)
            TopLevel(MainTab.SETTINGS, navController) {
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.goBack(backStackEntry) },
                onShareText = onShareText,
                onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenHowItWorks = { navController.navigate(Routes.HOW_IT_WORKS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onOpenStatus = { navController.navigate(Routes.STATUS) },
                onOpenCalls = { navController.navigate(Routes.CALLS) },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
                initialPage = backStackEntry.arguments?.getString("page"),
            )
            }
        }

        composable(Routes.RECORDINGS) { entry ->
            val vm: RecordingsViewModel = viewModel(factory = AppViewModels.factory)
            RecordingsScreen(viewModel = vm, onBack = { navController.goBack(entry) })
        }

        composable(Routes.DIAGNOSTICS) { entry ->
            val vm: DiagnosticsViewModel = viewModel(factory = AppViewModels.factory)
            DiagnosticsScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onShareFile = onShareFile,
            )
        }

        composable(Routes.PROMPTS) { entry ->
            val vm: PromptViewModel = viewModel(factory = AppViewModels.factory)
            PromptScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onShareText = onShareText,
            )
        }

        composable(Routes.MODEL_CALLS) { entry ->
            val vm: ModelCallsViewModel = viewModel(factory = AppViewModels.factory)
            ModelCallsScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onShareText = onShareText,
            )
        }

        composable(Routes.HOW_IT_WORKS) { entry ->
            val vm: HowItWorksViewModel = viewModel(factory = AppViewModels.factory)
            HowItWorksScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onOpenPrompts = { navController.navigate(Routes.PROMPTS) },
                onOpenModelCalls = { navController.navigate(Routes.MODEL_CALLS) },
                onOpenLog = { navController.navigate(Routes.LOG) },
            )
        }

        composable(Routes.LOG) { entry ->
            val vm: ActivityLogViewModel = viewModel(factory = AppViewModels.factory)
            ActivityLogScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onShareText = onShareText,
            )
        }

        composable(Routes.CALLS) { entry ->
            val vm: CallsViewModel = viewModel(factory = AppViewModels.factory)
            CallsScreen(
                viewModel = vm,
                onBack = { navController.goBack(entry) },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
            )
        }

        composable(
            Routes.TWIN,
            arguments = listOf(
                navArgument("q") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("start") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val vm: TwinViewModel = viewModel(factory = AppViewModels.factory)
            TopLevel(MainTab.TWIN, navController) {
                TwinScreen(
                    viewModel = vm,
                    initialQuestion = backStackEntry.arguments?.getString("q"),
                    startWith = twinStart ?: backStackEntry.arguments?.getString("start"),
                    onStartHandled = {
                        twinStart = null
                        backStackEntry.arguments?.remove("start")
                    },
                    onOpenTask = { id -> navController.navigate(Routes.taskDetail(id)) },
                )
            }
        }

        composable(Routes.IMPORT) { entry ->
            val vm: ImportViewModel = viewModel(factory = AppViewModels.factory)
            ImportTranscriptScreen(
                viewModel = vm,
                // Arriving from a share there is nothing behind this screen;
                // goBack lands on the task list instead of a blank one.
                onBack = { navController.goBack(entry) },
                initialText = shared,
                initialImages = images,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (startOpened) return@LaunchedEffect
        startOpened = true
        if (start != Routes.TASKS) return@LaunchedEffect
        when {
            startRoute == "status" -> navController.navigate(Routes.STATUS)
            startRoute == "review" -> navController.navigate(Routes.REVIEW) { launchSingleTop = true }
            startRoute != null && startRoute.startsWith(MainActivity.ROUTE_TWIN) ->
                navController.navigate(Routes.twin()) { launchSingleTop = true }
            // A task notification or the widget opens one task. Matched by
            // prefix rather than listed, because the id is part of the route.
            startRoute != null && startRoute.startsWith("task/") -> navController.navigate(startRoute)
        }
    }
}

/** Whether the screen on top is already the task [route] ("task/<id>") opens. */
private fun showingTask(navController: NavHostController, route: String): Boolean {
    val top = navController.currentBackStackEntry ?: return false
    return top.destination.route == Routes.TASK_DETAIL &&
        top.arguments?.getString("taskId") == route.removePrefix("task/")
}

/**
 * Back from a screen, with two guards.
 *
 * Only while [entry] is the screen on top: a second tap on the arrow, while
 * the first is still sliding the screen away, used to pop the list beneath it
 * as well. And with nothing behind it - a screen opened straight from a share
 * - it lands on the task list. Popping the only screen there was left an
 * empty window, which looked exactly like a freeze.
 */
private fun NavHostController.goBack(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id != entry.id) return
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(Routes.TASKS) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/** A route or shared text delivered to an already-running activity. */
data class Incoming(
    val route: String?,
    val sharedText: String?,
    val images: List<Uri> = emptyList(),
    val at: Long = System.nanoTime(),
)
