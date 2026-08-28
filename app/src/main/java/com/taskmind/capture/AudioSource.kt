package com.taskmind.capture

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reading a recording, wherever it turned out to live.
 *
 * A recording reaches the app by one of two routes, and only one of them is a
 * file path. The known-paths scan yields `/storage/emulated/0/...`, which
 * `File` understands. The folder the user nominates with the system picker
 * yields a `content://` document URI, which `File` does not: `File("content://…")`
 * reports that it does not exist.
 *
 * Every consumer previously assumed the first case, so a recording found
 * through the user's own folder failed its stability check, was logged as
 * "still being written", and was never transcribed - the folder picker appeared
 * to do nothing at all. This is the single place that knows the difference.
 */
object AudioSource {

    fun isContentUri(path: String): Boolean =
        path.startsWith("content://") || path.startsWith("file://")

    /** Size in bytes, or -1 when the source cannot be read at all. */
    fun sizeOf(context: Context, path: String): Long =
        if (isContentUri(path)) {
            runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.length() ?: -1L
            }.getOrDefault(-1L)
        } else {
            val file = File(path)
            if (file.exists()) file.length() else -1L
        }

    fun exists(context: Context, path: String): Boolean = sizeOf(context, path) >= 0

    fun displayName(context: Context, path: String): String =
        if (isContentUri(path)) {
            runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.name
            }.getOrNull() ?: Uri.parse(path).lastPathSegment ?: path
        } else {
            File(path).name
        }

    /**
     * A file on local disk holding this audio, ready for the decoder.
     *
     * A filesystem path is returned as-is - copying it would double the disk
     * cost of every call for nothing. A document URI is streamed into
     * [workDir]; MediaExtractor can be handed a URI, but the chunker seeks
     * within the decoded output and wants a real file either way.
     *
     * Returns null if the source cannot be read, which is the honest answer
     * when the user has revoked the folder permission or moved the file.
     */
    suspend fun materialise(context: Context, path: String, workDir: File): File? =
        withContext(Dispatchers.IO) {
            if (!isContentUri(path)) {
                val file = File(path)
                return@withContext if (file.exists() && file.length() > 0) file else null
            }
            runCatching {
                val uri = Uri.parse(path)
                val name = displayName(context, path).substringAfterLast('/')
                val extension = name.substringAfterLast('.', "audio").ifBlank { "audio" }
                workDir.mkdirs()
                val destination = File(workDir, "source.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                if (destination.length() > 0) destination else null
            }.getOrNull()
        }

    /** Removes the recording itself, whichever kind of source it is. */
    fun delete(context: Context, path: String): Boolean =
        runCatching {
            if (isContentUri(path)) {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() ?: false
            } else {
                File(path).delete()
            }
        }.getOrDefault(false)

    /**
     * True once the recorder has stopped growing the file.
     *
     * The dialer is still flushing when the call-end trigger fires, so a size
     * that is unchanged across two reads a second apart is the signal that the
     * file is complete. Works for both source kinds, which is the whole point.
     */
    suspend fun isStable(context: Context, path: String, minBytes: Long, gapMillis: Long = 1_000): Boolean =
        withContext(Dispatchers.IO) {
            val first = sizeOf(context, path)
            if (first <= minBytes) return@withContext false
            kotlinx.coroutines.delay(gapMillis)
            val second = sizeOf(context, path)
            first == second && second > minBytes
        }
}
