package com.zynelabs.stb

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved playlist provider.
 * v6.3: four types — Stalker portal (MAC), M3U link, M3U file, Xtream login.
 */
data class Provider(
    val id: String,
    val name: String,
    val url: String,
    val mac: String,
    val type: String = ProviderStore.TYPE_STALKER,
    val username: String = "",
    val password: String = "",
    val filePath: String = ""
)

/**
 * Saved provider list, backed by SharedPreferences ("stb_providers").
 * Lets the user keep several providers and switch without clearing app data.
 * On first access the list is seeded from the legacy single-provider prefs
 * ("portal_url"/"portal_mac") when present.
 */
object ProviderStore {

    const val TYPE_STALKER = "stalker"
    const val TYPE_M3U_URL = "m3u_url"
    const val TYPE_M3U_FILE = "m3u_file"
    const val TYPE_XTREAM = "xtream"

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
                    mac = o.optString("mac"),
                    type = o.optString("type").ifBlank { TYPE_STALKER },
                    username = o.optString("username"),
                    password = o.optString("password"),
                    filePath = o.optString("filePath")
                )
                if (p.id.isNotBlank() && (p.url.isNotBlank() || p.filePath.isNotBlank())) {
                    out.add(p)
                }
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

    // v5.6: active provider — the one the app actually connects with.
    private const val KEY_ACTIVE = "active_id"

    fun getActiveId(ctx: Context): String =
        prefs(ctx).getString(KEY_ACTIVE, "").orEmpty()

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Active provider, falling back to the first saved one. */
    fun getActive(ctx: Context): Provider? {
        val id = getActiveId(ctx)
        val p = if (id.isNotBlank()) get(ctx, id) else null
        return p ?: list(ctx).firstOrNull()
    }

    /**
     * v5.6: push the active provider into Prefs (portal_url/mac) and
     * drop the cached sessions so the next call reconnects.
     * v6.3: type-aware — only Stalker providers touch Prefs; every
     * switch drops the unified source cache too.
     * Returns false when no provider is saved.
     */
    fun applyActive(ctx: Context): Boolean {
        val p = getActive(ctx) ?: return false
        if (p.type == TYPE_STALKER) {
            Prefs.save(ctx, p.url, p.mac)
        }
        StalkerSession.reset()
        SourceManager.reset()
        return true
    }

    /** Display name: explicit name, else the URL host (e.g. "brinoxel.cc"). */
    fun displayName(p: Provider): String =
        p.name.ifBlank { hostOf(p.url) }.ifBlank { p.url }.ifBlank { typeLabel(p) }

    /** v6.3: short English label for the provider type. */
    fun typeLabel(p: Provider): String = when (p.type) {
        TYPE_XTREAM -> "Xtream"
        TYPE_M3U_URL -> "M3U link"
        TYPE_M3U_FILE -> "M3U file"
        else -> "Stalker"
    }

    /** v6.3: does this provider need a network login? (M3U file is offline.) */
    fun needsNetwork(p: Provider): Boolean = p.type != TYPE_M3U_FILE

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
                    .put("type", p.type)
                    .put("username", p.username)
                    .put("password", p.password)
                    .put("filePath", p.filePath)
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
