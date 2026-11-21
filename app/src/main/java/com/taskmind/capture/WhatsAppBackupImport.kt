package com.taskmind.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.core.WaBackup
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Every WhatsApp chat at once: reads WhatsApp's own encrypted backup with the
 * 64-digit key the user gives, and puts each message - both sides of every
 * conversation - into the long-term memory.
 *
 * Three steps, each of which can say plainly why it stopped:
 *
 *  1. Find the backup (WhatsApp keeps it under Android/media, which "All
 *     files access" covers), or take a file the user picked.
 *  2. Decrypt it to a scratch file inside the app (see [WaBackup]).
 *  3. Read WhatsApp's message tables out of it and store what is new.
 *
 * The scratch database is deleted whatever happens. Nothing here creates a
 * task or calls a model: this is memory, and the import of years of chat
 * must not flood the Inbox.
 *
 * WhatsApp's table layout is its own business and changes between versions.
 * The columns are checked before they are read, and a layout this does not
 * know is reported with what it found, not guessed at.
 */
class WhatsAppBackupImport(private val container: AppContainer) {

    sealed interface State {
        object Idle : State
        data class Working(val step: String, val fraction: Float? = null) : State
        data class Done(val added: Int, val read: Int, val chats: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val running = Mutex()
    private val context: Context get() = container.context

    /** The newest end-to-end encrypted backup on the phone, or null. */
    fun findBackup(): File? = backupFolders()
        .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.startsWith("msgstore") && f.name.endsWith(".crypt15") }?.toList().orEmpty() }
        .maxWithOrNull(compareBy<File> { it.name == "msgstore.db.crypt15" }.thenBy { it.lastModified() })

    /** True when a backup exists but only in the older, not end-to-end encrypted, form. */
    fun onlyOldBackup(): Boolean = findBackup() == null && backupFolders().any { dir ->
        dir.listFiles { f -> f.name.startsWith("msgstore") && (f.name.endsWith(".crypt14") || f.name.endsWith(".crypt12")) }?.isNotEmpty() == true
    }

    private fun backupFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Databases",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases",
            "WhatsApp/Databases",
            "WhatsApp Business/Databases",
        ).map { File(root, it) }.filter { it.isDirectory }
    }

    /**
     * Runs the whole import. [picked] is a file the user chose; without it the
     * backup is looked for where WhatsApp keeps it. One at a time: a second
     * call while one runs returns at once.
     */
    suspend fun run(keyText: String, picked: Uri? = null) = withContext(Dispatchers.IO) {
        if (!running.tryLock()) return@withContext
        val scratchDir = File(context.noBackupFilesDir, "wa-import")
        try {
            val key = WaBackup.parseKey(keyText)
                ?: return@withContext fail("The key must be 64 characters: digits and the letters a to f.")

            _state.value = State.Working("Looking for the backup")
            val file = if (picked == null) findBackup() else null
            if (picked == null && file == null) {
                return@withContext fail(
                    if (onlyOldBackup()) {
                        "WhatsApp's backup on this phone is not end-to-end encrypted yet. Turn that on in WhatsApp " +
                            "with a 64-digit key, tap Back up, and try again."
                    } else {
                        "No WhatsApp backup was found on this phone. In WhatsApp, tap Back up first; or allow " +
                            "All files access on the Health screen; or choose the file yourself."
                    },
                )
            }
            val size = file?.length() ?: 0L
            val backupTime = file?.lastModified() ?: 0L
            val source: InputStream = if (file != null) file.inputStream() else {
                context.contentResolver.openInputStream(picked!!) ?: return@withContext fail("That file could not be opened.")
            }

            scratchDir.deleteRecursively()
            scratchDir.mkdirs()
            val scratch = File(scratchDir, "msgstore.db")
            _state.value = State.Working("Unlocking the backup", 0f)
            source.buffered(1 shl 16).use { input ->
                scratch.outputStream().buffered(1 shl 16).use { output ->
                    WaBackup.decrypt(input, key, output) { read ->
                        if (size > 0) _state.value = State.Working("Unlocking the backup", (read.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }

            // The key opened it, so it is worth keeping for the next refresh.
            container.secretStore.waBackupKey = keyText

            val result = readInto(scratch)
            _state.value = result
            if (backupTime > 0) prefs.edit().putLong(K_LAST_BACKUP, backupTime).putLong(K_LAST_RUN, System.currentTimeMillis()).apply()
            // New history to search by meaning, if the model is there.
            container.semanticIndex.forget()
            container.applicationScope.launch { container.semanticIndex.run() }
            container.logger.write(
                Stage.CAPTURE,
                LogLevel.INFO,
                "WhatsApp backup imported",
                "read=${result.read} new=${result.added} chats=${result.chats}",
            )
        } catch (e: WaBackup.BadKey) {
            fail(e.message ?: "That key does not open this backup.")
        } catch (e: WaBackup.BadFile) {
            fail(e.message ?: "The backup could not be read.")
        } catch (e: UnknownLayout) {
            fail(e.message ?: "This WhatsApp version stores messages in a way TaskMind does not know yet.")
        } catch (e: SecurityException) {
            fail("TaskMind is not allowed to read the backup. Allow All files access on the Health screen, or choose the file yourself.")
        } catch (t: Throwable) {
            val text = t.message.orEmpty()
            fail(
                if (text.contains("ENOSPC") || text.contains("No space", ignoreCase = true)) {
                    "The phone ran out of free space while unlocking the backup. Free a few gigabytes and try again."
                } else {
                    "The import stopped: ${t.javaClass.simpleName}${if (text.isBlank()) "" else " - ${text.take(160)}"}"
                },
            )
        } finally {
            runCatching { scratchDir.deleteRecursively() }
            running.unlock()
        }
    }

    private val prefs get() = context.getSharedPreferences("wa_backup_refresh", Context.MODE_PRIVATE)

    /** When the backup last read was written by WhatsApp, and when it was read; 0 when never. */
    fun lastBackupRead(): Long = prefs.getLong(K_LAST_BACKUP, 0L)
    fun lastRefresh(): Long = prefs.getLong(K_LAST_RUN, 0L)

    /**
     * v1.36.1: reads WhatsApp's backup again on its own, once WhatsApp has
     * written a newer one (it backs up every night by default). Needs the key
     * from an earlier import and All files access. Only new messages are
     * added; nothing here makes a task or an AI call. Returns true when it ran.
     */
    suspend fun refreshIfNewer(): Boolean {
        val key = container.secretStore.waBackupKey
        if (key.isBlank()) return false
        val file = runCatching { findBackup() }.getOrNull() ?: return false
        if (file.lastModified() <= lastBackupRead()) return false
        run(key)
        return _state.value is State.Done
    }

    private suspend fun fail(message: String) {
        _state.value = State.Failed(message)
        container.logger.write(Stage.CAPTURE, LogLevel.WARN, "WhatsApp backup import stopped", message)
    }

    fun dismiss() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    // ------------------------------------------------------ reading the tables

    private class UnknownLayout(message: String) : Exception(message)

    private fun readInto(scratch: File): State.Done {
        _state.value = State.Working("Reading your chats", 0f)
        val db = SQLiteDatabase.openDatabase(scratch.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            return when {
                "message" in tables && "chat" in tables && "jid" in tables -> readModern(db)
                "messages" in tables -> readLegacy(db)
                else -> throw UnknownLayout(
                    "The backup opened, but its message tables were not where TaskMind expects them " +
                        "(found: ${tables.sorted().take(12).joinToString()}). Send this line with a diagnostic report.",
                )
            }
        } finally {
            db.close()
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndex("name")
            buildSet { while (c.moveToNext()) add(c.getString(name)) }
        }

    /** The layout WhatsApp has used since 2021-22: message, chat and jid tables. */
    private fun readModern(db: SQLiteDatabase): State.Done {
        val cols = columns(db, "message")
        val needed = listOf("_id", "chat_row_id", "from_me", "timestamp", "text_data")
        val missing = needed.filter { it !in cols }
        if (missing.isNotEmpty()) {
            throw UnknownLayout("The backup opened, but its message table has no ${missing.joinToString()} column. Send this line with a diagnostic report.")
        }
        val hasSender = "sender_jid_row_id" in cols
        val hasKeyId = "key_id" in cols

        val contacts = contactNames()
        val jids = HashMap<Long, String>()
        db.rawQuery("SELECT _id, raw_string FROM jid", null).use { c ->
            while (c.moveToNext()) jids[c.getLong(0)] = c.getString(1).orEmpty()
        }
        // Newer versions hide numbers behind "lid" ids, with a table mapping each back.
        val lidToJid = HashMap<Long, Long>()
        runCatching {
            db.rawQuery("SELECT lid_row_id, jid_row_id FROM jid_map", null).use { c ->
                while (c.moveToNext()) lidToJid[c.getLong(0)] = c.getLong(1)
            }
        }
        fun person(jidRow: Long?): String {
            if (jidRow == null) return UNKNOWN
            val raw = jids[lidToJid[jidRow] ?: jidRow].orEmpty()
            val user = raw.substringBefore('@')
            if (raw.endsWith("@lid") || user.isBlank() || !user.all { it.isDigit() }) return UNKNOWN
            return contacts[user.takeLast(10)] ?: "+$user"
        }

        class Chat(val name: String, val group: Boolean, val skip: Boolean)
        val chats = HashMap<Long, Chat>()
        val chatCols = columns(db, "chat")
        val subject = if ("subject" in chatCols) "subject" else "NULL"
        db.rawQuery("SELECT _id, jid_row_id, $subject FROM chat", null).use { c ->
            while (c.moveToNext()) {
                val jidRow = if (c.isNull(1)) null else c.getLong(1)
                val raw = jidRow?.let { jids[it] }.orEmpty()
                val group = raw.endsWith("@g.us")
                val skip = raw.endsWith("@broadcast") || raw.endsWith("@newsletter") || raw.isBlank()
                val name = if (group) c.getString(2)?.takeIf { it.isNotBlank() } ?: "A group" else person(jidRow)
                chats[c.getLong(0)] = Chat(name, group, skip)
            }
        }

        val total = db.rawQuery("SELECT COUNT(*) FROM message WHERE text_data IS NOT NULL AND text_data != ''", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        val select = "SELECT _id, chat_row_id, from_me, timestamp, text_data, " +
            (if (hasSender) "sender_jid_row_id" else "NULL") + ", " + (if (hasKeyId) "key_id" else "NULL") +
            " FROM message WHERE text_data IS NOT NULL AND text_data != '' AND _id > ? ORDER BY _id LIMIT $PAGE"

        var last = -1L
        var read = 0
        var added = 0
        val seenChats = HashSet<Long>()
        while (true) {
            val batch = ArrayList<MemoryStore.Message>(PAGE)
            var rows = 0
            db.rawQuery(select, arrayOf(last.toString())).use { c ->
                while (c.moveToNext()) {
                    rows++
                    last = c.getLong(0)
                    val chatRow = c.getLong(1)
                    val chat = chats[chatRow] ?: continue
                    if (chat.skip) continue
                    val text = c.getString(4)?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    val fromMe = c.getInt(2) != 0
                    val sender = when {
                        fromMe -> YOU
                        chat.group -> person(if (c.isNull(5)) null else c.getLong(5))
                        else -> chat.name
                    }
                    seenChats += chatRow
                    batch += MemoryStore.Message(
                        source = MemoryStore.SOURCE_WHATSAPP,
                        ext = c.getString(6)?.takeIf { it.isNotBlank() }?.let { "$chatRow:$it" } ?: "row:${c.getLong(0)}",
                        chat = chat.name,
                        isGroup = chat.group,
                        sender = sender,
                        fromMe = fromMe,
                        at = c.getLong(3),
                        body = text.take(MAX_BODY),
                    )
                }
            }
            if (rows == 0) break
            read += batch.size
            added += container.memoryStore.insertAll(batch)
            _state.value = State.Working("Reading your chats", if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null)
        }
        return State.Done(added = added, read = read, chats = seenChats.size)
    }

    /** The single "messages" table of older WhatsApp versions. */
    private fun readLegacy(db: SQLiteDatabase): State.Done {
        val cols = columns(db, "messages")
        val needed = listOf("_id", "key_remote_jid", "key_from_me", "data", "timestamp")
        val missing = needed.filter { it !in cols }
        if (missing.isNotEmpty()) {
            throw UnknownLayout("The backup opened, but its messages table has no ${missing.joinToString()} column. Send this line with a diagnostic report.")
        }
        val contacts = contactNames()
        fun person(raw: String?): String {
            val user = raw.orEmpty().substringBefore('@')
            if (user.isBlank() || !user.all { it.isDigit() }) return UNKNOWN
            return contacts[user.takeLast(10)] ?: "+$user"
        }
        val subjects = HashMap<String, String>()
        runCatching {
            db.rawQuery("SELECT key_remote_jid, subject FROM chat_list WHERE subject IS NOT NULL", null).use { c ->
                while (c.moveToNext()) subjects[c.getString(0)] = c.getString(1)
            }
        }
        val resource = if ("remote_resource" in cols) "remote_resource" else "NULL"
        val keyId = if ("key_id" in cols) "key_id" else "NULL"
        val select = "SELECT _id, key_remote_jid, key_from_me, timestamp, data, $resource, $keyId FROM messages " +
            "WHERE data IS NOT NULL AND data != '' AND _id > ? ORDER BY _id LIMIT $PAGE"
        var last = -1L
        var read = 0
        var added = 0
        val seenChats = HashSet<String>()
        while (true) {
            val batch = ArrayList<MemoryStore.Message>(PAGE)
            var rows = 0
            db.rawQuery(select, arrayOf(last.toString())).use { c ->
                while (c.moveToNext()) {
                    rows++
                    last = c.getLong(0)
                    val jid = c.getString(1).orEmpty()
                    if (jid.isBlank() || jid.endsWith("@broadcast")) continue
                    val text = c.getString(4)?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    val group = jid.endsWith("@g.us")
                    val chat = if (group) subjects[jid] ?: "A group" else person(jid)
                    val fromMe = c.getInt(2) != 0
                    seenChats += jid
                    batch += MemoryStore.Message(
                        source = MemoryStore.SOURCE_WHATSAPP,
                        ext = c.getString(6)?.takeIf { it.isNotBlank() }?.let { "$jid:$it" } ?: "row:${c.getLong(0)}",
                        chat = chat,
                        isGroup = group,
                        sender = if (fromMe) YOU else if (group) person(c.getString(5)) else chat,
                        fromMe = fromMe,
                        at = c.getLong(3),
                        body = text.take(MAX_BODY),
                    )
                }
            }
            if (rows == 0) break
            read += batch.size
            added += container.memoryStore.insertAll(batch)
            _state.value = State.Working("Reading your chats", null)
        }
        return State.Done(added = added, read = read, chats = seenChats.size)
    }

    /** The phone book by the last ten digits of each number; empty without the contacts permission. */
    private fun contactNames(): Map<String, String> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyMap()
        return runCatching {
            val out = HashMap<String, String>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val digits = c.getString(0).orEmpty().filter { it.isDigit() }
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val key = digits.takeLast(10)
                    if (digits.length >= 7 && key !in out) out[key] = name
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val K_LAST_BACKUP = "last_backup_mtime"
        const val K_LAST_RUN = "last_run"
        const val PAGE = 2000
        const val MAX_BODY = 8000
        const val YOU = "You"
        const val UNKNOWN = "Unknown contact"
    }
}
