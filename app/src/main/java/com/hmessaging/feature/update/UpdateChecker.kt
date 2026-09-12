package com.hmessaging.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hmessaging.BuildConfig
import com.hmessaging.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The published release, as `dist/latest.json` describes it. */
@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val sha256: String = "",
    val notes: String = "",
    val size: Long = 0,
)

/**
 * Finds out whether a newer build has been published, fetches it, and hands it to the installer.
 *
 * The app is distributed as a signed APK rather than through a store, so nothing else is going to
 * tell the user a new one exists. The manifest and the APK both come from the repository over
 * HTTPS; what makes this safe to install is not the transport but the two checks below —
 * the SHA-256 in the manifest must match the bytes that arrived, and Android will refuse the
 * install outright unless the download is signed with the same key as the installed app.
 */
class UpdateChecker(
    private val context: Context,
    private val prefs: AppPrefs,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the published release when it is newer than this build, otherwise null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = fetchManifest() ?: return@withContext null
        info.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.url.startsWith("https://") }
    }

    /** Fetches the manifest whether or not it describes something newer. */
    suspend fun fetchManifest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val text = open(manifestUrl()).use { it.inputStream.bufferedReader().readText() }
            json.decodeFromString(UpdateInfo.serializer(), text)
        }.getOrNull()
    }

    /**
     * Where to look. Settable, because where these releases are published is not something the app
     * can know for good: it depends on whether the repository they come from is reachable without
     * an account, and that is the owner's decision to change — not a reason to need a new build.
     */
    suspend fun manifestUrl(): String =
        prefs.settings.first().updateManifestUrl.takeIf { it.startsWith("https://") } ?: MANIFEST_URL

    /**
     * Downloads the release and verifies it against the manifest's digest.
     *
     * Written to a fresh file every time and deleted the moment the digest disagrees: a truncated
     * or tampered download must never be left lying where the install step could pick it up.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(downloadDir(), "HMessaging-${info.versionName}.apk")
                if (target.exists()) target.delete()

                val connection = open(info.url)
                val total = if (info.size > 0) info.size else connection.contentLength.toLong()
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L

                connection.use { source ->
                    source.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                read += count
                                if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
                    target.delete()
                    error("checksum mismatch")
                }
                target
            }
        }

    /**
     * The intent that hands the downloaded APK to the package installer.
     *
     * Through a [FileProvider], because a `file://` URI pointed at another app has been refused
     * since Android 7.
     */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun downloadDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Drops downloads left behind by an install the user did not go through with. */
    fun clearDownloads() {
        runCatching { downloadDir().listFiles()?.forEach { it.delete() } }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    companion object {
        /**
         * Where releases are published today, built in and overridden by the setting.
         *
         * This address only answers to a signed-in browser while the repository is private, which
         * an app on a phone is not — so the setting above is how the check is pointed at whatever
         * the owner decides to publish from.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/hoseinmovahed88/HMessaging/" +
                "claude/android-messenger-app-izbr89/dist/latest.json"

        private const val TIMEOUT_MS = 20_000
        private const val BUFFER_BYTES = 64 * 1024
    }
}
