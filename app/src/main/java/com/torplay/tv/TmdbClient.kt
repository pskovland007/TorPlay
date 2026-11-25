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
import java.util.concurrent.TimeUnit
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
    val source: String = "tmdb"
) {
    fun isSeries(): Boolean = mediaType.equals("tv", ignoreCase = true)

    fun formattedRating(): String {
        if (rating <= 0.05) return ""
        return String.format(Locale.US, "★ %.1f", rating)
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

    suspend fun probeConnection(apiKeyInput: String, apiBaseUrlInput: String): MirrorCheckResult =
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
                probeClient.newCall(req).execute().use { resp ->
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
                        genreNames = genres.take(3)
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
            10765 to "Фантастика и фэнтези",
            10766 to "Мелодрама",
            10768 to "Война и политика"
        )
    }
}
