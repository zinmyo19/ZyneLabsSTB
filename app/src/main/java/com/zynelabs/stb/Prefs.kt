package com.zynelabs.stb

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings persistence: Stalker portal URL + box MAC, plus player
 * preferences (aspect ratio, subtitles, preferred audio language).
 * Stored locally in SharedPreferences; nothing is hard-coded.
 */
object Prefs {

    private const val FILE = "zynelabs_stb"
    private const val KEY_PORTAL_URL = "portal_url"
    private const val KEY_MAC = "mac"
    private const val KEY_ASPECT = "aspect_ratio"
    private const val KEY_SUBTITLES = "subtitles"
    private const val KEY_AUDIO_LANG = "audio_lang"

    // v5.0 player settings
    private const val KEY_SPEED = "player_speed"
    private const val KEY_SUB_SIZE = "subtitle_size"
    private const val KEY_SUB_COLOR = "subtitle_color"
    private const val KEY_BUFFER = "buffer_size"
    private const val KEY_SLEEP = "sleep_timer"

    /** Aspect ratio options shown in Settings (STBEmu-style list). */
    val ASPECT_OPTIONS = arrayOf("Auto", "16:9", "16:10", "4:3", "2:1", "21:9")

    /** Audio language options: label to ISO 639-2 code ("" = system). */
    val AUDIO_LANG_OPTIONS = arrayOf(
        "System" to "",
        "English" to "eng",
        "Russian" to "rus",
        "Ukrainian" to "ukr",
        "French" to "fra",
        "Spanish" to "spa",
        "German" to "deu"
    )

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPortalUrl(context: Context): String =
        prefs(context).getString(KEY_PORTAL_URL, "").orEmpty()

    fun getMac(context: Context): String =
        prefs(context).getString(KEY_MAC, "").orEmpty()

    fun save(context: Context, portalUrl: String, mac: String) {
        prefs(context).edit()
            .putString(KEY_PORTAL_URL, portalUrl.trim().trimEnd('/'))
            .putString(KEY_MAC, mac.trim().uppercase())
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        getPortalUrl(context).isNotBlank() && getMac(context).isNotBlank()

    fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim())

    fun isValidPortalUrl(url: String): Boolean {
        val t = url.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && '.' in t
    }

    // ------------------------------------------------------- player prefs

    fun getAspectRatio(context: Context): String =
        prefs(context).getString(KEY_ASPECT, ASPECT_OPTIONS[0]) ?: ASPECT_OPTIONS[0]

    fun setAspectRatio(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ASPECT, value).apply()
    }

    fun cycleAspectRatio(context: Context): String {
        val cur = getAspectRatio(context)
        val next = ASPECT_OPTIONS[(ASPECT_OPTIONS.indexOf(cur) + 1) % ASPECT_OPTIONS.size]
        setAspectRatio(context, next)
        return next
    }

    fun getSubtitlesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUBTITLES, true)

    fun setSubtitlesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBTITLES, enabled).apply()
    }

    fun getAudioLangLabel(context: Context): String =
        prefs(context).getString(KEY_AUDIO_LANG, AUDIO_LANG_OPTIONS[0].first)
            ?: AUDIO_LANG_OPTIONS[0].first

    fun getAudioLangCode(context: Context): String =
        AUDIO_LANG_OPTIONS.firstOrNull { it.first == getAudioLangLabel(context) }
            ?.second.orEmpty()

    fun cycleAudioLang(context: Context): String {
        val cur = getAudioLangLabel(context)
        val idx = AUDIO_LANG_OPTIONS.indexOfFirst { it.first == cur }
        val next = AUDIO_LANG_OPTIONS[(idx + 1) % AUDIO_LANG_OPTIONS.size].first
        prefs(context).edit().putString(KEY_AUDIO_LANG, next).apply()
        return next
    }

    // ------------------------------------------------- v5.0 player prefs

    /** Playback speed options (labels); applied to ExoPlayer on init. */
    val SPEED_OPTIONS = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
    private val SPEED_VALUES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Subtitle text size options. */
    val SUB_SIZE_OPTIONS = arrayOf("Small", "Medium", "Large")

    /** Subtitle text color options. */
    val SUB_COLOR_OPTIONS = arrayOf("White", "Yellow")

    /** Buffer size presets (maps to ExoPlayer buffer durations). */
    val BUFFER_OPTIONS = arrayOf("Small", "Normal", "Large")

    /** Sleep timer presets (minutes; 0 = off). */
    val SLEEP_OPTIONS = arrayOf("Off", "15 min", "30 min", "60 min", "90 min")
    private val SLEEP_MINUTES = intArrayOf(0, 15, 30, 60, 90)

    private fun cycleOption(context: Context, key: String, options: Array<String>): String {
        val cur = prefs(context).getString(key, options[0]) ?: options[0]
        val idx = options.indexOf(cur).takeIf { it >= 0 } ?: 0
        val next = options[(idx + 1) % options.size]
        prefs(context).edit().putString(key, next).apply()
        return next
    }

    private fun getOption(context: Context, key: String, options: Array<String>): String =
        prefs(context).getString(key, options[0]) ?: options[0]

    fun getPlaybackSpeedLabel(context: Context): String =
        getOption(context, KEY_SPEED, SPEED_OPTIONS)

    fun cyclePlaybackSpeed(context: Context): String =
        cycleOption(context, KEY_SPEED, SPEED_OPTIONS)

    fun playbackSpeedValue(context: Context): Float {
        val idx = SPEED_OPTIONS.indexOf(getPlaybackSpeedLabel(context)).takeIf { it >= 0 } ?: 2
        return SPEED_VALUES[idx]
    }

    fun getSubtitleSize(context: Context): String =
        getOption(context, KEY_SUB_SIZE, SUB_SIZE_OPTIONS)

    fun cycleSubtitleSize(context: Context): String =
        cycleOption(context, KEY_SUB_SIZE, SUB_SIZE_OPTIONS)

    /** Fractional subtitle text size for PlayerView.setFractionalTextSize. */
    fun subtitleSizeFraction(context: Context): Float = when (getSubtitleSize(context)) {
        "Small" -> 0.04f
        "Large" -> 0.07f
        else -> 0.0533f // Medium (ExoPlayer default)
    }

    fun getSubtitleColor(context: Context): String =
        getOption(context, KEY_SUB_COLOR, SUB_COLOR_OPTIONS)

    fun cycleSubtitleColor(context: Context): String =
        cycleOption(context, KEY_SUB_COLOR, SUB_COLOR_OPTIONS)

    fun subtitleColorInt(context: Context): Int = when (getSubtitleColor(context)) {
        "Yellow" -> android.graphics.Color.YELLOW
        else -> android.graphics.Color.WHITE
    }

    fun getBufferSize(context: Context): String =
        getOption(context, KEY_BUFFER, BUFFER_OPTIONS)

    fun cycleBufferSize(context: Context): String =
        cycleOption(context, KEY_BUFFER, BUFFER_OPTIONS)

    /** (minBufferMs, maxBufferMs) for DefaultLoadControl. */
    fun bufferDurationsMs(context: Context): Pair<Int, Int> = when (getBufferSize(context)) {
        "Small" -> 5_000 to 15_000
        "Large" -> 30_000 to 120_000
        else -> 15_000 to 50_000 // Normal
    }

    fun getSleepTimer(context: Context): String =
        getOption(context, KEY_SLEEP, SLEEP_OPTIONS)

    fun cycleSleepTimer(context: Context): String =
        cycleOption(context, KEY_SLEEP, SLEEP_OPTIONS)

    fun sleepTimerMinutes(context: Context): Int {
        val idx = SLEEP_OPTIONS.indexOf(getSleepTimer(context)).takeIf { it >= 0 } ?: 0
        return SLEEP_MINUTES[idx]
    }

    // ------------------------------------------------------- v6.3 categories view

    private const val KEY_CAT_VIEW = "cat_view"
    const val CAT_VIEW_GRID = "grid"
    const val CAT_VIEW_LIST = "list"

    /** Categories view mode: "grid" (default) or "list". */
    fun getCatView(context: Context): String =
        prefs(context).getString(KEY_CAT_VIEW, CAT_VIEW_GRID) ?: CAT_VIEW_GRID

    fun setCatView(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CAT_VIEW, value).apply()
    }

    // ------------------------------------------------------------ v5.4 favorites

    private const val KEY_FAVORITES = "favorites"

    /** Favorite channel IDs, persisted as a string set. */
    fun getFavorites(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun isFavorite(context: Context, channelId: String): Boolean =
        getFavorites(context).contains(channelId)

    /** Toggles; returns the new state (true = now a favorite). */
    fun toggleFavorite(context: Context, channelId: String): Boolean {
        val set = getFavorites(context).toMutableSet()
        val nowFav = if (set.contains(channelId)) {
            set.remove(channelId); false
        } else {
            set.add(channelId); true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, set).apply()
        return nowFav
    }

    // ------------------------------------------------------------ v6.3.1 settings tab

    private const val KEY_SETTINGS_TAB = "settings_tab"

    /** Which Settings tab was last open (0=Providers … 3=Developer). */
    fun getSettingsTab(context: Context): Int =
        prefs(context).getInt(KEY_SETTINGS_TAB, 0)

    fun setSettingsTab(context: Context, tab: Int) {
        prefs(context).edit().putInt(KEY_SETTINGS_TAB, tab).apply()
    }
}
