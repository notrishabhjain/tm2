package com.taskmind.di

import android.content.Context
import android.content.pm.PackageManager
import com.taskmind.ai.AsrConfig
import com.taskmind.ai.CloudTaskExtractor
import com.taskmind.ai.ConnectionTester
import com.taskmind.ai.LlmClient
import com.taskmind.ai.ModelLister
import com.taskmind.ai.TaskExtractor
import com.taskmind.ai.Transcriber
import com.taskmind.ai.TranscriberFactory
import com.taskmind.capture.CallPipeline
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.capture.ExtractionPipeline
import com.taskmind.capture.RecordingFinder
import com.taskmind.capture.TranscriptionPipeline
import com.taskmind.core.AsrProvider
import com.taskmind.data.db.TaskMindDatabase
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.repo.BackupRepository
import com.taskmind.data.repo.RoomInferenceRecorder
import com.taskmind.data.repo.RoomIntakePorts
import com.taskmind.data.repo.TaskRepository
import com.taskmind.data.settings.PromptStore
import com.taskmind.data.settings.RuntimeStateStore
import com.taskmind.data.settings.SecretStore
import com.taskmind.data.settings.Settings
import com.taskmind.data.settings.SettingsRepository
import com.taskmind.intake.Clock
import com.taskmind.intake.IdGenerator
import com.taskmind.intake.IntakeFunnel
import com.taskmind.notify.Notifier
import com.taskmind.diagnostics.SelfTest
import com.taskmind.update.SelfUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Spec 2: no DI framework. A hand-written container singleton.
 *
 * Hilt codegen failures in CI are a first-build tax with no local machine to
 * debug on, and the wiring here is small enough to read in one sitting.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // -- storage -----------------------------------------------------------

    val database: TaskMindDatabase by lazy { TaskMindDatabase.build(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val secretStore: SecretStore by lazy { SecretStore(appContext) }

    /** User overrides for the system prompts (Settings -> Prompts). */
    val promptStore: PromptStore by lazy { PromptStore(appContext) }
    val runtimeStateStore: RuntimeStateStore by lazy { RuntimeStateStore(appContext) }

    /**
     * A snapshot of settings readable without suspending.
     *
     * `onNotificationPosted` must decide and write synchronously - the listener
     * process can die at any moment (spec 10.4) - and DataStore reads suspend.
     */
    @Volatile
    var cachedSettings: Settings = Settings.DEFAULT
        private set

    val logger: ActivityLogger by lazy { ActivityLogger(database.activityLogDao(), applicationScope) }

    val notifier: Notifier by lazy { Notifier(appContext) }

    // -- intake ------------------------------------------------------------

    private val intakePorts: RoomIntakePorts by lazy {
        RoomIntakePorts(
            taskDao = database.taskDao(),
            reviewItemDao = database.reviewItemDao(),
            rawCaptureDao = database.rawCaptureDao(),
        )
    }

    /**
     * The one and only path to the task table (spec 5). Everything that creates
     * a task goes through this instance.
     */
    val intakeFunnel: IntakeFunnel by lazy {
        IntakeFunnel(
            taskSink = intakePorts,
            reviewSink = intakePorts,
            captureMarker = intakePorts,
            log = logger,
            notifier = notifier,
            ids = object : IdGenerator {
                override fun newId(): String = UUID.randomUUID().toString()
            },
            clock = object : Clock {
                override fun now(): Long = System.currentTimeMillis()
            },
            configProvider = { settingsRepository.current().funnelConfig() },
        )
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(
            taskDao = database.taskDao(),
            reviewItemDao = database.reviewItemDao(),
            projectDao = database.projectDao(),
            tagDao = database.tagDao(),
            rawCaptureDao = database.rawCaptureDao(),
            callRecordDao = database.callRecordDao(),
            funnel = intakeFunnel,
            logger = logger,
        )
    }

    // -- providers ---------------------------------------------------------

    val httpClient: OkHttpClient by lazy { LlmClient.defaultHttpClient() }

    /**
     * Records every model call with the exact prompt and the raw reply, so the
     * user can audit what the app sends rather than take the privacy statement
     * on trust.
     */
    val inferenceRecorder: RoomInferenceRecorder by lazy {
        RoomInferenceRecorder(database.inferenceCallDao())
    }

    val llmClient: LlmClient by lazy { LlmClient(httpClient, inferenceRecorder) }

    suspend fun llmConfig(): LlmClient.Config {
        val s = settingsRepository.current()
        return LlmClient.Config(
            baseUrl = s.llmBaseUrl,
            apiKey = secretStore.llmApiKey,
            model = s.llmModel,
        )
    }

    suspend fun asrConfig(): AsrConfig {
        val s = settingsRepository.current()
        return AsrConfig(
            provider = s.asrProvider,
            baseUrl = s.asrBaseUrl,
            apiKey = secretStore.asrApiKey,
            model = s.asrModel,
            language = s.asrLanguage,
        )
    }

    val taskExtractor: TaskExtractor by lazy {
        CloudTaskExtractor(
            llm = llmClient,
            configProvider = { llmConfig() },
            promptProvider = { promptStore.current() },
        )
    }

    suspend fun transcriber(): Pair<Transcriber, AsrProvider> {
        val provider = settingsRepository.current().asrProvider
        return TranscriberFactory.create(
            http = httpClient,
            configProvider = { asrConfig() },
            provider = provider,
            recorder = inferenceRecorder,
        ) to provider
    }

    val modelLister: ModelLister by lazy { ModelLister(httpClient) }

    val connectionTester: ConnectionTester by lazy {
        ConnectionTester(llmClient) { stage, level, message, detail ->
            logger.write(stage, level, message, detail)
        }
    }

    // -- capture -----------------------------------------------------------

    val captureCoordinator: CaptureCoordinator by lazy {
        CaptureCoordinator(
            rawCaptureDao = database.rawCaptureDao(),
            seenPackageDao = database.seenPackageDao(),
            logger = logger,
        )
    }

    val recordingFinder: RecordingFinder by lazy { RecordingFinder(appContext) }

    val extractionPipeline: ExtractionPipeline by lazy {
        ExtractionPipeline(
            rawCaptureDao = database.rawCaptureDao(),
            fingerprintDao = database.fingerprintDao(),
            settingsRepository = settingsRepository,
            runtimeStateStore = runtimeStateStore,
            extractor = taskExtractor,
            funnel = intakeFunnel,
            logger = logger,
            hasLlmKey = { secretStore.hasLlmKey() },
            appLabelFor = ::appLabel,
        )
    }

    val transcriptionPipeline: TranscriptionPipeline by lazy {
        TranscriptionPipeline(
            context = appContext,
            rawCaptureDao = database.rawCaptureDao(),
            settingsRepository = settingsRepository,
            transcriberProvider = { transcriber() },
            logger = logger,
            hasAsrKey = { secretStore.hasAsrKey() },
        )
    }

    val callPipeline: CallPipeline by lazy {
        CallPipeline(
            context = appContext,
            callRecordDao = database.callRecordDao(),
            rawCaptureDao = database.rawCaptureDao(),
            captureCoordinator = captureCoordinator,
            recordingFinder = recordingFinder,
            settingsRepository = settingsRepository,
            logger = logger,
        )
    }

    val selfUpdater: SelfUpdater by lazy { SelfUpdater(appContext, httpClient, logger) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(appContext, database, intakeFunnel, logger)
    }

    /** Spec 15: exercises the production path, never a parallel one. */
    val selfTest: SelfTest by lazy { SelfTest(appContext, this) }

    // -- lifecycle ---------------------------------------------------------

    fun start() {
        notifier.ensureChannels()
        applicationScope.launch {
            settingsRepository.settings.collectLatest { cachedSettings = it }
        }
    }

    /** Human-readable app name for a package, for prompts and the log. */
    fun appLabel(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Message"
        return runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
    }

    fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrElse { false }

    val packageManager: PackageManager get() = appContext.packageManager

    /** Application context, for the few call sites that must schedule work. */
    val context: Context get() = appContext

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
