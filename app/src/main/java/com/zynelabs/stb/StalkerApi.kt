package com.zynelabs.stb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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
 *
 * v2.4 protocol shape (stock Ministra, Wireshark-verified):
 * - handshake: GET portal.php?type=stb&action=handshake&JsHttpRequest=1-xml
 *   (MAC travels in the mac cookie only, no token param)
 * - every later call: same URL shape + `Authorization: Bearer <token>` header.
 *   Never a token= or mac= query param — those break session association.
 */
class StalkerApi(
    portalUrl: String,
    private val mac: String,
    context: android.content.Context
) {

    open class StalkerException(message: String) : Exception(message)

    /**
     * Marker for auth/session failures that warrant a re-handshake + retry.
     * v2.5: the portal invalidates tokens (a handshake from elsewhere, or
     * OTT reconnecting, kills our token) and answers with HTTP 200 + empty
     * body — or a profile with id:null — instead of a 401.
     */
    private class AuthFailureException(message: String) : StalkerException(message)

    /** True when [e] looks like a dead/invalid session (not a network blip). */
    private fun isAuthFailure(e: StalkerException): Boolean {
        if (e is AuthFailureException) return true
        val msg = e.message.orEmpty()
        return msg.contains("Empty response from portal") ||
            msg.contains("Invalid JSON from portal") ||
            msg.contains("Portal error")
    }

    private val baseUrl: String = portalUrl.trim().trimEnd('/')

    /** Base portal URL (shown in the on-device Portal API Probe header). */
    val probePortalUrl: String get() = baseUrl

    /** Box MAC (shown in the on-device Portal API Probe header). */
    val probeBoxMac: String get() = mac

    /**
     * Device fingerprint status (shown in the on-device Probe header).
     * v2.3: device IDs are not sent — the panel may bind MAC+device IDs,
     * and our random IDs broke the match (OTT sends none / different ones).
     */
    val probeDeviceIds: String get() = "disabled"

    @Volatile
    private var token: String? = null

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
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

    /** The MAG-box fingerprint headers sent with every portal request. */
    private fun buildHeaders(): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                    "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
            )
            .add("X-User-Agent", "Model: MAG250; Link: Ethernet")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
        // v2.4: the session token travels as an Authorization: Bearer header
        // (stock Ministra behavior, Wireshark-verified) — never as a token=
        // query param. The MAC travels in the mac cookie only.
        token?.takeIf { it.isNotBlank() }?.let { b.add("Authorization", "Bearer $it") }
        return b.build()
    }

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
        // v2.4: NO token=/mac= query params. Token goes in the Authorization:
        // Bearer header (see buildHeaders()); MAC goes in the mac cookie.
        // Stock Ministra behavior — sending them as query params makes the
        // portal unable to associate requests with the session (id:null).
        // v2.3: no device IDs (sn/device_id/device_id2/signature).
        // v2.6: extra params (notably the channel `cmd` for create_link, which
        // is itself a URL) are sent UNENCODED. v2.2 proved this portal does
        // naive query parsing without URL-decoding — an encoded cmd like
        // "ffmpeg%20http%3A%2F%2F..." would never match server-side.
        for ((key, value) in extra) {
            builder.addEncodedQueryParameter(key, value)
        }
        return builder.build()
    }

    // v2.3: device ID params removed (see buildUrl()) — both helpers deleted.

    private suspend fun get(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(type, action, extra))
            .headers(buildHeaders())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw StalkerException("Portal HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw StalkerException("Empty response from portal (HTTP ${response.code})")
            }
            try {
                JSONObject(body)
            } catch (e: Exception) {
                val snippet = body.replace(Regex("\\s+"), " ").take(150)
                throw StalkerException("Invalid JSON from portal (HTTP ${response.code}): $snippet")
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

    /**
     * Returns the "js" payload as a JSONArray, tolerating both shapes:
     * {"js":[...]} (e.g. genres) and {"js":{"data":[...]}} (e.g. channels).
     * Null when the payload is absent or neither shape.
     */
    fun jsArray(response: JSONObject): JSONArray? {
        if (!response.has("js") || response.isNull("js")) return null
        response.optJSONArray("js")?.let { return it }
        val obj = response.optJSONObject("js") ?: return null
        return obj.optJSONArray("data")
    }

    /** Result of a raw [probe] call. Never throws — failures land in [error]. */
    data class ProbeResult(
        val httpCode: Int,
        val bodyLength: Int,
        val snippet: String,
        val error: String
    )

    /**
     * Raw probe: performs GET type/action with the standard headers
     * (Authorization: Bearer token + mac cookie), returning the HTTP status,
     * body length and a snippet of [snippetLen] chars (default 100).
     * Used by the on-device Portal API Probe to discover which actions a
     * portal actually implements. Never throws.
     */
    suspend fun probe(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
        snippetLen: Int = 100
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            if (token.isNullOrBlank()) handshake()
            val url = ("$baseUrl/portal.php").toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext ProbeResult(-1, 0, "", "Invalid portal URL")
            url.addQueryParameter("type", type)
            url.addQueryParameter("action", action)
            url.addQueryParameter("JsHttpRequest", "1-xml")
            // v2.4: no token=/mac= query params (Bearer header + mac cookie)
            // v2.3: no device IDs
            for ((k, v) in extra) {
                url.addQueryParameter(k, v)
            }
            val req = Request.Builder().url(url.build()).headers(buildHeaders()).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val snippet = body.replace(Regex("\\s+"), " ").take(snippetLen)
                ProbeResult(resp.code, body.length, snippet, "")
            }
        } catch (e: Exception) {
            ProbeResult(-1, 0, "", (e.message ?: e.javaClass.simpleName).take(80))
        }
    }

    // -------------------------------------------------------------- session

    /**
     * Raw probe of an arbitrary URL (plain GET, no API params, no token).
     * Used by the on-device Portal API Probe to fetch the portal index page
     * and identify what system the portal actually runs.
     * Returns Triple(httpCode, detail, error) where
     * detail = "<content-type> len=<n> <first 150 chars>". Never throws.
     */
    suspend fun probeRaw(url: String): Triple<Int, String, String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).headers(buildHeaders()).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val ct = resp.header("Content-Type", "?") ?: "?"
                    val snippet = body.replace(Regex("\\s+"), " ").take(150)
                    Triple(resp.code, "$ct len=${body.length} $snippet", "")
                }
            } catch (e: Exception) {
                Triple(-1, "", (e.message ?: e.javaClass.simpleName).take(80))
            }
        }

    /**
     * POST variant of [probe]: sends type/action/JsHttpRequest as a form body
     * instead of query params (token via Bearer header, MAC via cookie).
     * Some panels only accept POST for data actions. Never throws.
     */
    suspend fun probePost(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            if (token.isNullOrBlank()) handshake()
            val form = okhttp3.FormBody.Builder()
                .add("type", type)
                .add("action", action)
                .add("JsHttpRequest", "1-xml")
            // v2.4: no token/mac in body (Bearer header + mac cookie)
            // v2.3: no device IDs
            for ((k, v) in extra) form.add(k, v)
            val req = Request.Builder()
                .url("$baseUrl/portal.php")
                .headers(buildHeaders())
                .post(form.build())
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val snippet = body.replace(Regex("\\s+"), " ").take(100)
                ProbeResult(resp.code, body.length, snippet, "")
            }
        } catch (e: Exception) {
            ProbeResult(-1, 0, "", (e.message ?: e.javaClass.simpleName).take(80))
        }
    }

    /**
     * Performs the handshake and stores the session token.
     * @return the new token
     */
    suspend fun handshake(): String {
        token = null
        val js = jsPayload(get("stb", "handshake", mapOf("stb_type" to "MAG250")))
        val newToken = js.optString("token", "")
        if (newToken.isBlank()) {
            throw StalkerException("Handshake failed: no token issued")
        }
        token = newToken
        return newToken
    }

    /**
     * Runs [block], performing a handshake first when there is no token and
     * self-healing when the portal rejects the session.
     *
     * v2.5 self-healing: the portal invalidates tokens (a handshake from
     * elsewhere, or OTT reconnecting, kills our token) and answers with
     * HTTP 200 + empty body instead of 401. When an auth failure is
     * detected — empty body, portal error, or a profile with id:null — we
     * do ONE fresh handshake ([buildHeaders] picks up the new token for the
     * retry) and retry the call once. If the retry also fails, the ORIGINAL
     * error is thrown. Network-level (IOException) recovery is unchanged:
     * evict the pool, wait a beat, retry with the existing token first,
     * re-handshake only as a last resort.
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        if (token.isNullOrBlank()) {
            handshake()
        }
        try {
            return block()
        } catch (e: IOException) {
            // Transient network failure: retry on a fresh connection with the
            // existing token first. Re-handshake only as a last resort, since
            // the portal RSTs duplicate handshakes for one MAC.
            client.connectionPool.evictAll()
            delay(1000)
            try {
                return block()
            } catch (e2: StalkerException) {
                if (isAuthFailure(e2)) {
                    handshake()
                    try {
                        return block()
                    } catch (retryEx: Exception) {
                        throw e2 // original auth error, not the retry's
                    }
                }
                throw e2
            }
        } catch (e: StalkerException) {
            if (!isAuthFailure(e)) throw e
            val originalError = e
            handshake() // fresh token; buildHeaders() uses it for the retry
            try {
                return block()
            } catch (retryEx: Exception) {
                // Still failing after a FRESH handshake: if the profile
                // STILL has id:null, the MAC itself isn't registered
                // (not a dead token) — say so plainly.
                if (retryEx is AuthFailureException && originalError is AuthFailureException) {
                    throw StalkerException("MAC not registered on this portal")
                }
                throw originalError
            }
        }
    }

    // -------------------------------------------------------------- actions

    /** Returns the raw profile JSON object of the box. */
    suspend fun getProfile(): JSONObject = withSession {
        val profile = jsPayload(get("stb", "get_profile"))
        // v2.5: id:null means the portal doesn't associate this session with
        // a user — the token is dead OR the MAC isn't registered. Throw the
        // marker so withSession re-handshakes and retries once before
        // concluding the MAC is unregistered.
        if (!isMacRegistered(profile)) {
            throw AuthFailureException("Portal returned an empty profile (id:null)")
        }
        profile
    }

    /**
     * Returns true if the portal recognizes this MAC as a registered box.
     * An unregistered MAC gets a default profile with a null/blank "id".
     */
    fun isMacRegistered(profile: JSONObject): Boolean {
        // optString returns "" for JSON null
        return profile.optString("id").isNotBlank()
    }

    /** Returns all TV channels, sorted by channel number. */
    suspend fun getAllChannels(): List<Channel> = withSession {
        val data = jsArray(get("itv", "get_all_channels")) ?: return@withSession emptyList()
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

    /**
     * Probe helper (v2.6): resolves the first channel from itv/get_ordered_list
     * via [createLink] and returns a one-line summary. For the on-device
     * Portal API Probe — reveals the cmd format the portal returns
     * (http/https = ExoPlayer-playable; udp/rtmp = black screen explained).
     */
    suspend fun probeCreateLink(): String = withSession {
        val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
        val data = js.optJSONArray("data")
            ?: throw StalkerException("No channel data in get_ordered_list")
        if (data.length() == 0) throw StalkerException("Empty channel list")
        val first = data.getJSONObject(0)
        val id = first.optString("id", "?")
        val name = first.optString("name", "?")
        val cmd = first.optString("cmd", "")
        if (cmd.isBlank()) throw StalkerException("First channel (id=$id) has no cmd")
        val link = createLink(cmd, "itv")
        "ch[$id $name] cmd=${cmd.take(100)} => link=${link.take(200)}"
    }

    // -------------------------------------------------------------- genres

    /** TV genre/category (e.g. "Sports", "Movies"). */
    data class Genre(val id: String, val title: String)

    /** Returns TV genres from the portal. Empty list when unsupported. */
    suspend fun getGenres(): List<Genre> = withSession {
        val data = jsArray(get("itv", "get_genres")) ?: return@withSession emptyList()
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

/** One shared StalkerApi per portal/MAC for the whole process.
 *  The portal allows a single session per MAC and RSTs duplicate handshakes,
 *  so every screen MUST reuse this instead of constructing StalkerApi directly. */
object StalkerSession {
    @Volatile private var api: StalkerApi? = null
    @Volatile private var key: String? = null

    @Synchronized
    fun get(context: android.content.Context): StalkerApi {
        val url = Prefs.getPortalUrl(context)
        val mac = Prefs.getMac(context)
        val k = "$url|$mac"
        if (api == null || key != k) {
            key = k
            api = StalkerApi(url, mac, context)
        }
        return api!!
    }

    @Synchronized
    fun reset() {
        api = null
        key = null
    }
}
