package com.torplay.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WifiSyncManager(
    context: Context,
    private val storage: StorageManager
) {
    private val appContext = context.applicationContext
    private val deviceId: String = UUID.randomUUID().toString().take(12)
    val deviceName: String = buildDeviceDisplayName(appContext)

    data class DiscoveredPeer(
        val deviceId: String,
        val deviceName: String,
        val ip: String,
        val port: Int,
        val version: String,
        val watchlistCount: Int,
        val favoritesCount: Int,
        val historyCount: Int
    ) {
        fun metaLine(): String {
            return "$ip • Буду смотреть: $watchlistCount • Избранное: $favoritesCount • История: $historyCount"
        }
    }

    data class SyncResult(
        val ok: Boolean,
        val peerName: String,
        val message: String,
        val stats: StorageManager.SyncMergeStats? = null
    )

    private var serverScope: CoroutineScope? = null
    private var httpServerSocket: ServerSocket? = null
    private var udpDiscoverySocket: DatagramSocket? = null

    @Volatile
    private var isServerRunning: Boolean = false

    private val pingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .connectTimeout(450, TimeUnit.MILLISECONDS)
            .readTimeout(650, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val syncClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun getLocalIpv4Address(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val ifaceName = iface.name.orEmpty().lowercase(Locale.US)
                if (ifaceName.startsWith("tun") || ifaceName.startsWith("ppp") || ifaceName.startsWith("rmnet")) {
                    continue
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress.orEmpty()
                        if (ip.isNotBlank()) {
                            val priority = when {
                                ifaceName.startsWith("wlan") || ifaceName.startsWith("wifi") -> 0
                                ifaceName.startsWith("eth") -> 1
                                else -> 2
                            }
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second.orEmpty()
    }

    fun startServer(onRemoteSyncApplied: (peerName: String, stats: StorageManager.SyncMergeStats) -> Unit) {
        if (isServerRunning) return
        isServerRunning = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        // 1. LAN HTTP server on port 45821
        scope.launch {
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(HTTP_PORT))
                }
            }.getOrNull()

            if (server == null) {
                AppLogger.w("WifiSync", "Не удалось открыть порт $HTTP_PORT для Wi-Fi синхронизации")
                return@launch
            }
            httpServerSocket = server
            AppLogger.i("WifiSync", "Локальный сервер синхронизации запущен (${getLocalIpv4Address().ifBlank { "LAN" }}:$HTTP_PORT)")

            while (isActive && isServerRunning && !server.isClosed) {
                val clientSocket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                scope.launch {
                    handleHttpClient(clientSocket, onRemoteSyncApplied)
                }
            }
        }

        // 2. UDP discovery responder on port 45822
        scope.launch {
            val sock = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                    soTimeout = 1_500
                }
            }.getOrNull()

            if (sock == null) {
                AppLogger.w("WifiSync", "UDP-порт $UDP_PORT занят, будет доступно сканирование подсети/IP")
                return@launch
            }
            udpDiscoverySocket = sock
            val buf = ByteArray(1024)

            while (isActive && isServerRunning && !sock.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith(DISCOVER_MAGIC)) {
                        val senderId = msg.substringAfter('|', "").trim()
                        if (senderId.isBlank() || senderId != deviceId) {
                            val replyBytes = buildPingJson().toString().toByteArray(Charsets.UTF_8)
                            val reply = DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port)
                            runCatching { sock.send(reply) }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // loop and check isActive
                } catch (_: Exception) {
                    if (!isServerRunning || sock.isClosed) break
                }
            }
        }
    }

    fun stopServer() {
        isServerRunning = false
        runCatching { httpServerSocket?.close() }
        httpServerSocket = null
        runCatching { udpDiscoverySocket?.close() }
        udpDiscoverySocket = null
        serverScope?.cancel()
        serverScope = null
    }

    suspend fun discoverPeers(onPeerFound: (DiscoveredPeer) -> Unit): List<DiscoveredPeer> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredPeer>()
        val myIp = getLocalIpv4Address()

        suspend fun registerPeer(peer: DiscoveredPeer) {
            if (peer.deviceId == deviceId || (myIp.isNotBlank() && peer.ip == myIp)) return
            val prev = found.put(peer.ip, peer)
            if (prev == null) {
                withContext(Dispatchers.Main) {
                    onPeerFound(peer)
                }
            }
        }

        coroutineScope {
            // A. Direct check of last synced IP
            val lastIp = storage.lastWifiSyncIp.trim()
            val lastIpJob = if (isValidIpv4(lastIp) && lastIp != myIp) {
                async {
                    probePeerHttp(lastIp, HTTP_PORT)?.let { registerPeer(it) }
                }
            } else {
                null
            }

            // B. UDP broadcast discovery
            val udpJob = async {
                val udpPeers = discoverViaUdpBroadcast()
                for (p in udpPeers) {
                    registerPeer(p)
                }
            }

            lastIpJob?.await()
            udpJob.await()

            // C. Fallback fast /24 subnet HTTP ping if router blocks UDP broadcast
            if (found.isEmpty() && isValidIpv4(myIp)) {
                val prefix = myIp.substringBeforeLast('.')
                val myLastOctet = myIp.substringAfterLast('.').toIntOrNull() ?: -1
                val sem = Semaphore(24)
                val jobs = (1..254)
                    .filter { it != myLastOctet }
                    .map { octet ->
                        async {
                            sem.withPermit {
                                val targetIp = "$prefix.$octet"
                                probePeerHttp(targetIp, HTTP_PORT)?.let { registerPeer(it) }
                            }
                        }
                    }
                jobs.awaitAll()
            }
        }

        found.values.sortedBy { it.deviceName.lowercase(Locale.ROOT) }
    }

    suspend fun syncWithPeer(rawIp: String, port: Int = HTTP_PORT): SyncResult = withContext(Dispatchers.IO) {
        val cleanIp = rawIp.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        if (!isValidIpv4(cleanIp)) {
            return@withContext SyncResult(false, cleanIp, "Некорректный IP-адрес (пример: 192.168.1.45)")
        }

        val payload = storage.exportSyncPayloadJson().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("version", BuildConfig.VERSION_NAME)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder()
            .url("http://$cleanIp:$port/sync")
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "TorPlay-Sync")
            .build()

        runCatching {
            syncClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use SyncResult(false, cleanIp, "Устройство ответило HTTP ${resp.code}")
                }
                val raw = resp.body?.string().orEmpty().trim()
                if (!raw.startsWith("{")) {
                    return@use SyncResult(false, cleanIp, "Некорректный ответ устройства")
                }
                val obj = JSONObject(raw)
                val peerName = obj.optString("deviceName", cleanIp).ifBlank { cleanIp }
                val remotePayload = obj.optJSONObject("payload") ?: obj
                val stats = storage.mergeSyncPayloadJson(remotePayload)
                storage.lastWifiSyncIp = cleanIp
                AppLogger.i("WifiSync", "Синхронизация с $peerName ($cleanIp): ${stats.summaryText()}")
                SyncResult(
                    ok = true,
                    peerName = peerName,
                    message = "Синхронизировано с $peerName • ${stats.summaryText()}",
                    stats = stats
                )
            }
        }.getOrElse { err ->
            val reason = when (err) {
                is SocketTimeoutException -> "таймаут подключения (проверьте, открыт ли TorPlay на втором устройстве)"
                else -> err.message?.take(60) ?: "нет связи"
            }
            AppLogger.w("WifiSync", "Ошибка синхронизации с $cleanIp: $reason")
            SyncResult(false, cleanIp, "Не удалось подключиться к $cleanIp: $reason")
        }
    }

    private fun discoverViaUdpBroadcast(): List<DiscoveredPeer> {
        val result = LinkedHashMap<String, DiscoveredPeer>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 350

                val probeBytes = "$DISCOVER_MAGIC|$deviceId".toByteArray(Charsets.UTF_8)
                val targets = LinkedHashSet<InetAddress>()
                runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
                runCatching {
                    val ifaces = NetworkInterface.getNetworkInterfaces()
                    while (ifaces != null && ifaces.hasMoreElements()) {
                        val iface = ifaces.nextElement()
                        if (!iface.isUp || iface.isLoopback) continue
                        for (ia in iface.interfaceAddresses) {
                            ia.broadcast?.let { targets.add(it) }
                        }
                    }
                }

                repeat(2) {
                    for (addr in targets) {
                        runCatching {
                            sock.send(DatagramPacket(probeBytes, probeBytes.size, addr, UDP_PORT))
                        }
                    }
                    val deadline = System.currentTimeMillis() + 700L
                    val buf = ByteArray(2048)
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val reply = DatagramPacket(buf, buf.size)
                            sock.receive(reply)
                            val ip = reply.address?.hostAddress.orEmpty()
                            val raw = String(reply.data, 0, reply.length, Charsets.UTF_8).trim()
                            parsePeerFromPingJson(ip, raw)?.let { peer ->
                                if (peer.deviceId != deviceId) {
                                    result[peer.ip] = peer
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                    }
                }
            }
        }
        return result.values.toList()
    }

    private fun probePeerHttp(ip: String, port: Int): DiscoveredPeer? {
        val req = Request.Builder()
            .url("http://$ip:$port/ping")
            .header("Accept", "application/json")
            .header("User-Agent", "TorPlay-Sync")
            .build()
        return runCatching {
            pingClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty().trim()
                parsePeerFromPingJson(ip, raw)
            }
        }.getOrNull()
    }

    private fun parsePeerFromPingJson(ip: String, raw: String): DiscoveredPeer? {
        if (ip.isBlank() || !raw.startsWith("{")) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.optString("app").equals("TorPlay", ignoreCase = true)) return null
        val id = obj.optString("deviceId", "").trim()
        if (id.isBlank() || id == deviceId) return null
        return DiscoveredPeer(
            deviceId = id,
            deviceName = obj.optString("deviceName", ip).ifBlank { ip },
            ip = ip,
            port = obj.optInt("port", HTTP_PORT).coerceIn(1024, 65535),
            version = obj.optString("version", ""),
            watchlistCount = obj.optInt("watchlist", 0),
            favoritesCount = obj.optInt("favorites", 0),
            historyCount = obj.optInt("history", 0)
        )
    }

    private fun buildPingJson(): JSONObject {
        return JSONObject().apply {
            put("app", "TorPlay")
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("port", HTTP_PORT)
            put("version", BuildConfig.VERSION_NAME)
            put("watchlist", storage.getWatchlist().size)
            put("favorites", storage.getFavoriteTorrents().size)
            put("history", storage.getWatchHistory().size)
        }
    }

    private fun handleHttpClient(
        socket: Socket,
        onRemoteSyncApplied: (peerName: String, stats: StorageManager.SyncMergeStats) -> Unit
    ) {
        socket.use { sock ->
            runCatching {
                sock.soTimeout = 8_000
                val input = BufferedInputStream(sock.getInputStream())
                val headerBytes = readUntilHeadersEnd(input) ?: return@runCatching
                val headerText = String(headerBytes, Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeHttpJson(sock, 400, """{"error":"bad request"}""")
                    return@runCatching
                }
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        break
                    }
                }

                when {
                    method == "GET" && path == "/ping" -> {
                        writeHttpJson(sock, 200, buildPingJson().toString())
                    }
                    method == "POST" && path == "/sync" -> {
                        if (contentLength <= 0 || contentLength > MAX_SYNC_BODY_BYTES) {
                            writeHttpJson(sock, 400, """{"error":"invalid body size"}""")
                            return@runCatching
                        }
                        val bodyBytes = readExactBytes(input, contentLength)
                        val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
                        val remoteJson = JSONObject(bodyStr)
                        val peerIp = sock.inetAddress?.hostAddress.orEmpty()
                        val peerName = remoteJson.optString("deviceName", peerIp).ifBlank { peerIp.ifBlank { "Устройство" } }

                        val stats = storage.mergeSyncPayloadJson(remoteJson)
                        if (isValidIpv4(peerIp)) {
                            storage.lastWifiSyncIp = peerIp
                        }
                        AppLogger.i("WifiSync", "Входящая синхронизация от $peerName ($peerIp): ${stats.summaryText()}")

                        val responseObj = JSONObject().apply {
                            put("ok", true)
                            put("app", "TorPlay")
                            put("deviceId", deviceId)
                            put("deviceName", deviceName)
                            put("version", BuildConfig.VERSION_NAME)
                            put("payload", storage.exportSyncPayloadJson())
                        }
                        writeHttpJson(sock, 200, responseObj.toString())
                        onRemoteSyncApplied(peerName, stats)
                    }
                    else -> {
                        writeHttpJson(sock, 404, """{"error":"not found"}""")
                    }
                }
            }
        }
    }

    private fun readUntilHeadersEnd(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < 16_384) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return out.toByteArray()
        }
        return null
    }

    private fun readExactBytes(input: BufferedInputStream, total: Int): ByteArray {
        val data = ByteArray(total)
        var offset = 0
        while (offset < total) {
            val read = input.read(data, offset, total - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == total) data else data.copyOf(offset)
    }

    private fun writeHttpJson(socket: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val out = socket.getOutputStream()
        out.write(headers)
        out.write(bodyBytes)
        out.flush()
    }

    companion object {
        const val HTTP_PORT = 45821
        const val UDP_PORT = 45822
        private const val DISCOVER_MAGIC = "TORPLAY_DISCOVER_V1"
        private const val MAX_SYNC_BODY_BYTES = 2 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isValidIpv4(ip: String): Boolean {
            val parts = ip.trim().split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                val n = p.toIntOrNull()
                n != null && n in 0..255
            }
        }

        private fun buildDeviceDisplayName(context: Context): String {
            val uiModeMgr = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTv = uiModeMgr?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val prefix = if (isTv) "ТВ" else "Планшет/Устройство"
            val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            val hw = when {
                model.isBlank() -> ""
                manufacturer.isBlank() -> model
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }.take(26)
            return if (hw.isNotBlank()) "$prefix • $hw" else prefix
        }
    }
}
