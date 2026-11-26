package com.torplay.tv

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
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

        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val endpoints = listOf(
            "https://8.8.8.8/resolve?name=%s&type=A",
            "https://1.1.1.1/dns-query?name=%s&type=A",
            "https://9.9.9.9:5053/dns-query?name=%s&type=A"
        )

        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim()
            if (cleanHost.isBlank() || isLocalOrPrivateHost(cleanHost) || isIpv4Literal(cleanHost) || cleanHost.contains(':')) {
                return Dns.SYSTEM.lookup(hostname)
            }

            val now = System.currentTimeMillis()
            val cached = cache[cleanHost.lowercase(Locale.US)]
            if (cached != null && cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }

            val resolved = resolveViaDoh(cleanHost)
            if (resolved.isNotEmpty()) {
                cache[cleanHost.lowercase(Locale.US)] = CacheEntry(resolved, now + 10 * 60_000L)
                return resolved
            }

            return Dns.SYSTEM.lookup(hostname)
        }

        private fun resolveViaDoh(hostname: String): List<InetAddress> {
            val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrDefault(hostname)
            for (tpl in endpoints) {
                val url = String.format(Locale.US, tpl, encoded)
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "TorPlay-TV")
                    .build()

                val found = runCatching {
                    dohHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use emptyList()
                        val body = resp.body?.string().orEmpty()
                        if (!body.startsWith("{")) return@use emptyList()
                        val root = JSONObject(body)
                        val answers = root.optJSONArray("Answer") ?: return@use emptyList()
                        val list = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val ans = answers.optJSONObject(i) ?: continue
                            if (ans.optInt("type", -1) != 1) continue
                            val ip = ans.optString("data", "").trim()
                            val bytes = parseIpv4Bytes(ip) ?: continue
                            list.add(InetAddress.getByAddress(hostname, bytes))
                        }
                        list
                    }
                }.getOrDefault(emptyList())

                if (found.isNotEmpty()) {
                    return found
                }
            }
            return emptyList()
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
