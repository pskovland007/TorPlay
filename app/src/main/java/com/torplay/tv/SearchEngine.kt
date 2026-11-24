package com.torplay.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class SearchEngine(private val storage: StorageManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val publicJacRedMirrors = listOf(
        "https://jacred.xyz",
        "https://jr.maxvol.pro",
        "https://jac.red"
    )

    private val fallbackRutorMirrors = listOf(
        "https://rutor.is",
        "http://rutor.info"
    )

    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext SearchResponse(emptyList(), "", "Пустой запрос")
        }

        val errors = mutableListOf<String>()

        // 1. Custom user parser (JacRed or Jackett Torznab)
        val customUrl = storage.customParserUrl
        if (customUrl.isNotBlank()) {
            val apiKey = storage.parserApiKey
            val customItems = if (customUrl.contains("torznab", ignoreCase = true) ||
                customUrl.contains("/api/v2.0/indexers", ignoreCase = true)
            ) {
                runCatching { queryTorznab(customUrl, apiKey, cleanQuery) }
                    .onFailure { errors.add("Jackett: ${it.message}") }
                    .getOrNull()
            } else {
                runCatching { queryJacRed(customUrl, apiKey, cleanQuery) }
                    .onFailure { errors.add("Custom JacRed: ${it.message}") }
                    .getOrNull()
            }

            if (!customItems.isNullOrEmpty()) {
                return@withContext SearchResponse(
                    items = sortAndDeduplicate(customItems),
                    sourceName = hostLabel(customUrl)
                )
            }
        }

        // 2. Built-in public JacRed mirrors
        for (mirror in publicJacRedMirrors) {
            val items = runCatching { queryJacRed(mirror, "", cleanQuery) }
                .onFailure { errors.add("${hostLabel(mirror)}: ${it.message}") }
                .getOrNull()

            if (!items.isNullOrEmpty()) {
                return@withContext SearchResponse(
                    items = sortAndDeduplicate(items),
                    sourceName = "JacRed (${hostLabel(mirror)})"
                )
            }
        }

        // 3. Direct fallback parser (Rutor open mirrors)
        val rutorMirrors = linkedSetOf(storage.rutorMirrorUrl).apply {
            addAll(fallbackRutorMirrors)
        }
        for (mirror in rutorMirrors) {
            val items = runCatching { queryRutorHtml(mirror, cleanQuery) }
                .onFailure { errors.add("${hostLabel(mirror)}: ${it.message}") }
                .getOrNull()

            if (!items.isNullOrEmpty()) {
                return@withContext SearchResponse(
                    items = sortAndDeduplicate(items),
                    sourceName = "Rutor (${hostLabel(mirror)})"
                )
            }
        }

        val summaryError = if (errors.isNotEmpty()) {
            "Источники не ответили (${errors.first()})"
        } else {
            null
        }
        SearchResponse(emptyList(), "", summaryError)
    }

    private fun queryJacRed(baseUrl: String, apiKey: String, query: String): List<TorrentItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val normalized = baseUrl.trimEnd('/')
        val url = buildString {
            append(normalized)
            if (!normalized.endsWith("/api/v1.0/torrents")) {
                append("/api/v1.0/torrents")
            }
            append("?search=").append(encoded)
            if (apiKey.isNotBlank()) {
                append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty().trim()
            if (body.isBlank() || (!body.startsWith("[") && !body.startsWith("{"))) {
                return emptyList()
            }

            val array = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                val root = JSONObject(body)
                root.optJSONArray("Results")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("torrents")
                    ?: JSONArray()
            }

            val list = mutableListOf<TorrentItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title").ifBlank { obj.optString("Title") }.trim()
                val magnet = obj.optString("magnet").ifBlank { obj.optString("MagnetUri") }.trim()
                val link = obj.optString("link").ifBlank { obj.optString("Link") }.trim()
                if (title.isBlank() || (magnet.isBlank() && link.isBlank())) continue

                val sizeBytes = obj.optLong("size", obj.optLong("Size", 0L))
                val sizeName = obj.optString("sizeName", "").trim()
                val sid = obj.optInt("sid", obj.optInt("Seeders", 0))
                val pir = obj.optInt("pir", obj.optInt("Peers", 0))
                val tracker = obj.optString("tracker").ifBlank { obj.optString("Tracker") }.trim()

                val qualityInt = obj.optInt("quality", 0)
                val videoType = obj.optString("videotype", "").lowercase(Locale.US)
                val quality = detectQuality(qualityInt, title)
                val isHdr = videoType.contains("hdr") ||
                    videoType.contains("dolby") ||
                    detectHdr(title)

                val voices = mutableListOf<String>()
                val voicesArr = obj.optJSONArray("voices")
                if (voicesArr != null) {
                    for (v in 0 until voicesArr.length()) {
                        val voice = voicesArr.optString(v).trim()
                        if (voice.isNotBlank()) voices.add(voice)
                    }
                }

                list.add(
                    TorrentItem(
                        title = title,
                        magnet = magnet,
                        torrentUrl = link,
                        sizeBytes = sizeBytes,
                        sizeText = sizeName,
                        seeds = sid,
                        peers = pir,
                        tracker = tracker,
                        quality = quality,
                        isHdr = isHdr,
                        voices = voices
                    )
                )
            }
            return list
        }
    }

    private fun queryTorznab(baseUrl: String, apiKey: String, query: String): List<TorrentItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = buildString {
            append(baseUrl)
            append(separator).append("t=search&q=").append(encoded)
            if (apiKey.isNotBlank() && !baseUrl.contains("apikey=", ignoreCase = true)) {
                append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val xml = response.body?.string().orEmpty()
            if (xml.isBlank()) return emptyList()
            return parseTorznabXml(xml)
        }
    }

    private fun parseTorznabXml(xml: String): List<TorrentItem> {
        val list = mutableListOf<TorrentItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var inItem = false
        var title = ""
        var link = ""
        var magnet = ""
        var size = 0L
        var seeds = 0
        var peers = 0
        var tracker = "Jackett"

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        magnet = ""
                        size = 0L
                        seeds = 0
                        peers = 0
                        tracker = "Jackett"
                    } else if (inItem) {
                        when (tag) {
                            "title" -> title = parser.nextText().trim()
                            "link" -> link = parser.nextText().trim()
                            "size" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                            "jackettindexer" -> tracker = parser.nextText().trim().ifBlank { "Jackett" }
                            "enclosure" -> {
                                val encUrl = parser.getAttributeValue(null, "url").orEmpty()
                                if (encUrl.startsWith("magnet:")) magnet = encUrl
                                else if (link.isBlank()) link = encUrl
                                if (size == 0L) {
                                    size = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                                }
                            }
                            "torznab:attr", "attr" -> {
                                val attrName = parser.getAttributeValue(null, "name").orEmpty().lowercase(Locale.US)
                                val attrVal = parser.getAttributeValue(null, "value").orEmpty()
                                when (attrName) {
                                    "seeders" -> seeds = attrVal.toIntOrNull() ?: 0
                                    "peers" -> peers = attrVal.toIntOrNull() ?: 0
                                    "magneturl" -> if (attrVal.startsWith("magnet:")) magnet = attrVal
                                    "size" -> if (size == 0L) size = attrVal.toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        inItem = false
                        if (title.isNotBlank() && (magnet.isNotBlank() || link.isNotBlank())) {
                            list.add(
                                TorrentItem(
                                    title = title,
                                    magnet = magnet,
                                    torrentUrl = link,
                                    sizeBytes = size,
                                    seeds = seeds,
                                    peers = peers,
                                    tracker = tracker,
                                    quality = detectQuality(0, title),
                                    isHdr = detectHdr(title)
                                )
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun queryRutorHtml(baseUrl: String, query: String): List<TorrentItem> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "${baseUrl.trimEnd('/')}/search/0/0/010/2/$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            return parseRutorRows(html)
        }
    }

    private fun parseRutorRows(html: String): List<TorrentItem> {
        val results = mutableListOf<TorrentItem>()
        val rowRegex = Regex("<tr class="(?:gai|tum)">(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val magnetRegex = Regex("href="(magnet:\?xt=urn:btih:[^"]+)"", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<a href="/torrent/[^"]+">([^<]+)</a>", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("<td align="right">([^<]+?(?:GB|MB|ГБ|МБ))</td>", RegexOption.IGNORE_CASE)
        val seedsRegex = Regex("<span class="green">.*?(\d+)\s*</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (match in rowRegex.findAll(html)) {
            val row = match.groupValues[1]
            val magnet = magnetRegex.find(row)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.trim()
                .orEmpty()
            if (magnet.isBlank()) continue

            val rawTitle = titleRegex.find(row)?.groupValues?.get(1).orEmpty()
            val title = decodeHtmlEntities(rawTitle).trim()
            if (title.isBlank()) continue

            val sizeText = decodeHtmlEntities(sizeRegex.find(row)?.groupValues?.get(1).orEmpty()).trim()
            val sizeBytes = parseHumanSizeToBytes(sizeText)
            val seeds = seedsRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            results.add(
                TorrentItem(
                    title = title,
                    magnet = magnet,
                    sizeBytes = sizeBytes,
                    sizeText = sizeText,
                    seeds = seeds,
                    tracker = "rutor",
                    quality = detectQuality(0, title),
                    isHdr = detectHdr(title)
                )
            )
        }
        return results
    }

    private fun sortAndDeduplicate(items: List<TorrentItem>): List<TorrentItem> {
        val seen = HashSet<String>()
        return items
            .filter { item ->
                val key = extractInfoHash(item.magnet).ifBlank {
                    "${item.title.lowercase(Locale.US)}_${item.sizeBytes}"
                }
                seen.add(key)
            }
            .sortedWith(compareByDescending<TorrentItem> { it.seeds }.thenByDescending { it.sizeBytes })
            .take(80)
    }

    private fun extractInfoHash(magnet: String): String {
        val m = Regex("btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(magnet)
        return m?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()
    }

    private fun detectQuality(qualityInt: Int, title: String): String {
        if (qualityInt >= 2160) return "4K"
        if (qualityInt in 1000..1200) return "1080p"
        if (qualityInt in 700..800) return "720p"

        val t = title.lowercase(Locale.US)
        return when {
            t.contains("2160p") || t.contains("4k") || t.contains("uhd") -> "4K"
            t.contains("1080p") || t.contains("1080i") || t.contains("bdremux") -> "1080p"
            t.contains("720p") -> "720p"
            t.contains("bdrip") || t.contains("web-dl") || t.contains("webrip") -> "HD"
            else -> "SD"
        }
    }

    private fun detectHdr(title: String): Boolean {
        val t = title.lowercase(Locale.US)
        return t.contains("hdr") || t.contains("dolby vision") || Regex("\bdv\b").containsMatchIn(t)
    }

    private fun parseHumanSizeToBytes(text: String): Long {
        val clean = text.replace("&nbsp;", " ").replace(' ', ' ').trim()
        val match = Regex("([0-9]+(?:[.,][0-9]+)?)\s*(GB|MB|ГБ|МБ)", RegexOption.IGNORE_CASE).find(clean)
            ?: return 0L
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase(Locale.US)
        return when (unit) {
            "GB", "ГБ" -> (value * 1024.0 * 1024.0 * 1024.0).toLong()
            "MB", "МБ" -> (value * 1024.0 * 1024.0).toLong()
            else -> 0L
        }
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", """)
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun hostLabel(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; MIBOX4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
