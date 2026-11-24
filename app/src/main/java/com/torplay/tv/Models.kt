package com.torplay.tv

import java.util.Calendar
import java.util.Locale

data class TorrentItem(
    val title: String,
    val magnet: String,
    val torrentUrl: String = "",
    val sizeBytes: Long = 0L,
    val sizeText: String = "",
    val seeds: Int = 0,
    val peers: Int = 0,
    val tracker: String = "",
    val quality: String = "",
    val isHdr: Boolean = false,
    val voices: List<String> = emptyList(),
    val createTime: Long = 0L
) {
    val playableLink: String
        get() = magnet.ifBlank { torrentUrl }

    fun formattedSize(): String {
        if (sizeBytes > 0L) {
            return formatBytes(sizeBytes)
        }
        return sizeText.ifBlank { "—" }
    }

    fun formattedDate(): String = formatDate(createTime)

    fun formattedSource(): String {
        val parts = mutableListOf<String>()
        if (tracker.isNotBlank()) parts.add(tracker)
        if (voices.isNotEmpty()) parts.add(voices.take(2).joinToString(", "))
        return parts.joinToString(" • ")
    }
}

data class SearchResponse(
    val items: List<TorrentItem>,
    val sourceName: String,
    val error: String? = null
)

data class MirrorCheckResult(
    val name: String,
    val url: String,
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String
)

data class TorrServeFile(
    val id: Int,
    val path: String,
    val length: Long
) {
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "video_$id.mkv" }

    fun formattedSize(): String = if (length > 0L) formatBytes(length) else "—"
}

data class TorrServeInfo(
    val hash: String,
    val title: String,
    val stat: Int,
    val statString: String,
    val files: List<TorrServeFile>,
    val activePeers: Int = 0,
    val totalPeers: Int = 0,
    val connectedSeeders: Int = 0,
    val downloadSpeed: Double = 0.0,
    val preloadedBytes: Long = 0L,
    val preloadSize: Long = 0L
) {
    fun preloadPercent(): Int {
        if (preloadSize <= 0L) return 0
        return ((preloadedBytes * 100L) / preloadSize).toInt().coerceIn(0, 100)
    }

    fun formattedSpeed(): String {
        val mbps = downloadSpeed / (1024.0 * 1024.0)
        return if (mbps >= 0.1) {
            String.format(Locale.US, "%.1f МБ/с", mbps)
        } else {
            val kbps = downloadSpeed / 1024.0
            String.format(Locale.US, "%.0f КБ/с", kbps)
        }
    }
}

data class WatchHistoryEntry(
    val id: String,
    val title: String,
    val link: String,
    val fileId: Int,
    val episodeLabel: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    fun progressText(): String {
        if (durationMs <= 0L || positionMs <= 0L) {
            return if (episodeLabel.isNotBlank()) episodeLabel else "Начато"
        }
        val cur = formatDuration(positionMs)
        val tot = formatDuration(durationMs)
        return if (episodeLabel.isNotBlank()) {
            "$episodeLabel • $cur / $tot"
        } else {
            "$cur / $tot"
        }
    }
}

data class FavoriteQuery(
    val query: String,
    val addedAt: Long
)

fun formatBytes(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f ГБ", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.US, "%.0f МБ", mb)
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

fun formatDate(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val cal = Calendar.getInstance()
    cal.timeInMillis = epochMs
    val year = cal.get(Calendar.YEAR)
    if (year < 1990 || year > 2100) return ""
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val month = cal.get(Calendar.MONTH) + 1
    return String.format(Locale.US, "%02d.%02d.%04d", day, month, year)
}
