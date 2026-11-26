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
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class KinopoiskClient(private val storage: StorageManager) {

    private val client: OkHttpClient
        get() = NetworkHelper.getExternalClient(storage, connectSec = 8L, readSec = 12L)

    private fun probeClient(overrideConfig: NetworkHelper.NetworkConfig? = null): OkHttpClient {
        return NetworkHelper.getExternalClient(storage, connectSec = 6L, readSec = 6L, overrideConfig = overrideConfig)
    }

    suspend fun fetchShelves(): CatalogShelves = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskApiKey
        if (apiKey.isBlank()) {
            return@withContext CatalogShelves(emptyList(), emptyList(), emptyList(), null)
        }

        var firstError: String? = null

        coroutineScope {
            val trendingDef = async {
                runCatching { fetchCollection(apiKey, "TOP_POPULAR_MOVIES") }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val nowDef = async {
                runCatching {
                    val primary = fetchCollection(apiKey, "CLOSES_RELEASES")
                    if (primary.isNotEmpty()) primary else fetchCollection(apiKey, "TOP_250_MOVIES")
                }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }
            val seriesDef = async {
                runCatching { fetchCollection(apiKey, "POPULAR_SERIES") }
                    .onFailure { if (firstError == null) firstError = friendlyError(it) }
                    .getOrDefault(emptyList())
            }

            val trending = trendingDef.await()
            val nowPlaying = nowDef.await()
            val series = seriesDef.await()

            val err = if (trending.isEmpty() && nowPlaying.isEmpty() && series.isEmpty()) {
                firstError ?: "Пустой ответ Кинопоиска"
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

    suspend fun searchMovies(query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val apiKey = storage.kinopoiskApiKey
        if (clean.isBlank() || apiKey.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val req = buildRequest(apiKey, "/api/v2.1/films/search-by-keyword?keyword=$encoded&page=1")

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty().trim()
                if (raw.isBlank() || !raw.startsWith("{")) return@use emptyList()
                val root = JSONObject(raw)
                val arr = root.optJSONArray("films") ?: return@use emptyList()
                parseItemsArray(arr, maxItems = 12)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchFilmDetails(filmId: Int): CatalogItem? = withContext(Dispatchers.IO) {
        val apiKey = storage.kinopoiskApiKey
        if (filmId <= 0 || apiKey.isBlank()) return@withContext null

        val req = buildRequest(apiKey, "/api/v2.2/films/$filmId")
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty().trim()
                if (raw.isBlank() || !raw.startsWith("{")) return@use null
                parseFilmObject(JSONObject(raw))
            }
        }.getOrNull()
    }

    suspend fun probeConnection(
        apiKeyInput: String,
        networkOverride: NetworkHelper.NetworkConfig? = null
    ): MirrorCheckResult = withContext(Dispatchers.IO) {
        val key = apiKeyInput.trim()
        val label = "Кинопоиск (API)"
        if (key.isBlank()) {
            return@withContext MirrorCheckResult(label, BASE_URL, false, 0L, "Не указан ключ")
        }

        val started = SystemClock.elapsedRealtime()
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        val infoReq = buildRequest(key, "/api/v1/api_keys/$encodedKey")

        try {
            probeClient(networkOverride).newCall(infoReq).execute().use { resp ->
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                if (resp.code in 200..299) {
                    val raw = resp.body?.string().orEmpty()
                    val quotaHint = parseQuotaHint(raw)
                    val detail = if (quotaHint.isNotBlank()) "OK ($quotaHint)" else "OK"
                    return@withContext MirrorCheckResult(label, BASE_URL, true, elapsed, detail)
                }
                if (resp.code == 401) {
                    return@withContext MirrorCheckResult(label, BASE_URL, false, elapsed, "Неверный ключ (401)")
                }
                if (resp.code == 402) {
                    return@withContext MirrorCheckResult(label, BASE_URL, false, elapsed, "Лимит исчерпан (402)")
                }
            }
        } catch (err: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            return@withContext MirrorCheckResult(label, BASE_URL, false, elapsed, friendlyError(err))
        }

        // Fallback probe against collections endpoint if /api/v1/api_keys is unavailable
        val fallbackReq = buildRequest(key, "/api/v2.2/films/collections?type=TOP_POPULAR_MOVIES&page=1")
        try {
            probeClient(networkOverride).newCall(fallbackReq).execute().use { resp ->
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                val ok = resp.code in 200..299
                val detail = when {
                    ok -> "OK"
                    resp.code == 401 -> "Неверный ключ (401)"
                    resp.code == 402 -> "Лимит исчерпан (402)"
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

    private fun fetchCollection(apiKey: String, type: String): List<CatalogItem> {
        val req = buildRequest(apiKey, "/api/v2.2/films/collections?type=$type&page=1")
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                when (resp.code) {
                    401 -> throw IllegalStateException("Неверный API-ключ (401)")
                    402 -> throw IllegalStateException("Дневной лимит исчерпан (402)")
                    429 -> throw IllegalStateException("Слишком много запросов (429)")
                    else -> throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank() || !raw.startsWith("{")) return emptyList()
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return emptyList()
            return parseItemsArray(arr, maxItems = 18)
        }
    }

    private fun parseItemsArray(arr: JSONArray, maxItems: Int): List<CatalogItem> {
        val list = mutableListOf<CatalogItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = parseFilmObject(obj) ?: continue
            list.add(item)
            if (list.size >= maxItems) break
        }
        return list
    }

    private fun parseFilmObject(obj: JSONObject): CatalogItem? {
        val id = obj.optInt("kinopoiskId", 0).takeIf { it > 0 } ?: obj.optInt("filmId", 0)
        val nameRu = cleanJsonString(obj, "nameRu")
        val nameOrig = cleanJsonString(obj, "nameOriginal").ifBlank { cleanJsonString(obj, "nameEn") }
        val title = nameRu.ifBlank { nameOrig }
        if (title.isBlank()) return null

        val rawType = cleanJsonString(obj, "type").uppercase(Locale.US)
        val isTv = rawType.contains("SERIES") || rawType == "TV_SHOW"
        val mediaType = if (isTv) "tv" else "movie"

        val rawYear = cleanJsonString(obj, "year")
        val year = rawYear.take(4).takeIf { it.length == 4 && it.all { ch -> ch.isDigit() } }.orEmpty()

        val rating = parseRating(obj)
        val overview = cleanJsonString(obj, "description").ifBlank { cleanJsonString(obj, "shortDescription") }
        val posterUrl = cleanJsonString(obj, "posterUrlPreview").ifBlank { cleanJsonString(obj, "posterUrl") }

        val genres = mutableListOf<String>()
        val genresArr = obj.optJSONArray("genres")
        if (genresArr != null) {
            for (g in 0 until genresArr.length()) {
                val gObj = genresArr.optJSONObject(g) ?: continue
                val gName = cleanJsonString(gObj, "genre")
                if (gName.isNotBlank()) {
                    genres.add(gName.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                    })
                }
            }
        }

        return CatalogItem(
            id = id,
            mediaType = mediaType,
            title = title,
            originalTitle = nameOrig,
            year = year,
            rating = rating,
            overview = overview,
            posterPath = posterUrl,
            genreNames = genres.take(3),
            source = SOURCE_ID
        )
    }

    private fun parseRating(obj: JSONObject): Double {
        for (key in listOf("ratingKinopoisk", "ratingImdb", "rating")) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val raw = obj.optString(key, "").trim()
            if (raw.isBlank() || raw.equals("null", ignoreCase = true) || raw.endsWith("%")) continue
            val num = raw.replace(',', '.').toDoubleOrNull()
            if (num != null && num > 0.0 && num <= 10.0) {
                return num
            }
        }
        return 0.0
    }

    private fun parseQuotaHint(raw: String): String {
        if (raw.isBlank() || !raw.startsWith("{")) return ""
        return runCatching {
            val root = JSONObject(raw)
            val daily = root.optJSONObject("dailyQuota") ?: return@runCatching ""
            val total = daily.optInt("value", -1)
            val used = daily.optInt("used", -1)
            if (total > 0 && used >= 0) {
                val left = (total - used).coerceAtLeast(0)
                "ост. $left/$total"
            } else {
                ""
            }
        }.getOrDefault("")
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
        const val SOURCE_ID = "kinopoisk"
        const val BASE_URL = "https://kinopoiskapiunofficial.tech"
    }
}
