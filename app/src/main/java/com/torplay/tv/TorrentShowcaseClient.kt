package com.torplay.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class TorrentShowcaseShelves(
    val popularMovies: List<TorrentItem>,
    val newestMovies: List<TorrentItem>,
    val popularSeries: List<TorrentItem>,
    val sourceName: String = "",
    val error: String? = null
) {
    fun hasAnyItems(): Boolean {
        return popularMovies.isNotEmpty() || newestMovies.isNotEmpty() || popularSeries.isNotEmpty()
    }
}

class TorrentShowcaseClient(private val storage: StorageManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class TaggedTorrent(
        val item: TorrentItem,
        val isSeries: Boolean,
        val isExplicitMovie: Boolean,
        val isNonCinemaJunk: Boolean
    )

    suspend fun fetchShelves(): TorrentShowcaseShelves = withContext(Dispatchers.IO) {
        val endpoints = buildEndpoints()
        if (endpoints.isEmpty()) {
            return@withContext TorrentShowcaseShelves(
                emptyList(), emptyList(), emptyList(),
                error = "Источники JacRed отключены в настройках"
            )
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val prevYear = currentYear - 1
        var lastErr: String? = null

        for ((label, baseUrl, apiKey) in endpoints) {
            val attempt = runCatching {
                coroutineScope {
                    val moviesCur1080Def = async {
                        runCatching { fetchJacRedQuery(baseUrl, apiKey, "1080p $currentYear") }
                            .getOrDefault(emptyList())
                    }
                    val moviesCur4kDef = async {
                        runCatching { fetchJacRedQuery(baseUrl, apiKey, "2160p $currentYear") }
                            .getOrDefault(emptyList())
                    }
                    val moviesPrev1080Def = async {
                        runCatching { fetchJacRedQuery(baseUrl, apiKey, "1080p $prevYear") }
                            .getOrDefault(emptyList())
                    }
                    val seriesDef = async {
                        runCatching {
                            val ySeries = fetchJacRedQuery(baseUrl, apiKey, "сезон 1080p $currentYear")
                            if (ySeries.size >= 14) {
                                ySeries
                            } else {
                                ySeries + fetchJacRedQuery(baseUrl, apiKey, "сезон $currentYear") +
                                    fetchJacRedQuery(baseUrl, apiKey, "сезон 1080p $prevYear")
                            }
                        }.getOrDefault(emptyList())
                    }

                    val curMoviesRaw = moviesCur1080Def.await() + moviesCur4kDef.await()
                    val prevMoviesRaw = moviesPrev1080Def.await()
                    val seriesRaw = seriesDef.await()

                    val combinedMoviesAll = (curMoviesRaw + prevMoviesRaw)
                        .filter { !it.isNonCinemaJunk && !it.isSeries && isGoodMovieCandidate(it.item) }
                        .map { it.item }

                    val strongMovies = combinedMoviesAll.filter { it.seeds >= 15 }
                    val moviePool = if (strongMovies.size >= 8) strongMovies else combinedMoviesAll

                    val popularMovies = deduplicatePreferBest(
                        items = moviePool.sortedWith(
                            compareByDescending<TorrentItem> { it.seeds }
                                .thenByDescending { qualityRank(it.quality) }
                                .thenByDescending { it.createTime }
                        ),
                        maxCount = 18
                    )

                    val freshSource = curMoviesRaw
                        .filter { !it.isNonCinemaJunk && !it.isSeries && isGoodMovieCandidate(it.item) && it.item.seeds >= 5 }
                        .map { it.item }
                        .ifEmpty { combinedMoviesAll }

                    val newestMovies = deduplicatePreferBest(
                        items = freshSource.sortedWith(
                            compareByDescending<TorrentItem> { it.createTime }
                                .thenByDescending { it.seeds }
                                .thenByDescending { qualityRank(it.quality) }
                        ),
                        maxCount = 18
                    )

                    val allSeries = (seriesRaw + curMoviesRaw.filter { it.isSeries } + prevMoviesRaw.filter { it.isSeries })
                        .filter { !it.isNonCinemaJunk && isGoodSeriesCandidate(it.item) }
                        .map { it.item }

                    val strongSeries = allSeries.filter { it.seeds >= 10 }
                    val seriesPool = if (strongSeries.size >= 6) strongSeries else allSeries

                    val popularSeries = deduplicatePreferBest(
                        items = seriesPool.sortedWith(
                            compareByDescending<TorrentItem> { it.seeds }
                                .thenByDescending { it.createTime }
                                .thenByDescending { qualityRank(it.quality) }
                        ),
                        maxCount = 18
                    )

                    TorrentShowcaseShelves(
                        popularMovies = popularMovies,
                        newestMovies = newestMovies,
                        popularSeries = popularSeries,
                        sourceName = label,
                        error = null
                    )
                }
            }.onFailure { err ->
                lastErr = err.message?.take(48) ?: "Нет ответа"
            }.getOrNull()

            if (attempt != null && attempt.hasAnyItems()) {
                return@withContext attempt
            }
        }

        TorrentShowcaseShelves(
            emptyList(),
            emptyList(),
            emptyList(),
            error = lastErr ?: "Не удалось загрузить подборки с JacRed"
        )
    }

    private fun buildEndpoints(): List<Triple<String, String, String>> {
        val list = mutableListOf<Triple<String, String, String>>()
        val custom = storage.customParserUrl.trim().trimEnd('/')
        if (custom.isNotBlank()) {
            list.add(Triple("Свой сервер", custom, storage.parserApiKey))
        }
        if (storage.usePublicJacRed) {
            for (mirror in PUBLIC_JACRED_MIRRORS) {
                val host = mirror.removePrefix("https://").removePrefix("http://").substringBefore('/')
                list.add(Triple("JacRed ($host)", mirror, ""))
            }
        }
        return list
    }

    private fun fetchJacRedQuery(baseUrl: String, apiKey: String, query: String): List<TaggedTorrent> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append(baseUrl).append("/api/v1.0/torrents?search=").append(encoded)
            if (apiKey.isNotBlank()) {
                append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "TorPlay-TV")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank() || !raw.startsWith("[")) return emptyList()

            val arr = JSONArray(raw)
            val result = mutableListOf<TaggedTorrent>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseJacRedObject(obj)?.let { result.add(it) }
            }
            return result
        }
    }

    private fun parseJacRedObject(obj: JSONObject): TaggedTorrent? {
        val title = obj.optString("title").trim()
        val magnet = obj.optString("magnet").trim()
        val torrentUrl = obj.optString("link").trim()
        if (title.isBlank() || (magnet.isBlank() && torrentUrl.isBlank())) return null

        val sizeBytes = obj.optLong("size", 0L)
        val sizeText = obj.optString("sizeName").trim()
        val seeds = obj.optInt("sid", 0).coerceAtLeast(0)
        val peers = obj.optInt("pir", 0).coerceAtLeast(0)
        val tracker = obj.optString("tracker").trim()

        val rawQuality = obj.optInt("quality", 0)
        val quality = when {
            rawQuality >= 2160 || title.contains("2160", true) || title.contains("4K", true) || title.contains("UHD", true) -> "4K"
            rawQuality >= 1080 || title.contains("1080", true) -> "1080p"
            rawQuality >= 720 || title.contains("720", true) -> "720p"
            else -> ""
        }

        val videoType = obj.optString("videotype").trim().lowercase(Locale.US)
        val isHdr = videoType.contains("hdr") || videoType.contains("dolby") ||
            title.contains("HDR", true) || title.contains("Dolby Vision", true) || title.contains("DV", false)

        val voices = mutableListOf<String>()
        val vArr = obj.optJSONArray("voices")
        if (vArr != null) {
            for (i in 0 until vArr.length()) {
                val v = vArr.optString(i).trim()
                if (v.isNotBlank()) voices.add(v)
            }
        }

        val createTime = parseCreateTime(obj.opt("createTime"))

        var isSeries = false
        var isExplicitMovie = false
        var isNonCinemaJunk = false
        val typesArr = obj.optJSONArray("types")
        if (typesArr != null) {
            for (i in 0 until typesArr.length()) {
                val t = typesArr.optString(i).lowercase(Locale.US)
                if (t.contains("serial") || t == "tv") {
                    isSeries = true
                }
                if (t.contains("movie") || t.contains("film") || t.contains("multfilm")) {
                    isExplicitMovie = true
                }
                if (t.contains("sport") || t.contains("tvshow") || t.contains("docum") ||
                    t.contains("music") || t.contains("concert") || t.contains("xxx") ||
                    t.contains("porno") || t.contains("game") || t.contains("soft") ||
                    t.contains("book") || t.contains("clip") || t.contains("humor") ||
                    t.contains("anime")
                ) {
                    isNonCinemaJunk = true
                }
            }
        }
        if (JUNK_TITLE_REGEX.containsMatchIn(title)) {
            isNonCinemaJunk = true
        }
        if (!isSeries && SERIES_TITLE_REGEX.containsMatchIn(title)) {
            isSeries = true
        }

        val item = TorrentItem(
            title = title,
            magnet = magnet,
            torrentUrl = torrentUrl,
            sizeBytes = sizeBytes,
            sizeText = sizeText,
            seeds = seeds,
            peers = peers,
            tracker = tracker,
            quality = quality,
            isHdr = isHdr,
            voices = voices.take(2),
            createTime = createTime
        )
        return TaggedTorrent(
            item = item,
            isSeries = isSeries,
            isExplicitMovie = isExplicitMovie,
            isNonCinemaJunk = isNonCinemaJunk
        )
    }

    private fun isGoodMovieCandidate(item: TorrentItem): Boolean {
        if (item.playableLink.isBlank()) return false
        if (item.seeds < 3) return false
        if (item.isCamRelease()) return false
        if (item.sizeBytes in 1L until 700L * 1024L * 1024L) return false
        return true
    }

    private fun isGoodSeriesCandidate(item: TorrentItem): Boolean {
        if (item.playableLink.isBlank()) return false
        if (item.seeds < 3) return false
        if (item.isCamRelease()) return false
        if (item.sizeBytes in 1L until 500L * 1024L * 1024L) return false
        return true
    }

    private fun deduplicatePreferBest(items: List<TorrentItem>, maxCount: Int): List<TorrentItem> {
        val seen = HashSet<String>()
        val out = mutableListOf<TorrentItem>()
        for (item in items) {
            val key = normalizeTitleKey(item.title)
            if (key.length < 2) continue
            if (seen.add(key)) {
                out.add(item)
                if (out.size >= maxCount) break
            }
        }
        return out
    }

    private fun qualityRank(quality: String): Int {
        return when (quality) {
            "4K" -> 3
            "1080p" -> 2
            "720p" -> 1
            else -> 0
        }
    }

    private fun parseCreateTime(raw: Any?): Long {
        when (raw) {
            is Number -> {
                val n = raw.toLong()
                if (n <= 0L) return 0L
                return if (n < 10_000_000_000L) n * 1000L else n
            }
            is String -> {
                val s = raw.trim()
                if (s.isBlank()) return 0L
                s.toLongOrNull()?.let { num ->
                    return if (num < 10_000_000_000L) num * 1000L else num
                }
                val clean = s.replace(Regex("\\.\\d+"), "").replace("Z", "+0000")
                for (pattern in DATE_PATTERNS) {
                    runCatching {
                        val sdf = SimpleDateFormat(pattern, Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        sdf.parse(clean)?.time
                    }.getOrNull()?.let { parsed ->
                        if (parsed > 0L) return parsed
                    }
                }
            }
        }
        return 0L
    }

    companion object {
        private val PUBLIC_JACRED_MIRRORS = listOf(
            "https://jacred.xyz",
            "https://jr.maxvol.pro",
            "https://jac.red"
        )

        private val DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )

        private val SERIES_TITLE_REGEX = Regex(
            "(?:сезон|серии|серия|s\\d{1,2}e\\d{1,2}|\\bs\\d{1,2}\\b|\\[\\d+\\s*-\\s*\\d+\\s*из)",
            RegexOption.IGNORE_CASE
        )

        private val JUNK_TITLE_REGEX = Regex(
            "(?:\\bufc\\b|\\bwwe\\b|formula\\s*1|формула\\s*1|футбол|хоккей|матч\\s*тв|\\bкхл\\b|\\bнхл\\b|\\bnba\\b|\\bnhl\\b|лига\\s+чемпионов|чемпионат\\s+мира|гран-при|концерт|выпуск\\s*№?|выпуски|ток-шоу|стендап|stand\\s*up|comedy\\s*club|\\bквн\\b|передача|новости|аудиокнига|саундтрек|\\bflac\\b|\\bmp3\\b|repack|portable|ps4|ps5|xbox|nintendo\\s+switch|\\bxxx\\b|порно)",
            RegexOption.IGNORE_CASE
        )

        private val CLEAN_BRACKETS_REGEX = Regex("[\\[\\(].*?[\\]\\)]")
        private val CLEAN_TECH_REGEX = Regex(
            "\\b(?:2160p|1080p|720p|4k|uhd|hdr|hdr10|dv|dolby|web-?dl|webrip|bdrip|bdremux|remux|blu-?ray|hdtv|hevc|x265|x264|h264|h265|avc|ac3|dts|rus|eng|sub|сезон.*|серии.*|s\\d{1,2}.*)\\b.*",
            RegexOption.IGNORE_CASE
        )
        private val YEAR_SPLIT_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]+")

        fun extractCleanSearchQuery(torrentTitle: String): String {
            var s = torrentTitle.trim()
            if (s.contains(" / ")) {
                s = s.substringBefore(" / ").trim()
            }
            s = CLEAN_BRACKETS_REGEX.replace(s, " ")
            s = CLEAN_TECH_REGEX.replace(s, " ")
            val yearMatch = YEAR_SPLIT_REGEX.find(s)
            if (yearMatch != null && yearMatch.range.first > 2) {
                s = s.substring(0, yearMatch.range.first)
            }
            s = s.replace(Regex("[._|]+"), " ").replace(Regex("\\s+"), " ").trim()
            return s.ifBlank { torrentTitle.take(40).trim() }
        }

        private fun normalizeTitleKey(torrentTitle: String): String {
            val clean = extractCleanSearchQuery(torrentTitle)
            return NON_ALNUM_REGEX.replace(clean.lowercase(Locale.ROOT), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
