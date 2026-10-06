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
}
