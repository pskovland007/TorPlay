package com.torplay.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PosterLoader(context: Context, private val storage: StorageManager = StorageManager(context)) {

    private val cacheDir = File(context.applicationContext.cacheDir, "posters").apply { mkdirs() }

    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        // Cap cache at ~16 MB to keep memory comfortable on Xiaomi Mi Box (2 GB RAM)
        val cacheKb = (maxKb / 10).coerceIn(4_096, 16_384)
        object : LruCache<String, Bitmap>(cacheKb) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return (bitmap.byteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    private val client: OkHttpClient
        get() = NetworkHelper.getExternalClient(storage, connectSec = 6L, readSec = 10L)

    fun load(scope: CoroutineScope, imageView: ImageView, url: String) {
        if (url.isBlank()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        val cacheKey = normalizeCacheKey(url)
        imageView.tag = cacheKey
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                fetchOrDecode(url, cacheKey)
            }
            if (bitmap != null && imageView.tag == cacheKey) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchOrDecode(primaryUrl: String, cacheKey: String): Bitmap? {
        val diskFile = File(cacheDir, "${cacheKey.hashCode().toUInt().toString(16)}.img")
        if (diskFile.exists() && diskFile.length() > 128L) {
            val decoded = decodeSampled(diskFile)
            if (decoded != null) {
                memoryCache.put(cacheKey, decoded)
                return decoded
            } else {
                runCatching { diskFile.delete() }
            }
        }

        val candidates = buildCandidateUrls(primaryUrl)
        var lastError = ""
        val primaryHost = extractHost(primaryUrl)

        for (candidateUrl in candidates) {
            val req = try {
                Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/jpeg,image/png,*/*")
                    .build()
            } catch (_: IllegalArgumentException) {
                continue
            }

            val bmp = try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code} (${extractHost(candidateUrl)})"
                        return@use null
                    }
                    val bytes = resp.body?.bytes() ?: return@use null
                    if (bytes.size < 128) {
                        lastError = "Пустой ответ (${extractHost(candidateUrl)})"
                        return@use null
                    }
                    diskFile.writeBytes(bytes)
                    val decoded = decodeSampled(diskFile)
                    if (decoded == null) {
                        runCatching { diskFile.delete() }
                        lastError = "Некорректное изображение (${extractHost(candidateUrl)})"
                        return@use null
                    }
                    pruneDiskCacheIfNeeded()
                    memoryCache.put(cacheKey, decoded)
                    decoded
                }
            } catch (err: Exception) {
                lastError = "${err.javaClass.simpleName}: ${err.message?.take(60)} (${extractHost(candidateUrl)})"
                null
            }

            if (bmp != null) {
                val workingHost = extractHost(candidateUrl)
                if (isTmdbImageUrl(candidateUrl) && workingHost.isNotBlank()) {
                    val prev = preferredTmdbHost
                    preferredTmdbHost = workingHost
                    if (prev != workingHost && !workingHost.equals(primaryHost, ignoreCase = true)) {
                        AppLogger.i("Poster", "Постеры TMDB загружены через резервное зеркало: $workingHost")
                    }
                }
                return bmp
            }
        }

        if (lastError.isNotBlank() && loggedFirstFailure.compareAndSet(false, true)) {
            AppLogger.w("Poster", "Не удалось загрузить обложку ($primaryHost): $lastError")
        }
        return null
    }

    private fun buildCandidateUrls(primaryUrl: String): List<String> {
        val clean = primaryUrl.trim()
        val tpIdx = clean.indexOf("/t/p/", ignoreCase = true)
        if (tpIdx < 0) return listOf(clean)

        val suffix = clean.substring(tpIdx)
        val result = LinkedHashSet<String>()

        // Try last known working TMDB image CDN first to avoid repeated timeouts
        val stickyHost = preferredTmdbHost
        if (stickyHost.isNotBlank()) {
            result.add("https://$stickyHost$suffix")
        }

        result.add(clean)
        for (mirrorHost in TMDB_IMAGE_FALLBACK_HOSTS) {
            result.add("https://$mirrorHost$suffix")
        }
        return result.toList()
    }

    private fun normalizeCacheKey(url: String): String {
        val tpIdx = url.indexOf("/t/p/", ignoreCase = true)
        return if (tpIdx >= 0) "tmdb:${url.substring(tpIdx)}" else url
    }

    private fun isTmdbImageUrl(url: String): Boolean {
        return url.contains("/t/p/", ignoreCase = true)
    }

    private fun extractHost(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim()
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    private fun pruneDiskCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        val sorted = files.sortedBy { it.lastModified() }
        val toDelete = sorted.size - (MAX_DISK_FILES - 20)
        for (i in 0 until toDelete.coerceAtLeast(0)) {
            runCatching { sorted[i].delete() }
        }
    }

    companion object {
        private const val MAX_DISK_FILES = 110
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val TMDB_IMAGE_FALLBACK_HOSTS = listOf(
            "image.tmdb.org",
            "media.themoviedb.org",
            "imagetmdb.com"
        )

        @Volatile
        private var preferredTmdbHost: String = ""

        private val loggedFirstFailure = AtomicBoolean(false)

        fun resetSessionMirrorState() {
            preferredTmdbHost = ""
            loggedFirstFailure.set(false)
        }
    }
}
