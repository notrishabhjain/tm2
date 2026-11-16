package com.taskmind.data.memory

import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.vector.Chunker
import com.taskmind.vector.E5Embedder
import com.taskmind.vector.SpmTokenizer
import com.taskmind.vector.Vectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Search by meaning over the long-term memory, all of it on the phone.
 *
 * Three jobs:
 *
 *  - **The model.** multilingual-e5-small (int8, 118 MB) and its tokenizer
 *    (17 MB), downloaded once from Hugging Face when the user asks, each
 *    checked against the SHA-256 pinned here before it is used.
 *  - **Indexing.** The memory's messages are cut into stretches of chat
 *    ([Chunker]) and each stretch is given a vector. A large history takes
 *    a while on a phone, so it runs in slices that can stop and resume.
 *  - **Searching.** A question is turned into a vector and compared with
 *    every stored one; the closest stretches go to the Twin with the word
 *    matches it already had.
 *
 * Nothing here sends a message anywhere: the only network use is the model
 * download.
 */
class SemanticIndex(private val container: AppContainer) {

    sealed interface Download {
        object Missing : Download
        data class Running(val fraction: Float) : Download
        object Ready : Download
        data class Failed(val message: String) : Download
    }

    data class Progress(val running: Boolean, val done: Int, val total: Int, val messagesLeft: Int)

    private val dir: File get() = File(container.context.filesDir, "models/e5-small")
    private val modelFile: File get() = File(dir, MODEL.name)
    private val tokenizerFile: File get() = File(dir, TOKENIZER.name)

    private val _download = MutableStateFlow<Download>(Download.Missing)
    val download: StateFlow<Download> = _download.asStateFlow()

    private val _progress = MutableStateFlow(Progress(false, 0, 0, 0))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val downloading = Mutex()
    private val indexing = Mutex()

    /** One loaded model at a time, shared by indexing and questions; closed when idle. */
    private val modelLock = Mutex()
    private var embedder: E5Embedder? = null
    private var idleClose: Job? = null

    @Volatile private var index: Vectors.Index? = null

    fun modelReady(): Boolean = modelFile.length() == MODEL.bytes && tokenizerFile.length() == TOKENIZER.bytes

    /** Brings the visible state in line with what is on disk and in the database. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_download.value !is Download.Running) {
            _download.value = if (modelReady()) Download.Ready else if (_download.value is Download.Failed) _download.value else Download.Missing
        }
        val s = runCatching { container.memoryStore.chunkStats() }.getOrNull() ?: return@withContext
        _progress.value = _progress.value.copy(done = s.withVectors, total = s.chunks, messagesLeft = s.messagesLeft)
    }

    // ----------------------------------------------------------- download

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (!downloading.tryLock()) return@withContext
        try {
            dir.mkdirs()
            val total = (MODEL.bytes + TOKENIZER.bytes).toFloat()
            var before = 0L
            for (file in listOf(TOKENIZER, MODEL)) {
                val target = File(dir, file.name)
                if (target.length() != file.bytes || sha256(target) != file.sha256) {
                    fetch(file, target) { got -> _download.value = Download.Running(((before + got) / total).coerceIn(0f, 1f)) }
                }
                before += file.bytes
            }
            _download.value = Download.Ready
            container.logger.write(Stage.SYSTEM, LogLevel.INFO, "search model downloaded", "${(MODEL.bytes + TOKENIZER.bytes) / 1_000_000} MB")
        } catch (t: Throwable) {
            _download.value = Download.Failed(t.message ?: t.javaClass.simpleName)
            container.logger.write(Stage.SYSTEM, LogLevel.WARN, "search model download failed", t.toString())
        } finally {
            downloading.unlock()
        }
    }

    private fun fetch(file: ModelFile, target: File, progress: (Long) -> Unit) {
        val part = File(target.path + ".part")
        val request = Request.Builder().url(file.url).build()
        container.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The download failed (HTTP ${response.code}). Check the connection and try again.")
            val body = response.body ?: throw IllegalStateException("The download came back empty.")
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            body.byteStream().use { input ->
                part.outputStream().buffered(1 shl 16).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        got += n
                        progress(got)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != file.sha256) {
                part.delete()
                throw IllegalStateException("The downloaded ${file.name} is not the expected file, so it was not used. Hugging Face may have changed it; tell the developer.")
            }
            if (!part.renameTo(target)) throw IllegalStateException("Could not save ${file.name}.")
        }
    }

    private fun sha256(file: File): String? {
        if (!file.exists()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ indexing

    /**
     * Cuts new messages into stretches and gives vectors to those without,
     * until done or [untilMillis]. Returns true when nothing is left. One run
     * at a time; a second call while one runs returns false at once.
     */
    suspend fun run(untilMillis: Long = Long.MAX_VALUE): Boolean = withContext(Dispatchers.IO) {
        if (!modelReady()) return@withContext false
        if (!indexing.tryLock()) return@withContext false
        val memory = container.memoryStore
        try {
            _progress.value = _progress.value.copy(running = true)
            while (true) {
                val messages = memory.messagesToChunk(CHUNK_PAGE)
                if (messages.isEmpty()) break
                memory.addChunks(Chunker.chunk(messages), messages.maxOf { it.id })
                refresh()
            }
            var added = 0
            while (System.currentTimeMillis() < untilMillis) {
                val batch = memory.chunksWithoutVectors(EMBED_BATCH)
                if (batch.isEmpty()) break
                val vectors = withEmbedder { e -> batch.map { it.id to Vectors.pack(e.embed(it.text, E5Embedder.Kind.PASSAGE)) } }
                memory.setVectors(vectors)
                added += vectors.size
                index = null
                val p = _progress.value
                _progress.value = p.copy(done = (p.done + vectors.size).coerceAtMost(p.total))
            }
            refresh()
            if (added > 0) container.logger.write(Stage.SYSTEM, LogLevel.INFO, "search index grew", "$added stretch(es) of chat")
            val left = _progress.value
            left.done >= left.total && left.messagesLeft == 0
        } catch (t: Throwable) {
            container.logger.write(Stage.SYSTEM, LogLevel.WARN, "search indexing stopped", t.toString())
            false
        } finally {
            _progress.value = _progress.value.copy(running = false)
            indexing.unlock()
        }
    }

    // ----------------------------------------------------------- searching

    /**
     * The stretches of chat closest in meaning to [question], best first;
     * empty when the model or the vectors are not there yet.
     */
    suspend fun search(question: String, k: Int = 12): List<MemoryStore.Chunk> = withContext(Dispatchers.IO) {
        if (!modelReady() || question.isBlank()) return@withContext emptyList()
        val idx = index ?: Vectors.Index.of(container.memoryStore.allVectors(), E5Embedder.DIMENSIONS).also { index = it }
        if (idx.size == 0) return@withContext emptyList()
        val query = withEmbedder { it.embed(question, E5Embedder.Kind.QUERY) }
        val kept = Vectors.keep(idx.top(query, k))
        val byId = container.memoryStore.chunks(kept.map { it.first }).associateBy { it.id }
        kept.mapNotNull { byId[it.first] }
    }

    /** Forgets the cached vectors, after the memory was erased. */
    fun forget() {
        index = null
        _progress.value = Progress(false, 0, 0, 0)
    }

    private suspend fun <T> withEmbedder(block: (E5Embedder) -> T): T = modelLock.withLock {
        idleClose?.cancel()
        val e = embedder ?: run {
            val tokenizer = tokenizerFile.bufferedReader().use { SpmTokenizer.read(it) }
            E5Embedder(modelFile, tokenizer).also { embedder = it }
        }
        try {
            block(e)
        } finally {
            idleClose = container.applicationScope.launch {
                delay(IDLE_CLOSE_MILLIS)
                modelLock.withLock {
                    embedder?.close()
                    embedder = null
                }
            }
        }
    }

    data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

    companion object {
        private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"

        val MODEL = ModelFile(
            "model_quantized.onnx",
            "$BASE/onnx/model_quantized.onnx",
            118_308_185L,
            "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
        )
        val TOKENIZER = ModelFile(
            "tokenizer.json",
            "$BASE/tokenizer.json",
            17_082_730L,
            "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
        )

        private const val CHUNK_PAGE = 5000
        private const val EMBED_BATCH = 32
        private const val IDLE_CLOSE_MILLIS = 3 * 60 * 1000L
    }
}
