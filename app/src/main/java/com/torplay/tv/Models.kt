package com.torplay.tv

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
    val voices: List<String> = emptyList()
) {
    val playableLink: String
        get() = magnet.ifBlank { torrentUrl }

    fun formattedSize(): String {
        if (sizeBytes > 0L) {
            return formatBytes(sizeBytes)
        }
        return sizeText.ifBlank { "—" }
    }

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

fun formatBytes(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f ГБ", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.US, "%.0f МБ", mb)
    }
}
