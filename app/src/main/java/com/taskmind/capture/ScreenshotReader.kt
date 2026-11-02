package com.taskmind.capture

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the words in screenshots, on the phone.
 *
 * Anything that never reaches the phone as a notification - a chat read on
 * WhatsApp Web, a chat that was open, an email on the laptop, a photo of a
 * whiteboard - can still reach TaskMind: screenshot it and share it in. The
 * picture is read here by ML Kit and never uploaded; what goes on is the
 * text, exactly like text shared in.
 *
 * Latin script only, which covers English and Hinglish typed in Latin
 * letters - how most chats are written.
 */
object ScreenshotReader {

    /** Most pictures read at once; a long chat is a few screenshots, not forty. */
    const val MAX_IMAGES = 10

    /** The text of each picture, in order, separated by a blank line. Empty when nothing was read. */
    suspend fun read(context: Context, uris: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            uris.take(MAX_IMAGES).mapNotNull { uri ->
                val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return@mapNotNull null
                suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { result -> cont.resume(result.text.trim().ifBlank { null }) }
                        .addOnFailureListener { cont.resume(null) }
                }
            }.joinToString("\n\n")
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
