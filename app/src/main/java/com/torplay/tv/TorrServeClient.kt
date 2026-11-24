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

        // 1. Try starting known TorrServe background service quietly
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

        // 2. Fallback: launch TorrServe app (it initializes its HTTP daemon immediately)
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

    suspend fun waitForVideoFiles(hash: String, timeoutMs: Long = 14_000L): List<TorrServeFile> =
        withContext(Dispatchers.IO) {
            // Trigger metadata fetch via /stream?link=<hash>&stat
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
                    val videoFiles = info.files.filter { isVideoFile(it.path) }
                    if (videoFiles.isNotEmpty()) {
                        return@withContext videoFiles
                    }
                    return@withContext info.files
                }
                delay(650)
            }
            emptyList()
        }

    fun pickPrimaryVideoFile(files: List<TorrServeFile>): TorrServeFile? {
        if (files.isEmpty()) return null
        val videos = files.filter { isVideoFile(it.path) }
        val pool = if (videos.isNotEmpty()) videos else files
        return pool.maxByOrNull { it.length }
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
            val hash = obj.optString("hash", payload.optString("hash"))
            val title = obj.optString("title", payload.optString("title"))
            val stat = obj.optInt("stat", 0)
            val statString = obj.optString("stat_string", "")
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
            return TorrServeInfo(hash, title, stat, statString, files)
        }
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in VIDEO_EXTENSIONS
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
