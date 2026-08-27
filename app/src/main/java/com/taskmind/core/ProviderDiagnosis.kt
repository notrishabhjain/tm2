package com.taskmind.core

/**
 * Turns a provider's HTTP error into something the user can act on.
 *
 * This exists because of a real failure: the app was configured with Groq's
 * `groq/compound` and got back
 *
 *   HTTP 403: The model `meta-llama/llama-4-scout-17b-16e-instruct` is blocked
 *   at the project level.
 *
 * naming a model the user had never selected and could not find in the console.
 * The reason is that `groq/compound` is a ROUTER: it accepts the request and
 * then calls other models underneath. Blocking those blocks it.
 *
 * The activity log said "provider configuration error" and printed the raw
 * message, which was true and useless. Everything here is pure so it can be
 * unit-tested, and it is surfaced next to the failure rather than left for the
 * user to work out.
 */
object ProviderDiagnosis {

    /** Model names as providers write them: `vendor/name`, `name-v3`, etc. */
    private val MODEL_IN_MESSAGE = Regex("[`'\"]([A-Za-z0-9][A-Za-z0-9._/-]{3,})[`'\"]")

    /**
     * Model families that accept a request and then dispatch to other models.
     * A permission error from one of these will name a model the user never
     * chose, which is the confusing case worth explaining.
     */
    private val ROUTER_MODELS = listOf("compound", "router", "auto", "agentic")

    fun isRouterModel(model: String): Boolean {
        val m = model.lowercase()
        return ROUTER_MODELS.any { m.contains(it) }
    }

    /** The model the provider complained about, if it named one. */
    fun modelNamedIn(message: String): String? =
        MODEL_IN_MESSAGE.findAll(message)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains('/') || it.contains('-') }

    /**
     * @param configuredModel what the user selected in settings.
     * @return a plain-language explanation and what to do, or null when the raw
     *   message already says it well enough.
     */
    fun diagnose(configuredModel: String, status: Int, message: String): String? {
        val blamed = modelNamedIn(message)
        val blamedIsDifferent = blamed != null && !blamed.equals(configuredModel, ignoreCase = true)
        val lower = message.lowercase()

        return when {
            // The case that actually happened.
            (status == 403 || status == 404) && blamedIsDifferent && isRouterModel(configuredModel) ->
                "You selected \"$configuredModel\", but the provider tried to use \"$blamed\" and was refused.\n\n" +
                    "\"$configuredModel\" is a routing model: it accepts your request and then calls other " +
                    "models underneath. Those underlying models need to be enabled too, which is why the " +
                    "error names a model you never chose.\n\n" +
                    "Two ways forward. Either enable \"$blamed\" alongside it in your provider's project " +
                    "settings, or — better for this job — select a plain instruction-following model " +
                    "directly. TaskMind asks for one JSON object per message; it has no use for a router's " +
                    "tool-calling or web-search, and a direct model is faster and cheaper for it."

            (status == 403 || status == 404) && blamedIsDifferent ->
                "You selected \"$configuredModel\", but the provider refused because of \"$blamed\". " +
                    "The model you chose is reaching for another one that your account cannot use. " +
                    "Either enable \"$blamed\", or pick a model that stands on its own."

            status == 403 && lower.contains("blocked") ->
                "\"$configuredModel\" is enabled for your account but blocked for this project. " +
                    "Enable it in the provider's project settings, or choose a model that is already allowed."

            status == 404 ->
                "\"$configuredModel\" does not exist on this provider, or the base URL points somewhere " +
                    "that does not serve it. Check the model name against the provider's model list, and " +
                    "check the base URL ends at the API root."

            status == 401 ->
                "The API key was rejected. Check it was pasted whole, and that it belongs to the provider " +
                    "the base URL points at."

            lower.contains("decommission") || lower.contains("deprecated") || lower.contains("retired") ->
                "\"$configuredModel\" has been retired by the provider. Pick a current model; the model " +
                    "list in the provider's console shows which ones still work."

            status == 429 ->
                "Rate limited. The key and the model are fine — this is throughput. TaskMind will retry " +
                    "on its own; if it keeps happening, lower the daily call limit in Settings so captures " +
                    "spread out instead of arriving in bursts."

            status in 500..599 ->
                "The provider had a server error. Nothing is wrong with your configuration and TaskMind " +
                    "will retry."

            else -> null
        }
    }

    /**
     * Routers are a poor fit for this workload even when they work: TaskMind
     * wants one small JSON object back, deterministically. The settings screen
     * shows this before a failure rather than after.
     */
    fun settingsWarningFor(model: String): String? =
        if (isRouterModel(model)) {
            "\"$model\" looks like a routing or agentic model. It will call other models underneath, so it " +
                "needs those enabled too, and it adds latency TaskMind cannot use — every request here is " +
                "a single JSON answer with no tools involved. A plain instruction-following model is " +
                "usually faster, cheaper and more predictable for this."
        } else {
            null
        }
}
