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
        val trimmed = current.take(15)
        val arr = JSONArray()
        for (item in trimmed) {
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
        val arr = JSONArray()
        for (fav in updated.take(20)) {
            arr.put(
                JSONObject().apply {
                    put("query", fav.query)
                    put("addedAt", fav.addedAt)
                }
            )
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
        return !exists
    }

    companion object {
        private const val PREFS_NAME = "torplay_prefs"
        private const val KEY_USE_BUILTIN_PLAYER = "use_builtin_player"
        private const val KEY_USE_PUBLIC_JACRED = "use_public_jacred"
        private const val KEY_USE_RUTOR_FALLBACK = "use_rutor_fallback"
        private const val KEY_TORRSERVE_URL = "torrserve_url"
        private const val KEY_CUSTOM_PARSER_URL = "custom_parser_url"
        private const val KEY_PARSER_API_KEY = "parser_api_key"
        private const val KEY_RUTOR_MIRROR = "rutor_mirror"
        private const val KEY_LAST_QUERY = "last_query"
        private const val KEY_WATCHED_EPISODES = "watched_episodes"
        private const val KEY_WATCH_HISTORY = "watch_history"
        private const val KEY_FAVORITES = "favorites"

        const val DEFAULT_TORRSERVE_URL = "http://127.0.0.1:8090"
        const val DEFAULT_RUTOR_MIRROR = "https://rutor.info"
    }
}
