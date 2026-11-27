package com.torplay.tv

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

data class CatalogItem(
    val id: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String,
    val year: String,
    val rating: Double,
    val overview: String,
    val posterPath: String,
    val genreNames: List<String>,
    val source: String = "tmdb",
    val runtimeMinutes: Int = 0,
    val bestQuality: String = "",
    val bestSeeds: Int = 0,
    val bestPeers: Int = 0
) {
    fun isSeries(): Boolean = mediaType.equals("tv", ignoreCase = true)

    fun stableKey(): String {
        return "$source:$mediaType:$id:${title.trim().lowercase(Locale.ROOT)}"
    }

    fun titleWithYear(): String {
        val cleanTitle = title.trim()
        val cleanYear = year.trim()
        if (cleanYear.length == 4 && !cleanTitle.contains("($cleanYear)")) {
            return "$cleanTitle ($cleanYear)"
        }
        return cleanTitle
    }

    fun primaryGenre(): String {
        return genreNames.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun formattedRating(): String {
        if (rating <= 0.05) return ""
        return String.format(Locale.US, "★ %.1f", rating)
    }

    fun formattedDuration(): String {
        val mins = runtimeMinutes
        if (mins <= 0) return ""
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 && m > 0 -> "${h}ч ${m}м"
            h > 0 -> "${h}ч"
            else -> "$m мин"
        }
    }

    fun hasTorrentStats(): Boolean {
        return bestSeeds > 0 || bestQuality.isNotBlank()
    }

    fun formattedTorrentStats(): String {
        val parts = mutableListOf<String>()
        if (bestQuality.isNotBlank()) parts.add(bestQuality)
        if (bestSeeds > 0) {
            val peersPart = if (bestPeers > bestSeeds) "/$bestPeers" else ""
            parts.add("▲ $bestSeeds$peersPart")
        }
        return parts.joinToString(" • ")
    }

    fun torrentSearchQuery(): String {
        return if (!isSeries() && year.length == 4) {
            "$title $year"
        } else {
            title
        }
    }

    fun fallbackSearchQueries(): List<String> {
        val primary = torrentSearchQuery().trim()
        val list = mutableListOf<String>()
        val cleanTitle = title.trim()
        if (cleanTitle.isNotBlank() && !cleanTitle.equals(primary, ignoreCase = true)) {
            list.add(cleanTitle)
        }
        val cleanOrig = originalTitle.trim()
        if (cleanOrig.isNotBlank() &&
            !cleanOrig.equals(primary, ignoreCase = true) &&
            list.none { it.equals(cleanOrig, ignoreCase = true) }
        ) {
            list.add(cleanOrig)
        }
        return list
    }
}

data class CatalogShelves(
    val trending: List<CatalogItem>,
    val nowPlaying: List<CatalogItem>,
    val popularSeries: List<CatalogItem>,
    val error: String? = null
) {
    fun hasAnyItems(): Boolean {
        return trending.isNotEmpty() || nowPlaying.isNotEmpty() || popularSeries.isNotEmpty()
    }
}

class TmdbClient(private val storage: StorageManager) {

    private val runtimeCache = ConcurrentHashMap<String, Int>()

    private val client: OkHttpClient
        get() = NetworkHelper.getExternalClient(storage, connectSec = 7L, readSec = 10L)

    private fun probeClient(overrideConfig: NetworkHelper.NetworkConfig? = null): OkHttpClient {
        return NetworkHelper.getExternalClient(storage, connectSec = 5L, readSec = 5L, overrideConfig = overrideConfig)
    }

    fun buildPosterUrl(posterPath: String): String {
        val cleanPath = posterPath.trim()
        if (cleanPath.isBlank()) return ""
        if (cleanPath.startsWith("http://", ignoreCase = true) || cleanPath.startsWith("https://", ignoreCase = true)) {
            return cleanPath
        }
        val base = storage.tmdbImageBaseUrl.trimEnd('/')
        val normalizedPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return "$base$normalizedPath"
    }

    suspend fun fetchShelves(): CatalogShelves = withContext(Dispatchers.IO) {
        val apiKey = storage.tmdbApiKey
        if (apiKey.isBlank()) {
            return@withContext CatalogShelves(emptyList(), emptyList(), emptyList(), null)
        }

        val baseUrl = storage.tmdbApiBaseUrl
        var firstError: String? = null

        coroutineScope {
            val trendingDef = async {
                runCatching { fetchList(baseUrl, apiKey, "/trending/movie/week", "movie", "language=ru-RU") }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val nowDef = async {
                runCatching { fetchList(baseUrl, apiKey, "/movie/now_playing", "movie", "language=ru-RU&region=RU") }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val seriesDef = async {
                runCatching { fetchList(baseUrl, apiKey, "/tv/popular", "tv", "language=ru-RU") }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }

            val trending = trendingDef.await()
            val nowPlaying = nowDef.await()
            val series = seriesDef.await()

            val err = if (trending.isEmpty() && nowPlaying.isEmpty() && series.isEmpty()) {
                firstError ?: "Пустой ответ TMDB"
            } else {
                null
            }
            CatalogShelves(
                trending = trending,
                nowPlaying = nowPlaying,
                popularSeries = series,
                error = err
            )
        }
    }

    suspend fun fetchDailyTopCandidates(dateSeed: Long): List<CatalogItem> = withContext(Dispatchers.IO) {
        val apiKey = storage.tmdbApiKey
        if (apiKey.isBlank()) return@withContext emptyList()

        val baseUrl = storage.tmdbApiBaseUrl
        val rng = Random(dateSeed)
        val topRatedPage = 1 + rng.nextInt(18)
        val popularHighPage = 1 + rng.nextInt(14)

        coroutineScope {
            val topRatedDef = async {
                runCatching {
                    fetchList(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        endpoint = "/discover/movie",
                        defaultType = "movie",
                        queryParams = "language=ru-RU&include_adult=false&sort_by=vote_average.desc&vote_count.gte=500&vote_average.gte=7.5&without_genres=99,10770&page=$topRatedPage",
                        maxItems = 20
                    )
                }.getOrDefault(emptyList())
            }
            val popHighDef = async {
                runCatching {
                    fetchList(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        endpoint = "/discover/movie",
                        defaultType = "movie",
                        queryParams = "language=ru-RU&include_adult=false&sort_by=popularity.desc&vote_count.gte=400&vote_average.gte=7.5&without_genres=99,10770&page=$popularHighPage",
                        maxItems = 20
                    )
                }.getOrDefault(emptyList())
            }

            val merged = LinkedHashMap<Int, CatalogItem>()
            for (item in topRatedDef.await() + popHighDef.await()) {
                if (item.id > 0 && item.rating >= 7.45 && item.title.isNotBlank()) {
                    merged.putIfAbsent(item.id, item)
                }
            }
            val list = merged.values.toMutableList()
            list.shuffle(rng)
            list.take(28)
        }
    }

    suspend fun fetchRuntimeMinutes(id: Int, isSeries: Boolean): Int = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext 0
        val cacheKey = "${if (isSeries) "tv" else "movie"}:$id"
        runtimeCache[cacheKey]?.let { return@withContext it }

        val apiKey = storage.tmdbApiKey
        if (apiKey.isBlank()) return@withContext 0

        val baseUrl = storage.tmdbApiBaseUrl
        val endpoint = if (isSeries) "/tv/$id" else "/movie/$id"
        val minutes = runCatching {
            val req = buildRequest(baseUrl, apiKey, endpoint, "language=ru-RU")
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use 0
                val raw = resp.body?.string().orEmpty().trim()
                if (!raw.startsWith("{")) return@use 0
                val obj = JSONObject(raw)
                if (!isSeries) {
                    obj.optInt("runtime", 0).coerceAtLeast(0)
                } else {
                    val epArr = obj.optJSONArray("episode_run_time")
                    var found = 0
                    if (epArr != null) {
                        for (i in 0 until epArr.length()) {
                            val v = epArr.optInt(i, 0)
                            if (v > 0) {
                                found = v
                                break
                            }
                        }
                    }
                    if (found <= 0) {
                        found = obj.optJSONObject("last_episode_to_air")?.optInt("runtime", 0) ?: 0
                    }
                    found.coerceAtLeast(0)
                }
            }
        }.getOrDefault(0)

        if (minutes > 0) {
            runtimeCache[cacheKey] = minutes
        }
        minutes
    }

    suspend fun searchMulti(query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val apiKey = storage.tmdbApiKey
        if (clean.isBlank() || apiKey.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val baseUrl = storage.tmdbApiBaseUrl
        runCatching {
            fetchList(
                baseUrl = baseUrl,
                apiKey = apiKey,
                endpoint = "/search/multi",
                defaultType = "movie",
                queryParams = "language=ru-RU&include_adult=false&query=$encoded",
                maxItems = 12
            )
        }.getOrDefault(emptyList())
    }

    suspend fun probeConnection(
        apiKeyInput: String,
        apiBaseUrlInput: String,
        networkOverride: NetworkHelper.NetworkConfig? = null
    ): MirrorCheckResult =
        withContext(Dispatchers.IO) {
            val key = apiKeyInput.trim()
            val base = apiBaseUrlInput.trim().trimEnd('/').ifBlank { StorageManager.DEFAULT_TMDB_API_URL }
            val label = "TMDB (${hostLabel(base)})"
            if (key.isBlank()) {
                return@withContext MirrorCheckResult(label, base, false, 0L, "Не указан ключ")
            }

            val started = SystemClock.elapsedRealtime()
            val req = try {
                buildRequest(base, key, "/configuration", "")
            } catch (_: IllegalArgumentException) {
                return@withContext MirrorCheckResult(label, base, false, 0L, "Неверный URL")
            }

            try {
                probeClient(networkOverride).newCall(req).execute().use { resp ->
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    val ok = resp.code in 200..299
                    val detail = when {
                        ok -> "OK"
                        resp.code == 401 -> "Неверный ключ (401)"
                        else -> "HTTP ${resp.code}"
                    }
                    MirrorCheckResult(label, base, ok, elapsed, detail)
                }
            } catch (err: Exception) {
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                MirrorCheckResult(label, base, false, elapsed, friendlyError(err))
            }
        }

    suspend fun probePosterConnection(
        posterBaseUrlInput: String,
        networkOverride: NetworkHelper.NetworkConfig? = null
    ): MirrorCheckResult = withContext(Dispatchers.IO) {
        val rawBase = posterBaseUrlInput.trim().trimEnd('/').ifBlank { StorageManager.DEFAULT_TMDB_IMAGE_URL }
        val candidates = LinkedHashSet<String>()
        candidates.add("$rawBase$PROBE_POSTER_SUFFIX")
        for (h in PosterLoader.TMDB_IMAGE_FALLBACK_HOSTS) {
            candidates.add("https://$h/t/p/w92$PROBE_POSTER_SUFFIX")
        }

        val started = SystemClock.elapsedRealtime()
        var lastErr = "Нет ответа"
        val http = probeClient(networkOverride)
        for (url in candidates) {
            val host = hostLabel(url)
            val req = try {
                Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Accept", "image/webp,image/jpeg,*/*")
                    .build()
            } catch (_: IllegalArgumentException) {
                continue
            }
            try {
                http.newCall(req).execute().use { resp ->
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    if (resp.isSuccessful && (resp.body?.bytes()?.size ?: 0) > 128) {
                        val isPrimary = host.equals(hostLabel(rawBase), ignoreCase = true)
                        val detail = if (isPrimary) "OK" else "резерв: $host"
                        return@withContext MirrorCheckResult("Постеры TMDB", url, true, elapsed, detail)
                    }
                    lastErr = "HTTP ${resp.code} ($host)"
                }
            } catch (err: Exception) {
                lastErr = "${friendlyError(err)} ($host)"
            }
        }
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        MirrorCheckResult("Постеры TMDB", rawBase, false, elapsed, lastErr)
    }

    private fun fetchList(
        baseUrl: String,
        apiKey: String,
        endpoint: String,
        defaultType: String,
        queryParams: String,
        maxItems: Int = 18
    ): List<CatalogItem> {
        val req = buildRequest(baseUrl, apiKey, endpoint, queryParams)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 401) throw IllegalStateException("Неверный API-ключ (401)")
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank() || !raw.startsWith("{")) return emptyList()

            val root = JSONObject(raw)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<CatalogItem>()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", 0)
                val rawType = obj.optString("media_type").trim()
                if (rawType.equals("person", ignoreCase = true)) continue
                val type = rawType.ifBlank { defaultType }
                val isTv = type.equals("tv", ignoreCase = true)

                val title = if (isTv) {
                    obj.optString("name").ifBlank { obj.optString("original_name") }
                } else {
                    obj.optString("title").ifBlank { obj.optString("original_title") }
                }.trim()
                if (title.isBlank()) continue

                val orig = if (isTv) {
                    obj.optString("original_name").trim()
                } else {
                    obj.optString("original_title").trim()
                }
                val dateStr = if (isTv) {
                    obj.optString("first_air_date")
                } else {
                    obj.optString("release_date")
                }.trim()
                val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""
                val rating = obj.optDouble("vote_average", 0.0)
                val overview = obj.optString("overview").trim()
                val posterPath = obj.optString("poster_path").trim()

                val genres = mutableListOf<String>()
                val genreIds = obj.optJSONArray("genre_ids")
                if (genreIds != null) {
                    for (g in 0 until genreIds.length()) {
                        val gid = genreIds.optInt(g, -1)
                        GENRE_MAP[gid]?.let { genres.add(it) }
                    }
                }

                val cachedRuntime = runtimeCache["${if (isTv) "tv" else "movie"}:$id"] ?: 0

                list.add(
                    CatalogItem(
                        id = id,
                        mediaType = type,
                        title = title,
                        originalTitle = orig,
                        year = year,
                        rating = rating,
                        overview = overview,
                        posterPath = posterPath,
                        genreNames = genres.take(3),
                        runtimeMinutes = cachedRuntime
                    )
                )
            }
            return list.take(maxItems)
        }
    }

    private fun buildRequest(baseUrl: String, apiKey: String, endpoint: String, queryParams: String): Request {
        val cleanBase = baseUrl.trimEnd('/')
        val isBearer = isBearerToken(apiKey)
        val url = buildString {
            append(cleanBase).append(endpoint)
            val params = mutableListOf<String>()
            if (queryParams.isNotBlank()) params.add(queryParams)
            if (!isBearer) {
                params.add("api_key=${URLEncoder.encode(apiKey, "UTF-8")}")
            }
            if (params.isNotEmpty()) {
                append("?").append(params.joinToString("&"))
            }
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "TorPlay-TV")
        if (isBearer) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    private fun isBearerToken(key: String): Boolean {
        return key.length > 45 && key.contains('.')
    }

    private fun friendlyError(err: Throwable): String {
        return when (err) {
            is SocketTimeoutException -> "Таймаут (возможно, нужен прокси)"
            is UnknownHostException -> "Нет DNS / сети"
            is SSLException -> "Ошибка SSL"
            else -> err.message?.take(48)?.ifBlank { "Нет ответа" } ?: "Нет ответа"
        }
    }

    private fun hostLabel(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
    }

    companion object {
        private const val PROBE_POSTER_SUFFIX = "/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg"

        private val GENRE_MAP = mapOf(
            28 to "Боевик",
            12 to "Приключения",
            16 to "Мультфильм",
            35 to "Комедия",
            80 to "Криминал",
            99 to "Документальный",
            18 to "Драма",
            10751 to "Семейный",
            14 to "Фэнтези",
            36 to "История",
            27 to "Ужасы",
            10402 to "Музыка",
            9648 to "Детектив",
            10749 to "Мелодрама",
            878 to "Фантастика",
            10770 to "ТВ-фильм",
            53 to "Триллер",
            10752 to "Военный",
            37 to "Вестерн",
            10759 to "Экшен",
            10762 to "Детский",
            10764 to "Реалити",
            10765 to "Фантастика",
            10766 to "Мелодрама",
            10768 to "Военный"
        )
    }
}
