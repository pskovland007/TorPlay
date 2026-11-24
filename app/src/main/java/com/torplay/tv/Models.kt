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
            val gb = sizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) {
                String.format(Locale.US, "%.2f ГБ", gb)
            } else {
                val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                String.format(Locale.US, "%.0f МБ", mb)
            }
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
}

data class TorrServeInfo(
    val hash: String,
    val title: String,
    val stat: Int,
    val statString: String,
    val files: List<TorrServeFile>
)
