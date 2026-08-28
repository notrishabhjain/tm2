package com.taskmind.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taskmind.core.AsrProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_settings")

/**
 * Spec 2: DataStore (Preferences) for settings; EncryptedSharedPreferences for
 * API keys (see [SecretStore]). Keys never touch this file.
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val cloudConsent = booleanPreferencesKey("cloud_consent")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")

        val llmBaseUrl = stringPreferencesKey("llm_base_url")
        val llmModel = stringPreferencesKey("llm_model")

        val asrProvider = stringPreferencesKey("asr_provider")
        val asrBaseUrl = stringPreferencesKey("asr_base_url")
        val asrModel = stringPreferencesKey("asr_model")
        val asrLanguage = stringPreferencesKey("asr_language")

        val allowedPackages = stringSetPreferencesKey("allowed_packages")
        val captureNotifications = booleanPreferencesKey("capture_notifications")
        val captureCalls = booleanPreferencesKey("capture_calls")
        val autoTranscribeCalls = booleanPreferencesKey("auto_transcribe_calls")
        val minCallDuration = longPreferencesKey("min_call_duration")
        val callRecordingDirUri = stringPreferencesKey("call_recording_dir_uri")

        val autoCreateThreshold = doublePreferencesKey("auto_create_threshold")
        val reviewThreshold = doublePreferencesKey("review_threshold")
        val notificationTolerance = doublePreferencesKey("notification_tolerance")
        val clipboardTolerance = doublePreferencesKey("clipboard_tolerance")
        val callTolerance = doublePreferencesKey("call_tolerance")
        val verifyPass = booleanPreferencesKey("verify_pass")

        val maxLlmCallsPerDay = intPreferencesKey("max_llm_calls_per_day")
        val maxAsrMinutesPerDay = intPreferencesKey("max_asr_minutes_per_day")
        val maxLlmCallsPerPackage = intPreferencesKey("max_llm_calls_per_package")
        val wifiOnlyAsr = booleanPreferencesKey("wifi_only_asr")

        val retentionDays = intPreferencesKey("retention_days")
        val deleteRecordings = booleanPreferencesKey("delete_recordings_after_transcription")

        val updateManifestUrl = stringPreferencesKey("update_manifest_url")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")

        val logLevelFilter = stringPreferencesKey("log_level_filter")

        // -- daily budget accounting (spec 9) ------------------------------
        val budgetDayKey = stringPreferencesKey("budget_day_key")
        val llmCallsToday = intPreferencesKey("llm_calls_today")
        val asrSecondsToday = intPreferencesKey("asr_seconds_today")
        val perPackageToday = stringSetPreferencesKey("per_package_today")

        // -- foreground service dataSync budget (spec 17.1) ----------------
        val fgsDayKey = stringPreferencesKey("fgs_day_key")
        val fgsDataSyncSeconds = intPreferencesKey("fgs_data_sync_seconds")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data
        .catch { e ->
            // A corrupt preferences file must not take the app down; defaults
            // are a safe fallback because none of them enable anything.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings(): Settings {
        val d = Settings.DEFAULT
        return Settings(
            cloudConsent = this[K.cloudConsent] ?: d.cloudConsent,
            onboardingComplete = this[K.onboardingComplete] ?: d.onboardingComplete,
            llmBaseUrl = this[K.llmBaseUrl] ?: d.llmBaseUrl,
            llmModel = this[K.llmModel] ?: d.llmModel,
            asrProvider = this[K.asrProvider]?.let { name ->
                runCatching { AsrProvider.valueOf(name) }.getOrDefault(d.asrProvider)
            } ?: d.asrProvider,
            asrBaseUrl = this[K.asrBaseUrl] ?: d.asrBaseUrl,
            asrModel = this[K.asrModel] ?: d.asrModel,
            asrLanguage = this[K.asrLanguage] ?: d.asrLanguage,
            allowedPackages = this[K.allowedPackages] ?: d.allowedPackages,
            captureNotifications = this[K.captureNotifications] ?: d.captureNotifications,
            captureCalls = this[K.captureCalls] ?: d.captureCalls,
            autoTranscribeCalls = this[K.autoTranscribeCalls] ?: d.autoTranscribeCalls,
            minCallDurationSeconds = this[K.minCallDuration] ?: d.minCallDurationSeconds,
            callRecordingDirUri = this[K.callRecordingDirUri] ?: d.callRecordingDirUri,
            autoCreateThreshold = this[K.autoCreateThreshold] ?: d.autoCreateThreshold,
            reviewThreshold = this[K.reviewThreshold] ?: d.reviewThreshold,
            notificationTolerance = this[K.notificationTolerance] ?: d.notificationTolerance,
            clipboardTolerance = this[K.clipboardTolerance] ?: d.clipboardTolerance,
            callTolerance = this[K.callTolerance] ?: d.callTolerance,
            verifyPass = this[K.verifyPass] ?: d.verifyPass,
            maxLlmCallsPerDay = this[K.maxLlmCallsPerDay] ?: d.maxLlmCallsPerDay,
            maxAsrMinutesPerDay = this[K.maxAsrMinutesPerDay] ?: d.maxAsrMinutesPerDay,
            maxLlmCallsPerPackagePerDay = this[K.maxLlmCallsPerPackage] ?: d.maxLlmCallsPerPackagePerDay,
            wifiOnlyAsr = this[K.wifiOnlyAsr] ?: d.wifiOnlyAsr,
            retentionDays = this[K.retentionDays] ?: d.retentionDays,
            deleteRecordingsAfterTranscription = this[K.deleteRecordings] ?: d.deleteRecordingsAfterTranscription,
            updateManifestUrl = this[K.updateManifestUrl] ?: d.updateManifestUrl,
            autoCheckUpdates = this[K.autoCheckUpdates] ?: d.autoCheckUpdates,
            logLevelFilter = this[K.logLevelFilter] ?: d.logLevelFilter,
        )
    }

    // ------------------------------------------------------------- mutations

    suspend fun setCloudConsent(value: Boolean) = edit { it[K.cloudConsent] = value }
    suspend fun setOnboardingComplete(value: Boolean) = edit { it[K.onboardingComplete] = value }

    suspend fun setLlm(baseUrl: String, model: String) = edit {
        it[K.llmBaseUrl] = baseUrl.trim().trimEnd('/')
        it[K.llmModel] = model.trim()
    }

    suspend fun setAsr(provider: AsrProvider, baseUrl: String, model: String, language: String) = edit {
        it[K.asrProvider] = provider.name
        it[K.asrBaseUrl] = baseUrl.trim().trimEnd('/')
        it[K.asrModel] = model.trim()
        it[K.asrLanguage] = language.trim()
    }

    suspend fun setAllowedPackages(packages: Set<String>) = edit { it[K.allowedPackages] = packages }

    suspend fun togglePackage(packageName: String, allowed: Boolean) = edit { prefs ->
        val current = prefs[K.allowedPackages] ?: Settings.DEFAULT.allowedPackages
        prefs[K.allowedPackages] = if (allowed) current + packageName else current - packageName
    }

    suspend fun setCaptureNotifications(value: Boolean) = edit { it[K.captureNotifications] = value }
    suspend fun setCaptureCalls(value: Boolean) = edit { it[K.captureCalls] = value }

    suspend fun setAutoTranscribeCalls(value: Boolean) = edit { it[K.autoTranscribeCalls] = value }
    suspend fun setMinCallDuration(seconds: Long) = edit { it[K.minCallDuration] = seconds }
    suspend fun setCallRecordingDirUri(uri: String?) = edit {
        if (uri == null) it.remove(K.callRecordingDirUri) else it[K.callRecordingDirUri] = uri
    }

    suspend fun setThresholds(autoCreate: Double, review: Double) = edit {
        it[K.autoCreateThreshold] = autoCreate
        it[K.reviewThreshold] = review
    }

    suspend fun setTolerances(notification: Double, clipboard: Double, call: Double) = edit {
        it[K.notificationTolerance] = notification
        it[K.clipboardTolerance] = clipboard
        it[K.callTolerance] = call
    }

    suspend fun setVerifyPass(value: Boolean) = edit { it[K.verifyPass] = value }

    suspend fun setBudgets(llmCalls: Int, asrMinutes: Int, perPackage: Int) = edit {
        it[K.maxLlmCallsPerDay] = llmCalls
        it[K.maxAsrMinutesPerDay] = asrMinutes
        it[K.maxLlmCallsPerPackage] = perPackage
    }

    suspend fun setWifiOnlyAsr(value: Boolean) = edit { it[K.wifiOnlyAsr] = value }
    suspend fun setRetentionDays(days: Int) = edit { it[K.retentionDays] = days }
    suspend fun setDeleteRecordings(value: Boolean) = edit { it[K.deleteRecordings] = value }
    suspend fun setUpdateManifestUrl(url: String) = edit { it[K.updateManifestUrl] = url.trim() }
    suspend fun setAutoCheckUpdates(value: Boolean) = edit { it[K.autoCheckUpdates] = value }
    suspend fun setLogLevelFilter(level: String) = edit { it[K.logLevelFilter] = level }

    /** Spec 14.2: a "reset to defaults" button that actually resets. */
    suspend fun resetQualityDefaults() = edit {
        val d = Settings.DEFAULT
        it[K.autoCreateThreshold] = d.autoCreateThreshold
        it[K.reviewThreshold] = d.reviewThreshold
        it[K.notificationTolerance] = d.notificationTolerance
        it[K.clipboardTolerance] = d.clipboardTolerance
        it[K.callTolerance] = d.callTolerance
        it[K.verifyPass] = d.verifyPass
    }

    // --------------------------------------------------------------- budgets

    data class Usage(
        val dayKey: String,
        val llmCalls: Int,
        val asrSeconds: Int,
        val perPackage: Map<String, Int>,
    )

    val usage: Flow<Usage> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            Usage(
                dayKey = p[K.budgetDayKey] ?: "",
                llmCalls = p[K.llmCallsToday] ?: 0,
                asrSeconds = p[K.asrSecondsToday] ?: 0,
                perPackage = decodePerPackage(p[K.perPackageToday] ?: emptySet()),
            )
        }

    suspend fun currentUsage(): Usage = usage.first()

    /** Rolls the counters over when the IST day has changed. */
    suspend fun rollBudgetIfNeeded(todayKey: String) = edit { p ->
        if (p[K.budgetDayKey] != todayKey) {
            p[K.budgetDayKey] = todayKey
            p[K.llmCallsToday] = 0
            p[K.asrSecondsToday] = 0
            p[K.perPackageToday] = emptySet()
        }
    }

    suspend fun recordLlmCall(todayKey: String, packageName: String?) = edit { p ->
        if (p[K.budgetDayKey] != todayKey) {
            p[K.budgetDayKey] = todayKey
            p[K.llmCallsToday] = 0
            p[K.asrSecondsToday] = 0
            p[K.perPackageToday] = emptySet()
        }
        p[K.llmCallsToday] = (p[K.llmCallsToday] ?: 0) + 1
        if (packageName != null) {
            val map = decodePerPackage(p[K.perPackageToday] ?: emptySet()).toMutableMap()
            map[packageName] = (map[packageName] ?: 0) + 1
            p[K.perPackageToday] = encodePerPackage(map)
        }
    }

    suspend fun recordAsrSeconds(todayKey: String, seconds: Int) = edit { p ->
        if (p[K.budgetDayKey] != todayKey) {
            p[K.budgetDayKey] = todayKey
            p[K.llmCallsToday] = 0
            p[K.asrSecondsToday] = 0
            p[K.perPackageToday] = emptySet()
        }
        p[K.asrSecondsToday] = (p[K.asrSecondsToday] ?: 0) + seconds
    }

    private fun decodePerPackage(raw: Set<String>): Map<String, Int> =
        raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf('=')
            if (idx <= 0) return@mapNotNull null
            val count = entry.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, idx) to count
        }.toMap()

    private fun encodePerPackage(map: Map<String, Int>): Set<String> =
        map.entries.map { "${it.key}=${it.value}" }.toSet()

    // ---------------------------------------- foreground service budget (17.1)

    data class FgsUsage(val dayKey: String, val dataSyncSeconds: Int)

    suspend fun fgsUsage(): FgsUsage {
        val p = context.settingsDataStore.data.first()
        return FgsUsage(p[K.fgsDayKey] ?: "", p[K.fgsDataSyncSeconds] ?: 0)
    }

    suspend fun recordDataSyncSeconds(todayKey: String, seconds: Int) = edit { p ->
        if (p[K.fgsDayKey] != todayKey) {
            p[K.fgsDayKey] = todayKey
            p[K.fgsDataSyncSeconds] = 0
        }
        p[K.fgsDataSyncSeconds] = (p[K.fgsDataSyncSeconds] ?: 0) + seconds
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
