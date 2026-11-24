package com.blackstrings

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.torproject.jni.TorService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages the Tor v3 Hidden Service, SOCKS5 outbound P2P/Mailbox transport,
 * local Hidden Service HTTP listener, and the 30-second active sync / sleep battery cycle.
 */
class TorManager(
    private val context: Context,
    private val crypto: CryptoManager,
    private val outbox: OutboxManager,
    private val mailbox: MailboxManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("bs_tor_prefs", Context.MODE_PRIVATE)

    @Volatile var torStatus: String = "BOOTSTRAPPING" // BOOTSTRAPPING, SYNCING, SLEEPING
        private set

    @Volatile var socksPort: Int = 9050
        private set

    private val localServicePort = 18742
    private var serverSocket: ServerSocket? = null
    private var listenerJob: Job? = null
    private var sleepJob: Job? = null
    private var torService: TorService? = null
    private var isBound = false

    var onUiUpdate: ((String) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TorService.LocalBinder
            torService = binder?.service
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            isBound = false
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            when (status) {
                TorService.STATUS_ON -> {
                    torStatus = "SYNCING"
                    readRealOnionHostname()
                    notifyUi("TOR_STATUS")
                    scope.launch {
                        flushOutbox()
                        fetchFromConfiguredMailboxes()
                    }
                    scheduleSleepAfter30Seconds()
                }
                TorService.STATUS_STARTING -> {
                    torStatus = "BOOTSTRAPPING"
                    notifyUi("TOR_STATUS")
                }
                TorService.STATUS_OFF -> {
                    torStatus = "SLEEPING"
                    notifyUi("TOR_STATUS")
                }
            }
        }
    }

    fun getMyOnionId(): String {
        val real = readRealOnionHostname()
        if (real.isNotBlank()) return real
        val cached = prefs.getString("onion_id", "") ?: ""
        if (cached.isNotBlank()) return cached
        // Derive deterministic 56-char base32 v3 placeholder from identity pubkey until Tor daemon writes hostname
        val digest = MessageDigest.getInstance("SHA-256").digest(crypto.getPublicKeyB64().toByteArray())
        val base32 = encodeBase32(digest + digest.copyOfRange(0, 3)).lowercase().take(56)
        val generated = "$base32.onion"
        prefs.edit().putString("onion_id", generated).apply()
        return generated
    }

    private fun readRealOnionHostname(): String {
        val candidates = listOf(
            File(context.filesDir, "tor/hidden_service/hostname"),
            File(context.getDir("tor", Context.MODE_PRIVATE), "hidden_service/hostname")
        )
        for (f in candidates) {
            if (f.exists()) {
                val host = f.readText().trim().lowercase()
                if (host.endsWith(".onion")) {
                    prefs.edit().putString("onion_id", host).apply()
                    return host
                }
            }
        }
        return ""
    }

    fun startAndSync30s() {
        startLocalHiddenServiceListener()
        configureTorrc()
        torStatus = "BOOTSTRAPPING"
        notifyUi("TOR_STATUS")

        val filter = IntentFilter(TorService.ACTION_STATUS)
        ContextCompat.registerReceiver(context, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val intent = Intent(context, TorService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Ensure outbox flushes even if Tor is already ON
        scope.launch {
            delay(2500)
            flushOutbox()
            fetchFromConfiguredMailboxes()
        }
        scheduleSleepAfter30Seconds()
    }

    fun scheduleSleepAfter30Seconds() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(30_000L)
            sleepTor()
        }
    }

    fun wakeForSyncAndSleep(durationMs: Long = 30_000L) {
        torStatus = "SYNCING"
        notifyUi("TOR_STATUS")
        startLocalHiddenServiceListener()
        val intent = Intent(context, TorService::class.java)
        context.startService(intent)
        scope.launch {
            flushOutbox()
            fetchFromConfiguredMailboxes()
        }
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(durationMs)
            sleepTor()
        }
    }

    fun sleepTor() {
        torStatus = "SLEEPING"
        notifyUi("TOR_STATUS")
        try {
            if (isBound) {
                context.unbindService(serviceConnection)
                isBound = false
            }
            context.stopService(Intent(context, TorService::class.java))
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        sleepJob?.cancel()
        listenerJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        sleepTor()
    }

    private fun configureTorrc() {
        val torDir = File(context.filesDir, "tor").apply { mkdirs() }
        val hsDir = File(torDir, "hidden_service").apply { mkdirs() }
        val torrc = File(torDir, "torrc")
        torrc.writeText(
            """
            SocksPort $socksPort
            HiddenServiceDir ${hsDir.absolutePath}
            HiddenServiceVersion 3
            HiddenServicePort 80 127.0.0.1:$localServicePort
            """.trimIndent()
        )
    }

    private fun startLocalHiddenServiceListener() {
        if (listenerJob?.isActive == true) return
        listenerJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(localServicePort, 50, InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleIncomingSocket(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleIncomingSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val rawPath = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val bodyChars = CharArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            val body = String(bodyChars, 0, readTotal)

            val responseJson = when {
                method == "POST" && rawPath == "/msg" -> handleIncomingMessage(body)
                method == "POST" && rawPath == "/mailbox/store" -> handleMailboxStore(body)
                method == "GET" && rawPath.startsWith("/mailbox/fetch") -> handleMailboxFetch(rawPath)
                method == "POST" && rawPath == "/mailbox/ack" -> handleMailboxAck(body)
                else -> JSONObject().put("ok", false).put("error", "not_found").toString()
            }

            val writer = OutputStreamWriter(s.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${responseJson.toByteArray().size}\r\nConnection: close\r\n\r\n$responseJson")
            writer.flush()
        }
    }

    private fun handleIncomingMessage(body: String): String {
        return try {
            val packet = JSONObject(body)
            val senderOnion = packet.getString("senderOnion").lowercase()
            val envelope = packet.getString("envelope")
            processDecryptedPacket(senderOnion, envelope)
            JSONObject().put("ok", true).put("delivered", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "decrypt_error").toString()
        }
    }

    private fun processDecryptedPacket(senderOnion: String, envelope: String) {
        val friend = outbox.getFriend(senderOnion)
        val peerPub = friend?.optString("pubKey", "") ?: ""
        if (friend == null) {
            outbox.upsertFriend(senderOnion, senderOnion.take(12), "", "")
        }

        val plainBytes = crypto.decryptFromPeer(senderOnion, peerPub, envelope)
        val payload = JSONObject(String(plainBytes, Charsets.UTF_8))

        val msgId = payload.optString("id", UUID.randomUUID().toString())
        val msgType = payload.optString("type", "text")
        val text = payload.optString("text", "")
        val replyToId = payload.optString("replyToId", "")
        val replyPreview = payload.optString("replyPreview", "")
        val mediaName = payload.optString("mediaName", "")
        val mediaDuration = payload.optInt("mediaDuration", 0)
        val timestamp = payload.optLong("timestamp", System.currentTimeMillis())

        var savedMediaPath = ""
        if (payload.has("mediaCipher") && payload.optString("mediaCipher").isNotEmpty()) {
            val decryptedMedia = crypto.decryptMediaBytes(
                payload.getString("mediaKey"),
                payload.getString("mediaIv"),
                payload.getString("mediaCipher")
            )
            val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
            val ext = mediaName.substringAfterLast('.', "bin")
            val outFile = File(mediaDir, "${msgId}.$ext")
            outFile.writeBytes(decryptedMedia)
            savedMediaPath = outFile.absolutePath
        }

        outbox.saveMessage(
            id = msgId,
            peerOnion = senderOnion,
            direction = "in",
            msgType = msgType,
            textBody = text,
            mediaPath = savedMediaPath,
            mediaName = mediaName,
            mediaDuration = mediaDuration,
            replyToId = replyToId,
            replyPreview = replyPreview,
            status = "DELIVERED",
            timestamp = timestamp
        )
        notifyUi("NEW_MESSAGE:$senderOnion")
    }

    private fun handleMailboxStore(body: String): String {
        val req = JSONObject(body)
        val ok = mailbox.storeEncryptedPacket(
            packetId = req.getString("packetId"),
            recipientOnion = req.getString("recipientOnion"),
            senderOnion = req.getString("senderOnion"),
            encryptedBlob = req.getString("encryptedBlob")
        )
        return JSONObject().put("ok", ok).toString()
    }

    private fun handleMailboxFetch(rawPath: String): String {
        val onion = rawPath.substringAfter("onion=", "").substringBefore("&").lowercase()
        val packets = mailbox.fetchPacketsFor(onion)
        return JSONObject().put("ok", true).put("packets", packets).toString()
    }

    private fun handleMailboxAck(body: String): String {
        val req = JSONObject(body)
        val arr = req.optJSONArray("packetIds") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getString(it) }
        mailbox.acknowledgePackets(ids)
        return JSONObject().put("ok", true).toString()
    }

    /**
     * Flushes all due PENDING messages from SQLite outbox.
     * 1. Tries direct P2P Tor delivery to peer's .onion.
     * 2. If peer is offline, falls back to peer's Mailbox .onion (store-and-forward).
     * 3. If both fail, keeps message in SQLite OUTBOX and schedules exponential backoff (1m, 5m, 15m, 1h).
     */
    fun flushOutbox() {
        val due = outbox.getDuePendingOutbox()
        if (due.isEmpty()) return
        val myOnion = getMyOnionId()

        for (item in due) {
            val directPacket = JSONObject().apply {
                put("senderOnion", myOnion)
                put("envelope", item.encryptedEnvelope)
            }.toString()

            val directRes = sendOverTor(item.peerOnion, "POST", "/msg", directPacket)
            if (directRes != null && directRes.optBoolean("ok", false)) {
                outbox.markOutboxSent(item.id, delivered = true)
                notifyUi("MSG_STATUS:${item.id}:DELIVERED")
                continue
            }

            // Receiver offline -> try Mailbox relay if configured
            val relayOnion = item.mailboxOnion.ifBlank { mailbox.getDefaultRelayOnion() }
            if (relayOnion.isNotBlank() && relayOnion.endsWith(".onion")) {
                val relayPacket = JSONObject().apply {
                    put("packetId", item.id)
                    put("recipientOnion", item.peerOnion)
                    put("senderOnion", myOnion)
                    put("encryptedBlob", item.encryptedEnvelope)
                }.toString()
                val relayRes = sendOverTor(relayOnion, "POST", "/mailbox/store", relayPacket)
                if (relayRes != null && relayRes.optBoolean("ok", false)) {
                    outbox.markOutboxSent(item.id, delivered = false) // SENT to mailbox, awaiting pickup
                    notifyUi("MSG_STATUS:${item.id}:SENT")
                    continue
                }
            }

            // Still offline -> keep in OUTBOX DB with exponential backoff
            outbox.markOutboxRetry(item.id, item.retryCount)
        }
    }

    private fun fetchFromConfiguredMailboxes() {
        val myOnion = getMyOnionId()
        val relays = mutableSetOf<String>()
        val def = mailbox.getDefaultRelayOnion()
        if (def.endsWith(".onion")) relays.add(def)

        val friends = outbox.getAllFriendsWithLastMessage()
        for (i in 0 until friends.length()) {
            val m = friends.getJSONObject(i).optString("mailboxOnion", "")
            if (m.endsWith(".onion")) relays.add(m)
        }

        for (relay in relays) {
            val res = sendOverTor(relay, "GET", "/mailbox/fetch?onion=$myOnion", "") ?: continue
            val packets = res.optJSONArray("packets") ?: continue
            val ackIds = mutableListOf<String>()
            for (i in 0 until packets.length()) {
                val p = packets.getJSONObject(i)
                try {
                    processDecryptedPacket(p.getString("senderOnion"), p.getString("encryptedBlob"))
                    ackIds.add(p.getString("packetId"))
                } catch (_: Exception) {
                }
            }
            if (ackIds.isNotEmpty()) {
                val ackBody = JSONObject().put("packetIds", JSONArray(ackIds)).toString()
                sendOverTor(relay, "POST", "/mailbox/ack", ackBody)
            }
        }
    }

    private fun sendOverTor(targetOnion: String, method: String, path: String, jsonBody: String): JSONObject? {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val socket = Socket(proxy)
            socket.use { s ->
                s.soTimeout = 20_000
                s.connect(InetSocketAddress.createUnresolved(targetOnion, 80), 20_000)
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                val req = buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: $targetOnion\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                s.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
                if (bytes.isNotEmpty()) s.getOutputStream().write(bytes)
                s.getOutputStream().flush()

                val rawResp = s.getInputStream().bufferedReader().readText()
                val bodyPart = rawResp.substringAfter("\r\n\r\n", "")
                if (bodyPart.isNotBlank()) JSONObject(bodyPart) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyUi(event: String) {
        onUiUpdate?.invoke(event)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(alphabet[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        return out.toString()
    }
}
