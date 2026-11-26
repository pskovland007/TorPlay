package com.torplay.tv

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

object NetworkHelper {

    data class NetworkConfig(
        val useDoh: Boolean,
        val useDpiBypass: Boolean,
        val proxyUrl: String
    )

    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    private val directStreamClientInstance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun buildDirectStreamClient(): OkHttpClient = directStreamClientInstance

    fun buildLocalApiClient(connectSec: Long, readSec: Long): OkHttpClient {
        val key = "local|$connectSec|$readSec"
        return clientCache.getOrPut(key) {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(Dns.SYSTEM)
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun getExternalClient(
        storage: StorageManager,
        connectSec: Long = 7L,
        readSec: Long = 10L,
        overrideConfig: NetworkConfig? = null
    ): OkHttpClient {
        val cfg = overrideConfig ?: NetworkConfig(
            useDoh = storage.useDohDns,
            useDpiBypass = storage.useDpiBypass,
            proxyUrl = storage.networkProxyUrl.trim()
        )
        val key = "ext|${cfg.useDoh}|${cfg.useDpiBypass}|${cfg.proxyUrl}|$connectSec|$readSec"
        return clientCache.getOrPut(key) {
            buildExternalClient(cfg, connectSec, readSec)
        }
    }

    private fun buildExternalClient(
        cfg: NetworkConfig,
        connectSec: Long,
        readSec: Long
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)

        val parsedProxy = parseProxy(cfg.proxyUrl)
        if (parsedProxy != null) {
            builder.proxySelector(SmartProxySelector(parsedProxy))
        } else {
            if (cfg.useDoh) {
                builder.dns(DohDns)
            }
            if (cfg.useDpiBypass) {
                builder.socketFactory(TlsFragmentSocketFactory())
            }
        }
        return builder.build()
    }

    fun isLocalOrPrivateHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase(Locale.US)?.trim('[', ']') ?: return false
        if (h.isBlank()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val parts = h.split('.')
            val second = parts.getOrNull(1)?.toIntOrNull() ?: -1
            if (second in 16..31) return true
        }
        return false
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull()
            n != null && n in 0..255
        }
    }

    private fun isSinkholeOrPrivateIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0 || b0 == 10 || b0 == 127) return true
        if (b0 == 169 && b1 == 254) return true
        if (b0 == 172 && b1 in 16..31) return true
        if (b0 == 192 && b1 == 168) return true
        if (b0 >= 224) return true
        return false
    }

    private fun isSinkholeAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
            return true
        }
        if (addr is Inet4Address) {
            return isSinkholeOrPrivateIpv4(addr.address)
        }
        return false
    }

    fun parseProxy(raw: String): Proxy? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase(Locale.US)
        val (type, hostPort) = when {
            lower.startsWith("socks5://") -> Proxy.Type.SOCKS to trimmed.substring(9)
            lower.startsWith("socks4://") -> Proxy.Type.SOCKS to trimmed.substring(9)
            lower.startsWith("socks://") -> Proxy.Type.SOCKS to trimmed.substring(8)
            lower.startsWith("http://") -> Proxy.Type.HTTP to trimmed.substring(7)
            lower.startsWith("https://") -> Proxy.Type.HTTP to trimmed.substring(8)
            else -> Proxy.Type.SOCKS to trimmed
        }

        val cleanHostPort = hostPort.substringBefore('/').substringAfterLast('@').trim()
        val colonIdx = cleanHostPort.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx >= cleanHostPort.lastIndex) return null
        val host = cleanHostPort.substring(0, colonIdx).trim().trim('[', ']')
        val port = cleanHostPort.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null

        return runCatching {
            Proxy(type, InetSocketAddress.createUnresolved(host, port))
        }.getOrNull()
    }

    private class SmartProxySelector(private val externalProxy: Proxy) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            return if (isLocalOrPrivateHost(host)) {
                listOf(Proxy.NO_PROXY)
            } else {
                listOf(externalProxy)
            }
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            AppLogger.w("Net", "Прокси недоступен (${uri?.host}): ${ioe?.message}")
        }
    }

    private object DohDns : Dns {
        private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)
        private data class ResolverTarget(val name: String, val kind: String, val endpoint: String)

        private val dnsMessageMediaType = "application/dns-message".toMediaType()
        private val txIdCounter = AtomicInteger(0x1234)
        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val loggedHosts = ConcurrentHashMap<String, Long>()

        @Volatile
        private var preferredTargetName: String? = null

        private val resolverTargets = listOf(
            ResolverTarget("DoH-AdGuard", "wire", "https://94.140.14.140/dns-query"),
            ResolverTarget("DoH-Alibaba", "wire", "https://223.5.5.5/dns-query"),
            ResolverTarget("DoH-Google", "wire", "https://8.8.8.8/dns-query"),
            ResolverTarget("DoH-Cloudflare", "wire", "https://1.1.1.1/dns-query"),
            ResolverTarget("DoH-Quad9", "wire", "https://9.9.9.9/dns-query"),
            ResolverTarget("DoH-Google-JSON", "json", "https://dns.google/resolve?name=%s&type=A"),
            ResolverTarget("DoH-Alibaba-JSON", "json", "https://223.5.5.5/resolve?name=%s&type=A"),
            ResolverTarget("UDP-AdGuard", "udp", "94.140.14.140"),
            ResolverTarget("UDP-Alibaba", "udp", "223.5.5.5"),
            ResolverTarget("UDP-Google", "udp", "8.8.8.8"),
            ResolverTarget("UDP-Cloudflare", "udp", "1.1.1.1")
        )

        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim()
            if (cleanHost.isBlank() || isLocalOrPrivateHost(cleanHost) || isIpv4Literal(cleanHost) || cleanHost.contains(':')) {
                return Dns.SYSTEM.lookup(hostname)
            }

            val lowerHost = cleanHost.lowercase(Locale.US)
            val now = System.currentTimeMillis()
            val cached = cache[lowerHost]
            if (cached != null && cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }

            val ordered = buildOrderedTargets()
            for (target in ordered) {
                val resolved = when (target.kind) {
                    "wire" -> resolveViaDohWire(cleanHost, target.endpoint)
                    "json" -> resolveViaDohJson(cleanHost, target.endpoint)
                    "udp" -> resolveViaUdp(cleanHost, target.endpoint)
                    else -> emptyList()
                }
                if (resolved.isNotEmpty()) {
                    preferredTargetName = target.name
                    cache[lowerHost] = CacheEntry(resolved, now + 10 * 60_000L)
                    logResolutionOnce(lowerHost, resolved.first().hostAddress.orEmpty(), target.name)
                    return resolved
                }
            }

            // Last resort: system DNS, strictly filtering out ISP sinkholes (127.0.0.x / 0.0.0.0)
            val sysAll = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
            val sysClean = sysAll.filter { !isSinkholeAddress(it) }
            if (sysClean.isNotEmpty()) {
                cache[lowerHost] = CacheEntry(sysClean, now + 3 * 60_000L)
                logResolutionOnce(lowerHost, sysClean.first().hostAddress.orEmpty(), "SystemDNS")
                return sysClean
            }
            if (sysAll.isNotEmpty()) {
                val blockedIp = sysAll.first().hostAddress.orEmpty()
                AppLogger.w("DNS", "Все DoH/UDP серверы недоступны, системный DNS вернул заглушку ($cleanHost -> $blockedIp)")
                throw UnknownHostException("DNS подмена ($blockedIp) для $cleanHost")
            }
            throw UnknownHostException("Не удалось разрешить домен $cleanHost")
        }

        private fun buildOrderedTargets(): List<ResolverTarget> {
            val pref = preferredTargetName ?: return resolverTargets
            val idx = resolverTargets.indexOfFirst { it.name == pref }
            if (idx <= 0) return resolverTargets
            val out = ArrayList<ResolverTarget>(resolverTargets.size)
            out.add(resolverTargets[idx])
            for (i in resolverTargets.indices) {
                if (i != idx) out.add(resolverTargets[i])
            }
            return out
        }

        private fun logResolutionOnce(lowerHost: String, ip: String, via: String) {
            val now = System.currentTimeMillis()
            val prev = loggedHosts[lowerHost] ?: 0L
            if (now - prev >= 10 * 60_000L) {
                loggedHosts[lowerHost] = now
                AppLogger.i("DNS", "$lowerHost -> $ip ($via)")
            }
        }

        private fun resolveViaDohWire(hostname: String, endpointUrl: String): List<InetAddress> {
            val queryPacket = buildDnsQueryPacket(hostname) ?: return emptyList()
            val req = Request.Builder()
                .url(endpointUrl)
                .post(queryPacket.toRequestBody(dnsMessageMediaType))
                .header("Accept", "application/dns-message")
                .header("User-Agent", "TorPlay-TV")
                .build()

            return runCatching {
                dohHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val bodyBytes = resp.body?.bytes() ?: return@use emptyList()
                    parseDnsResponsePacket(hostname, bodyBytes)
                }
            }.getOrDefault(emptyList())
        }

        private fun resolveViaDohJson(hostname: String, urlTemplate: String): List<InetAddress> {
            val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrDefault(hostname)
            val url = String.format(Locale.US, urlTemplate, encoded)
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("User-Agent", "TorPlay-TV")
                .build()

            return runCatching {
                dohHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty().trim()
                    if (!body.startsWith("{")) return@use emptyList()
                    val root = JSONObject(body)
                    val answers = root.optJSONArray("Answer") ?: return@use emptyList()
                    val list = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val ans = answers.optJSONObject(i) ?: continue
                        if (ans.optInt("type", -1) != 1) continue
                        val ip = ans.optString("data", "").trim()
                        val bytes = parseIpv4Bytes(ip) ?: continue
                        if (isSinkholeOrPrivateIpv4(bytes)) continue
                        list.add(InetAddress.getByAddress(hostname, bytes))
                    }
                    list
                }
            }.getOrDefault(emptyList())
        }

        private fun resolveViaUdp(hostname: String, serverIp: String): List<InetAddress> {
            val queryPacket = buildDnsQueryPacket(hostname) ?: return emptyList()
            val serverBytes = parseIpv4Bytes(serverIp) ?: return emptyList()
            return runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 2_200
                    val targetAddr = InetAddress.getByAddress(serverBytes)
                    socket.send(DatagramPacket(queryPacket, queryPacket.size, targetAddr, 53))
                    val buf = ByteArray(1024)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    val respBytes = buf.copyOf(reply.length)
                    parseDnsResponsePacket(hostname, respBytes)
                }
            }.getOrDefault(emptyList())
        }

        private fun buildDnsQueryPacket(hostname: String): ByteArray? {
            val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
            if (labels.isEmpty()) return null
            val out = ByteArrayOutputStream(64)
            val txId = txIdCounter.incrementAndGet() and 0xFFFF
            out.write((txId ushr 8) and 0xFF)
            out.write(txId and 0xFF)
            // Standard query, Recursion Desired (RD=1)
            out.write(0x01)
            out.write(0x00)
            // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return null
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0x00) // root terminator
            out.write(0x00); out.write(0x01) // QTYPE = A (1)
            out.write(0x00); out.write(0x01) // QCLASS = IN (1)
            return out.toByteArray()
        }

        private fun parseDnsResponsePacket(hostname: String, data: ByteArray): List<InetAddress> {
            if (data.size < 12) return emptyList()
            val rcode = data[3].toInt() and 0x0F
            if (rcode != 0) return emptyList()

            val qdCount = readU16(data, 4)
            val anCount = readU16(data, 6)
            if (anCount <= 0) return emptyList()

            var pos = 12
            for (i in 0 until qdCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0 || pos + 4 > data.size) return emptyList()
                pos += 4
            }

            val result = mutableListOf<InetAddress>()
            for (i in 0 until anCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0 || pos + 10 > data.size) break
                val type = readU16(data, pos)
                val clazz = readU16(data, pos + 2)
                val rdLen = readU16(data, pos + 8)
                pos += 10
                if (pos + rdLen > data.size) break
                if (type == 1 && clazz == 1 && rdLen == 4) {
                    val ipBytes = data.copyOfRange(pos, pos + 4)
                    if (!isSinkholeOrPrivateIpv4(ipBytes)) {
                        runCatching {
                            result.add(InetAddress.getByAddress(hostname, ipBytes))
                        }
                    }
                }
                pos += rdLen
            }
            return result
        }

        private fun readU16(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        private fun skipDnsName(data: ByteArray, start: Int): Int {
            var pos = start
            var guard = 0
            while (pos < data.size && guard < 128) {
                guard++
                val len = data[pos].toInt() and 0xFF
                if (len == 0) return pos + 1
                if ((len and 0xC0) == 0xC0) {
                    return if (pos + 2 <= data.size) pos + 2 else -1
                }
                pos += 1 + len
            }
            return -1
        }

        private fun parseIpv4Bytes(ip: String): ByteArray? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val v = parts[i].toIntOrNull() ?: return null
                if (v !in 0..255) return null
                out[i] = v.toByte()
            }
            return out
        }
    }

    private class TlsFragmentSocketFactory : SocketFactory() {
        private val defaultFactory: SocketFactory = SocketFactory.getDefault()

        override fun createSocket(): Socket {
            return DelegatingFragmentSocket(defaultFactory.createSocket())
        }

        override fun createSocket(host: String, port: Int): Socket {
            val sock = DelegatingFragmentSocket(defaultFactory.createSocket())
            sock.connect(InetSocketAddress(host, port))
            return sock
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val sock = DelegatingFragmentSocket(defaultFactory.createSocket())
            sock.bind(InetSocketAddress(localHost, localPort))
            sock.connect(InetSocketAddress(host, port))
            return sock
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val sock = DelegatingFragmentSocket(defaultFactory.createSocket())
            sock.connect(InetSocketAddress(host, port))
            return sock
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            val sock = DelegatingFragmentSocket(defaultFactory.createSocket())
            sock.bind(InetSocketAddress(localAddress, localPort))
            sock.connect(InetSocketAddress(address, port))
            return sock
        }
    }

    private class DelegatingFragmentSocket(private val delegate: Socket) : Socket() {
        @Volatile
        private var allowFragmentation: Boolean = true
        private var wrappedOut: OutputStream? = null

        override fun connect(endpoint: SocketAddress?) {
            updateLocalFlag(endpoint)
            delegate.connect(endpoint)
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            updateLocalFlag(endpoint)
            delegate.connect(endpoint, timeout)
        }

        private fun updateLocalFlag(endpoint: SocketAddress?) {
            val inet = endpoint as? InetSocketAddress ?: return
            val host = inet.hostString
            val ip = inet.address?.hostAddress
            if (isLocalOrPrivateHost(host) || isLocalOrPrivateHost(ip)) {
                allowFragmentation = false
            }
        }

        override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun getChannel(): SocketChannel? = null
        override fun getInputStream(): InputStream = delegate.getInputStream()

        override fun getOutputStream(): OutputStream {
            if (!allowFragmentation) return delegate.getOutputStream()
            val existing = wrappedOut
            if (existing != null) return existing
            val created = ClientHelloFragmentingOutputStream(delegate, delegate.getOutputStream())
            wrappedOut = created
            return created
        }

        override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
        override fun getSoLinger(): Int = delegate.soLinger
        override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
        override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
        override fun getOOBInline(): Boolean = delegate.oobInline
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
        override fun getSendBufferSize(): Int = delegate.sendBufferSize
        override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
        override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
        override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
        override fun getTrafficClass(): Int = delegate.trafficClass
        override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
        override fun getReuseAddress(): Boolean = delegate.reuseAddress
        override fun close() = delegate.close()
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isBound(): Boolean = delegate.isBound
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    }

    private class ClientHelloFragmentingOutputStream(
        private val socket: Socket,
        out: OutputStream
    ) : FilterOutputStream(out) {

        private var firstPacketHandled: Boolean = false

        override fun write(b: Int) {
            firstPacketHandled = true
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!firstPacketHandled && len >= 16 &&
                b[off].toInt() == 0x16 &&
                b[off + 1].toInt() == 0x03 &&
                b[off + 5].toInt() == 0x01
            ) {
                firstPacketHandled = true
                runCatching { socket.tcpNoDelay = true }
                val firstCut = 2
                val secondCut = (len / 3).coerceIn(12, len - 4)
                out.write(b, off, firstCut)
                out.flush()
                runCatching { Thread.sleep(1L) }
                out.write(b, off + firstCut, secondCut - firstCut)
                out.flush()
                runCatching { Thread.sleep(1L) }
                out.write(b, off + secondCut, len - secondCut)
                out.flush()
                return
            }
            firstPacketHandled = true
            out.write(b, off, len)
        }
    }
}
