package com.taskmind.data.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The long-term memory: every message TaskMind has been given, kept for good.
 *
 * Raw captures are a work queue and are purged on a schedule; a twin that
 * forgets after thirty days is not one. Messages imported from a WhatsApp
 * backup, and captures on their way out at the retention purge, are kept here
 * instead - who said it, in which chat, when, and the words - with a full-text
 * index so "what did we agree with the vendor in August" can be answered.
 *
 * A database file of its own, not more tables in the main one: it can grow to
 * hundreds of thousands of rows, it has no migration history to share, and a
 * mistake here cannot stop the task list from opening.
 *
 * It never leaves the phone. Everything is blocking; call it off the main
 * thread.
 */
class MemoryStore(context: Context) {

    data class Message(
        /** Where it came from: [SOURCE_WHATSAPP] or [SOURCE_CAPTURE]. */
        val source: String,
        /** The source's own id for it, so importing twice stores it once. */
        val ext: String,
        /** The chat as the user knows it: a person's name, or a group's. */
        val chat: String,
        val isGroup: Boolean,
        /** Who said it; "You" when [fromMe]. */
        val sender: String,
        val fromMe: Boolean,
        val at: Long,
        val body: String,
    )

    data class Stats(val messages: Int, val chats: Int, val oldest: Long?, val newest: Long?)

    /** A stretch of one chat, the unit a vector is made for (see Chunker). */
    data class Chunk(val id: Long, val chat: String, val isGroup: Boolean, val startAt: Long, val endAt: Long, val text: String)

    data class ChunkStats(val chunks: Int, val withVectors: Int, val messagesLeft: Int)

    private val helper = object : SQLiteOpenHelper(context.applicationContext, FILE, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            createMessages(db)
            createChunks(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createChunks(db)
        }

        private fun createMessages(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, ext TEXT NOT NULL, " +
                    "chat TEXT NOT NULL, is_group INTEGER NOT NULL, sender TEXT NOT NULL, " +
                    "from_me INTEGER NOT NULL, at INTEGER NOT NULL, body TEXT NOT NULL, " +
                    "UNIQUE(source, ext))",
            )
            db.execSQL("CREATE INDEX messages_at ON messages(at)")
            db.execSQL("CREATE INDEX messages_chat ON messages(chat, at)")
            // unicode61 so Hindi in Devanagari is split into words like English is.
            db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(body, sender, chat, tokenize=unicode61)")
        }

        /** Version 2: stretches of chat and their vectors, and how far the cutting has got. */
        private fun createChunks(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE chunks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, is_group INTEGER NOT NULL, " +
                    "start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, first_id INTEGER NOT NULL, " +
                    "last_id INTEGER NOT NULL, text TEXT NOT NULL, vec BLOB)",
            )
            db.execSQL("CREATE INDEX chunks_pending ON chunks(vec) WHERE vec IS NULL")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        }
    }

    /** Stores what is new in [batch], in one transaction. Returns how many were new. */
    fun insertAll(batch: List<Message>): Int {
        if (batch.isEmpty()) return 0
        val db = helper.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val row = db.compileStatement(
                "INSERT OR IGNORE INTO messages (source, ext, chat, is_group, sender, from_me, at, body) VALUES (?,?,?,?,?,?,?,?)",
            )
            val index = db.compileStatement("INSERT INTO messages_fts (docid, body, sender, chat) VALUES (?,?,?,?)")
            for (m in batch) {
                row.clearBindings()
                row.bindString(1, m.source)
                row.bindString(2, m.ext)
                row.bindString(3, m.chat)
                row.bindLong(4, if (m.isGroup) 1 else 0)
                row.bindString(5, m.sender)
                row.bindLong(6, if (m.fromMe) 1 else 0)
                row.bindLong(7, m.at)
                row.bindString(8, m.body)
                val id = row.executeInsert()
                if (id <= 0) continue
                index.clearBindings()
                index.bindLong(1, id)
                index.bindString(2, m.body)
                index.bindString(3, m.sender)
                index.bindString(4, m.chat)
                index.executeInsert()
                added++
            }
            row.close()
            index.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun stats(): Stats = helper.readableDatabase
        .rawQuery("SELECT COUNT(*), COUNT(DISTINCT chat), MIN(at), MAX(at) FROM messages", null)
        .use { c ->
            if (!c.moveToFirst() || c.getInt(0) == 0) Stats(0, 0, null, null)
            else Stats(c.getInt(0), c.getInt(1), c.getLong(2), c.getLong(3))
        }

    /**
     * Messages containing any of [terms] - whole words or their beginnings -
     * newest first, optionally only those between [from] and [until]. The
     * caller ranks them; this only narrows hundreds of thousands to a few
     * hundred.
     */
    fun search(terms: List<String>, limit: Int, from: Long? = null, until: Long? = null, chat: String? = null): List<Message> {
        val words = terms.map { t -> t.filter { it.isLetterOrDigit() } }.filter { it.length >= 2 }.distinct().take(MAX_TERMS)
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" OR ") { "$it*" }
        val sql = StringBuilder(
            "SELECT m.source, m.ext, m.chat, m.is_group, m.sender, m.from_me, m.at, m.body " +
                "FROM messages_fts f JOIN messages m ON m.id = f.docid WHERE messages_fts MATCH ?",
        )
        val args = mutableListOf(match)
        if (from != null) {
            sql.append(" AND m.at >= ?")
            args += from.toString()
        }
        if (until != null) {
            sql.append(" AND m.at < ?")
            args += until.toString()
        }
        if (chat != null) {
            sql.append(" AND m.chat = ?")
            args += chat
        }
        sql.append(" ORDER BY m.at DESC LIMIT ").append(limit.coerceIn(1, 5000))
        return messages(sql.toString(), args.toTypedArray())
    }

    private fun messages(sql: String, args: Array<String>): List<Message> =
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Message>(c.count)
            while (c.moveToNext()) {
                out += Message(
                    source = c.getString(0),
                    ext = c.getString(1),
                    chat = c.getString(2),
                    isGroup = c.getInt(3) != 0,
                    sender = c.getString(4),
                    fromMe = c.getInt(5) != 0,
                    at = c.getLong(6),
                    body = c.getString(7),
                )
            }
            out
        }

    // ------------------------------------------------------------- chats

    /** One chat in the memory, for a person's page and the chat filter. */
    data class ChatInfo(
        val chat: String,
        val isGroup: Boolean,
        val messages: Int,
        val mine: Int,
        val first: Long,
        val last: Long,
    )

    /**
     * Chats whose name matches [name]: an exact match (any case) first, then
     * chats whose name contains it, most messages first. Groups are included
     * only when [groups] is true.
     */
    fun chatsNamed(name: String, groups: Boolean = false, limit: Int = 6): List<ChatInfo> {
        val n = name.trim()
        if (n.length < 2) return emptyList()
        val like = "%" + n.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = "SELECT chat, MAX(is_group), COUNT(*), SUM(from_me), MIN(at), MAX(at) FROM messages " +
            "WHERE chat LIKE ? ESCAPE '\\'" + (if (groups) "" else " AND is_group = 0") +
            " GROUP BY chat ORDER BY (LOWER(chat) = LOWER(?)) DESC, COUNT(*) DESC LIMIT " + limit.coerceIn(1, 50)
        return helper.readableDatabase.rawQuery(sql, arrayOf(like, n)).use { c ->
            val out = ArrayList<ChatInfo>(c.count)
            while (c.moveToNext()) {
                out += ChatInfo(c.getString(0), c.getInt(1) != 0, c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5))
            }
            out
        }
    }

    /** The chats with the most messages, for the profile sample. */
    fun busiestChats(since: Long, limit: Int): List<String> = helper.readableDatabase.rawQuery(
        "SELECT chat FROM messages WHERE at >= ? AND from_me = 1 GROUP BY chat ORDER BY COUNT(*) DESC LIMIT " + limit.coerceIn(1, 200),
        arrayOf(since.toString()),
    ).use { c ->
        val out = ArrayList<String>(c.count)
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    /** The newest [limit] messages of one chat, newest first. */
    fun recent(chat: String, limit: Int, fromMeOnly: Boolean = false, since: Long = 0): List<Message> = messages(
        "SELECT source, ext, chat, is_group, sender, from_me, at, body FROM messages WHERE chat = ? AND at >= ?" +
            (if (fromMeOnly) " AND from_me = 1" else "") + " ORDER BY at DESC LIMIT " + limit.coerceIn(1, 2000),
        arrayOf(chat, since.toString()),
    )

    /**
     * The conversation around one moment of [chat]: up to [before] messages
     * before [at], the moment itself, and up to [after] after it, oldest first.
     */
    fun around(chat: String, at: Long, before: Int = 8, after: Int = 8): List<Message> {
        val cols = "SELECT source, ext, chat, is_group, sender, from_me, at, body FROM messages WHERE chat = ? AND "
        val earlier = messages(cols + "at < ? ORDER BY at DESC LIMIT " + before.coerceIn(0, 200), arrayOf(chat, at.toString()))
        val later = messages(cols + "at >= ? ORDER BY at ASC LIMIT " + (after + 1).coerceIn(1, 201), arrayOf(chat, at.toString()))
        return earlier.reversed() + later
    }

    // ------------------------------------------------------------ chunks

    private fun meta(key: String): Long = helper.readableDatabase
        .rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key))
        .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Messages not yet cut into stretches, oldest stored first, at most [limit]. */
    fun messagesToChunk(limit: Int): List<com.taskmind.vector.Chunker.Message> {
        val after = meta(KEY_CHUNKED_THROUGH)
        return helper.readableDatabase.rawQuery(
            "SELECT id, chat, is_group, sender, at, body FROM messages WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(after.toString(), limit.toString()),
        ).use { c ->
            val out = ArrayList<com.taskmind.vector.Chunker.Message>(c.count)
            while (c.moveToNext()) {
                out += com.taskmind.vector.Chunker.Message(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getString(3), c.getLong(4), c.getString(5))
            }
            out
        }
    }

    /** Stores [chunks] and moves the cutting mark to [through], in one transaction. */
    fun addChunks(chunks: List<com.taskmind.vector.Chunker.Chunk>, through: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val row = db.compileStatement(
                "INSERT INTO chunks (chat, is_group, start_at, end_at, first_id, last_id, text) VALUES (?,?,?,?,?,?,?)",
            )
            for (c in chunks) {
                row.clearBindings()
                row.bindString(1, c.chat)
                row.bindLong(2, if (c.isGroup) 1 else 0)
                row.bindLong(3, c.startAt)
                row.bindLong(4, c.endAt)
                row.bindLong(5, c.firstId)
                row.bindLong(6, c.lastId)
                row.bindString(7, c.text)
                row.executeInsert()
            }
            row.close()
            db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf<Any>(KEY_CHUNKED_THROUGH, through))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stretches still waiting for a vector, at most [limit]. */
    fun chunksWithoutVectors(limit: Int): List<Chunk> = helper.readableDatabase.rawQuery(
        "SELECT id, chat, is_group, start_at, end_at, text FROM chunks WHERE vec IS NULL ORDER BY end_at DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use { c ->
        val out = ArrayList<Chunk>(c.count)
        while (c.moveToNext()) out += Chunk(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3), c.getLong(4), c.getString(5))
        out
    }

    fun setVectors(vectors: List<Pair<Long, ByteArray>>) {
        if (vectors.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val row = db.compileStatement("UPDATE chunks SET vec = ? WHERE id = ?")
            for ((id, vec) in vectors) {
                row.clearBindings()
                row.bindBlob(1, vec)
                row.bindLong(2, id)
                row.executeUpdateDelete()
            }
            row.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every vector, with the stretch it belongs to, for searching in memory. */
    fun allVectors(): List<Pair<Long, ByteArray>> = helper.readableDatabase
        .rawQuery("SELECT id, vec FROM chunks WHERE vec IS NOT NULL", null)
        .use { c ->
            val out = ArrayList<Pair<Long, ByteArray>>(c.count)
            while (c.moveToNext()) out += c.getLong(0) to c.getBlob(1)
            out
        }

    fun chunks(ids: Collection<Long>): List<Chunk> {
        if (ids.isEmpty()) return emptyList()
        return helper.readableDatabase.rawQuery(
            "SELECT id, chat, is_group, start_at, end_at, text FROM chunks WHERE id IN (${ids.joinToString(",")})",
            null,
        ).use { c ->
            val out = ArrayList<Chunk>(c.count)
            while (c.moveToNext()) out += Chunk(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3), c.getLong(4), c.getString(5))
            out
        }
    }

    fun chunkStats(): ChunkStats {
        val db = helper.readableDatabase
        val (total, done) = db.rawQuery("SELECT COUNT(*), COUNT(vec) FROM chunks", null).use { c ->
            c.moveToFirst()
            c.getInt(0) to c.getInt(1)
        }
        val left = db.rawQuery("SELECT COUNT(*) FROM messages WHERE id > ?", arrayOf(meta(KEY_CHUNKED_THROUGH).toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return ChunkStats(total, done, left)
    }

    /** Forgets everything, and gives the space back. */
    fun clear() {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM messages_fts")
        db.execSQL("DELETE FROM chunks")
        db.execSQL("DELETE FROM meta")
        runCatching { db.execSQL("VACUUM") }
    }

    companion object {
        const val SOURCE_WHATSAPP = "whatsapp"
        const val SOURCE_CAPTURE = "capture"
        private const val FILE = "taskmind_memory.db"
        private const val MAX_TERMS = 12
        private const val KEY_CHUNKED_THROUGH = "chunked_through"
    }
}
