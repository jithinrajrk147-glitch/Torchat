package com.blackstrings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * JavascriptInterface exposed to the WebView SPA as `window.BlackstringsBridge`.
 */
class WebViewBridge(
    private val activity: MainActivity,
    private val torManager: TorManager,
    private val cryptoManager: CryptoManager,
    private val outboxManager: OutboxManager,
    private val mailboxManager: MailboxManager
) {
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private var currentVoiceFile: File? = null
    private var recordStartMs: Long = 0L

    @JavascriptInterface
    fun getIdentity(): String {
        return JSONObject().apply {
            put("onionId", torManager.getMyOnionId())
            put("pubKey", cryptoManager.getPublicKeyB64())
            put("mailboxEnabled", mailboxManager.isMailboxModeEnabled())
            put("defaultRelay", mailboxManager.getDefaultRelayOnion())
            put("mailboxCount", mailboxManager.getStoredCount())
            put("torStatus", torManager.torStatus)
            put("pendingCount", outboxManager.getPendingCount())
        }.toString()
    }

    @JavascriptInterface
    fun generateQrSvg(text: String): String {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 220, 220)
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" width=\"180\" height=\"180\" shape-rendering=\"crispEdges\">")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#0d1117\"/>")
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    sb.append("<rect x=\"$x\" y=\"$y\" width=\"1\" height=\"1\" fill=\"#00c97e\"/>")
                }
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    @JavascriptInterface
    fun getFriends(): String = outboxManager.getAllFriendsWithLastMessage().toString()

    @JavascriptInterface
    fun addFriend(onionId: String, name: String, pubKey: String, mailboxOnion: String): String {
        val clean = onionId.trim().lowercase()
        if (!clean.endsWith(".onion") || clean.length < 16) {
            return JSONObject().put("ok", false).put("error", "Invalid v3 .onion address").toString()
        }
        outboxManager.upsertFriend(clean, name, pubKey, mailboxOnion)
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun deleteFriend(onionId: String) {
        outboxManager.deleteFriend(onionId.trim().lowercase())
    }

    @JavascriptInterface
    fun getMessages(peerOnion: String): String {
        val arr = outboxManager.getMessages(peerOnion)
        // Convert local mediaPath to data URI for seamless WebView rendering
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val path = m.optString("mediaPath", "")
            if (path.isNotEmpty()) {
                val f = File(path)
                if (f.exists() && f.length() < 12 * 1024 * 1024) {
                    val mime = guessMime(m.optString("type", "file"), m.optString("mediaName", ""))
                    val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    m.put("mediaDataUrl", "data:$mime;base64,$b64")
                }
            }
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun sendMessage(
        peerOnion: String,
        type: String,
        text: String,
        mediaPath: String,
        mediaName: String,
        mediaDuration: Int,
        replyToId: String,
        replyPreview: String
    ): String {
        val cleanPeer = peerOnion.trim().lowercase()
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Save immediately in local SQLite messages + OUTBOX with status=PENDING
        outboxManager.saveMessage(
            id = msgId,
            peerOnion = cleanPeer,
            direction = "out",
            msgType = type,
            textBody = text,
            mediaPath = mediaPath,
            mediaName = mediaName,
            mediaDuration = mediaDuration,
            replyToId = replyToId,
            replyPreview = replyPreview,
            status = "PENDING",
            timestamp = now
        )

        ioScope.launch {
            val friend = outboxManager.getFriend(cleanPeer)
            val peerPub = friend?.optString("pubKey", "") ?: ""
            val mailboxOnion = friend?.optString("mailboxOnion", "") ?: ""

            val innerPayload = JSONObject().apply {
                put("id", msgId)
                put("type", type)
                put("text", text)
                put("mediaName", mediaName)
                put("mediaDuration", mediaDuration)
                put("replyToId", replyToId)
                put("replyPreview", replyPreview)
                put("timestamp", now)
                if (mediaPath.isNotBlank()) {
                    val f = File(mediaPath)
                    if (f.exists()) {
                        val (mk, miv, mct) = cryptoManager.encryptMediaBytes(f.readBytes())
                        put("mediaKey", mk)
                        put("mediaIv", miv)
                        put("mediaCipher", mct)
                    }
                }
            }

            val envelope = cryptoManager.encryptForPeer(
                cleanPeer,
                peerPub,
                innerPayload.toString().toByteArray(Charsets.UTF_8)
            )
            outboxManager.enqueueOutbox(msgId, cleanPeer, mailboxOnion, envelope)
            torManager.wakeForSyncAndSleep(30_000L)
        }

        return JSONObject().put("ok", true).put("id", msgId).toString()
    }

    @JavascriptInterface
    fun forwardMessage(targetOnion: String, sourceMsgId: String): String {
        val src = outboxManager.getMessageById(sourceMsgId)
            ?: return JSONObject().put("ok", false).toString()
        return sendMessage(
            peerOnion = targetOnion,
            type = src.optString("type", "text"),
            text = src.optString("text", ""),
            mediaPath = src.optString("mediaPath", ""),
            mediaName = src.optString("mediaName", ""),
            mediaDuration = src.optInt("mediaDuration", 0),
            replyToId = "",
            replyPreview = ""
        )
    }

    @JavascriptInterface
    fun deleteMessage(msgId: String) {
        outboxManager.deleteMessage(msgId)
    }

    @JavascriptInterface
    fun setMailboxMode(enabled: Boolean) {
        mailboxManager.setMailboxModeEnabled(enabled)
    }

    @JavascriptInterface
    fun setDefaultRelay(onion: String) {
        mailboxManager.setDefaultRelayOnion(onion)
    }

    @JavascriptInterface
    fun triggerSyncNow() {
        torManager.wakeForSyncAndSleep(30_000L)
    }

    @JavascriptInterface
    fun copyText(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BLACKSTRINGS", text))
    }

    @JavascriptInterface
    fun pickMedia(kind: String) {
        activity.runOnUiThread { activity.launchMediaPicker(kind) }
    }

    @JavascriptInterface
    fun scanQrCode() {
        activity.runOnUiThread { activity.launchQrScanner() }
    }

    @JavascriptInterface
    fun startVoiceRecord(): Boolean {
        return try {
            val dir = File(activity.filesDir, "media").apply { mkdirs() }
            val out = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            currentVoiceFile = out
            recordStartMs = System.currentTimeMillis()
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(activity)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun stopVoiceRecord(): String {
        val durationSec = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt().coerceAtLeast(1)
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            val f = currentVoiceFile
            if (f != null && f.exists()) {
                JSONObject().apply {
                    put("ok", true)
                    put("path", f.absolutePath)
                    put("name", f.name)
                    put("duration", durationSec)
                }.toString()
            } else {
                JSONObject().put("ok", false).toString()
            }
        } catch (_: Exception) {
            recorder = null
            JSONObject().put("ok", false).toString()
        }
    }

    private fun guessMime(type: String, name: String): String = when (type) {
        "image" -> if (name.endsWith(".png", true)) "image/png" else "image/jpeg"
        "video" -> "video/mp4"
        "voice" -> "audio/mp4"
        else -> "application/octet-stream"
    }
}
