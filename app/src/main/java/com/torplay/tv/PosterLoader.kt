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
import java.util.concurrent.TimeUnit

class PosterLoader(context: Context) {

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun load(scope: CoroutineScope, imageView: ImageView, url: String) {
        if (url.isBlank()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        imageView.tag = url
        val cached = memoryCache.get(url)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                fetchOrDecode(url)
            }
            if (bitmap != null && imageView.tag == url) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchOrDecode(url: String): Bitmap? {
        val diskFile = File(cacheDir, "${url.hashCode().toUInt().toString(16)}.img")
        if (diskFile.exists() && diskFile.length() > 128L) {
            decodeSampled(diskFile)?.let { bmp ->
                memoryCache.put(url, bmp)
                return bmp
            }
        }

        val req = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", "TorPlay-TV")
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        }

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.size < 128) return@use null
                diskFile.writeBytes(bytes)
                pruneDiskCacheIfNeeded()
                val bmp = decodeSampled(diskFile)
                if (bmp != null) {
                    memoryCache.put(url, bmp)
                }
                bmp
            }
        }.getOrNull()
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
        private const val MAX_DISK_FILES = 90
    }
}
