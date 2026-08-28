package com.taskmind.capture

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spec 11.3 - finding the recording the device's own dialer wrote.
 *
 * The app must NOT record calls: Android does not permit third-party call-audio
 * capture. It consumes what the dialer produced, which means every path here is
 * a guess about where a particular OEM put the file.
 */
class RecordingFinder(private val context: Context) {

    /** Spec 11.3. Each is scanned one subdirectory deep. */
    val knownPaths: List<String> = listOf(
        "/storage/emulated/0/MIUI/sound_recorder/call_rec",
        "/storage/emulated/0/MIUI/sounds/Call",
        "/storage/emulated/0/Recordings/Call recordings",
        "/storage/emulated/0/Recordings/Call Recording",
        "/storage/emulated/0/Sound Recorder",
        "/storage/emulated/0/Music/Recordings/Call Recordings",
        "/storage/emulated/0/Record/PhoneRecord",
        "/storage/emulated/0/Record/Call",
        "/storage/emulated/0/Recordings/Record/Call",
        "/storage/emulated/0/Recordings/Call",
        "/storage/emulated/0/Recordings/CallRecordings",
        "/storage/emulated/0/Documents/Call Recordings",
        "/storage/emulated/0/Recorder/CallRecord",
        "/storage/emulated/0/CallRecording",
        "/storage/emulated/0/PhoneCallRecordings",
        "/storage/emulated/0/Sounds",
    )

    val extensions: Set<String> = setOf("m4a", "amr", "3gp", "mp3", "wav", "aac", "opus", "ogg")

    data class Candidate(
        val path: String,
        val name: String,
        val lastModified: Long,
        val sizeBytes: Long,
        val uri: Uri? = null,
    )

    /**
     * Spec 11.3: detect denial explicitly. Discovery fails silently without All
     * Files Access and looks exactly like "the dialer is not recording".
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Spec 18.7: does call recording appear to be enabled at all? Used during
     * onboarding to say so plainly rather than failing silently later.
     */
    suspend fun anyRecordingExists(userDirUri: String?): Boolean = withContext(Dispatchers.IO) {
        scanUserDirectory(userDirUri).isNotEmpty() ||
            scanKnownPaths().isNotEmpty() ||
            queryMediaStore(0).isNotEmpty()
    }

    /**
     * Finds the recording for a call.
     *
     * Match rule (spec 11.3): lastModified within [callStart - 60s,
     * callEnd + 180s], preferring a filename containing the phone number's last
     * six digits. Newest match wins.
     */
    suspend fun findForCall(
        callStartMillis: Long,
        callEndMillis: Long,
        phoneNumber: String?,
        userDirUri: String?,
    ): Candidate? = withContext(Dispatchers.IO) {
        val from = callStartMillis - 60_000
        val to = callEndMillis + 180_000

        val candidates = buildList {
            addAll(scanUserDirectory(userDirUri))
            addAll(scanKnownPaths())
            addAll(queryMediaStore(from))
        }
            .distinctBy { it.path }
            .filter { it.lastModified in from..to && it.sizeBytes > MIN_USABLE_BYTES }

        if (candidates.isEmpty()) return@withContext null

        val digits = phoneNumber?.filter { it.isDigit() }.orEmpty()
        val lastSix = if (digits.length >= 6) digits.takeLast(6) else null
        val preferred = if (lastSix != null) candidates.filter { it.name.contains(lastSix) } else emptyList()

        (preferred.ifEmpty { candidates }).maxByOrNull { it.lastModified }
    }

    /**
     * Spec 11.3: the recorder is still flushing when the call-end trigger
     * fires. Require the size to be stable across two reads 1 s apart, or you
     * will transcribe a half-written file.
     *
     * Delegated to [AudioSource] because a candidate from the user's nominated
     * folder is a `content://` URI, not a path: reading it with `File` reported
     * "does not exist", which read as "still being written" and silently
     * stalled every SAF-discovered recording forever.
     */
    suspend fun isStable(candidate: Candidate): Boolean =
        AudioSource.isStable(context, candidate.path, MIN_USABLE_BYTES)

    /**
     * What discovery can actually see right now, for the diagnostic report.
     *
     * Deliberately unfiltered by time: "the folder has 40 recordings but none
     * within the call window" and "the folder is empty or unreadable" are
     * completely different problems that look identical from the outside.
     */
    suspend fun survey(userDirUri: String?): Survey = withContext(Dispatchers.IO) {
        val user = scanUserDirectory(userDirUri)
        val known = scanKnownPaths()
        val media = queryMediaStore(0)
        Survey(
            allFilesAccess = hasAllFilesAccess(),
            userDirConfigured = !userDirUri.isNullOrBlank(),
            userDirReadable = userDirUri.isNullOrBlank() ||
                runCatching { DocumentFile.fromTreeUri(context, Uri.parse(userDirUri))?.isDirectory == true }
                    .getOrDefault(false),
            userDirCount = user.size,
            knownPathCount = known.size,
            mediaStoreCount = media.size,
            existingKnownPaths = knownPaths.filter { runCatching { File(it).isDirectory }.getOrDefault(false) },
            newest = (user + known + media).distinctBy { it.path }.maxByOrNull { it.lastModified },
        )
    }

    data class Survey(
        val allFilesAccess: Boolean,
        val userDirConfigured: Boolean,
        val userDirReadable: Boolean,
        val userDirCount: Int,
        val knownPathCount: Int,
        val mediaStoreCount: Int,
        val existingKnownPaths: List<String>,
        val newest: Candidate?,
    ) {
        val total: Int get() = userDirCount + knownPathCount + mediaStoreCount
    }

    // ------------------------------------------------------------- scanning

    private fun scanKnownPaths(): List<Candidate> {
        if (!hasAllFilesAccess()) return emptyList()
        val out = mutableListOf<Candidate>()
        for (path in knownPaths) {
            val dir = File(path)
            if (!dir.isDirectory) continue
            collect(dir, depth = 1, into = out)
        }
        return out
    }

    private fun collect(dir: File, depth: Int, into: MutableList<Candidate>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory && depth > 0 -> collect(child, depth - 1, into)
                child.isFile && child.extension.lowercase() in extensions ->
                    into.add(Candidate(child.absolutePath, child.name, child.lastModified(), child.length()))
            }
        }
    }

    /** A directory the user nominated with the system picker, if any. */
    private fun scanUserDirectory(userDirUri: String?): List<Candidate> {
        if (userDirUri.isNullOrBlank()) return emptyList()
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(userDirUri)) }.getOrNull()
            ?: return emptyList()
        if (!tree.isDirectory) return emptyList()
        val out = mutableListOf<Candidate>()
        collectDocuments(tree, depth = 1, into = out)
        return out
    }

    private fun collectDocuments(dir: DocumentFile, depth: Int, into: MutableList<Candidate>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            when {
                child.isDirectory && depth > 0 -> collectDocuments(child, depth - 1, into)
                child.isFile && name.substringAfterLast('.', "").lowercase() in extensions ->
                    into.add(
                        Candidate(
                            path = child.uri.toString(),
                            name = name,
                            lastModified = child.lastModified(),
                            sizeBytes = child.length(),
                            uri = child.uri,
                        ),
                    )
            }
        }
    }

    /** Last resort: MediaStore filtered on path keywords. */
    private fun queryMediaStore(modifiedAfterMillis: Long): List<Candidate> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
        )
        val out = mutableListOf<Candidate>()
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
            )
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val modIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            var scanned = 0
            while (c.moveToNext() && scanned < MEDIASTORE_SCAN_LIMIT) {
                scanned++
                val data = if (dataIdx >= 0) c.getString(dataIdx) else null
                if (data.isNullOrBlank()) continue
                val lower = data.lowercase()
                if (PATH_KEYWORDS.none { lower.contains(it) }) continue
                if (data.substringAfterLast('.', "").lowercase() !in extensions) continue
                val modified = if (modIdx >= 0) c.getLong(modIdx) * 1000 else 0L
                if (modified < modifiedAfterMillis) continue
                out.add(
                    Candidate(
                        path = data,
                        name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else File(data).name,
                        lastModified = modified,
                        sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else File(data).length(),
                    ),
                )
            }
        }
        return out
    }

    private companion object {
        val PATH_KEYWORDS = listOf("call", "record", "phone", "voice", "dialer", "rec")

        /** Below this a file is a stub the recorder has only just created. */
        const val MIN_USABLE_BYTES = 4_096L
        const val MEDIASTORE_SCAN_LIMIT = 2_000
    }
}
