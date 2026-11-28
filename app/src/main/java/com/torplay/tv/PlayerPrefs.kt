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

    // 0 = Small, 1 = Normal (default), 2 = Large, 3 = Huge
    var subtitleSizeIndex: Int
        get() = prefs.getInt(KEY_SUB_SIZE, SUB_SIZE_NORMAL).coerceIn(SUB_SIZE_SMALL, SUB_SIZE_HUGE)
        set(value) {
            prefs.edit().putInt(KEY_SUB_SIZE, value.coerceIn(SUB_SIZE_SMALL, SUB_SIZE_HUGE)).apply()
        }

    // 0 = Transparent, 1 = Translucent (default), 2 = Black
    var subtitleBgIndex: Int
        get() = prefs.getInt(KEY_SUB_BG, SUB_BG_TRANSLUCENT).coerceIn(SUB_BG_TRANSPARENT, SUB_BG_BLACK)
        set(value) {
            prefs.edit().putInt(KEY_SUB_BG, value.coerceIn(SUB_BG_TRANSPARENT, SUB_BG_BLACK)).apply()
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
        private const val KEY_SUB_SIZE = "subtitle_size_index"
        private const val KEY_SUB_BG = "subtitle_bg_index"

        const val SUB_SIZE_SMALL = 0
        const val SUB_SIZE_NORMAL = 1
        const val SUB_SIZE_LARGE = 2
        const val SUB_SIZE_HUGE = 3

        const val SUB_BG_TRANSPARENT = 0
        const val SUB_BG_TRANSLUCENT = 1
        const val SUB_BG_BLACK = 2
    }
}
