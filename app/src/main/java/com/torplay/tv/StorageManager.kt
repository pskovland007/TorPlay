package com.torplay.tv

import android.content.Context
import java.util.Locale

class StorageManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    companion object {
        private const val PREFS_NAME = "torplay_prefs"
        private const val KEY_TORRSERVE_URL = "torrserve_url"
        private const val KEY_CUSTOM_PARSER_URL = "custom_parser_url"
        private const val KEY_PARSER_API_KEY = "parser_api_key"
        private const val KEY_RUTOR_MIRROR = "rutor_mirror"
        private const val KEY_LAST_QUERY = "last_query"
        private const val KEY_WATCHED_EPISODES = "watched_episodes"

        const val DEFAULT_TORRSERVE_URL = "http://127.0.0.1:8090"
        const val DEFAULT_RUTOR_MIRROR = "https://rutor.info"
    }
}
