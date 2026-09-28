package com.hmessaging.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.hmessaging.data.db.HmDatabase
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.repo.MessageRepository
import com.hmessaging.system.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A copy of every message, kept where removing the app cannot reach it.
 *
 * The app's own database goes with the app when it is uninstalled, and on at least one vendor ROM
 * the rows this app wrote into the phone's SMS store go with it too — so a reader who removed the
 * app to reinstall it lost every message received since it became the default. This writes the
 * whole history into the public Download folder, which belongs to the user rather than to any
 * app, once a day and on demand; a fresh install reads it back from there.
 *
 * Two files, because they have different shapes. Messages are one JSON object per line, written
 * and read as a stream, so a hundred thousand of them never sit in memory at once. Everything
 * else — rules, templates, settings — is the same small document the manual export produces.
 *
 * Reading back needs the user to point at the folder: from Android 11 an app cannot see files in
 * Download that a previous installation of it created, however they are named.
 */
class AutoBackup(
    private val context: Context,
    private val database: HmDatabase,
    private val prefs: AppPrefs,
    private val repository: MessageRepository,
    private val backupManager: BackupManager,
    private val diagnostics: Diagnostics,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Written(val messages: Int, val where: String)

    data class Restored(val messages: Int, val settingsFile: Boolean)

    /** Writes both files, prunes older ones this app wrote, and records the result. */
    suspend fun writeNow(): Result<Written> = withContext(Dispatchers.IO) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            val count = writeMessagesFile("$MESSAGES_PREFIX$stamp.jsonl")
            writeSettingsFile("$SETTINGS_PREFIX$stamp.json")
            prune(MESSAGES_PREFIX)
            prune(SETTINGS_PREFIX)
            prefs.setLastBackup(System.currentTimeMillis(), count)
            diagnostics.record(Diagnostics.KIND_BACKUP, "wrote $count message(s) to $RELATIVE_PATH")
            Written(count, RELATIVE_PATH)
        }.onFailure {
            diagnostics.record(Diagnostics.KIND_BACKUP, "FAILED — ${it.message}")
        }
    }

    /**
     * Reads the newest backup out of a folder the user picked.
     *
     * Messages already held are skipped, so this is safe to run on a phone that lost nothing.
     * Restored messages carry no platform-store id, which puts them in the queue to be written
     * into the phone's SMS store again — the store that was emptied along with the app.
     */
    suspend fun restoreFromFolder(tree: Uri): Result<Restored> = withContext(Dispatchers.IO) {
        runCatching {
            val files = listFolder(tree)
            val messagesFile = files.filter { it.name.startsWith(MESSAGES_PREFIX) }.maxByOrNull { it.modified }
                ?: error(context.getString(com.hmessaging.R.string.settings_restore_none))
            val restored = context.contentResolver.openInputStream(messagesFile.uri)?.use { readMessages(it) }
                ?: error("Could not open ${messagesFile.name}")
            val settingsFile = files.filter { it.name.startsWith(SETTINGS_PREFIX) }.maxByOrNull { it.modified }
            val settingsApplied = settingsFile?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    backupManager.importFrom(stream, replaceExisting = false)
                    true
                }
            } ?: false
            diagnostics.record(
                Diagnostics.KIND_BACKUP,
                "restored $restored message(s) from ${messagesFile.name}" +
                    if (settingsApplied) " and settings from ${settingsFile?.name}" else "",
            )
            Restored(restored, settingsApplied)
        }.onFailure {
            diagnostics.record(Diagnostics.KIND_BACKUP, "restore FAILED — ${it.message}")
        }
    }

    // ---- writing ----

    private suspend fun writeMessagesFile(name: String): Int {
        val (uri, stream) = openForWrite(name, MIME_JSONL) ?: error("Could not create $name in $RELATIVE_PATH")
        var count = 0
        try {
            BufferedWriter(stream.writer(Charsets.UTF_8)).use { writer ->
                writer.write("""{"hmessaging":"messages","version":1,"exportedAt":${System.currentTimeMillis()}}""")
                writer.newLine()
                var offset = 0
                while (true) {
                    val page = database.messageDao().page(PAGE, offset)
                    if (page.isEmpty()) break
                    for (message in page) {
                        writer.write(json.encodeToString(BackupMessage.serializer(), message.toBackup()))
                        writer.newLine()
                        count++
                    }
                    offset += page.size
                }
            }
        } finally {
            finishWrite(uri)
        }
        return count
    }

    private suspend fun writeSettingsFile(name: String) {
        val (uri, stream) = openForWrite(name, BackupManager.MIME_TYPE) ?: error("Could not create $name")
        try {
            stream.use { backupManager.exportTo(it, includeMessages = false) }
        } finally {
            finishWrite(uri)
        }
    }

    private fun MessageEntity.toBackup() = BackupMessage(
        address = address,
        body = body,
        date = date,
        type = type.name,
        read = read,
        isOtp = isOtp,
        subscriptionId = subscriptionId,
    )

    private fun openForWrite(name: String, mime: String): Pair<Uri, OutputStream>? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri, "wt") ?: return null
            return uri to stream
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        return Uri.fromFile(file) to FileOutputStream(file)
    }

    private fun finishWrite(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    /** Keeps the newest [KEEP] files with this prefix that this installation wrote. */
    private fun prune(prefix: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val rows = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_MODIFIED),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("$RELATIVE_PATH%", "$prefix%"),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }.orEmpty()
                rows.drop(KEEP).forEach { id ->
                    resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                dir.listFiles { file -> file.name.startsWith(prefix) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP)
                    ?.forEach { it.delete() }
            }
        }
    }

    // ---- reading ----

    private class Entry(val uri: Uri, val name: String, val modified: Long)

    private fun listFolder(tree: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Entry(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                            name = cursor.getString(1).orEmpty(),
                            modified = cursor.getLong(2),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private suspend fun readMessages(stream: InputStream): Int {
        val known = HashSet(database.messageDao().fingerprints())
        val threadIds = HashMap<String, Long>()
        val batch = ArrayList<MessageEntity>(PAGE)
        var restored = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            database.messageDao().insertAll(batch)
            restored += batch.size
            batch.clear()
        }
        BufferedReader(stream.reader(Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank() || line.startsWith("{\"hmessaging\"")) continue
                val message = runCatching { json.decodeFromString(BackupMessage.serializer(), line) }
                    .getOrNull() ?: continue
                if (!known.add("${message.address}|${message.date}")) continue
                val threadId = threadIds.getOrPut(message.address) { repository.threadIdFor(message.address) }
                val type = runCatching { MessageType.valueOf(message.type) }.getOrDefault(MessageType.INBOX)
                batch.add(
                    MessageEntity(
                        threadId = threadId,
                        address = message.address,
                        body = message.body,
                        date = message.date,
                        type = type,
                        read = message.read,
                        status = if (type == MessageType.SENT) DeliveryStatus.SENT else DeliveryStatus.NONE,
                        subscriptionId = message.subscriptionId,
                        isOtp = message.isOtp,
                    ),
                )
                if (batch.size >= PAGE) flush()
            }
        }
        flush()
        if (restored > 0) database.threadDao().rebuildSummaries()
        return restored
    }

    companion object {
        const val FOLDER = "HMessaging"
        const val RELATIVE_PATH = "Download/$FOLDER/"
        private const val MESSAGES_PREFIX = "hmessaging-messages-"
        private const val SETTINGS_PREFIX = "hmessaging-settings-"
        private const val MIME_JSONL = "application/x-ndjson"
        private const val PAGE = 1_000
        private const val KEEP = 3
    }
}
