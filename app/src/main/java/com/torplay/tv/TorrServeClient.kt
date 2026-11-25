package com.torplay.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class TorrServeClient(private val storage: StorageManager) {

    private val shortClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val baseUrl: String
        get() = storage.torrServeUrl.trimEnd('/')

    fun isLocalAddress(): Boolean {
        val url = baseUrl.lowercase(Locale.US)
        return url.contains("127.0.0.1") || url.contains("localhost")
    }

    suspend fun checkEcho(): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/echo")
            .get()
            .build()
        runCatching {
            shortClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.trim()?.ifBlank { "OK" }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    suspend fun ensureRunning(context: Context): String? {
        checkEcho()?.let { return it }

        if (!isLocalAddress()) {
            return null
        }

        tryStartLocalTorrServe(context)

        repeat(16) {
            delay(500)
            checkEcho()?.let { return it }
        }
        return null
    }

    private fun tryStartLocalTorrServe(context: Context) {
        val pm = context.packageManager
        val installedPkg = TORRSERVE_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        } ?: return

        val serviceClasses = listOf(
            "ru.yourok.torrserve.server.local.services.GlobalTorrServeService",
            "ru.yourok.torrserve.server.local.ServerFile"
        )
        for (cls in serviceClasses) {
            runCatching {
                val svcIntent = Intent().setComponent(ComponentName(installedPkg, cls))
                context.startService(svcIntent)
            }
        }

        runCatching {
            val launchIntent = pm.getLaunchIntentForPackage(installedPkg)
                ?: pm.getLeanbackLaunchIntentForPackage(installedPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        }
    }

    suspend fun addTorrent(link: String, title: String): TorrServeInfo = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("action", "add")
            put("link", link)
            put("title", title)
            put("poster", "")
            put("save_to_db", false)
        }
        postTorrents(payload)
    }

    suspend fun getTorrentInfo(hash: String): TorrServeInfo = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("action", "get")
            put("hash", hash)
        }
        postTorrents(payload)
    }

    suspend fun listTorrents(): List<TorrServeInfo> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("action", "list")
        }
        val body = payload.toString().toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("$baseUrl/torrents")
            .post(body)
            .build()

        apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("TorrServe HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank()) return@withContext emptyList()

            val arr = when {
                raw.startsWith("[") -> JSONArray(raw)
                raw.startsWith("{") -> {
                    val root = JSONObject(raw)
                    root.optJSONArray("torrents") ?: JSONArray()
                }
                else -> JSONArray()
            }

            val result = mutableListOf<TorrServeInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val info = parseTorrentObject(obj, "", "")
                if (info.hash.isNotBlank()) {
                    result.add(info)
                }
            }
            result
        }
    }

    suspend fun clearAllTorrents(): Int = withContext(Dispatchers.IO) {
        val list = listTorrents()
        var removed = 0
        for (item in list) {
            if (item.hash.isNotBlank()) {
                stopAndRemoveTorrent(item.hash)
                removed++
            }
        }
        removed
    }

    suspend fun waitForVideoFiles(hash: String, timeoutMs: Long = 14_000L): List<TorrServeFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val triggerReq = Request.Builder()
                    .url("$baseUrl/stream?link=${URLEncoder.encode(hash, "UTF-8")}&stat")
                    .get()
                    .build()
                shortClient.newCall(triggerReq).execute().close()
            }

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val info = runCatching { getTorrentInfo(hash) }.getOrNull()
                if (info != null && info.files.isNotEmpty()) {
                    val videoFiles = filterAndSortVideos(info.files)
                    if (videoFiles.isNotEmpty()) {
                        return@withContext videoFiles
                    }
                    return@withContext sortFilesNaturally(info.files)
                }
                delay(600)
            }
            emptyList()
        }

    fun filterAndSortVideos(files: List<TorrServeFile>): List<TorrServeFile> {
        val videos = files.filter { isVideoFile(it.path) }
        return sortFilesNaturally(videos)
    }

    fun triggerPreload(hash: String, fileId: Int) {
        runCatching {
            val encodedLink = URLEncoder.encode(hash, "UTF-8")
            val req = Request.Builder()
                .url("$baseUrl/stream?link=$encodedLink&index=$fileId&preload")
                .get()
                .build()
            shortClient.newCall(req).execute().close()
        }
    }

    suspend fun preloadWithProgress(
        hash: String,
        fileId: Int,
        maxWaitMs: Long = 0L,
        onProgress: suspend (TorrServeInfo) -> Unit
    ) = withContext(Dispatchers.IO) {
        val profile = storage.bufferProfile
        val effectiveMaxWait = if (maxWaitMs > 0L) {
            maxWaitMs
        } else {
            when (profile) {
                StorageManager.BUFFER_PROFILE_FAST -> 6_000L
                StorageManager.BUFFER_PROFILE_4K -> 20_000L
                else -> 12_000L
            }
        }
        val targetPercent = when (profile) {
            StorageManager.BUFFER_PROFILE_FAST -> 45
            else -> 95
        }
        val minBytesThreshold = when (profile) {
            StorageManager.BUFFER_PROFILE_FAST -> 1_500_000L
            StorageManager.BUFFER_PROFILE_4K -> 8L * 1024L * 1024L
            else -> 2L * 1024L * 1024L
        }
        val minElapsedForEarlyStart = when (profile) {
            StorageManager.BUFFER_PROFILE_FAST -> 1_500L
            StorageManager.BUFFER_PROFILE_4K -> 4_000L
            else -> 2_500L
        }

        triggerPreload(hash, fileId)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < effectiveMaxWait) {
            val info = runCatching { getTorrentInfo(hash) }.getOrNull()
            if (info != null) {
                withContext(Dispatchers.Main) {
                    onProgress(info)
                }
                val percent = info.preloadPercent()
                if (info.preloadSize > 0L && percent >= targetPercent) {
                    break
                }
                if (info.stat == 3 &&
                    info.preloadedBytes >= minBytesThreshold &&
                    (System.currentTimeMillis() - start) >= minElapsedForEarlyStart
                ) {
                    break
                }
            }
            delay(500)
        }
    }

    fun buildStreamUrl(hashOrLink: String, fileId: Int, fileName: String): String {
        val safeName = fileName
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "video.mkv" }
        val encodedName = URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
        val encodedLink = URLEncoder.encode(hashOrLink, "UTF-8")
        return "$baseUrl/stream/$encodedName?link=$encodedLink&index=$fileId&play"
    }

    suspend fun stopAndRemoveTorrent(hash: String) = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext
        runCatching {
            val dropPayload = JSONObject().apply {
                put("action", "drop")
                put("hash", hash)
            }
            postTorrents(dropPayload)
        }
        runCatching {
            val remPayload = JSONObject().apply {
                put("action", "rem")
                put("hash", hash)
            }
            postTorrents(remPayload)
        }
    }

    private fun postTorrents(payload: JSONObject): TorrServeInfo {
        val body = payload.toString().toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("$baseUrl/torrents")
            .post(body)
            .build()

        apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("TorrServe HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank() || !raw.startsWith("{")) {
                return TorrServeInfo(
                    hash = payload.optString("hash"),
                    title = payload.optString("title"),
                    stat = 0,
                    statString = "",
                    files = emptyList()
                )
            }
            val obj = JSONObject(raw)
            return parseTorrentObject(obj, payload.optString("hash"), payload.optString("title"))
        }
    }

    private fun parseTorrentObject(obj: JSONObject, fallbackHash: String, fallbackTitle: String): TorrServeInfo {
        val hash = obj.optString("hash", fallbackHash)
        val title = obj.optString("title", fallbackTitle)
            .ifBlank { obj.optString("name", fallbackTitle) }
        val stat = obj.optInt("stat", 0)
        val statString = obj.optString("stat_string", "")
        val activePeers = obj.optInt("active_peers", 0)
        val totalPeers = obj.optInt("total_peers", 0)
        val connectedSeeders = obj.optInt("connected_seeders", 0)
        val downloadSpeed = obj.optDouble("download_speed", 0.0)
        val preloadedBytes = obj.optLong("preloaded_bytes", 0L)
        val preloadSize = obj.optLong("preload_size", 0L)
        val torrentSize = obj.optLong("torrent_size", 0L)

        val files = mutableListOf<TorrServeFile>()
        val fileStats = obj.optJSONArray("file_stats")
        if (fileStats != null) {
            for (i in 0 until fileStats.length()) {
                val f = fileStats.optJSONObject(i) ?: continue
                val id = f.optInt("id", i + 1)
                val path = f.optString("path", "")
                val len = f.optLong("length", 0L)
                if (path.isNotBlank()) {
                    files.add(TorrServeFile(id = id, path = path, length = len))
                }
            }
        }
        return TorrServeInfo(
            hash = hash,
            title = title,
            stat = stat,
            statString = statString,
            files = files,
            activePeers = activePeers,
            totalPeers = totalPeers,
            connectedSeeders = connectedSeeders,
            downloadSpeed = downloadSpeed,
            preloadedBytes = preloadedBytes,
            preloadSize = preloadSize,
            torrentSize = torrentSize
        )
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in VIDEO_EXTENSIONS
    }

    private fun sortFilesNaturally(files: List<TorrServeFile>): List<TorrServeFile> {
        return files.sortedWith { a, b -> compareNatural(a.path, b.path) }
    }

    private fun compareNatural(a: String, b: String): Int {
        val la = a.lowercase(Locale.US)
        val lb = b.lowercase(Locale.US)
        var ia = 0
        var ib = 0
        while (ia < la.length && ib < lb.length) {
            val ca = la[ia]
            val cb = lb[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var numA = 0L
                while (ia < la.length && la[ia].isDigit()) {
                    numA = numA * 10 + (la[ia] - '0')
                    ia++
                }
                var numB = 0L
                while (ib < lb.length && lb[ib].isDigit()) {
                    numB = numB * 10 + (lb[ib] - '0')
                    ib++
                }
                if (numA != numB) return numA.compareTo(numB)
            } else {
                if (ca != cb) return ca.compareTo(cb)
                ia++
                ib++
            }
        }
        return la.length.compareTo(lb.length)
    }

    companion object {
        private val TORRSERVE_PACKAGES = listOf(
            "ru.yourok.torrserve",
            "ru.yourok.torrserve.df"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "ts", "m2ts", "mov", "m4v", "webm", "mpg", "mpeg", "wmv", "flv"
        )
    }
}
