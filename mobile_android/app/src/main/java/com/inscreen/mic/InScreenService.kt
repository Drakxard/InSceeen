package com.inscreen.mic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class InScreenService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var config: PairConfig? = null
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var stopped = false
    private var reconnectDelay = 1_000L
    private var reconnectRunnable: Runnable? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var activeSessionId: String? = null
    private var recordingMime = "audio/mp4"
    private var limitRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var upload: UploadState? = null
    private var cleanedUp = false
    private var discoveryInProgress = false
    private var activeSubject = ""
    private var recordingSubject = ""
    private lateinit var mediaObserver: MediaSessionObserver
    private var mediaState = "none"
    private val aprioriReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            activeSubject = AprioriStore.activeSubject(this@InScreenService)
            sendQueueState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeSubject = AprioriStore.activeSubject(this)
        mediaObserver = MediaSessionObserver(this, handler) { state ->
            mediaState = state
            sendMediaState()
        }
        mediaState = mediaObserver.start()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            aprioriReceiver,
            IntentFilter(AprioriUpdates.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopped = true
                disconnectAndStop(explicit = true)
                return START_NOT_STICKY
            }
            ACTION_QUEUE_CHANGED -> {
                activeSubject = AprioriStore.activeSubject(this)
                sendQueueState()
                if (socket == null && config == null) stopSelf(startId)
            }
            ACTION_MEDIA_ACCESS_CHANGED -> {
                mediaState = mediaObserver.start()
                sendReady()
                if (socket == null && config == null) stopSelf(startId)
            }
            ACTION_CONNECT, null -> startConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            broadcast("FALTA PERMISO", "Autoriza el micrófono desde la aplicación.")
            stopSelf()
            return
        }
        val stored = PairingStore.load(this)
        if (stored == null) {
            broadcast("SIN VINCULAR", "Escanea el QR de la PC.")
            stopSelf()
            return
        }
        config = stored
        stopped = false
        cleanedUp = false
        mediaState = mediaObserver.start()
        promoteToForeground("SIN CONEXIÓN", "Conectando con ${stored.host}…")
        connectWebSocket()
    }

    private fun promoteToForeground(state: String, detail: String) {
        val notification = buildNotification(state, detail)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        broadcast(state, detail)
    }

    private fun updateNotification(state: String, detail: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state, detail))
        broadcast(state, detail)
    }

    private fun buildNotification(state: String, detail: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            state.contains("GRABANDO") -> "InScreen grabando"
            state.contains("ENVIANDO") -> "InScreen enviando audio"
            state.contains("CONECTADO") -> "InScreen conectado"
            else -> "InScreen sin conexión"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Conexión InScreen", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantiene la conexión local con la PC y muestra cuándo se está grabando."
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun connectWebSocket() {
        if (stopped || socket != null) return
        val current = config ?: return
        try {
            if (client == null) client = buildHttpClient(current)
            val token = URLEncoder.encode(current.token, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("wss://${current.host}:${current.httpsPort}/ws?token=$token")
                .build()
            socket = client!!.newWebSocket(request, SocketListener())
        } catch (error: Exception) {
            socket = null
            updateNotification("SIN CONEXIÓN", error.message ?: "No se pudo abrir la conexión segura.")
            scheduleReconnect()
        }
    }

    private fun buildHttpClient(config: PairConfig): OkHttpClient {
        val certificateBytes = Base64.decode(config.caDerBase64, Base64.DEFAULT)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream())
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("inscreen-ca", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handler.post {
                socket = webSocket
                reconnectDelay = 1_000L
                updateNotification(currentState(), "PC ${config?.host} conectada.")
                sendReady()
                upload?.apply {
                    awaitingAck = false
                    completeSent = false
                }
                continueUpload()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handler.post { handleMessage(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post { handleSocketLoss(webSocket) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handler.post {
                if (socket === webSocket) socket = null
                upload?.awaitingAck = false
                if (!stopped) {
                    updateNotification(currentState(offline = true), t.message ?: "Conexión interrumpida.")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun handleSocketLoss(webSocket: WebSocket) {
        if (socket === webSocket) socket = null
        upload?.awaitingAck = false
        if (!stopped) {
            updateNotification(currentState(offline = true), "Reconectando con la PC…")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        if (stopped || discoveryInProgress) return
        val current = config ?: return
        discoveryInProgress = true
        thread(name = "InScreenDiscovery") {
            val discovered = discoverEndpoint(current)
            handler.post {
                discoveryInProgress = false
                if (stopped) return@post
                if (discovered != null &&
                    (discovered.host != current.host || discovered.httpsPort != current.httpsPort)
                ) {
                    config = PairingStore.updateEndpoint(
                        this,
                        current,
                        discovered.host,
                        discovered.httpsPort,
                    )
                    client?.dispatcher?.executorService?.shutdown()
                    client?.connectionPool?.evictAll()
                    client = null
                    reconnectDelay = 1_000L
                    updateNotification("SIN CONEXIÓN", "PC encontrada en ${discovered.host}; reconectando…")
                }
                val delay = if (discovered != null) 0L else reconnectDelay
                val task = Runnable {
                    reconnectRunnable = null
                    connectWebSocket()
                }
                reconnectRunnable = task
                handler.postDelayed(task, delay)
                if (discovered == null) {
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    private fun discoverEndpoint(current: PairConfig): DiscoveredEndpoint? {
        val nonce = DiscoveryProtocol.newNonce()
        val request = DiscoveryProtocol.buildRequest(current, nonce).toByteArray(Charsets.UTF_8)
        return try {
            DatagramSocket().use { datagram ->
                datagram.broadcast = true
                repeat(3) {
                    discoveryAddresses().forEach { address ->
                        datagram.send(DatagramPacket(request, request.size, address, current.setupPort))
                    }
                    val deadline = SystemClock.elapsedRealtime() + 800L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
                        datagram.soTimeout = remaining
                        val buffer = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            datagram.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val response = DiscoveryProtocol.parseResponse(
                            String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                            current,
                            nonce,
                        ) ?: continue
                        val host = packet.address.hostAddress ?: continue
                        return DiscoveredEndpoint(host, response.httpsPort)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun discoveryAddresses(): Set<InetAddress> {
        val addresses = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val properties = connectivity.getLinkProperties(connectivity.activeNetwork)
            properties?.linkAddresses?.forEach { link ->
                val address = link.address as? Inet4Address ?: return@forEach
                val prefix = link.prefixLength.coerceIn(0, 32)
                val value = ByteBuffer.wrap(address.address).int
                val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                val broadcast = value or mask.inv()
                addresses += InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array())
            }
        } catch (_: Exception) {
            // El broadcast global sigue disponible como respaldo.
        }
        return addresses
    }

    private fun handleMessage(raw: String) {
        val message = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        if (message.optInt("v", -1) != PROTOCOL_VERSION) return
        when (message.optString("type")) {
            "recording.start" -> startRecording(
                message.optString("session_id"),
                message.optInt("max_seconds", 300).coerceIn(1, 1800),
            )
            "recording.stop" -> {
                val sessionId = message.optString("session_id")
                if (sessionId == activeSessionId && recorder != null) stopRecording(false)
            }
            "recording.cancel" -> {
                val sessionId = message.optString("session_id")
                if (sessionId == activeSessionId) cancelRecording()
            }
            "audio.ack" -> handleAudioAck(message)
            "audio.complete_ack" -> handleCompleteAck(message)
            "media.command" -> handleMediaCommand(message)
            "error" -> updateNotification(currentState(), message.optString("message", "Error informado por la PC."))
        }
    }

    private fun handleMediaCommand(message: JSONObject) {
        val commandId = message.optString("command_id")
        if (commandId.isBlank()) return
        val result = mediaObserver.execute(message.optString("action"))
        val reply = Runnable {
            mediaState = mediaObserver.refreshAndNotify()
            val confirmed = result.ok && (result.expectedState == null || result.expectedState == mediaState)
            val error = when {
                result.error.isNotBlank() -> result.error
                !confirmed -> "La aplicación multimedia no confirmó el cambio de reproducción."
                else -> ""
            }
            sendJson(
                JSONObject()
                    .put("v", PROTOCOL_VERSION)
                    .put("type", "media.result")
                    .put("command_id", commandId)
                    .put("ok", confirmed)
                    .put("state", mediaState)
                    .put("error", error)
            )
        }
        if (result.ok) handler.postDelayed(reply, 450L) else reply.run()
    }

    private fun startRecording(sessionId: String, maxSeconds: Int) {
        if (sessionId.isBlank()) return
        if (recorder != null || upload != null) {
            sendError(sessionId, "busy", "El celular todavía está grabando o enviando audio.")
            return
        }
        val output = File(cacheDir, "inscreen-$sessionId.m4a")
        output.delete()
        try {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(16_000)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setOutputFile(output.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            recordingFile = output
            activeSessionId = sessionId
            recordingSubject = activeSubject
            acquireRecordingWakeLock(maxSeconds)
            val timeout = Runnable { if (activeSessionId == sessionId && recorder != null) stopRecording(false) }
            limitRunnable = timeout
            handler.postDelayed(timeout, maxSeconds * 1_000L)
            updateNotification("GRABANDO", "Pulsa «-» nuevamente en la PC para terminar.")
            sendJson(
                JSONObject()
                    .put("v", PROTOCOL_VERSION)
                    .put("type", "recording.started")
                    .put("session_id", sessionId)
                    .put("mime_type", recordingMime)
                    .put("active_subject", recordingSubject)
            )
        } catch (error: Exception) {
            recorder?.release()
            recorder = null
            recordingFile = null
            activeSessionId = null
            output.delete()
            releaseRecordingWakeLock()
            sendError(sessionId, "microphone_failed", error.message ?: "Android no pudo abrir el micrófono.")
            updateNotification("CONECTADO", "No se pudo abrir el micrófono.")
        }
    }

    private fun stopRecording(interrupted: Boolean) {
        val sessionId = activeSessionId ?: return
        val output = recordingFile
        limitRunnable?.let(handler::removeCallbacks)
        limitRunnable = null
        var valid = true
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            valid = false
        } finally {
            recorder?.reset()
            recorder?.release()
            recorder = null
            recordingFile = null
            releaseRecordingWakeLock()
        }
        if (!valid || output == null || !output.exists() || output.length() == 0L) {
            output?.delete()
            activeSessionId = null
            sendError(sessionId, "empty_audio", "La grabación fue demasiado corta o quedó vacía.")
            updateNotification("CONECTADO", "No se obtuvo audio; vuelve a intentarlo.")
            return
        }
        if (output.length() > MAX_AUDIO_BYTES) {
            output.delete()
            activeSessionId = null
            sendError(sessionId, "audio_too_large", "El audio superó el límite de 24 MB.")
            updateNotification("CONECTADO", "El audio fue demasiado grande.")
            return
        }
        upload = UploadState(sessionId, output, interrupted)
        updateNotification("ENVIANDO", "El micrófono ya está cerrado. Enviando audio a la PC…")
        continueUpload()
    }

    private fun cancelRecording() {
        limitRunnable?.let(handler::removeCallbacks)
        limitRunnable = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.reset() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        activeSessionId = null
        recordingSubject = ""
        releaseRecordingWakeLock()
        updateNotification("CONECTADO", "")
        sendReady()
    }

    private fun continueUpload() {
        val state = upload ?: return
        val webSocket = socket ?: return
        if (state.awaitingAck || state.completeSent) return
        if (state.offset >= state.file.length()) {
            state.completeSent = sendJson(
                JSONObject()
                    .put("v", PROTOCOL_VERSION)
                    .put("type", "audio.complete")
                    .put("session_id", state.sessionId)
                    .put("interrupted", state.interrupted)
                    .put("sequence_count", state.sequence)
            )
            return
        }
        val remaining = (state.file.length() - state.offset).coerceAtMost(CHUNK_SIZE.toLong()).toInt()
        val bytes = ByteArray(remaining)
        RandomAccessFile(state.file, "r").use {
            it.seek(state.offset)
            it.readFully(bytes)
        }
        val metadata = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("type", "audio.chunk")
            .put("session_id", state.sessionId)
            .put("sequence", state.sequence)
            .put("size", bytes.size)
        if (!webSocket.send(metadata.toString())) return
        if (!webSocket.send(ByteString.of(*bytes))) return
        state.awaitingAck = true
        state.pendingSize = bytes.size
    }

    private fun handleAudioAck(message: JSONObject) {
        val state = upload ?: return
        if (message.optString("session_id") != state.sessionId ||
            message.optInt("sequence", -1) != state.sequence
        ) return
        state.offset += state.pendingSize
        state.sequence += 1
        state.pendingSize = 0
        state.awaitingAck = false
        continueUpload()
    }

    private fun handleCompleteAck(message: JSONObject) {
        val state = upload ?: return
        if (message.optString("session_id") != state.sessionId) return
        state.file.delete()
        upload = null
        activeSessionId = null
        recordingSubject = ""
        updateNotification("CONECTADO", "Audio recibido por la PC. Micrófono cerrado.")
        sendReady()
    }

    private fun sendReady() {
        sendJson(
            JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("type", "ready")
                .put("client", "android")
                .put("client_version", BuildConfig.VERSION_NAME)
                .put("session_id", activeSessionId)
                .put("microphone_ready", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                .put("recording", recorder != null)
                .put("active_subject", activeSubject)
                .put("media_control_ready", mediaObserver.hasAccess())
                .put("media_state", mediaObserver.state())
        )
    }

    private fun sendMediaState() {
        sendJson(
            JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("type", "media.state")
                .put("state", mediaState)
        )
    }

    private fun sendQueueState() {
        sendJson(
            JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("type", "queue.state")
                .put("active_subject", activeSubject)
        )
    }

    private fun sendError(sessionId: String?, code: String, message: String) {
        sendJson(
            JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("type", "error")
                .put("session_id", sessionId)
                .put("code", code)
                .put("message", message)
        )
    }

    private fun sendJson(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    private fun currentState(offline: Boolean = false): String = when {
        recorder != null -> if (offline) "GRABANDO · SIN CONEXIÓN" else "GRABANDO"
        upload != null -> if (offline) "ENVIANDO · SIN CONEXIÓN" else "ENVIANDO"
        offline -> "SIN CONEXIÓN"
        else -> "CONECTADO"
    }

    private fun acquireRecordingWakeLock(maxSeconds: Int) {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InScreen:Recording").apply {
            acquire((maxSeconds + 30) * 1_000L)
        }
    }

    private fun releaseRecordingWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun broadcast(state: String, detail: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail)
        )
    }

    private fun disconnectAndStop(explicit: Boolean) {
        if (cleanedUp) return
        cleanedUp = true
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        limitRunnable?.let(handler::removeCallbacks)
        limitRunnable = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        upload?.file?.delete()
        upload = null
        activeSessionId = null
        releaseRecordingWakeLock()
        mediaObserver.stop()
        socket?.close(1000, "Desconectado por el usuario")
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (explicit) {
            broadcast("DESCONECTADO", "InScreen ya no está conectado con la PC.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopped = true
        disconnectAndStop(explicit = false)
        unregisterReceiver(aprioriReceiver)
        super.onDestroy()
    }

    private data class DiscoveredEndpoint(val host: String, val httpsPort: Int)

    private data class UploadState(
        val sessionId: String,
        val file: File,
        val interrupted: Boolean,
        var offset: Long = 0,
        var sequence: Int = 0,
        var pendingSize: Int = 0,
        var awaitingAck: Boolean = false,
        var completeSent: Boolean = false,
    )

    companion object {
        const val ACTION_CONNECT = "com.inscreen.mic.CONNECT"
        const val ACTION_DISCONNECT = "com.inscreen.mic.DISCONNECT"
        const val ACTION_STATUS = "com.inscreen.mic.STATUS"
        const val ACTION_QUEUE_CHANGED = "com.inscreen.mic.QUEUE_CHANGED"
        const val ACTION_MEDIA_ACCESS_CHANGED = "com.inscreen.mic.MEDIA_ACCESS_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        private const val CHANNEL_ID = "inscreen_connection"
        private const val NOTIFICATION_ID = 4102
        private const val PROTOCOL_VERSION = 1
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_AUDIO_BYTES = 24L * 1024L * 1024L
    }
}
