package com.zynelabs.stb

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved Stalker portal (URL + box MAC). */
data class Provider(val id: String, val name: String, val url: String, val mac: String)

/**
 * Saved provider list, backed by SharedPreferences ("stb_providers").
 * Lets the user keep several portals and switch without clearing app data.
 * On first access the list is seeded from the legacy single-provider prefs
 * ("portal_url"/"portal_mac") when present.
 */
object ProviderStore {

    private const val FILE = "stb_providers"
    private const val KEY = "providers"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Provider> {
        ensureSeeded(ctx)
        val raw = prefs(ctx).getString(KEY, "[]").orEmpty()
        val out = ArrayList<Provider>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    mac = o.optString("mac")
                )
                if (p.id.isNotBlank() && p.url.isNotBlank()) out.add(p)
            }
        } catch (_: Exception) {
            // Corrupt JSON -> treat as empty.
        }
        return out
    }

    /** Insert or update by id. */
    fun save(ctx: Context, p: Provider) {
        val cur = list(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.id == p.id }
        if (idx >= 0) cur[idx] = p else cur.add(p)
        persist(ctx, cur)
    }

    fun delete(ctx: Context, id: String) {
        persist(ctx, list(ctx).filter { it.id != id })
    }

    fun get(ctx: Context, id: String): Provider? =
        list(ctx).firstOrNull { it.id == id }

    /** Display name: explicit name, else the URL host (e.g. "brinoxel.cc"). */
    fun displayName(p: Provider): String =
        p.name.ifBlank { hostOf(p.url) }.ifBlank { p.url }

    fun newId(): String = UUID.randomUUID().toString()

    private fun hostOf(url: String): String = try {
        Uri.parse(url.trim()).host.orEmpty().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    private fun persist(ctx: Context, providers: List<Provider>) {
        val arr = JSONArray()
        for (p in providers) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("url", p.url)
                    .put("mac", p.mac)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun ensureSeeded(ctx: Context) {
        val sp = prefs(ctx)
        if (sp.contains(KEY)) return
        val url = Prefs.getPortalUrl(ctx)
        val mac = Prefs.getMac(ctx)
        val seed = if (url.isNotBlank() && mac.isNotBlank()) {
            listOf(Provider(newId(), hostOf(url), url, mac))
        } else {
            emptyList()
        }
        persist(ctx, seed)
    }
}
