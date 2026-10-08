package com.zynelabs.stb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.9: list caching so back-navigation from the player is instant.
 *
 * Two tiers:
 * - memory: Map<key, Entry> — survives within the process.
 * - disk: SharedPreferences "list_cache" (key -> JSON, key+"__t" -> timestamp)
 *   — survives app restarts; only small payloads (genres, categories).
 *
 * TTL 5 minutes: [getFresh] returns data younger than TTL (skip network),
 * [getStale] returns data of any age (show instantly while refreshing).
 * Callers serialize their own lists via the helpers below.
 */
object ListCache {

    const val TTL_MS = 5 * 60 * 1000L
    private const val PREFS = "list_cache"
    private const val T_SUFFIX = "__t"

    private data class Entry(val json: String, val time: Long)
    private val memory = mutableMapOf<String, Entry>()

    @Synchronized
    fun put(ctx: Context, key: String, json: String, persist: Boolean = false) {
        val e = Entry(json, System.currentTimeMillis())
        memory[key] = e
        if (persist) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, json)
                .putLong(key + T_SUFFIX, e.time)
                .apply()
        }
    }

    @Synchronized
    fun getFresh(ctx: Context, key: String): String? {
        val e = memory[key] ?: readDisk(ctx, key)?.also { memory[key] = it }
        ?: return null
        return if (System.currentTimeMillis() - e.time < TTL_MS) e.json else null
    }

    @Synchronized
    fun getStale(ctx: Context, key: String): String? {
        val e = memory[key] ?: readDisk(ctx, key)?.also { memory[key] = it }
        ?: return null
        return e.json
    }

    @Synchronized
    fun invalidate(key: String) {
        memory.remove(key)
    }

    @Synchronized
    fun invalidateAll() {
        memory.clear()
    }

    private fun readDisk(ctx: Context, key: String): Entry? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = p.getString(key, null) ?: return null
        val t = p.getLong(key + T_SUFFIX, 0L)
        return Entry(json, t)
    }

    // ---------------------------------------------------------- serializers

    fun channelToJson(c: Channel): JSONObject = JSONObject()
        .put("id", c.id)
        .put("number", c.number)
        .put("name", c.name)
        .put("cmd", c.cmd)
        .put("logo", c.logo)
        .put("genreId", c.genreId)

    fun channelFromJson(o: JSONObject): Channel = Channel(
        id = o.optString("id"),
        number = o.optString("number"),
        name = o.optString("name"),
        cmd = o.optString("cmd"),
        logo = o.optString("logo"),
        genreId = o.optString("genreId")
    )

    fun vodCategoryToJson(c: StalkerApi.VodCategory): JSONObject = JSONObject()
        .put("id", c.id)
        .put("title", c.title)

    fun vodCategoryFromJson(o: JSONObject): StalkerApi.VodCategory =
        StalkerApi.VodCategory(id = o.optString("id"), title = o.optString("title"))

    fun channelsToJson(list: List<Channel>): String {
        val a = JSONArray()
        for (c in list) a.put(channelToJson(c))
        return a.toString()
    }

    fun channelsFromJson(json: String): List<Channel> {
        val out = ArrayList<Channel>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) out.add(channelFromJson(a.getJSONObject(i)))
        return out
    }

    fun genresToJson(list: List<StalkerApi.Genre>): String {
        val a = JSONArray()
        for (g in list) {
            a.put(JSONObject().put("id", g.id).put("title", g.title))
        }
        return a.toString()
    }

    fun genresFromJson(json: String): List<StalkerApi.Genre> {
        val out = ArrayList<StalkerApi.Genre>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(StalkerApi.Genre(id = o.optString("id"), title = o.optString("title")))
        }
        return out
    }

    fun vodCatsToJson(list: List<StalkerApi.VodCategory>): String {
        val a = JSONArray()
        for (c in list) a.put(vodCategoryToJson(c))
        return a.toString()
    }

    fun vodCatsFromJson(json: String): List<StalkerApi.VodCategory> {
        val out = ArrayList<StalkerApi.VodCategory>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) out.add(vodCategoryFromJson(a.getJSONObject(i)))
        return out
    }

    fun vodItemsToJson(list: List<StalkerApi.VodItem>): String {
        val a = JSONArray()
        for (v in list) {
            a.put(
                JSONObject()
                    .put("id", v.id)
                    .put("name", v.name)
                    .put("cmd", v.cmd)
                    .put("posterUrl", v.posterUrl)
            )
        }
        return a.toString()
    }

    fun vodItemsFromJson(json: String): List<StalkerApi.VodItem> {
        val out = ArrayList<StalkerApi.VodItem>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(
                StalkerApi.VodItem(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    cmd = o.optString("cmd"),
                    posterUrl = o.optString("posterUrl")
                )
            )
        }
        return out
    }
}
