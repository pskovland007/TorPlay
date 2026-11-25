package com.torplay.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class StorageManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var useBuiltInPlayer: Boolean
        get() = prefs.getBoolean(KEY_USE_BUILTIN_PLAYER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_BUILTIN_PLAYER, value).apply()
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

    var showTmdbShowcase: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TMDB_SHOWCASE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_TMDB_SHOWCASE, value).apply()
        }

    var catalogProvider: Int
        get() = prefs.getInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_TORRENTS)
            .coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TORRENTS)
        set(value) {
            prefs.edit()
                .putInt(KEY_CATALOG_PROVIDER, value.coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TORRENTS))
                .apply()
        }

    var kinopoiskApiKey: String
        get() = prefs.getString(KEY_KINOPOISK_API_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_KINOPOISK_API_KEY, value.trim()).apply()
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
        get() = prefs.getString(KEY_TMDB_API_KEY, "")?.trim().orEmpty()
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
        return list.sortedByDescending { it.updatedAt }.take(15)
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
        prefs.edit().putString(KEY_WATCH_HISTORY, "[]").apply()
    }

    private fun persistWatchHistory(items: List<WatchHistoryEntry>) {
        val arr = JSONArray()
        for (item in items) {
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
        prefs.edit().putString(KEY_WATCH_HISTORY, arr.toString()).apply()
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

    fun getFavorites(): List<FavoriteQuery> {
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
        return list.sortedByDescending { it.addedAt }.take(20)
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
        prefs.edit().putString(KEY_FAVORITES, "[]").apply()
    }

    fun getFavoriteTorrents(): List<FavoriteTorrent> {
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
        return list.sortedByDescending { it.addedAt }.take(40)
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
        prefs.edit().putString(KEY_FAVORITE_TORRENTS, "[]").apply()
    }

    private fun persistFavoriteTorrents(items: List<FavoriteTorrent>) {
        val arr = JSONArray()
        for (item in items) {
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
        prefs.edit().putString(KEY_FAVORITE_TORRENTS, arr.toString()).apply()
    }

    private fun persistFavorites(items: List<FavoriteQuery>) {
        val arr = JSONArray()
        for (fav in items) {
            arr.put(
                JSONObject().apply {
                    put("query", fav.query)
                    put("addedAt", fav.addedAt)
                }
            )
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT_SEARCHES, "[]").orEmpty()
        val list = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val q = arr.optString(i).trim()
                if (q.isNotBlank()) list.add(q)
            }
        }
        return list.take(8)
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
        prefs.edit().putString(KEY_RECENT_SEARCHES, "[]").apply()
    }

    private fun persistRecentSearches(items: List<String>) {
        val arr = JSONArray()
        for (q in items) {
            arr.put(q)
        }
        prefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
    }

    fun exportStateJson(): JSONObject {
        val watchedArr = JSONArray()
        val watchedSet = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet()) ?: emptySet()
        for (w in watchedSet) {
            watchedArr.put(w)
        }
        return JSONObject().apply {
            put(KEY_USE_BUILTIN_PLAYER, useBuiltInPlayer)
            put(KEY_BUFFER_PROFILE, bufferProfile)
            put(KEY_USE_PUBLIC_JACRED, usePublicJacRed)
            put(KEY_USE_RUTOR_FALLBACK, useRutorFallback)
            put(KEY_AUTO_CHECK_UPDATES, autoCheckUpdates)
            put(KEY_HIDE_CAM_RELEASES, hideCamReleases)
            put(KEY_ONLY_LIVE_SEEDS, onlyLiveSeeds)
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
            put(KEY_WATCH_HISTORY, JSONArray(prefs.getString(KEY_WATCH_HISTORY, "[]") ?: "[]"))
            put(KEY_FAVORITES, JSONArray(prefs.getString(KEY_FAVORITES, "[]") ?: "[]"))
            put(KEY_FAVORITE_TORRENTS, JSONArray(prefs.getString(KEY_FAVORITE_TORRENTS, "[]") ?: "[]"))
            put(KEY_RECENT_SEARCHES, JSONArray(prefs.getString(KEY_RECENT_SEARCHES, "[]") ?: "[]"))
        }
    }

    fun importStateJson(obj: JSONObject) {
        val editor = prefs.edit()
        if (obj.has(KEY_USE_BUILTIN_PLAYER)) editor.putBoolean(KEY_USE_BUILTIN_PLAYER, obj.optBoolean(KEY_USE_BUILTIN_PLAYER, true))
        if (obj.has(KEY_BUFFER_PROFILE)) editor.putInt(KEY_BUFFER_PROFILE, obj.optInt(KEY_BUFFER_PROFILE, BUFFER_PROFILE_STANDARD).coerceIn(BUFFER_PROFILE_FAST, BUFFER_PROFILE_4K))
        if (obj.has(KEY_USE_PUBLIC_JACRED)) editor.putBoolean(KEY_USE_PUBLIC_JACRED, obj.optBoolean(KEY_USE_PUBLIC_JACRED, true))
        if (obj.has(KEY_USE_RUTOR_FALLBACK)) editor.putBoolean(KEY_USE_RUTOR_FALLBACK, obj.optBoolean(KEY_USE_RUTOR_FALLBACK, true))
        if (obj.has(KEY_AUTO_CHECK_UPDATES)) editor.putBoolean(KEY_AUTO_CHECK_UPDATES, obj.optBoolean(KEY_AUTO_CHECK_UPDATES, true))
        if (obj.has(KEY_HIDE_CAM_RELEASES)) editor.putBoolean(KEY_HIDE_CAM_RELEASES, obj.optBoolean(KEY_HIDE_CAM_RELEASES, false))
        if (obj.has(KEY_ONLY_LIVE_SEEDS)) editor.putBoolean(KEY_ONLY_LIVE_SEEDS, obj.optBoolean(KEY_ONLY_LIVE_SEEDS, false))
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
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "torplay_prefs"
        private const val KEY_USE_BUILTIN_PLAYER = "use_builtin_player"
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

        const val BUFFER_PROFILE_FAST = 0
        const val BUFFER_PROFILE_STANDARD = 1
        const val BUFFER_PROFILE_4K = 2

        const val CATALOG_PROVIDER_KINOPOISK = 0
        const val CATALOG_PROVIDER_TMDB = 1
        const val CATALOG_PROVIDER_TORRENTS = 2

        const val DEFAULT_TORRSERVE_URL = "http://127.0.0.1:8090"
        const val DEFAULT_RUTOR_MIRROR = "https://rutor.info"
        const val DEFAULT_TMDB_API_URL = "https://api.themoviedb.org/3"
        const val DEFAULT_TMDB_IMAGE_URL = "https://image.tmdb.org/t/p/w342"
    }
}
