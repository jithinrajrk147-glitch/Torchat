package com.blackstrings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store-and-Forward Mailbox / Relay Node.
 * Any user can enable Mailbox Mode to relay encrypted packets for offline friends.
 * The mailbox node only stores opaque E2E-encrypted blobs and cannot read them.
 */
class MailboxManager(private val context: Context, private val outbox: OutboxManager) {

    private val prefs = context.getSharedPreferences("bs_mailbox_prefs", Context.MODE_PRIVATE)

    fun isMailboxModeEnabled(): Boolean = prefs.getBoolean("mailbox_enabled", false)

    fun setMailboxModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mailbox_enabled", enabled).apply()
    }

    fun getDefaultRelayOnion(): String = prefs.getString("default_relay_onion", "") ?: ""

    fun setDefaultRelayOnion(onion: String) {
        prefs.edit().putString("default_relay_onion", onion.trim().lowercase()).apply()
    }

    fun storeEncryptedPacket(packetId: String, recipientOnion: String, senderOnion: String, encryptedBlob: String): Boolean {
        if (!isMailboxModeEnabled()) return false
        val cv = ContentValues().apply {
            put("packet_id", packetId)
            put("recipient_onion", recipientOnion.trim().lowercase())
            put("sender_onion", senderOnion.trim().lowercase())
            put("encrypted_blob", encryptedBlob)
            put("created_at", System.currentTimeMillis())
        }
        outbox.writableDatabase.insertWithOnConflict("mailbox_store", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    fun fetchPacketsFor(recipientOnion: String): JSONArray {
        val arr = JSONArray()
        outbox.readableDatabase.rawQuery(
            "SELECT packet_id, sender_onion, encrypted_blob, created_at FROM mailbox_store WHERE recipient_onion = ? ORDER BY created_at ASC",
            arrayOf(recipientOnion.trim().lowercase())
        ).use { c ->
            while (c.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("packetId", c.getString(0))
                    put("senderOnion", c.getString(1))
                    put("encryptedBlob", c.getString(2))
                    put("createdAt", c.getLong(3))
                })
            }
        }
        return arr
    }

    fun acknowledgePackets(packetIds: List<String>) {
        if (packetIds.isEmpty()) return
        val db = outbox.writableDatabase
        for (id in packetIds) {
            db.delete("mailbox_store", "packet_id = ?", arrayOf(id))
        }
    }

    fun getStoredCount(): Int {
        outbox.readableDatabase.rawQuery("SELECT COUNT(*) FROM mailbox_store", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
