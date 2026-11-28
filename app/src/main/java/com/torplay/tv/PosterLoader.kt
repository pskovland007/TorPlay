package com.torplay.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PosterLoader(context: Context, private val storage: StorageManager = StorageManager(context)) {

    private class LoadTag(
        val cacheKey: String,
        var job: Job? = null
    )

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
        val prevTag = imageView.tag as? LoadTag
        if (url.isBlank()) {
            prevTag?.job?.cancel()
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        val cacheKey = normalizeCacheKey(url)
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            prevTag?.job?.cancel()
            imageView.tag = LoadTag(cacheKey)
            imageView.setImageBitmap(cached)
            return
        }

        if (prevTag != null && prevTag.cacheKey == cacheKey && prevTag.job?.isActive == true) {
            return
        }
        prevTag?.job?.cancel()

        val tag = LoadTag(cacheKey)
        imageView.tag = tag
        imageView.setImageDrawable(null)

        val job = scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    fetchOrDecode(url, cacheKey)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            val currentTag = imageView.tag as? LoadTag
            if (bitmap != null && !bitmap.isRecycled && currentTag === tag && currentTag.cacheKey == cacheKey) {
                imageView.setImageBitmap(bitmap)
            }
        }
        tag.job = job
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
                    val writtenFile = writeBytesAtomically(diskFile, bytes)
                    if (writtenFile == null) {
                        lastError = "Ошибка записи кэша (${extractHost(candidateUrl)})"
                        return@use null
                    }
                    val decoded = decodeSampled(writtenFile)
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

    private fun writeBytesAtomically(targetFile: File, bytes: ByteArray): File? {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val tmpFile = File(cacheDir, "${targetFile.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(tmpFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            if (tmpFile.renameTo(targetFile)) {
                targetFile
            } else {
                runCatching { targetFile.delete() }
                if (tmpFile.renameTo(targetFile)) {
                    targetFile
                } else {
                    runCatching { tmpFile.delete() }
                    null
                }
            }
        } catch (_: Exception) {
            runCatching { tmpFile.delete() }
            null
        }
    }

    private fun buildCandidateUrls(primaryUrl: String): List<String> {
        val clean = primaryUrl.trim()
        val tpIdx = clean.indexOf("/t/p/", ignoreCase = true)
        if (tpIdx < 0) return listOf(clean)

        val suffix = clean.substring(tpIdx)
        val result = LinkedHashSet<String>()

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
        val allFiles = cacheDir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val imgFiles = mutableListOf<File>()
        for (f in allFiles) {
            if (f.name.endsWith(".tmp")) {
                if (now - f.lastModified() > 5 * 60_000L) {
                    runCatching { f.delete() }
                }
            } else {
                imgFiles.add(f)
            }
        }
        if (imgFiles.size <= MAX_DISK_FILES) return
        val sorted = imgFiles.sortedBy { it.lastModified() }
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
