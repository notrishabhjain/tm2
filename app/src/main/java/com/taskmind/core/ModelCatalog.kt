package com.taskmind.core

/**
 * Sorting a provider's model list into what is useful here.
 *
 * The device this was written for had four models enabled on the account:
 * `groq/compound`, two `llama-prompt-guard-2` models, and
 * `whisper-large-v3-turbo`. None of them can do extraction - the first is a
 * router, the prompt-guard pair are safety classifiers that return a label
 * rather than text, and the last is speech recognition. The app asked the
 * router anyway and spent a day's quota discovering that.
 *
 * Guessing a model name from the outside does not work either: which models an
 * account may use is an account-level fact. So the app asks the provider and
 * classifies the answer here.
 */
object ModelCatalog {

    enum class Kind {
        /** Answers a prompt with text. The only kind extraction can use. */
        CHAT,

        /** Speech to text. */
        TRANSCRIPTION,

        /** A safety classifier: returns a label, not an answer. */
        GUARD,

        /** Dispatches to other models, which need enabling separately. */
        ROUTER,

        /** Embeddings, image generation, anything else. */
        OTHER,
    }

    data class Model(
        val id: String,
        val kind: Kind,
        val ownedBy: String? = null,
        val contextWindow: Int? = null,
    ) {
        /** Usable for pulling tasks out of a message. */
        val usableForExtraction: Boolean get() = kind == Kind.CHAT

        val usableForTranscription: Boolean get() = kind == Kind.TRANSCRIPTION
    }

    private val TRANSCRIPTION = Regex("whisper|saarika|saaras|speech|transcrib|\\basr\\b", RegexOption.IGNORE_CASE)
    private val GUARD = Regex("guard|moderation|safety|classifier", RegexOption.IGNORE_CASE)
    private val OTHER = Regex("embed|rerank|\\btts\\b|dall-?e|image|vision-only|stable-diffusion", RegexOption.IGNORE_CASE)

    fun classify(id: String): Kind = when {
        TRANSCRIPTION.containsMatchIn(id) -> Kind.TRANSCRIPTION
        GUARD.containsMatchIn(id) -> Kind.GUARD
        OTHER.containsMatchIn(id) -> Kind.OTHER
        ProviderDiagnosis.isRouterModel(id) -> Kind.ROUTER
        else -> Kind.CHAT
    }

    /**
     * Why a model that is not CHAT cannot be used for extraction, in the terms
     * the user is looking at it in - their provider's console listed it, so
     * "not available" would be simply wrong.
     */
    fun whyUnusable(kind: Kind): String? = when (kind) {
        Kind.CHAT -> null
        Kind.TRANSCRIPTION -> "Speech recognition. Use it for call transcription below, not for reading messages."
        Kind.GUARD -> "A safety classifier: it answers with a label like \"safe\", not with the JSON TaskMind needs."
        Kind.ROUTER -> "A router: it calls other models underneath, which have to be enabled separately."
        Kind.OTHER -> "Not a text-answering model."
    }

    /**
     * Best first: a chat model this app can actually use, largest context
     * first among equals, with everything unusable last.
     */
    fun forExtraction(models: List<Model>): List<Model> =
        models.sortedWith(
            compareBy<Model> { if (it.kind == Kind.CHAT) 0 else 1 }
                .thenBy { if (it.kind == Kind.ROUTER) 0 else 1 }
                .thenBy { it.id },
        )

    fun forTranscription(models: List<Model>): List<Model> =
        models.sortedWith(
            compareBy<Model> { if (it.kind == Kind.TRANSCRIPTION) 0 else 1 }.thenBy { it.id },
        )
}
