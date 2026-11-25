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
        get() = prefs.getInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_KINOPOISK)
            .coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TMDB)
        set(value) {
            prefs.edit()
                .putInt(KEY_CATALOG_PROVIDER, value.coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TMDB))
                .apply()
        }

    var kinopoiskApiKey: String
        get() = prefs.getString(KEY_KINOPOISK_API_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_KINOPOISK_API_KEY, value.trim()).apply()
        }

    fun activeCatalogKey(): String {
        return if (catalogProvider == CATALOG_PROVIDER_KINOPOISK) kinopoiskApiKey else tmdbApiKey
    }

    fun activeCatalogName(): String {
        return if (catalogProvider == CATALOG_PROVIDER_KINOPOISK) "Кинопоиск" else "TMDB"
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

    fun markEpisodeWatched(hash: String, fileId: Int) {
        if (hash.isBlank()) return
        val current = prefs.getStringSet(KEY_WATCHED_EPISODES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(episodeKey(hash, fileId))
        prefs.edit().putStringSet(KEY_WATCHED_EPISODES, current).apply()
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

    fun getSavedPositionMs(link: String, fileId: Int): Long {
        if (link.isBlank()) return 0L
        val id = "${link.hashCode()}_$fileId"
        return getWatchHistory().firstOrNull { it.id == id }?.positionMs ?: 0L
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
            put(KEY_WATCH_HISTORY, JSONArray(prefs.getString(KEY_WATCH_HISTORY, "[]") ?: "[]"))
            put(KEY_FAVORITES, JSONArray(prefs.getString(KEY_FAVORITES, "[]") ?: "[]"))
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
                obj.optInt(KEY_CATALOG_PROVIDER, CATALOG_PROVIDER_KINOPOISK)
                    .coerceIn(CATALOG_PROVIDER_KINOPOISK, CATALOG_PROVIDER_TMDB)
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
        obj.optJSONArray(KEY_WATCH_HISTORY)?.let { arr ->
            editor.putString(KEY_WATCH_HISTORY, arr.toString())
        }
        obj.optJSONArray(KEY_FAVORITES)?.let { arr ->
            editor.putString(KEY_FAVORITES, arr.toString())
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
        private const val KEY_WATCH_HISTORY = "watch_history"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENT_SEARCHES = "recent_searches"

        const val BUFFER_PROFILE_FAST = 0
        const val BUFFER_PROFILE_STANDARD = 1
        const val BUFFER_PROFILE_4K = 2

        const val CATALOG_PROVIDER_KINOPOISK = 0
        const val CATALOG_PROVIDER_TMDB = 1

        const val DEFAULT_TORRSERVE_URL = "http://127.0.0.1:8090"
        const val DEFAULT_RUTOR_MIRROR = "https://rutor.info"
        const val DEFAULT_TMDB_API_URL = "https://api.themoviedb.org/3"
        const val DEFAULT_TMDB_IMAGE_URL = "https://image.tmdb.org/t/p/w342"
    }
}
