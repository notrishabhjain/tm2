package com.taskmind.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import com.taskmind.BuildConfig
import com.taskmind.core.LlmJson
import com.taskmind.core.LlmJson.bool
import com.taskmind.core.LlmJson.dbl
import com.taskmind.core.LlmJson.str
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.repo.ActivityLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Spec 19 - self-update. Required, because no store will distribute this app:
 * MANAGE_EXTERNAL_STORAGE plus notification access makes Play Store approval
 * unrealistic (spec 2).
 *
 * The APK must be signed with the SAME keystore as the installed build or the
 * update silently fails - which is why CI signs from a fixed key.
 */
class SelfUpdater(
    private val context: Context,
    private val http: OkHttpClient,
    private val logger: ActivityLogger,
) {

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val releaseNotes: String?,
        val mandatory: Boolean,
    )

    sealed interface DownloadResult {
        data class Ready(val apk: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    val installedVersionCode: Int get() = BuildConfig.APP_VERSION_CODE
    val installedVersionName: String get() = BuildConfig.APP_VERSION_NAME

    fun isNewer(manifest: Manifest): Boolean = manifest.versionCode > installedVersionCode

    suspend fun fetchManifest(url: String): Manifest? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.write(Stage.UPDATE, LogLevel.WARN, "update manifest HTTP ${response.code}", url)
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val obj = LlmJson.parseObject(body) ?: return@withContext null
                val versionCode = obj.dbl("versionCode")?.toInt() ?: return@withContext null
                val versionName = obj.str("versionName") ?: return@withContext null
                val apkUrl = obj.str("apkUrl") ?: return@withContext null
                val sha256 = obj.str("sha256") ?: return@withContext null
                Manifest(
                    versionCode = versionCode,
                    versionName = versionName,
                    apkUrl = apkUrl,
                    sha256 = sha256.lowercase().trim(),
                    releaseNotes = obj.str("releaseNotes"),
                    mandatory = obj.bool("mandatory") ?: false,
                )
            }
        } catch (e: IOException) {
            logger.write(Stage.UPDATE, LogLevel.WARN, "update check failed", e.message)
            null
        }
    }

    /**
     * Downloads to the app cache with progress, then VERIFIES THE SHA-256
     * BEFORE DOING ANYTHING WITH THE FILE. An APK that fails the check is
     * deleted and never handed to the installer.
     */
    suspend fun download(manifest: Manifest, onProgress: (Float) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "taskmind-${manifest.versionName}.apk")
            try {
                val request = Request.Builder().url(manifest.apkUrl).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DownloadResult.Failed("HTTP ${response.code} downloading the APK")
                    }
                    val body = response.body ?: return@withContext DownloadResult.Failed("empty download body")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                read += n
                                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                val actual = sha256(target)
                if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                    target.delete()
                    logger.write(
                        Stage.UPDATE,
                        LogLevel.ERROR,
                        "update rejected - SHA-256 mismatch",
                        "expected=${manifest.sha256} actual=$actual",
                    )
                    return@withContext DownloadResult.Failed("The downloaded file did not match its published checksum.")
                }

                logger.write(Stage.UPDATE, LogLevel.INFO, "update downloaded and verified", manifest.versionName)
                DownloadResult.Ready(target)
            } catch (e: IOException) {
                runCatching { target.delete() }
                DownloadResult.Failed(e.message ?: "download failed")
            }
        }

    /**
     * Installs via PackageInstaller, falling back to an ACTION_VIEW intent with
     * a FileProvider URI.
     *
     * The downloaded APK is NOT deleted here: spec 19 says never delete it
     * before the install session reports a result, and the session outlives
     * this call.
     */
    fun install(apk: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("taskmind", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            logger.post(Stage.UPDATE, LogLevel.INFO, "install session committed")
            true
        } catch (t: Throwable) {
            logger.post(Stage.UPDATE, LogLevel.WARN, "PackageInstaller failed, falling back to intent", t.toString())
            installByIntent(apk)
        }
    }

    private fun installByIntent(apk: File): Boolean = try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        logger.post(Stage.UPDATE, LogLevel.ERROR, "could not start the installer", t.toString())
        false
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Exposed so the settings screen can show the key fingerprint if asked. */
    fun manifestUrlHint(): String =
        "https://github.com/<owner>/<repo>/releases/latest/download/update.json"
}
