package com.taskmind.data.settings

import com.taskmind.core.AsrProvider
import com.taskmind.core.PreFilter
import com.taskmind.intake.FunnelConfig

/**
 * Everything the user can change. Spec 2: threshold tuning is a settings
 * screen - no remote config, no server.
 */
data class Settings(
    // -- onboarding / consent (spec 18, 20) --------------------------------
    val cloudConsent: Boolean = false,
    val onboardingComplete: Boolean = false,

    // -- LLM provider (spec 8.1) -------------------------------------------
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmModel: String = "gpt-4o-mini",

    // -- ASR provider (spec 8.2) -------------------------------------------
    val asrProvider: AsrProvider = AsrProvider.SARVAM,
    val asrBaseUrl: String = "https://api.sarvam.ai",
    val asrModel: String = "saarika:v2.5",
    val asrLanguage: String = "hi",

    // -- capture -----------------------------------------------------------
    val allowedPackages: Set<String> = PreFilter.DEFAULT_ALLOWED_PACKAGES,
    val captureNotifications: Boolean = true,
    val captureCalls: Boolean = true,
    val minCallDurationSeconds: Long = 15,

    /**
     * Whether a discovered recording is transcribed without being asked for.
     *
     * On a phone with thousands of recordings this is the difference between a
     * useful feature and an unbounded upload bill, so it can be turned off and
     * recordings picked by hand instead.
     */
    val autoTranscribeCalls: Boolean = true,
    val callRecordingDirUri: String? = null,

    // -- extraction quality (spec 13, 14.2) --------------------------------
    val autoCreateThreshold: Double = 0.75,
    val reviewThreshold: Double = 0.40,
    val notificationTolerance: Double = 0.90,
    val clipboardTolerance: Double = 0.85,
    val callTolerance: Double = 0.75,
    val verifyPass: Boolean = true,

    // -- cost and rate control (spec 9) ------------------------------------
    val maxLlmCallsPerDay: Int = 300,
    val maxAsrMinutesPerDay: Int = 60,
    val maxLlmCallsPerPackagePerDay: Int = 60,
    val wifiOnlyAsr: Boolean = true,

    // -- retention (spec 6.3) ----------------------------------------------
    val retentionDays: Int = 30,
    val deleteRecordingsAfterTranscription: Boolean = false,

    // -- self-update (spec 19) ---------------------------------------------
    val updateManifestUrl: String = "",
    val autoCheckUpdates: Boolean = true,

    // -- ui ----------------------------------------------------------------
    val logLevelFilter: String = "INFO",
) {
    fun funnelConfig(): FunnelConfig = FunnelConfig(
        autoCreateThreshold = autoCreateThreshold,
        reviewThreshold = reviewThreshold,
        notificationTolerance = notificationTolerance,
        clipboardTolerance = clipboardTolerance,
        callTolerance = callTolerance,
    )

    companion object {
        val DEFAULT = Settings()

        /** Spec 8.1 known-good presets, base URL prefilled. */
        val LLM_PRESETS: List<Pair<String, String>> = listOf(
            "OpenAI" to "https://api.openai.com/v1",
            "Groq" to "https://api.groq.com/openai/v1",
            "OpenRouter" to "https://openrouter.ai/api/v1",
            "Custom" to "",
        )

        val LLM_MODEL_SUGGESTIONS: Map<String, String> = mapOf(
            "https://api.openai.com/v1" to "gpt-4o-mini",
            // What actually works on a current Groq account: strong at
            // instruction-following and at returning one JSON object.
            "https://api.groq.com/openai/v1" to "openai/gpt-oss-120b",
            "https://openrouter.ai/api/v1" to "openai/gpt-4o-mini",
        )

        /** Spec 8.2. Sarvam is materially better on Hindi phone audio. */
        val ASR_PRESETS: List<Triple<String, AsrProvider, Pair<String, String>>> = listOf(
            Triple("Sarvam AI (best for Hindi)", AsrProvider.SARVAM, "https://api.sarvam.ai" to "saarika:v2.5"),
            // large-v3 before turbo, deliberately. Turbo is the distilled
            // model: roughly twice as fast and noticeably worse on non-English
            // audio. On Hindi phone calls from the device this was debugged
            // against it produced text garbled enough that extraction could
            // find nothing in it - which reads as "the app does not work".
            Triple("Groq Whisper large v3 (better on Hindi)", AsrProvider.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1" to "whisper-large-v3"),
            Triple("Groq Whisper turbo (faster, less accurate)", AsrProvider.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1" to "whisper-large-v3-turbo"),
            Triple("OpenAI Whisper", AsrProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1" to "whisper-1"),
        )

        val RETENTION_CHOICES = listOf(7, 30, 90)
    }
}
