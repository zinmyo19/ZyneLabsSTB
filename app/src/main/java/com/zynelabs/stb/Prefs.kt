package com.zynelabs.stb

import android.content.Context
import android.content.SharedPreferences

/**
 * Portal settings persistence (Stalker portal URL + box MAC address).
 * Stored locally in SharedPreferences; nothing is hard-coded.
 */
object Prefs {

    private const val FILE = "zynelabs_stb"
    private const val KEY_PORTAL_URL = "portal_url"
    private const val KEY_MAC = "mac"

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
}
