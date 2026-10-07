package com.zynelabs.stb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Stalker Middleware API client (portal.php).
 *
 * Supported actions:
 * - handshake       (type=stb)
 * - get_profile     (type=stb)
 * - get_all_channels(type=itv)
 * - create_link     (type=itv)
 *
 * The token is obtained via [handshake] and refreshed automatically when the
 * portal reports an invalid/expired token.
 */
class StalkerApi(portalUrl: String, private val mac: String) {

    class StalkerException(message: String) : Exception(message)

    private val baseUrl: String = portalUrl.trim().trimEnd('/')

    @Volatile
    private var token: String? = null

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        // Pre-seed the STB cookies a real MAG box sends with every request.
        val portalHttp = ("$baseUrl/").toHttpUrlOrNull()
        if (portalHttp != null) {
            cookieJar.seed(
                portalHttp,
                listOf(
                    Cookie.Builder().name("mac").value(mac)
                        .domain(portalHttp.host).path("/").build(),
                    Cookie.Builder().name("stb_lang").value("en")
                        .domain(portalHttp.host).path("/").build(),
                    Cookie.Builder().name("timezone").value("Asia/Kuala_Lumpur")
                        .domain(portalHttp.host).path("/").build()
                )
            )
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun buildUrl(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): HttpUrl {
        val builder = ("$baseUrl/portal.php").toHttpUrlOrNull()?.newBuilder()
            ?: throw StalkerException("Invalid portal URL")
        builder.addQueryParameter("type", type)
        builder.addQueryParameter("action", action)
        builder.addQueryParameter("JsHttpRequest", "1-xml")
        token?.let { builder.addQueryParameter("token", it) }
        builder.addQueryParameter("mac", mac)
        for ((key, value) in extra) {
            builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    private suspend fun get(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(type, action, extra))
            .header(
                "User-Agent",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                    "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
            )
            .header("X-User-Agent", "Model: MAG250; Link: Ethernet")
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw StalkerException("Empty response from portal")
            try {
                JSONObject(body)
            } catch (e: Exception) {
                throw StalkerException("Invalid JSON from portal")
            }
        }
    }

    /** Extracts the "js" payload or throws when the portal reports an error. */
    private fun jsPayload(response: JSONObject): JSONObject {
        if (!response.has("js") || response.isNull("js")) {
            val err = response.optJSONObject("js")?.optString("error")
                ?: response.optString("error", "unknown portal error")
            throw StalkerException("Portal error: $err")
        }
        return response.getJSONObject("js")
    }

    // -------------------------------------------------------------- session

    /**
     * Performs the handshake and stores the session token.
     * @return the new token
     */
    suspend fun handshake(): String {
        token = null
        val js = jsPayload(get("stb", "handshake"))
        val newToken = js.optString("token", "")
        if (newToken.isBlank()) {
            throw StalkerException("Handshake failed: no token issued")
        }
        token = newToken
        return newToken
    }

    /**
     * Runs [block], performing a handshake first when there is no token and
     * re-handshaking once when the portal rejects the current token.
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        if (token.isNullOrBlank()) {
            handshake()
        }
        try {
            return block()
        } catch (e: StalkerException) {
            // Token probably expired — get a fresh one and retry once.
            handshake()
            return block()
        }
    }

    // -------------------------------------------------------------- actions

    /** Returns the raw profile JSON object of the box. */
    suspend fun getProfile(): JSONObject = withSession {
        jsPayload(get("stb", "get_profile"))
    }

    /** Returns all TV channels, sorted by channel number. */
    suspend fun getAllChannels(): List<Channel> = withSession {
        val js = jsPayload(get("itv", "get_all_channels"))
        val data = js.optJSONArray("data") ?: return@withSession emptyList()
        val list = ArrayList<Channel>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            list.add(
                Channel(
                    id = o.optString("id"),
                    number = o.optString("number"),
                    name = o.optString("name"),
                    cmd = o.optString("cmd"),
                    logo = o.optString("logo"),
                    genreId = o.optString("tv_genre_id")
                )
            )
        }
        list.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    }

    /**
     * Resolves a channel [cmd] into a playable stream URL.
     * Strips the "ffmpeg "/"ffprobe " transport prefix when present.
     *
     * @param type "itv" for live TV, "vod" for video club items
     */
    suspend fun createLink(cmd: String, type: String = "itv"): String = withSession {
        val js = jsPayload(get(type, "create_link", mapOf("cmd" to cmd)))
        var link = js.optString("cmd", "").trim()
        if (link.isBlank()) {
            throw StalkerException("Portal returned no stream URL")
        }
        for (prefix in arrayOf("ffmpeg ", "ffprobe ")) {
            if (link.startsWith(prefix)) {
                link = link.removePrefix(prefix).trim()
                break
            }
        }
        link
    }

    // -------------------------------------------------------------- genres

    /** TV genre/category (e.g. "Sports", "Movies"). */
    data class Genre(val id: String, val title: String)

    /** Returns TV genres from the portal. Empty list when unsupported. */
    suspend fun getGenres(): List<Genre> = withSession {
        val js = jsPayload(get("itv", "get_genres"))
        val data = js.optJSONArray("data") ?: return@withSession emptyList()
        val list = ArrayList<Genre>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isNotBlank()) {
                list.add(Genre(id = o.optString("id"), title = title))
            }
        }
        list
    }

    // ----------------------------------------------------------------- EPG

    /** A single EPG program entry. Times are display strings from the portal. */
    data class EpgProgram(
        val name: String,
        val start: String,
        val end: String,
        val descr: String
    )

    /**
     * Returns EPG entries for a channel on [date] (format dd-MM-yyyy).
     * Best-effort: returns an empty list when the portal does not support it.
     */
    suspend fun getEpg(chId: String, date: String): List<EpgProgram> = withSession {
        val js = jsPayload(
            get("itv", "get_epg", mapOf("ch_id" to chId, "date" to date))
        )
        val data = js.optJSONArray("data") ?: return@withSession emptyList()
        val list = ArrayList<EpgProgram>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            list.add(
                EpgProgram(
                    name = name,
                    start = o.optString("start").ifBlank { o.optString("t_time") },
                    end = o.optString("end").ifBlank { o.optString("t_time_to") },
                    descr = o.optString("descr").ifBlank { o.optString("description") }
                )
            )
        }
        list
    }

    // ----------------------------------------------------------------- VOD

    /** A VOD category (movies, series, …). */
    data class VodCategory(val id: String, val title: String)

    /** A single VOD item (movie / episode). */
    data class VodItem(val id: String, val name: String, val cmd: String)

    /** Returns VOD categories. Empty list when unsupported. */
    suspend fun getVodCategories(): List<VodCategory> = withSession {
        val js = jsPayload(get("vod", "get_categories"))
        val data = js.optJSONArray("data") ?: return@withSession emptyList()
        val list = ArrayList<VodCategory>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isNotBlank()) {
                list.add(VodCategory(id = o.optString("id"), title = title))
            }
        }
        list
    }

    /** Returns VOD items inside a category. Empty list when unsupported. */
    suspend fun getVodList(categoryId: String): List<VodItem> = withSession {
        val js = jsPayload(
            get("vod", "get_ordered_list", mapOf("category" to categoryId, "p" to "1"))
        )
        val data = js.optJSONArray("data") ?: return@withSession emptyList()
        val list = ArrayList<VodItem>(data.length())
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val name = o.optString("name").trim()
            val cmd = o.optString("cmd").trim()
            if (name.isNotEmpty() && cmd.isNotEmpty()) {
                list.add(VodItem(id = o.optString("id"), name = name, cmd = cmd))
            }
        }
        list
    }

    // ------------------------------------------------------------ cookie jar

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        /** Pre-loads cookies (e.g. the STB fingerprint cookies) before any request. */
        fun seed(url: HttpUrl, cookies: List<Cookie>) {
            saveFromResponse(url, cookies)
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                store.removeAll { it.name == cookie.name }
            }
            store.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }
            return store.toList()
        }
    }
}
