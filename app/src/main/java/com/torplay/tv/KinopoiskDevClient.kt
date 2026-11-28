package com.torplay.tv

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Calendar
import java.util.Locale
import javax.net.ssl.SSLException

class KinopoiskDevClient(private val storage: StorageManager) {

    private val client: OkHttpClient
        get() = NetworkHelper.getExternalClient(storage, connectSec = 8L, readSec = 12L)

    private fun probeClient(overrideConfig: NetworkHelper.NetworkConfig? = null): OkHttpClient {
        return NetworkHelper.getExternalClient(storage, connectSec = 6L, readSec = 6L, overrideConfig = overrideConfig)
    }

    suspend fun fetchShelves(): CatalogShelves = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskDevApiKey
        if (apiKey.isBlank()) {
            return@withContext CatalogShelves(emptyList(), emptyList(), emptyList(), "Не указан ключ kinopoisk.dev")
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val minNewYear = (currentYear - 1).coerceAtLeast(2024)
        var firstError: String? = null

        coroutineScope {
            val trendingDef = async {
                runCatching {
                    fetchDocs(
                        apiKey,
                        "/v1.4/movie?page=1&limit=18&type=movie&notNullFields=poster.url&notNullFields=name&rating.kp=6.5-10&votes.kp=15000-10000000&sortField=votes.kp&sortType=-1"
                    )
                }.onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val nowDef = async {
                runCatching {
                    fetchDocs(
                        apiKey,
                        "/v1.4/movie?page=1&limit=18&type=movie&year=$minNewYear-$currentYear&notNullFields=poster.url&notNullFields=name&votes.kp=1000-10000000&sortField=votes.kp&sortType=-1"
                    )
                }.onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val seriesDef = async {
                runCatching {
                    fetchDocs(
                        apiKey,
                        "/v1.4/movie?page=1&limit=18&isSeries=true&notNullFields=poster.url&notNullFields=name&rating.kp=7-10&votes.kp=5000-10000000&sortField=votes.kp&sortType=-1"
                    )
                }.onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }

            val trending = trendingDef.await()
            val nowPlaying = nowDef.await()
            val series = seriesDef.await()
            val err = if (trending.isEmpty() && nowPlaying.isEmpty() && series.isEmpty()) {
                firstError ?: "Пустой ответ kinopoisk.dev"
            } else {
                null
            }
            CatalogShelves(trending, nowPlaying, series, err)
        }
    }

    suspend fun searchMovies(query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val apiKey = storage.kinopoiskDevApiKey
        if (clean.isBlank() || apiKey.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        runCatching {
            fetchDocs(apiKey, "/v1.4/movie/search?page=1&limit=12&query=$encoded")
        }.getOrDefault(emptyList())
    }

    suspend fun fetchFilmDetails(filmId: Int): CatalogItem? = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskDevApiKey
        if (filmId <= 0 || apiKey.isBlank()) return@withContext null

        val obj = fetchMovieJson(apiKey, filmId) ?: return@withContext null
        parseMovieObject(obj)
    }

    suspend fun fetchSimilarMovies(filmId: Int): List<CatalogItem> = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskDevApiKey
        if (filmId <= 0 || apiKey.isBlank()) return@withContext emptyList()

        val obj = fetchMovieJson(apiKey, filmId) ?: return@withContext emptyList()
        val arr = obj.optJSONArray("similarMovies") ?: return@withContext emptyList()
        parseDocsArray(arr, maxItems = 12)
    }

    suspend fun fetchTrailerUrl(filmId: Int): String? = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskDevApiKey
        if (filmId <= 0 || apiKey.isBlank()) return@withContext null

        val obj = fetchMovieJson(apiKey, filmId) ?: return@withContext null
        val videos = obj.optJSONObject("videos") ?: return@withContext null
        val trailers = videos.optJSONArray("trailers") ?: return@withContext null
        var fallback: String? = null
        for (i in 0 until trailers.length()) {
            val t = trailers.optJSONObject(i) ?: continue
            val url = TrailerLauncher.normalizeTrailerUrl(t.optString("url", ""))
            if (url.isBlank()) continue
            val site = t.optString("site", "").lowercase(Locale.US)
            if (site.contains("youtube") || url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)) {
                return@withContext url
            }
            if (fallback == null) fallback = url
        }
        fallback
    }

    suspend fun probeConnection(
        apiKeyInput: String,
        networkOverride: NetworkHelper.NetworkConfig? = null
    ): MirrorCheckResult = withContext(Dispatchers.IO) {
        val key = apiKeyInput.trim()
        val label = "Кинопоиск (kinopoisk.dev)"
        if (key.isBlank()) {
            return@withContext MirrorCheckResult(label, BASE_URL, false, 0L, "Не указан ключ")
        }
        val started = SystemClock.elapsedRealtime()
        val req = buildRequest(key, "/v1.4/movie?page=1&limit=1")
        try {
            probeClient(networkOverride).newCall(req).execute().use { resp ->
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                val ok = resp.code in 200..299
                val detail = when {
                    ok -> "OK"
                    resp.code == 401 || resp.code == 403 -> "Неверный ключ (${resp.code})"
                    resp.code == 429 -> "Лимит запросов (429)"
                    else -> "HTTP ${resp.code}"
                }
                MirrorCheckResult(label, BASE_URL, ok, elapsed, detail)
            }
        } catch (err: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            MirrorCheckResult(label, BASE_URL, false, elapsed, friendlyError(err))
        }
    }

    private fun fetchMovieJson(apiKey: String, filmId: Int): JSONObject? {
        val req = buildRequest(apiKey, "/v1.4/movie/$filmId")
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty().trim()
                if (raw.isBlank() || !raw.startsWith("{")) return@use null
                JSONObject(raw)
            }
        }.getOrNull()
    }

    private fun fetchDocs(apiKey: String, pathAndQuery: String): List<CatalogItem> {
        val req = buildRequest(apiKey, pathAndQuery)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                when (resp.code) {
                    401, 403 -> throw IllegalStateException("Неверный ключ kinopoisk.dev (${resp.code})")
                    429 -> throw IllegalStateException("Лимит kinopoisk.dev (429)")
                    else -> throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank() || !raw.startsWith("{")) return emptyList()
            val root = JSONObject(raw)
            val arr = root.optJSONArray("docs") ?: return emptyList()
            return parseDocsArray(arr, maxItems = 18)
        }
    }

    private fun parseDocsArray(arr: JSONArray, maxItems: Int): List<CatalogItem> {
        val list = mutableListOf<CatalogItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = parseMovieObject(obj) ?: continue
            list.add(item)
            if (list.size >= maxItems) break
        }
        return list
    }

    private fun parseMovieObject(obj: JSONObject): CatalogItem? {
        val id = obj.optInt("id", 0)
        val nameRu = cleanJsonString(obj, "name")
        val altName = cleanJsonString(obj, "alternativeName").ifBlank { cleanJsonString(obj, "enName") }
        val title = nameRu.ifBlank { altName }
        if (title.isBlank()) return null

        val rawType = cleanJsonString(obj, "type").lowercase(Locale.US)
        val isSeries = obj.optBoolean("isSeries", false) ||
            rawType.contains("series") ||
            rawType == "tv-series" ||
            rawType == "animated-series"
        val mediaType = if (isSeries) "tv" else "movie"

        val yearInt = obj.optInt("year", 0)
        val year = if (yearInt in 1900..2100) yearInt.toString() else ""

        val ratingObj = obj.optJSONObject("rating")
        val rating = when {
            ratingObj != null -> {
                val kp = ratingObj.optDouble("kp", 0.0).takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
                if (kp > 0.0) kp else (ratingObj.optDouble("imdb", 0.0).takeIf { !it.isNaN() && it > 0.0 } ?: 0.0)
            }
            else -> 0.0
        }

        val overview = cleanJsonString(obj, "description").ifBlank { cleanJsonString(obj, "shortDescription") }
        val posterObj = obj.optJSONObject("poster")
        val posterUrl = if (posterObj != null) {
            cleanJsonString(posterObj, "previewUrl").ifBlank { cleanJsonString(posterObj, "url") }
        } else {
            ""
        }

        val genres = mutableListOf<String>()
        val gArr = obj.optJSONArray("genres")
        if (gArr != null) {
            for (g in 0 until gArr.length()) {
                val gObj = gArr.optJSONObject(g) ?: continue
                val gName = cleanJsonString(gObj, "name")
                if (gName.isNotBlank()) {
                    genres.add(gName.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                    })
                }
            }
        }

        val runtime = obj.optInt("movieLength", 0).takeIf { it > 0 }
            ?: obj.optInt("seriesLength", 0).coerceAtLeast(0)

        return CatalogItem(
            id = id,
            mediaType = mediaType,
            title = title,
            originalTitle = altName,
            year = year,
            rating = rating,
            overview = overview,
            posterPath = posterUrl,
            genreNames = genres.take(3),
            source = SOURCE_ID,
            runtimeMinutes = runtime
        )
    }

    private fun cleanJsonString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key, "").trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun buildRequest(apiKey: String, pathAndQuery: String): Request {
        return Request.Builder()
            .url("$BASE_URL$pathAndQuery")
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", "TorPlay-TV")
            .build()
    }

    private fun friendlyError(err: Throwable): String {
        return when (err) {
            is SocketTimeoutException -> "Таймаут сети"
            is UnknownHostException -> "Нет DNS / сети"
            is SSLException -> "Ошибка SSL"
            else -> err.message?.take(48)?.ifBlank { "Нет ответа" } ?: "Нет ответа"
        }
    }

    companion object {
        const val SOURCE_ID = "kinopoisk_dev"
        const val BASE_URL = "https://api.kinopoisk.dev"
    }
}
