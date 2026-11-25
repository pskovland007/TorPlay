package com.torplay.tv

import android.content.Context

class PlayerPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var resizeModeIndex: Int
        get() = prefs.getInt(KEY_RESIZE_MODE, 0).coerceIn(0, 3)
        set(value) {
            prefs.edit().putInt(KEY_RESIZE_MODE, value.coerceIn(0, 3)).apply()
        }

    var autoNextEpisode: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply()
        }

    var preferredAudioLang: String
        get() = prefs.getString(KEY_AUDIO_LANG, "rus")?.trim().orEmpty().ifBlank { "rus" }
        set(value) {
            prefs.edit().putString(KEY_AUDIO_LANG, value.trim()).apply()
        }

    var preferredAudioLabel: String
        get() = prefs.getString(KEY_AUDIO_LABEL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_AUDIO_LABEL, value.trim()).apply()
        }

    var subtitlesDisabled: Boolean
        get() = prefs.getBoolean(KEY_SUB_DISABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SUB_DISABLED, value).apply()
        }

    var preferredSubLang: String
        get() = prefs.getString(KEY_SUB_LANG, "rus")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SUB_LANG, value.trim()).apply()
        }

    var preferredSubLabel: String
        get() = prefs.getString(KEY_SUB_LABEL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SUB_LABEL, value.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "torplay_player_prefs"
        private const val KEY_RESIZE_MODE = "resize_mode"
        private const val KEY_AUTO_NEXT = "auto_next_episode"
        private const val KEY_AUDIO_LANG = "preferred_audio_lang"
        private const val KEY_AUDIO_LABEL = "preferred_audio_label"
        private const val KEY_SUB_DISABLED = "subtitles_disabled"
        private const val KEY_SUB_LANG = "preferred_sub_lang"
        private const val KEY_SUB_LABEL = "preferred_sub_label"
    }
}
