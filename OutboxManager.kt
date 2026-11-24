package com.blackstrings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local SQLite storage for:
 * 1. OUTBOX queue (status = PENDING / SENT / DELIVERED) with exponential backoff (1m, 5m, 15m, 1h)
 * 2. MESSAGES history (text, image, video, file, voice)
 * 3. FRIENDS directory (.onion ID, display name, X25519 pubkey, optional mailbox .onion)
 */
class OutboxManager(context: Context) : SQLiteOpenHelper(context, "blackstrings.db", null, 1) {

    companion object {
        val BACKOFF_MS = longArrayOf(60_000L, 300_000L, 900_000L, 3_600_000L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS friends (
                onion_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                pub_key TEXT DEFAULT '',
                mailbox_onion TEXT DEFAULT '',
                added_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                peer_onion TEXT NOT NULL,
                direction TEXT NOT NULL,
                msg_type TEXT NOT NULL,
                text_body TEXT DEFAULT '',
                media_path TEXT DEFAULT '',
                media_name TEXT DEFAULT '',
                media_duration INTEGER DEFAULT 0,
                reply_to_id TEXT DEFAULT '',
                reply_preview TEXT DEFAULT '',
                status TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outbox (
                id TEXT PRIMARY KEY,
                peer_onion TEXT NOT NULL,
                mailbox_onion TEXT DEFAULT '',
                encrypted_envelope TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                retry_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS mailbox_store (
                packet_id TEXT PRIMARY KEY,
                recipient_onion TEXT NOT NULL,
                sender_onion TEXT NOT NULL,
                encrypted_blob TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    // --- Friends ---
    fun upsertFriend(onionId: String, name: String, pubKey: String = "", mailboxOnion: String = "") {
        val cv = ContentValues().apply {
            put("onion_id", onionId.trim().lowercase())
            put("display_name", name.trim().ifEmpty { onionId.take(12) })
            put("pub_key", pubKey.trim())
            put("mailbox_onion", mailboxOnion.trim().lowercase())
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("friends", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getFriend(onionId: String): JSONObject? {
        readableDatabase.rawQuery(
            "SELECT onion_id, display_name, pub_key, mailbox_onion, added_at FROM friends WHERE onion_id = ?",
            arrayOf(onionId.trim().lowercase())
        ).use { c ->
            if (c.moveToFirst()) {
                return JSONObject().apply {
                    put("onionId", c.getString(0))
                    put("name", c.getString(1))
                    put("pubKey", c.getString(2))
                    put("mailboxOnion", c.getString(3))
                    put("addedAt", c.getLong(4))
                }
            }
        }
        return null
    }

    fun getAllFriendsWithLastMessage(): JSONArray {
        val arr = JSONArray()
        val sql = """
            SELECT f.onion_id, f.display_name, f.pub_key, f.mailbox_onion,
                   (SELECT text_body FROM messages m WHERE m.peer_onion = f.onion_id ORDER BY m.timestamp DESC LIMIT 1) AS last_text,
                   (SELECT msg_type FROM messages m WHERE m.peer_onion = f.onion_id ORDER BY m.timestamp DESC LIMIT 1) AS last_type,
                   (SELECT timestamp FROM messages m WHERE m.peer_onion = f.onion_id ORDER BY m.timestamp DESC LIMIT 1) AS last_ts,
                   (SELECT status FROM messages m WHERE m.peer_onion = f.onion_id ORDER BY m.timestamp DESC LIMIT 1) AS last_status
            FROM friends f
            ORDER BY COALESCE(last_ts, f.added_at) DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("onionId", c.getString(0))
                    put("name", c.getString(1))
                    put("pubKey", c.getString(2))
                    put("mailboxOnion", c.getString(3))
                    put("lastText", c.getString(4) ?: "")
                    put("lastType", c.getString(5) ?: "text")
                    put("lastTs", if (c.isNull(6)) 0L else c.getLong(6))
                    put("lastStatus", c.getString(7) ?: "")
                })
            }
        }
        return arr
    }

    fun deleteFriend(onionId: String) {
        writableDatabase.delete("friends", "onion_id = ?", arrayOf(onionId))
        writableDatabase.delete("messages", "peer_onion = ?", arrayOf(onionId))
        writableDatabase.delete("outbox", "peer_onion = ?", arrayOf(onionId))
    }

    // --- Messages ---
    fun saveMessage(
        id: String,
        peerOnion: String,
        direction: String,
        msgType: String,
        textBody: String,
        mediaPath: String,
        mediaName: String,
        mediaDuration: Int,
        replyToId: String,
        replyPreview: String,
        status: String,
        timestamp: Long
    ) {
        val cv = ContentValues().apply {
            put("id", id)
            put("peer_onion", peerOnion.lowercase())
            put("direction", direction)
            put("msg_type", msgType)
            put("text_body", textBody)
            put("media_path", mediaPath)
            put("media_name", mediaName)
            put("media_duration", mediaDuration)
            put("reply_to_id", replyToId)
            put("reply_preview", replyPreview)
            put("status", status)
            put("timestamp", timestamp)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateMessageStatus(id: String, status: String) {
        val cv = ContentValues().apply { put("status", status) }
        writableDatabase.update("messages", cv, "id = ?", arrayOf(id))
    }

    fun deleteMessage(id: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
        writableDatabase.delete("outbox", "id = ?", arrayOf(id))
    }

    fun getMessageById(id: String): JSONObject? {
        readableDatabase.rawQuery(
            "SELECT id, peer_onion, direction, msg_type, text_body, media_path, media_name, media_duration, reply_to_id, reply_preview, status, timestamp FROM messages WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) return cursorToMessage(c)
        }
        return null
    }

    fun getMessages(peerOnion: String): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, peer_onion, direction, msg_type, text_body, media_path, media_name, media_duration, reply_to_id, reply_preview, status, timestamp FROM messages WHERE peer_onion = ? ORDER BY timestamp ASC",
            arrayOf(peerOnion.lowercase())
        ).use { c ->
            while (c.moveToNext()) {
                arr.put(cursorToMessage(c))
            }
        }
        return arr
    }

    private fun cursorToMessage(c: android.database.Cursor): JSONObject = JSONObject().apply {
        put("id", c.getString(0))
        put("peerOnion", c.getString(1))
        put("direction", c.getString(2))
        put("type", c.getString(3))
        put("text", c.getString(4))
        put("mediaPath", c.getString(5))
        put("mediaName", c.getString(6))
        put("mediaDuration", c.getInt(7))
        put("replyToId", c.getString(8))
        put("replyPreview", c.getString(9))
        put("status", c.getString(10))
        put("timestamp", c.getLong(11))
    }

    // --- Outbox Queue ---
    fun enqueueOutbox(id: String, peerOnion: String, mailboxOnion: String, encryptedEnvelope: String) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("peer_onion", peerOnion.lowercase())
            put("mailbox_onion", mailboxOnion.lowercase())
            put("encrypted_envelope", encryptedEnvelope)
            put("status", "PENDING")
            put("retry_count", 0)
            put("next_retry_at", now)
            put("created_at", now)
        }
        writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class OutboxItem(
        val id: String,
        val peerOnion: String,
        val mailboxOnion: String,
        val encryptedEnvelope: String,
        val retryCount: Int
    )

    fun getDuePendingOutbox(): List<OutboxItem> {
        val list = mutableListOf<OutboxItem>()
        val now = System.currentTimeMillis()
        readableDatabase.rawQuery(
            "SELECT id, peer_onion, mailbox_onion, encrypted_envelope, retry_count FROM outbox WHERE status = 'PENDING' AND next_retry_at <= ? ORDER BY created_at ASC",
            arrayOf(now.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    OutboxItem(
                        id = c.getString(0),
                        peerOnion = c.getString(1),
                        mailboxOnion = c.getString(2),
                        encryptedEnvelope = c.getString(3),
                        retryCount = c.getInt(4)
                    )
                )
            }
        }
        return list
    }

    fun getPendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun markOutboxRetry(id: String, currentRetryCount: Int) {
        val idx = currentRetryCount.coerceIn(0, BACKOFF_MS.lastIndex)
        val nextAt = System.currentTimeMillis() + BACKOFF_MS[idx]
        val cv = ContentValues().apply {
            put("retry_count", currentRetryCount + 1)
            put("next_retry_at", nextAt)
        }
        writableDatabase.update("outbox", cv, "id = ?", arrayOf(id))
    }

    fun markOutboxSent(id: String, delivered: Boolean) {
        val newStatus = if (delivered) "DELIVERED" else "SENT"
        writableDatabase.delete("outbox", "id = ?", arrayOf(id))
        updateMessageStatus(id, newStatus)
    }
}
