package com.torplay.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

object TrailerLauncher {

    private val PREFERRED_VIDEO_PACKAGES = listOf(
        "com.teamsmart.videomanager.tv",      // SmartTube (stable)
        "com.liskovsoft.smarttubetv.beta",    // SmartTube Beta
        "com.liskovsoft.smarttubetv",         // SmartTube Next
        "com.liskovsoft.videomanager2",       // SmartTube legacy
        "com.google.android.youtube.tv",      // YouTube for Android TV
        "com.google.android.youtube"          // YouTube mobile/tablet
    )

    fun openTrailer(context: Context, directUrl: String?, fallbackTitle: String, fallbackYear: String = "") {
        val cleanDirect = normalizeTrailerUrl(directUrl.orEmpty())
        val targetUrl = if (cleanDirect.isNotBlank()) {
            cleanDirect
        } else {
            buildYoutubeSearchUrl(fallbackTitle, fallbackYear)
        }
        val uri = Uri.parse(targetUrl)

        if (isYoutubeUri(targetUrl)) {
            for (pkg in PREFERRED_VIDEO_PACKAGES) {
                if (!isPackageInstalled(context, pkg)) continue
                val pkgIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { context.startActivity(pkgIntent) }.isSuccess) {
                    AppLogger.i("Trailer", "Открыт трейлер через $pkg: $targetUrl")
                    return
                }
            }
        }

        val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(genericIntent)
            AppLogger.i("Trailer", "Открыт трейлер через системный обработчик: $targetUrl")
        }.onFailure {
            Toast.makeText(
                context,
                "Не найдено приложение для просмотра трейлера (установите SmartTube или YouTube)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun normalizeTrailerUrl(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            // Convert youtube embed links to standard watch URL so SmartTube / YouTube TV handle them cleanly
            val embedMatch = YT_EMBED_REGEX.find(clean)
            if (embedMatch != null) {
                return "https://www.youtube.com/watch?v=${embedMatch.groupValues[1]}"
            }
            return clean
        }
        if (YT_KEY_REGEX.matches(clean)) {
            return "https://www.youtube.com/watch?v=$clean"
        }
        return ""
    }

    private fun buildYoutubeSearchUrl(title: String, year: String): String {
        val query = buildString {
            append(title.trim())
            if (year.length == 4) {
                append(' ').append(year)
            }
            append(" трейлер")
        }.trim()
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        return "https://www.youtube.com/results?search_query=$encoded"
    }

    private fun isYoutubeUri(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null ||
                context.packageManager.getPackageInfo(packageName, 0) != null
        }.getOrDefault(false)
    }

    private val YT_EMBED_REGEX = Regex(
        "(?:youtube\\.com/embed/|youtube-nocookie\\.com/embed/|youtu\\.be/)([A-Za-z0-9_-]{8,15})",
        RegexOption.IGNORE_CASE
    )
    private val YT_KEY_REGEX = Regex("^[A-Za-z0-9_-]{8,15}$")
}
