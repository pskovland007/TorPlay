package com.torplay.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class StorageManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        applyV106BuiltInDefaultsIfNeeded()
    }

    private fun applyV106BuiltInDefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_DEFAULTS_V106_APPLIED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_KINOPOISK_API_KEY, "").orEmpty().trim().isBlank()) {
            editor.putString(KEY_KINOPOISK_API_KEY, DEFAULT_KINOPOISK_API_KEY)
        }
        if (prefs.getString(KEY_TMDB_API_KEY, "").orEmpty().trim().isBlank()) {
            editor.putString(KEY_TMDB_API_KEY, DEFAULT_TMDB_API_KEY)
        }
        val curImg = prefs.getString(KEY_TMDB_IMAGE_BASE, "").orEmpty().trim()
        if (curImg.isBlank() || curImg.equals("https://image.tmdb.org/t/p/w342", ignoreCase = true)) {
            editor.putString(KEY_TMDB_IMAGE_BASE, DEFAULT_TMDB_IMAGE_URL)
        }
        if (!prefs.contains(KEY_CATALOG_PROVIDER) ||
            prefs.getInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_KINOPOISK) == CATALOG_PROVIDER_TORRENTS
        ) {
            editor.putInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_KINOPOISK)
        }
        editor.putBoolean(KEY_DEFAULTS_V106_APPLIED, true)
        editor.apply()
    }

    var playerEngine: Int
        get() {
            if (prefs.contains(KEY_PLAYER_ENGINE)) {
                return prefs.getInt(KEY_PLAYER_ENGINE, PLAYER_ENGINE_MEDIA3)
                    .coerceIn(PLAYER_ENGINE_MEDIA3, PLAYER_ENGINE_EXTERNAL)
            }
            return if (prefs.getBoolean(KEY_USE_BUILTIN_PLAYER, true)) {
                PLAYER_ENGINE_MEDIA3
            } else {
                PLAYER_ENGINE_EXTERNAL
            }
        }
        set(value) {
            val clamped = value.coerceIn(PLAYER_ENGINE_MEDIA3, PLAYER_ENGINE_EXTERNAL)
            prefs.edit()
                .putInt(KEY_PLAYER_ENGINE, clamped)
                .putBoolean(KEY_USE_BUILTIN_PLAYER, clamped != PLAYER_ENGINE_EXTERNAL)
                .apply()
        }

    var useBuiltInPlayer: Boolean
        get() = playerEngine != PLAYER_ENGINE_EXTERNAL
        set(value) {
            if (value) {
                if (playerEngine == PLAYER_ENGINE_EXTERNAL) {
                    playerEngine = PLAYER_ENGINE_MEDIA3
                }
            } else {
                playerEngine = PLAYER_ENGINE_EXTERNAL
            }
        }

    var bufferProfile: Int
        get() = prefs.getInt(KEY_BUFFER_PROFILE, BUFFER_PROFILE_STANDARD)
            .coerceIn(BUFFER_PROFILE_FAST, BUFFER_PROFILE_4K)
        set(value) {
            prefs.edit().putInt(KEY_BUFFER_PROFILE, value.coerceIn(BUFFER_PROFILE_FAST, BUFFER_PROFILE_4K)).apply()
        }

    var usePublicJacRed: Boolean
        get() = prefs.getBoolean(KEY_USE_PUBLIC_JACRED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_PUBLIC_JACRED, value).apply()
        }

    var useRutorFallback: Boolean
        get() = prefs.getBoolean(KEY_USE_RUTOR_FALLBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_RUTOR_FALLBACK, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()
        }

    var hideCamReleases: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CAM_RELEASES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_CAM_RELEASES, value).apply()
        }

    var onlyLiveSeeds: Boolean
        get() = prefs.getBoolean(KEY_ONLY_LIVE_SEEDS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONLY_LIVE_SEEDS, value).apply()
        }

    var onlyDubReleases: Boolean
        get() = prefs.getBoolean(KEY_ONLY_DUB_RELEASES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONLY_DUB_RELEASES, value).apply()
        }

    var hide4kReleases: Boolean
        get() = prefs.getBoolean(KEY_HIDE_4K_RELEASES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_4K_RELEASES, value).apply()
        }

    var autoFrameRate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FRAME_RATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_FRAME_RATE, value).apply()
        }

    var showTmdbShowcase: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TMDB_SHOWCASE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_TMDB_SHOWCASE, value).apply()
        }

    var catalogProvider: Int
        get() = prefs.getInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_KINOPOISK)
            .coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TORRENTS)
        set(value) {
            prefs.edit()
                .putInt(KEY_CATALOG_PROVIDER, value.coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TORRENTS))
                .apply()
        }

    var kinopoiskApiKey: String
        get() = prefs.getString(KEY_KINOPOISK_API_KEY, DEFAULT_KINOPOISK_API_KEY)?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_KINOPOISK_API_KEY, value.trim()).apply()
        }

    var audioBoostDb: Int
        get() = prefs.getInt(KEY_AUDIO_BOOST_DB, 0).coerceIn(0, 12)
        set(value) {
            prefs.edit().putInt(KEY_AUDIO_BOOST_DB, value.coerceIn(0, 12)).apply()
        }

    var useDohDns: Boolean
        get() = prefs.getBoolean(KEY_USE_DOH_DNS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DOH_DNS, value).apply()
        }

    var useDpiBypass: Boolean
        get() = prefs.getBoolean(KEY_USE_DPI_BYPASS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DPI_BYPASS, value).apply()
        }

    var networkProxyUrl: String
        get() = prefs.getString(KEY_NETWORK_PROXY_URL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_NETWORK_PROXY_URL, value.trim()).apply()
        }

    var showPlayerStatsHud: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PLAYER_STATS_HUD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_PLAYER_STATS_HUD, value).apply()
        }

    var plasmaPixelShift: Boolean
        get() = prefs.getBoolean(KEY_PLASMA_PIXEL_SHIFT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PLASMA_PIXEL_SHIFT, value).apply()
        }

    var playerStatsCorner: Int
        get() = prefs.getInt(KEY_PLAYER_STATS_CORNER, STATS_CORNER_TOP_RIGHT)
            .coerceIn(STATS_CORNER_TOP_RIGHT, STATS_CORNER_BOTTOM_RIGHT)
        set(value) {
            prefs.edit()
                .putInt(KEY_PLAYER_STATS_CORNER, value.coerceIn(STATS_CORNER_TOP_RIGHT, STATS_CORNER_BOTTOM_RIGHT))
                .apply()
        }

    fun activeCatalogRequiresKey(): Boolean {
        return catalogProvider != CATALOG_PROVIDER_TORRENTS
    }

    fun activeCatalogKey(): String {
        return when (catalogProvider) {
            CATALOG_PROVIDER_KINOPOISK -> kinopoiskApiKey
            CATALOG_PROVIDER_TMDB -> tmdbApiKey
            else -> ""
        }
    }

    fun activeCatalogName(): String {
        return when (catalogProvider) {
            CATALOG_PROVIDER_TORRENTS -> "Торренты"
            CATALOG_PROVIDER_KINOPOISK -> "Кинопоиск"
            else -> "TMDB"
        }
    }

    var tmdbApiKey: String
        get() = prefs.getString(KEY_TMDB_API_KEY, DEFAULT_TMDB_API_KEY)?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TMDB_API_KEY, value.trim()).apply()
        }

    var tmdbApiBaseUrl: String
        get() = prefs.getString(KEY_TMDB_API_BASE, DEFAULT_TMDB_API_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { DEFAULT_TMDB_API_URL }
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_TMDB_API_URL }
            prefs.edit().putString(KEY_TMDB_API_BASE, clean).apply()
        }

    var tmdbImageBaseUrl: String
        get() = prefs.getString(KEY_TMDB_IMAGE_BASE, DEFAULT_TMDB_IMAGE_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { DEFAULT_TMDB_IMAGE_URL }
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_TMDB_IMAGE_URL }
            prefs.edit().putString(KEY_TMDB_IMAGE_BASE, clean).apply()
        }

    var torrServeUrl: String
        get() = prefs.getString(KEY_TORRSERVE_URL, DEFAULT_TORRSERVE_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { DEFAULT_TORRSERVE_URL }
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_TORRSERVE_URL }
            prefs.edit().putString(KEY_TORRSERVE_URL, clean).apply()
        }

    var customParserUrl: String
        get() = prefs.getString(KEY_CUSTOM_PARSER_URL, "")?.trim()?.trimEnd('/').orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_PARSER_URL, value.trim().trimEnd('/')).apply()
        }

    var parserApiKey: String
        get() = prefs.getString(KEY_PARSER_API_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PARSER_API_KEY, value.trim()).apply()
        }

    var rutorMirrorUrl: String
        get() = prefs.getString(KEY_RUTOR_MIRROR, DEFAULT_RUTOR_MIRROR)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { DEFAULT_RUTOR_MIRROR }
        set(value) {
            val clean = value.trim().trimEnd('/').ifBlank { DEFAULT_RUTOR_MIRROR }
            prefs.edit().putString(KEY_RUTOR_MIRROR, clean).apply()
        }

    var lastQuery: String
        get() = prefs.getString(KEY_LAST_QUERY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_QUERY, value.trim()).apply()
        }

    fun isEpisodeWatched(hash: String, fileId: Int): Boolean {
        if (hash.isBlank()) return false
        val set = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet()) ?: emptySet()
        return set.contains(episodeKey(hash, fileId))
    }

    fun isEpisodeCompleted(hash: String, fileId: Int): Boolean {
        if (hash.isBlank()) return false
        val progress = getEpisodeProgress(hash, fileId)
        if (progress != null) return progress.isCompletedEffective()
        return isEpisodeWatched(hash, fileId)
    }

    fun markEpisodeWatched(hash: String, fileId: Int) {
        if (hash.isBlank()) return
        val current = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(episodeKey(hash, fileId))
        prefs.edit().putStringSet(KEY_WATCHED_EPISODES, current).apply()
    }

    fun unmarkEpisodeWatched(hash: String, fileId: Int) {
        if (hash.isBlank()) return
        val key = episodeKey(hash, fileId)
        val current = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(key)
        val progObj = readEpisodeProgressJson()
        progObj.remove(key)
        prefs.edit()
            .putStringSet(KEY_WATCHED_EPISODES, current)
            .putString(KEY_EPISODE_PROGRESS, progObj.toString())
            .apply()
    }

    fun toggleEpisodeWatched(hash: String, fileId: Int): Boolean {
        if (hash.isBlank()) return false
        val alreadyCompleted = isEpisodeCompleted(hash, fileId)
        return if (alreadyCompleted) {
            unmarkEpisodeWatched(hash, fileId)
            false
        } else {
            val prev = getEpisodeProgress(hash, fileId)
            val dur = prev?.durationMs ?: 0L
            saveEpisodeProgress(
                hash = hash,
                fileId = fileId,
                positionMs = dur,
                durationMs = dur,
                completed = true
            )
            true
        }
    }

    fun setLastEpisodeForTorrent(hash: String, fileId: Int) {
        val cleanHash = hash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank() || fileId <= 0) return
        val mapObj = readLastEpisodeJson()
        mapObj.put(cleanHash, fileId)
        prefs.edit().putString(KEY_LAST_EPISODE_MAP, mapObj.toString()).apply()
    }

    fun getLastEpisodeForTorrent(hash: String): Int? {
        val cleanHash = hash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank()) return null
        val mapObj = readLastEpisodeJson()
        if (!mapObj.has(cleanHash)) return null
        val id = mapObj.optInt(cleanHash, -1)
        return if (id > 0) id else null
    }

    fun saveEpisodeProgress(
        hash: String,
        fileId: Int,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        val cleanHash = hash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank() || fileId <= 0) return
        val safePos = positionMs.coerceAtLeast(0L)
        val safeDur = durationMs.coerceAtLeast(0L)
        val isDone = completed || (safeDur > 15_000L && safePos >= (safeDur * 90L) / 100L)

        val key = episodeKey(cleanHash, fileId)
        val progObj = readEpisodeProgressJson()
        progObj.put(
            key,
            JSONObject().apply {
                put("fileId", fileId)
                put("pos", if (isDone && safeDur > 0L) safeDur else safePos)
                put("dur", safeDur)
                put("done", isDone)
                put("ts", System.currentTimeMillis())
            }
        )
        trimEpisodeProgressIfNeeded(progObj, maxEntries = 350)

        val lastMap = readLastEpisodeJson()
        lastMap.put(cleanHash, fileId)

        val watched = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isDone) {
            watched.add(key)
        } else {
            watched.remove(key)
        }

        prefs.edit()
            .putString(KEY_EPISODE_PROGRESS, progObj.toString())
            .putString(KEY_LAST_EPISODE_MAP, lastMap.toString())
            .putStringSet(KEY_WATCHED_EPISODES, watched)
            .apply()
    }

    fun getEpisodeProgress(hash: String, fileId: Int): EpisodeProgress? {
        val cleanHash = hash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank() || fileId <= 0) return null
        val obj = readEpisodeProgressJson().optJSONObject(episodeKey(cleanHash, fileId)) ?: return null
        return EpisodeProgress(
            fileId = obj.optInt("fileId", fileId),
            positionMs = obj.optLong("pos", 0L),
            durationMs = obj.optLong("dur", 0L),
            completed = obj.optBoolean("done", false),
            updatedAt = obj.optLong("ts", 0L)
        )
    }

    fun resolveResumeEpisodeIndex(hash: String, files: List<TorrServeFile>): Int {
        if (files.isEmpty()) return 0
        val cleanHash = hash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank()) return 0

        // 1. Prefer explicitly recorded last opened episode for this torrent
        val lastFileId = getLastEpisodeForTorrent(cleanHash)
        if (lastFileId != null) {
            val idx = files.indexOfFirst { it.id == lastFileId }
            if (idx >= 0) {
                return if (isEpisodeCompleted(cleanHash, lastFileId)) {
                    (idx + 1).coerceAtMost(files.lastIndex)
                } else {
                    idx
                }
            }
        }

        // 2. Otherwise check most recently updated in-progress episode
        var bestInProgressIdx = -1
        var bestInProgressTs = -1L
        var lastCompletedIdx = -1
        for (i in files.indices) {
            val f = files[i]
            val prog = getEpisodeProgress(cleanHash, f.id)
            if (prog != null) {
                if (!prog.isCompletedEffective() && prog.positionMs > 0L && prog.updatedAt > bestInProgressTs) {
                    bestInProgressTs = prog.updatedAt
                    bestInProgressIdx = i
                } else if (prog.isCompletedEffective()) {
                    lastCompletedIdx = i
                }
            } else if (isEpisodeWatched(cleanHash, f.id)) {
                lastCompletedIdx = i
            }
        }
        if (bestInProgressIdx >= 0) return bestInProgressIdx
        if (lastCompletedIdx >= 0) return (lastCompletedIdx + 1).coerceAtMost(files.lastIndex)

        // 3. Fallback: first unwatched episode
        return files.indexOfFirst { !isEpisodeCompleted(cleanHash, it.id) }.takeIf { it >= 0 } ?: 0
    }

    private fun readEpisodeProgressJson(): JSONObject {
        val raw = prefs.getString(KEY_EPISODE_PROGRESS, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun readLastEpisodeJson(): JSONObject {
        val raw = prefs.getString(KEY_LAST_EPISODE_MAP, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun trimEpisodeProgressIfNeeded(obj: JSONObject, maxEntries: Int) {
        if (obj.length() <= maxEntries) return
        val entries = mutableListOf<Pair<String, Long>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ts = obj.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
            entries.add(k to ts)
        }
        entries.sortBy { it.second }
        val toDrop = entries.size - maxEntries
        for (i in 0 until toDrop) {
            obj.remove(entries[i].first)
        }
    }

    private fun episodeKey(hash: String, fileId: Int): String {
        return "${hash.lowercase(Locale.US)}_$fileId"
    }

    fun getWatchHistory(): List<WatchHistoryEntry> {
        watchHistoryCache?.let { return it }
        return synchronized(listCacheLock) {
            watchHistoryCache?.let { return@synchronized it }
            val raw = prefs.getString(KEY_WATCH_HISTORY, "[]").orEmpty()
            val list = mutableListOf<WatchHistoryEntry>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val title = o.optString("title")
                    val link = o.optString("link")
                    if (id.isNotBlank() && title.isNotBlank() && link.isNotBlank()) {
                        list.add(
                            WatchHistoryEntry(
                                id = id,
                                title = title,
                                link = link,
                                fileId = o.optInt("fileId", 1),
                                episodeLabel = o.optString("episodeLabel", ""),
                                positionMs = o.optLong("positionMs", 0L),
                                durationMs = o.optLong("durationMs", 0L),
                                updatedAt = o.optLong("updatedAt", 0L)
                            )
                        )
                    }
                }
            }
            val result = list.sortedByDescending { it.updatedAt }.take(15)
            watchHistoryCache = result
            result
        }
    }

    fun saveWatchPosition(
        title: String,
        link: String,
        fileId: Int,
        episodeLabel: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (title.isBlank() || link.isBlank()) return
        val id = "${link.hashCode()}_$fileId"
        val current = getWatchHistory().filterNot { it.id == id }.toMutableList()
        current.add(
            0,
            WatchHistoryEntry(
                id = id,
                title = title,
                link = link,
                fileId = fileId,
                episodeLabel = episodeLabel,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis()
            )
        )
        persistWatchHistory(current.take(15))
    }

    fun removeWatchHistoryItem(id: String) {
        if (id.isBlank()) return
        val updated = getWatchHistory().filterNot { it.id == id }
        persistWatchHistory(updated)
    }

    fun clearWatchHistory() {
        synchronized(listCacheLock) {
            watchHistoryCache = emptyList()
            prefs.edit().putString(KEY_WATCH_HISTORY, "[]").apply()
        }
    }

    fun getDailyTopMovies(dateKey: String): List<CatalogItem> {
        val savedDate = prefs.getString(KEY_DAILY_TOP_DATE, "").orEmpty()
        if (savedDate != dateKey) return emptyList()
        val raw = prefs.getString(KEY_DAILY_TOP_ITEMS, "[]").orEmpty()
        val list = mutableListOf<CatalogItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", 0)
                val title = o.optString("title").trim()
                if (title.isBlank()) continue
                val genres = mutableListOf<String>()
                val gArr = o.optJSONArray("genres")
                if (gArr != null) {
                    for (g in 0 until gArr.length()) {
                        val gn = gArr.optString(g).trim()
                        if (gn.isNotBlank()) genres.add(gn)
                    }
                }
                list.add(
                    CatalogItem(
                        id = id,
                        mediaType = o.optString("mediaType", "movie"),
                        title = title,
                        originalTitle = o.optString("originalTitle", ""),
                        year = o.optString("year", ""),
                        rating = o.optDouble("rating", 0.0),
                        overview = o.optString("overview", ""),
                        posterPath = o.optString("posterPath", ""),
                        genreNames = genres,
                        source = o.optString("source", "tmdb"),
                        runtimeMinutes = o.optInt("runtimeMinutes", 0),
                        bestQuality = o.optString("bestQuality", ""),
                        bestSeeds = o.optInt("bestSeeds", 0),
                        bestPeers = o.optInt("bestPeers", 0)
                    )
                )
            }
        }
        return list
    }

    fun saveDailyTopMovies(dateKey: String, items: List<CatalogItem>) {
        val arr = JSONArray()
        for (item in items) {
            val gArr = JSONArray()
            for (g in item.genreNames) gArr.put(g)
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("mediaType", item.mediaType)
                    put("title", item.title)
                    put("originalTitle", item.originalTitle)
                    put("year", item.year)
                    put("rating", item.rating)
                    put("overview", item.overview)
                    put("posterPath", item.posterPath)
                    put("genres", gArr)
                    put("source", item.source)
                    put("runtimeMinutes", item.runtimeMinutes)
                    put("bestQuality", item.bestQuality)
                    put("bestSeeds", item.bestSeeds)
                    put("bestPeers", item.bestPeers)
                }
            )
        }
        prefs.edit()
            .putString(KEY_DAILY_TOP_DATE, dateKey)
            .putString(KEY_DAILY_TOP_ITEMS, arr.toString())
            .apply()
    }

    fun clearDailyTopCache() {
        prefs.edit()
            .remove(KEY_DAILY_TOP_DATE)
            .remove(KEY_DAILY_TOP_ITEMS)
            .apply()
    }

    private fun persistWatchHistory(items: List<WatchHistoryEntry>) {
        val snapshot = items.sortedByDescending { it.updatedAt }.take(15)
        val arr = JSONArray()
        for (item in snapshot) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("link", item.link)
                    put("fileId", item.fileId)
                    put("episodeLabel", item.episodeLabel)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("updatedAt", item.updatedAt)
                }
            )
        }
        synchronized(listCacheLock) {
            watchHistoryCache = snapshot
            prefs.edit().putString(KEY_WATCH_HISTORY, arr.toString()).apply()
        }
    }

    fun getSavedPositionMs(link: String, fileId: Int, hash: String = ""): Long {
        if (link.isNotBlank()) {
            val id = "${link.hashCode()}_$fileId"
            val fromHistory = getWatchHistory().firstOrNull { it.id == id }?.positionMs ?: 0L
            if (fromHistory > 0L) return fromHistory
        }
        if (hash.isNotBlank()) {
            val prog = getEpisodeProgress(hash, fileId)
            if (prog != null && !prog.isCompletedEffective() && prog.positionMs > 0L) {
                return prog.positionMs
            }
        }
        return 0L
    }

    fun hasWatchHistoryFor(link: String, hash: String = ""): Boolean {
        val cleanHash = hash.trim().lowercase(Locale.US)
        val cleanLink = link.trim()
        if (cleanLink.isNotBlank() || cleanHash.isNotBlank()) {
            val history = getWatchHistory()
            if (history.any { entry ->
                    (cleanLink.isNotBlank() && entry.link == cleanLink) ||
                        (cleanHash.isNotBlank() && entry.link.contains(cleanHash, ignoreCase = true))
                }
            ) {
                return true
            }
        }
        if (cleanHash.isNotBlank() && getLastEpisodeForTorrent(cleanHash) != null) {
            return true
        }
        return false
    }

    private fun mediaBoostKey(link: String, hash: String): String {
        val h = hash.trim().lowercase(Locale.US)
        if (h.isNotBlank()) return "h:$h"
        val l = link.trim()
        if (l.isNotBlank()) return "l:${l.hashCode()}"
        return ""
    }

    private fun readTorrentAudioBoostJson(): JSONObject {
        val raw = prefs.getString(KEY_TORRENT_AUDIO_BOOST_MAP, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    fun getAudioBoostForMedia(link: String, hash: String = ""): Int {
        val key = mediaBoostKey(link, hash)
        if (key.isBlank()) return 0
        val map = readTorrentAudioBoostJson()
        if (map.has(key)) {
            return map.optInt(key, 0).coerceIn(0, 12)
        }
        val altLinkKey = if (link.isNotBlank()) "l:${link.trim().hashCode()}" else ""
        if (altLinkKey.isNotBlank() && altLinkKey != key && map.has(altLinkKey)) {
            return map.optInt(altLinkKey, 0).coerceIn(0, 12)
        }
        return 0
    }

    fun setAudioBoostForMedia(link: String, hash: String = "", boostDb: Int) {
        val key = mediaBoostKey(link, hash)
        if (key.isBlank()) return
        val clamped = boostDb.coerceIn(0, 12)
        val map = readTorrentAudioBoostJson()
        if (clamped <= 0) {
            map.remove(key)
        } else {
            map.put(key, clamped)
            val keys = map.keys().asSequence().toList()
            if (keys.size > 60) {
                for (oldKey in keys.take(keys.size - 60)) {
                    if (oldKey != key) map.remove(oldKey)
                }
            }
        }
        prefs.edit().putString(KEY_TORRENT_AUDIO_BOOST_MAP, map.toString()).apply()
    }

    private fun readTorrentResizeModeJson(): JSONObject {
        val raw = prefs.getString(KEY_TORRENT_RESIZE_MODE_MAP, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    fun getResizeModeForMedia(link: String, hash: String = ""): Int {
        val key = mediaBoostKey(link, hash)
        if (key.isBlank()) return 0
        val map = readTorrentResizeModeJson()
        if (map.has(key)) {
            return map.optInt(key, 0).coerceIn(0, 3)
        }
        val altLinkKey = if (link.isNotBlank()) "l:${link.trim().hashCode()}" else ""
        if (altLinkKey.isNotBlank() && altLinkKey != key && map.has(altLinkKey)) {
            return map.optInt(altLinkKey, 0).coerceIn(0, 3)
        }
        return 0
    }

    fun setResizeModeForMedia(link: String, hash: String = "", resizeMode: Int) {
        val key = mediaBoostKey(link, hash)
        if (key.isBlank()) return
        val clamped = resizeMode.coerceIn(0, 3)
        val map = readTorrentResizeModeJson()
        if (clamped <= 0) {
            map.remove(key)
        } else {
            map.put(key, clamped)
            val keys = map.keys().asSequence().toList()
            if (keys.size > 60) {
                for (oldKey in keys.take(keys.size - 60)) {
                    if (oldKey != key) map.remove(oldKey)
                }
            }
        }
        prefs.edit().putString(KEY_TORRENT_RESIZE_MODE_MAP, map.toString()).apply()
    }

    fun getWatchlist(): List<CatalogItem> {
        watchlistCache?.let { return it }
        return synchronized(listCacheLock) {
            watchlistCache?.let { return@synchronized it }
            val raw = prefs.getString(KEY_WATCHLIST, "[]").orEmpty()
            val list = mutableListOf<CatalogItem>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    if (title.isBlank()) continue
                    val genres = mutableListOf<String>()
                    val gArr = o.optJSONArray("genres")
                    if (gArr != null) {
                        for (g in 0 until gArr.length()) {
                            val gn = gArr.optString(g).trim()
                            if (gn.isNotBlank()) genres.add(gn)
                        }
                    }
                    list.add(
                        CatalogItem(
                            id = o.optInt("id", 0),
                            mediaType = o.optString("mediaType", "movie"),
                            title = title,
                            originalTitle = o.optString("originalTitle", ""),
                            year = o.optString("year", ""),
                            rating = o.optDouble("rating", 0.0),
                            overview = o.optString("overview", ""),
                            posterPath = o.optString("posterPath", ""),
                            genreNames = genres,
                            source = o.optString("source", "tmdb"),
                            runtimeMinutes = o.optInt("runtimeMinutes", 0)
                        )
                    )
                }
            }
            val result = list.take(50)
            watchlistCache = result
            result
        }
    }

    private fun watchlistMatches(a: CatalogItem, b: CatalogItem): Boolean {
        if (a.stableKey() == b.stableKey()) return true
        if (a.id > 0 && b.id > 0 && a.id == b.id && a.mediaType.equals(b.mediaType, ignoreCase = true) && a.source == b.source) {
            return true
        }
        return a.title.trim().equals(b.title.trim(), ignoreCase = true) &&
            a.year.trim() == b.year.trim() &&
            a.isSeries() == b.isSeries()
    }

    fun isInWatchlist(item: CatalogItem): Boolean {
        return getWatchlist().any { watchlistMatches(it, item) }
    }

    fun toggleWatchlist(item: CatalogItem): Boolean {
        val current = getWatchlist().toMutableList()
        val existingIdx = current.indexOfFirst { watchlistMatches(it, item) }
        return if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            persistWatchlist(current)
            false
        } else {
            current.add(0, item)
            persistWatchlist(current.take(50))
            true
        }
    }

    fun removeFromWatchlist(item: CatalogItem) {
        val updated = getWatchlist().filterNot { watchlistMatches(it, item) }
        persistWatchlist(updated)
    }

    fun clearWatchlist() {
        synchronized(listCacheLock) {
            watchlistCache = emptyList()
            prefs.edit().putString(KEY_WATCHLIST, "[]").apply()
        }
    }

    private fun persistWatchlist(items: List<CatalogItem>) {
        val snapshot = items.take(50)
        val arr = JSONArray()
        for (item in snapshot) {
            val gArr = JSONArray()
            for (g in item.genreNames) gArr.put(g)
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("mediaType", item.mediaType)
                    put("title", item.title)
                    put("originalTitle", item.originalTitle)
                    put("year", item.year)
                    put("rating", item.rating)
                    put("overview", item.overview)
                    put("posterPath", item.posterPath)
                    put("genres", gArr)
                    put("source", item.source)
                    put("runtimeMinutes", item.runtimeMinutes)
                }
            )
        }
        synchronized(listCacheLock) {
            watchlistCache = snapshot
            prefs.edit().putString(KEY_WATCHLIST, arr.toString()).apply()
        }
    }

    fun getFavorites(): List<FavoriteQuery> {
        favoritesCache?.let { return it }
        return synchronized(listCacheLock) {
            favoritesCache?.let { return@synchronized it }
            val raw = prefs.getString(KEY_FAVORITES, "[]").orEmpty()
            val list = mutableListOf<FavoriteQuery>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val q = o.optString("query").trim()
                    if (q.isNotBlank()) {
                        list.add(FavoriteQuery(query = q, addedAt = o.optLong("addedAt", 0L)))
                    }
                }
            }
            val result = list.sortedByDescending { it.addedAt }.take(20)
            favoritesCache = result
            result
        }
    }

    fun isFavorite(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return false
        return getFavorites().any { it.query.lowercase(Locale.ROOT) == q }
    }

    fun toggleFavorite(query: String): Boolean {
        val clean = query.trim()
        if (clean.isBlank()) return false
        val lower = clean.lowercase(Locale.ROOT)
        val current = getFavorites().toMutableList()
        val exists = current.any { it.query.lowercase(Locale.ROOT) == lower }
        val updated = if (exists) {
            current.filterNot { it.query.lowercase(Locale.ROOT) == lower }
        } else {
            current.toMutableList().apply {
                add(0, FavoriteQuery(query = clean, addedAt = System.currentTimeMillis()))
            }
        }
        persistFavorites(updated.take(20))
        return !exists
    }

    fun removeFavorite(query: String) {
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return
        val updated = getFavorites().filterNot { it.query.lowercase(Locale.ROOT) == lower }
        persistFavorites(updated)
    }

    fun clearFavorites() {
        synchronized(listCacheLock) {
            favoritesCache = emptyList()
            prefs.edit().putString(KEY_FAVORITES, "[]").apply()
        }
    }

    fun getFavoriteTorrents(): List<FavoriteTorrent> {
        favoriteTorrentsCache?.let { return it }
        return synchronized(listCacheLock) {
            favoriteTorrentsCache?.let { return@synchronized it }
            val raw = prefs.getString(KEY_FAVORITE_TORRENTS, "[]").orEmpty()
            val list = mutableListOf<FavoriteTorrent>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val title = o.optString("title").trim()
                    val magnet = o.optString("magnet").trim()
                    val torrentUrl = o.optString("torrentUrl").trim()
                    if (id.isNotBlank() && title.isNotBlank() && (magnet.isNotBlank() || torrentUrl.isNotBlank())) {
                        val voices = mutableListOf<String>()
                        val vArr = o.optJSONArray("voices")
                        if (vArr != null) {
                            for (v in 0 until vArr.length()) {
                                val vs = vArr.optString(v).trim()
                                if (vs.isNotBlank()) voices.add(vs)
                            }
                        }
                        list.add(
                            FavoriteTorrent(
                                id = id,
                                title = title,
                                magnet = magnet,
                                torrentUrl = torrentUrl,
                                sizeBytes = o.optLong("sizeBytes", 0L),
                                sizeText = o.optString("sizeText", ""),
                                quality = o.optString("quality", ""),
                                isHdr = o.optBoolean("isHdr", false),
                                tracker = o.optString("tracker", ""),
                                voices = voices,
                                addedAt = o.optLong("addedAt", 0L)
                            )
                        )
                    }
                }
            }
            val result = list.sortedByDescending { it.addedAt }.take(40)
            favoriteTorrentsCache = result
            result
        }
    }

    fun isFavoriteTorrent(item: TorrentItem): Boolean {
        val id = item.stableFavoriteId()
        return getFavoriteTorrents().any { it.id == id }
    }

    fun toggleFavoriteTorrent(item: TorrentItem): Boolean {
        if (item.title.isBlank() || item.playableLink.isBlank()) return false
        val id = item.stableFavoriteId()
        val current = getFavoriteTorrents()
        val exists = current.any { it.id == id }
        val updated = if (exists) {
            current.filterNot { it.id == id }
        } else {
            val entry = FavoriteTorrent(
                id = id,
                title = item.title,
                magnet = item.magnet,
                torrentUrl = item.torrentUrl,
                sizeBytes = item.sizeBytes,
                sizeText = item.sizeText,
                quality = item.quality,
                isHdr = item.isHdr,
                tracker = item.tracker,
                voices = item.voices,
                addedAt = System.currentTimeMillis()
            )
            listOf(entry) + current
        }
        persistFavoriteTorrents(updated.take(40))
        return !exists
    }

    fun removeFavoriteTorrent(id: String) {
        if (id.isBlank()) return
        val updated = getFavoriteTorrents().filterNot { it.id == id }
        persistFavoriteTorrents(updated)
    }

    fun clearFavoriteTorrents() {
        synchronized(listCacheLock) {
            favoriteTorrentsCache = emptyList()
            prefs.edit().putString(KEY_FAVORITE_TORRENTS, "[]").apply()
        }
    }

    private fun persistFavoriteTorrents(items: List<FavoriteTorrent>) {
        val snapshot = items.sortedByDescending { it.addedAt }.take(40)
        val arr = JSONArray()
        for (item in snapshot) {
            val vArr = JSONArray()
            for (v in item.voices) vArr.put(v)
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("magnet", item.magnet)
                    put("torrentUrl", item.torrentUrl)
                    put("sizeBytes", item.sizeBytes)
                    put("sizeText", item.sizeText)
                    put("quality", item.quality)
                    put("isHdr", item.isHdr)
                    put("tracker", item.tracker)
                    put("voices", vArr)
                    put("addedAt", item.addedAt)
                }
            )
        }
        synchronized(listCacheLock) {
            favoriteTorrentsCache = snapshot
            prefs.edit().putString(KEY_FAVORITE_TORRENTS, arr.toString()).apply()
        }
    }

    private fun persistFavorites(items: List<FavoriteQuery>) {
        val snapshot = items.sortedByDescending { it.addedAt }.take(20)
        val arr = JSONArray()
        for (fav in snapshot) {
            arr.put(
                JSONObject().apply {
                    put("query", fav.query)
                    put("addedAt", fav.addedAt)
                }
            )
        }
        synchronized(listCacheLock) {
            favoritesCache = snapshot
            prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
        }
    }

    fun getRecentSearches(): List<String> {
        recentSearchesCache?.let { return it }
        return synchronized(listCacheLock) {
            recentSearchesCache?.let { return@synchronized it }
            val raw = prefs.getString(KEY_RECENT_SEARCHES, "[]").orEmpty()
            val list = mutableListOf<String>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val q = arr.optString(i).trim()
                    if (q.isNotBlank()) list.add(q)
                }
            }
            val result = list.take(8)
            recentSearchesCache = result
            result
        }
    }

    fun addRecentSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val lower = clean.lowercase(Locale.ROOT)
        val updated = getRecentSearches()
            .filterNot { it.lowercase(Locale.ROOT) == lower }
            .toMutableList()
        updated.add(0, clean)
        persistRecentSearches(updated.take(8))
    }

    fun removeRecentSearch(query: String) {
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return
        val updated = getRecentSearches().filterNot { it.lowercase(Locale.ROOT) == lower }
        persistRecentSearches(updated)
    }

    fun clearRecentSearches() {
        synchronized(listCacheLock) {
            recentSearchesCache = emptyList()
            prefs.edit().putString(KEY_RECENT_SEARCHES, "[]").apply()
        }
    }

    private fun persistRecentSearches(items: List<String>) {
        val snapshot = items.take(8)
        val arr = JSONArray()
        for (q in snapshot) {
            arr.put(q)
        }
        synchronized(listCacheLock) {
            recentSearchesCache = snapshot
            prefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
        }
    }

    private fun safeJsonArray(key: String): JSONArray {
        val raw = prefs.getString(key, "[]").orEmpty().trim()
        if (raw.isBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun exportStateJson(): JSONObject {
        val watchedArr = JSONArray()
        val watchedSet = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet()) ?: emptySet()
        for (w in watchedSet) {
            watchedArr.put(w)
        }
        return JSONObject().apply {
            put(KEY_USE_BUILTIN_PLAYER, useBuiltInPlayer)
            put(KEY_PLAYER_ENGINE, playerEngine)
            put(KEY_BUFFER_PROFILE, bufferProfile)
            put(KEY_AUDIO_BOOST_DB, audioBoostDb)
            put(KEY_USE_DOH_DNS, useDohDns)
            put(KEY_USE_DPI_BYPASS, useDpiBypass)
            put(KEY_NETWORK_PROXY_URL, networkProxyUrl)
            put(KEY_SHOW_PLAYER_STATS_HUD, showPlayerStatsHud)
            put(KEY_PLASMA_PIXEL_SHIFT, plasmaPixelShift)
            put(KEY_PLAYER_STATS_CORNER, playerStatsCorner)
            put(KEY_USE_PUBLIC_JACRED, usePublicJacRed)
            put(KEY_USE_RUTOR_FALLBACK, useRutorFallback)
            put(KEY_AUTO_CHECK_UPDATES, autoCheckUpdates)
            put(KEY_HIDE_CAM_RELEASES, hideCamReleases)
            put(KEY_ONLY_LIVE_SEEDS, onlyLiveSeeds)
            put(KEY_ONLY_DUB_RELEASES, onlyDubReleases)
            put(KEY_HIDE_4K_RELEASES, hide4kReleases)
            put(KEY_AUTO_FRAME_RATE, autoFrameRate)
            put(KEY_SHOW_TMDB_SHOWCASE, showTmdbShowcase)
            put(KEY_CATALOG_PROVIDER, catalogProvider)
            put(KEY_KINOPOISK_API_KEY, kinopoiskApiKey)
            put(KEY_TMDB_API_KEY, tmdbApiKey)
            put(KEY_TMDB_API_BASE, tmdbApiBaseUrl)
            put(KEY_TMDB_IMAGE_BASE, tmdbImageBaseUrl)
            put(KEY_TORRSERVE_URL, torrServeUrl)
            put(KEY_CUSTOM_PARSER_URL, customParserUrl)
            put(KEY_PARSER_API_KEY, parserApiKey)
            put(KEY_RUTOR_MIRROR, rutorMirrorUrl)
            put(KEY_LAST_QUERY, lastQuery)
            put(KEY_WATCHED_EPISODES, watchedArr)
            put(KEY_EPISODE_PROGRESS, readEpisodeProgressJson())
            put(KEY_LAST_EPISODE_MAP, readLastEpisodeJson())
            put(KEY_TORRENT_AUDIO_BOOST_MAP, readTorrentAudioBoostJson())
            put(KEY_TORRENT_RESIZE_MODE_MAP, readTorrentResizeModeJson())
            put(KEY_WATCHLIST, safeJsonArray(KEY_WATCHLIST))
            put(KEY_WATCH_HISTORY, safeJsonArray(KEY_WATCH_HISTORY))
            put(KEY_FAVORITES, safeJsonArray(KEY_FAVORITES))
            put(KEY_FAVORITE_TORRENTS, safeJsonArray(KEY_FAVORITE_TORRENTS))
            put(KEY_RECENT_SEARCHES, safeJsonArray(KEY_RECENT_SEARCHES))
        }
    }

    fun importStateJson(obj: JSONObject) {
        val editor = prefs.edit()
        if (obj.has(KEY_PLAYER_ENGINE)) {
            val eng = obj.optInt(KEY_PLAYER_ENGINE, PLAYER_ENGINE_MEDIA3)
                .coerceIn(PLAYER_ENGINE_MEDIA3, PLAYER_ENGINE_EXTERNAL)
            editor.putInt(KEY_PLAYER_ENGINE, eng)
            editor.putBoolean(KEY_USE_BUILTIN_PLAYER, eng != PLAYER_ENGINE_EXTERNAL)
        } else if (obj.has(KEY_USE_BUILTIN_PLAYER)) {
            val builtIn = obj.optBoolean(KEY_USE_BUILTIN_PLAYER, true)
            editor.putBoolean(KEY_USE_BUILTIN_PLAYER, builtIn)
            editor.putInt(KEY_PLAYER_ENGINE, if (builtIn) PLAYER_ENGINE_MEDIA3 else PLAYER_ENGINE_EXTERNAL)
        }
        if (obj.has(KEY_BUFFER_PROFILE)) editor.putInt(KEY_BUFFER_PROFILE, obj.optInt(KEY_BUFFER_PROFILE, BUFFER_PROFILE_STANDARD).coerceIn(BUFFER_PROFILE_FAST, BUFFER_PROFILE_4K))
        if (obj.has(KEY_AUDIO_BOOST_DB)) editor.putInt(KEY_AUDIO_BOOST_DB, obj.optInt(KEY_AUDIO_BOOST_DB, 0).coerceIn(0, 12))
        if (obj.has(KEY_USE_DOH_DNS)) editor.putBoolean(KEY_USE_DOH_DNS, obj.optBoolean(KEY_USE_DOH_DNS, true))
        if (obj.has(KEY_USE_DPI_BYPASS)) editor.putBoolean(KEY_USE_DPI_BYPASS, obj.optBoolean(KEY_USE_DPI_BYPASS, false))
        if (obj.has(KEY_NETWORK_PROXY_URL)) editor.putString(KEY_NETWORK_PROXY_URL, obj.optString(KEY_NETWORK_PROXY_URL, "").trim())
        if (obj.has(KEY_SHOW_PLAYER_STATS_HUD)) editor.putBoolean(KEY_SHOW_PLAYER_STATS_HUD, obj.optBoolean(KEY_SHOW_PLAYER_STATS_HUD, true))
        if (obj.has(KEY_PLASMA_PIXEL_SHIFT)) editor.putBoolean(KEY_PLASMA_PIXEL_SHIFT, obj.optBoolean(KEY_PLASMA_PIXEL_SHIFT, true))
        if (obj.has(KEY_PLAYER_STATS_CORNER)) {
            editor.putInt(
                KEY_PLAYER_STATS_CORNER,
                obj.optInt(KEY_PLAYER_STATS_CORNER, STATS_CORNER_TOP_RIGHT)
                    .coerceIn(STATS_CORNER_TOP_RIGHT, STATS_CORNER_BOTTOM_RIGHT)
            )
        }
        if (obj.has(KEY_USE_PUBLIC_JACRED)) editor.putBoolean(KEY_USE_PUBLIC_JACRED, obj.optBoolean(KEY_USE_PUBLIC_JACRED, true))
        if (obj.has(KEY_USE_RUTOR_FALLBACK)) editor.putBoolean(KEY_USE_RUTOR_FALLBACK, obj.optBoolean(KEY_USE_RUTOR_FALLBACK, true))
        if (obj.has(KEY_AUTO_CHECK_UPDATES)) editor.putBoolean(KEY_AUTO_CHECK_UPDATES, obj.optBoolean(KEY_AUTO_CHECK_UPDATES, true))
        if (obj.has(KEY_HIDE_CAM_RELEASES)) editor.putBoolean(KEY_HIDE_CAM_RELEASES, obj.optBoolean(KEY_HIDE_CAM_RELEASES, false))
        if (obj.has(KEY_ONLY_LIVE_SEEDS)) editor.putBoolean(KEY_ONLY_LIVE_SEEDS, obj.optBoolean(KEY_ONLY_LIVE_SEEDS, false))
        if (obj.has(KEY_ONLY_DUB_RELEASES)) editor.putBoolean(KEY_ONLY_DUB_RELEASES, obj.optBoolean(KEY_ONLY_DUB_RELEASES, false))
        if (obj.has(KEY_HIDE_4K_RELEASES)) editor.putBoolean(KEY_HIDE_4K_RELEASES, obj.optBoolean(KEY_HIDE_4K_RELEASES, false))
        if (obj.has(KEY_AUTO_FRAME_RATE)) editor.putBoolean(KEY_AUTO_FRAME_RATE, obj.optBoolean(KEY_AUTO_FRAME_RATE, true))
        if (obj.has(KEY_SHOW_TMDB_SHOWCASE)) editor.putBoolean(KEY_SHOW_TMDB_SHOWCASE, obj.optBoolean(KEY_SHOW_TMDB_SHOWCASE, true))
        if (obj.has(KEY_CATALOG_PROVIDER)) {
            editor.putInt(
                KEY_CATALOG_PROVIDER,
                obj.optInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_TORRENTS)
                    .coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TORRENTS)
            )
        }
        if (obj.has(KEY_KINOPOISK_API_KEY)) {
            editor.putString(KEY_KINOPOISK_API_KEY, obj.optString(KEY_KINOPOISK_API_KEY, "").trim())
        }
        if (obj.has(KEY_TMDB_API_KEY)) editor.putString(KEY_TMDB_API_KEY, obj.optString(KEY_TMDB_API_KEY, "").trim())
        if (obj.has(KEY_TMDB_API_BASE)) {
            val v = obj.optString(KEY_TMDB_API_BASE, DEFAULT_TMDB_API_URL).trim().trimEnd('/').ifBlank { DEFAULT_TMDB_API_URL }
            editor.putString(KEY_TMDB_API_BASE, v)
        }
        if (obj.has(KEY_TMDB_IMAGE_BASE)) {
            val v = obj.optString(KEY_TMDB_IMAGE_BASE, DEFAULT_TMDB_IMAGE_URL).trim().trimEnd('/').ifBlank { DEFAULT_TMDB_IMAGE_URL }
            editor.putString(KEY_TMDB_IMAGE_BASE, v)
        }
        if (obj.has(KEY_TORRSERVE_URL)) {
            val v = obj.optString(KEY_TORRSERVE_URL, DEFAULT_TORRSERVE_URL).trim().trimEnd('/').ifBlank { DEFAULT_TORRSERVE_URL }
            editor.putString(KEY_TORRSERVE_URL, v)
        }
        if (obj.has(KEY_CUSTOM_PARSER_URL)) {
            editor.putString(KEY_CUSTOM_PARSER_URL, obj.optString(KEY_CUSTOM_PARSER_URL, "").trim().trimEnd('/'))
        }
        if (obj.has(KEY_PARSER_API_KEY)) {
            editor.putString(KEY_PARSER_API_KEY, obj.optString(KEY_PARSER_API_KEY, "").trim())
        }
        if (obj.has(KEY_RUTOR_MIRROR)) {
            val v = obj.optString(KEY_RUTOR_MIRROR, DEFAULT_RUTOR_MIRROR).trim().trimEnd('/').ifBlank { DEFAULT_RUTOR_MIRROR }
            editor.putString(KEY_RUTOR_MIRROR, v)
        }
        if (obj.has(KEY_LAST_QUERY)) {
            editor.putString(KEY_LAST_QUERY, obj.optString(KEY_LAST_QUERY, "").trim())
        }
        obj.optJSONArray(KEY_WATCHED_EPISODES)?.let { arr ->
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) set.add(s)
            }
            editor.putStringSet(KEY_WATCHED_EPISODES, set)
        }
        obj.optJSONObject(KEY_EPISODE_PROGRESS)?.let { prog ->
            editor.putString(KEY_EPISODE_PROGRESS, prog.toString())
        }
        obj.optJSONObject(KEY_LAST_EPISODE_MAP)?.let { lastMap ->
            editor.putString(KEY_LAST_EPISODE_MAP, lastMap.toString())
        }
        obj.optJSONObject(KEY_TORRENT_AUDIO_BOOST_MAP)?.let { boostMap ->
            editor.putString(KEY_TORRENT_AUDIO_BOOST_MAP, boostMap.toString())
        }
        obj.optJSONObject(KEY_TORRENT_RESIZE_MODE_MAP)?.let { resizeMap ->
            editor.putString(KEY_TORRENT_RESIZE_MODE_MAP, resizeMap.toString())
        }
        obj.optJSONArray(KEY_WATCHLIST)?.let { wl ->
            editor.putString(KEY_WATCHLIST, wl.toString())
        }
        obj.optJSONArray(KEY_WATCH_HISTORY)?.let { arr ->
            editor.putString(KEY_WATCH_HISTORY, arr.toString())
        }
        obj.optJSONArray(KEY_FAVORITES)?.let { arr ->
            editor.putString(KEY_FAVORITES, arr.toString())
        }
        obj.optJSONArray(KEY_FAVORITE_TORRENTS)?.let { arr ->
            editor.putString(KEY_FAVORITE_TORRENTS, arr.toString())
        }
        obj.optJSONArray(KEY_RECENT_SEARCHES)?.let { arr ->
            editor.putString(KEY_RECENT_SEARCHES, arr.toString())
        }
        synchronized(listCacheLock) {
            editor.apply()
            invalidateListCachesLocked()
        }
    }

    companion object {
        private val listCacheLock = Any()

        @Volatile
        private var watchHistoryCache: List<WatchHistoryEntry>? = null

        @Volatile
        private var watchlistCache: List<CatalogItem>? = null

        @Volatile
        private var favoritesCache: List<FavoriteQuery>? = null

        @Volatile
        private var favoriteTorrentsCache: List<FavoriteTorrent>? = null

        @Volatile
        private var recentSearchesCache: List<String>? = null

        private fun invalidateListCachesLocked() {
            watchHistoryCache = null
            watchlistCache = null
            favoritesCache = null
            favoriteTorrentsCache = null
            recentSearchesCache = null
        }

        fun invalidateListCaches() {
            synchronized(listCacheLock) {
                invalidateListCachesLocked()
            }
        }

        private const val PREFS_NAME = "torplay_prefs"
        private const val KEY_USE_BUILTIN_PLAYER = "use_builtin_player"
        private const val KEY_PLAYER_ENGINE = "player_engine"
        private const val KEY_BUFFER_PROFILE = "buffer_profile"
        private const val KEY_USE_PUBLIC_JACRED = "use_public_jacred"
        private const val KEY_USE_RUTOR_FALLBACK = "use_rutor_fallback"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_HIDE_CAM_RELEASES = "hide_cam_releases"
        private const val KEY_ONLY_LIVE_SEEDS = "only_live_seeds"
        private const val KEY_SHOW_TMDB_SHOWCASE = "show_tmdb_showcase"
        private const val KEY_CATALOG_PROVIDER = "catalog_provider"
        private const val KEY_KINOPOISK_API_KEY = "kinopoisk_api_key"
        private const val KEY_TMDB_API_KEY = "tmdb_api_key"
        private const val KEY_TMDB_API_BASE = "tmdb_api_base"
        private const val KEY_TMDB_IMAGE_BASE = "tmdb_image_base"
        private const val KEY_AUDIO_BOOST_DB = "audio_boost_db"
        private const val KEY_TORRENT_AUDIO_BOOST_MAP = "torrent_audio_boost_map"
        private const val KEY_TORRENT_RESIZE_MODE_MAP = "torrent_resize_mode_map"
        private const val KEY_ONLY_DUB_RELEASES = "only_dub_releases"
        private const val KEY_HIDE_4K_RELEASES = "hide_4k_releases"
        private const val KEY_AUTO_FRAME_RATE = "auto_frame_rate"
        private const val KEY_WATCHLIST = "movie_watchlist"
        private const val KEY_USE_DOH_DNS = "use_doh_dns"
        private const val KEY_USE_DPI_BYPASS = "use_dpi_bypass"
        private const val KEY_NETWORK_PROXY_URL = "network_proxy_url"
        private const val KEY_SHOW_PLAYER_STATS_HUD = "show_player_stats_hud"
        private const val KEY_PLASMA_PIXEL_SHIFT = "plasma_pixel_shift"
        private const val KEY_PLAYER_STATS_CORNER = "player_stats_corner"
        private const val KEY_DAILY_TOP_DATE = "daily_top_date"
        private const val KEY_DAILY_TOP_ITEMS = "daily_top_items"
        private const val KEY_DEFAULTS_V106_APPLIED = "defaults_v106_applied"
        private const val KEY_TORRSERVE_URL = "torrserve_url"
        private const val KEY_CUSTOM_PARSER_URL = "custom_parser_url"
        private const val KEY_PARSER_API_KEY = "parser_api_key"
        private const val KEY_RUTOR_MIRROR = "rutor_mirror"
        private const val KEY_LAST_QUERY = "last_query"
        private const val KEY_WATCHED_EPISODES = "watched_episodes"
        private const val KEY_EPISODE_PROGRESS = "episode_progress"
        private const val KEY_LAST_EPISODE_MAP = "last_episode_map"
        private const val KEY_WATCH_HISTORY = "watch_history"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FAVORITE_TORRENTS = "favorite_torrents"
        private const val KEY_RECENT_SEARCHES = "recent_searches"

        const val PLAYER_ENGINE_MEDIA3 = 0
        const val PLAYER_ENGINE_VLC = 1
        const val PLAYER_ENGINE_EXTERNAL = 2

        const val STATS_CORNER_TOP_RIGHT = 0
        const val STATS_CORNER_TOP_LEFT = 1
        const val STATS_CORNER_BOTTOM_RIGHT = 2

        const val BUFFER_PROFILE_FAST = 0
        const val BUFFER_PROFILE_STANDARD = 1
        const val BUFFER_PROFILE_4K = 2

        const val CATALOG_PROVIDER_KINOPOISK = 0
        const val CATALOG_PROVIDER_TMDB = 1
        const val CATALOG_PROVIDER_TORRENTS = 2

        const val DEFAULT_TORRSERVE_URL = "http://127.0.0.1:8090"
        const val DEFAULT_RUTOR_MIRROR = "https://rutor.info"
        const val DEFAULT_KINOPOISK_API_KEY = "46b3c435-7c7a-4267-9bd9-f883cbc14c1d"
        const val DEFAULT_TMDB_API_KEY = "d2a353381bc2814cbc95694672d7b149"
        const val DEFAULT_TMDB_API_URL = "https://api.themoviedb.org/3"
        const val DEFAULT_TMDB_IMAGE_URL = "https://imagetmdb.com/t/p/w342"
    }
}
