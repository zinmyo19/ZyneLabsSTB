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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

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
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/90.0 Mobile Safari/537.36"
            )
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
     */
    suspend fun createLink(cmd: String): String = withSession {
        val js = jsPayload(get("itv", "create_link", mapOf("cmd" to cmd)))
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

    // ------------------------------------------------------------ cookie jar

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

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
