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

    private val helper = object : SQLiteOpenHelper(context.applicationContext, FILE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
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

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
    fun search(terms: List<String>, limit: Int, from: Long? = null, until: Long? = null): List<Message> {
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
        sql.append(" ORDER BY m.at DESC LIMIT ").append(limit.coerceIn(1, 5000))
        return helper.readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
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
    }

    /** Forgets everything, and gives the space back. */
    fun clear() {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM messages_fts")
        runCatching { db.execSQL("VACUUM") }
    }

    companion object {
        const val SOURCE_WHATSAPP = "whatsapp"
        const val SOURCE_CAPTURE = "capture"
        private const val FILE = "taskmind_memory.db"
        private const val MAX_TERMS = 12
    }
}
