package com.zynelabs.stb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v6.3: Xtream Codes API client (player_api.php).
 * Covers live TV, VOD movies and series (flattened to episodes).
 * Stream URLs are built directly — no per-play API call needed.
 */
class XtreamApi(server: String, val username: String, private val password: String) {

    val baseUrl: String = server.trim().trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun getJson(params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder(baseUrl)
                .append("/player_api.php?username=").append(enc(username))
                .append("&password=").append(enc(password))
            for ((k, v) in params) {
                sb.append('&').append(k).append('=').append(enc(v))
            }
            val req = Request.Builder()
                .url(sb.toString())
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw StalkerApi.StalkerException("Xtream HTTP ${resp.code}")
                }
                JSONObject(body)
            }
        }

    private fun JSONArray?.orEmptyList(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            out.add(optJSONObject(i) ?: continue)
        }
        return out
    }

    /** user_info + server_info. Throws when login is rejected. */
    suspend fun userInfo(): JSONObject {
        val js = getJson(emptyMap())
        val user = js.optJSONObject("user_info")
            ?: throw StalkerApi.StalkerException("Xtream login failed")
        if (!user.optString("auth", "0").equals("1")) {
            throw StalkerApi.StalkerException(
                user.optString("status", "Xtream login failed")
            )
        }
        return js
    }

    suspend fun liveCategories(): List<JSONObject> =
        getJson(mapOf("action" to "get_live_categories"))
            .optJSONArray("categories").orEmptyList()

    suspend fun liveStreams(categoryId: String? = null): List<JSONObject> {
        val p = mutableMapOf("action" to "get_live_streams")
        if (!categoryId.isNullOrBlank()) p["category_id"] = categoryId
        return getJson(p).optJSONArray("streams").orEmptyList()
    }

    suspend fun vodCategories(): List<JSONObject> =
        getJson(mapOf("action" to "get_vod_categories"))
            .optJSONArray("categories").orEmptyList()

    suspend fun seriesCategories(): List<JSONObject> =
        getJson(mapOf("action" to "get_series_categories"))
            .optJSONArray("categories").orEmptyList()

    suspend fun vodStreams(categoryId: String? = null): List<JSONObject> {
        val p = mutableMapOf("action" to "get_vod_streams")
        if (!categoryId.isNullOrBlank()) p["category_id"] = categoryId
        return getJson(p).optJSONArray("streams").orEmptyList()
    }

    suspend fun seriesList(categoryId: String? = null): List<JSONObject> {
        val p = mutableMapOf("action" to "get_series")
        if (!categoryId.isNullOrBlank()) p["category_id"] = categoryId
        return getJson(p).optJSONArray("series").orEmptyList()
    }

    /** Full series detail (seasons → episodes). */
    suspend fun seriesInfo(seriesId: String): JSONObject =
        getJson(mapOf("action" to "get_series_info", "series_id" to seriesId))

    // ------------------------------------------------------- stream URLs

    fun liveUrl(streamId: String): String =
        "$baseUrl/live/$username/$password/$streamId.m3u8"

    fun movieUrl(streamId: String, container: String): String {
        val ext = container.ifBlank { "mp4" }
        return "$baseUrl/movie/$username/$password/$streamId.$ext"
    }

    fun episodeUrl(streamId: String, container: String): String {
        val ext = container.ifBlank { "mp4" }
        return "$baseUrl/series/$username/$password/$streamId.$ext"
    }
}
