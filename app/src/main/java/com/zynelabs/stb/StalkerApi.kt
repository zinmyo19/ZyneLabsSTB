package com.zynelabs.stb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Minimal Stalker Middleware API client (load.php).
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
 * v3.8 protocol shape (IPTV Stalker Player v1.43, string-verified):
 * Working native apps use load.php — NOT portal.php. portal.php is the
 * MAG-box web-UI endpoint with strict client validation ("Your STB is not
 * supported"); load.php is the native-app API endpoint.
 * - handshake: GET load.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml
 *   (MAC travels in the mac cookie; empty token= param included)
 *   v3.5's JsHttpRequest removal applied to portal.php — for load.php the
 *   working app SENDS JsHttpRequest=1-xml, so we restore it here.
 * - every later call: same URL shape + `Authorization: Bearer <token>` header.
 *   Never a token= or mac= query param — those break session association.
 *
 * v3.1 auth-method fallback: some panels (older variants, e.g. ultvtivon)
 * expect the session token as a `token=` query parameter instead of the
 * Bearer header. If get_profile returns id:null after fresh Bearer
 * handshakes, we fall back to token-as-query-param, probe get_profile, and
 * keep whichever method yields a valid profile id (cached per portal URL in
 * [authMethod], reset with StalkerSession.reset()).
 *
 * v3.2 auth-method ladder: ultvtivon.site handshakes OK but returns
 * HTTP 200 len=0 for EVERY call with both Bearer and token= — it needs a
 * different wire format. We now try, in order: Bearer header → token=
 * query param → token= cookie → MAC-only (no token anywhere, session is
 * cookie-bound). The first method yielding a valid profile id is cached.
 * "MAC not registered" fires only when all four fail. handshake() logs
 * its raw response (first 500 chars) for token-shape diagnostics.
 *
 * v3.6: Copy Debug Info now also captures the FIRST get_profile request
 * in full (URL, redacted headers, HTTP code, body) — v3.5 proved the
 * handshake succeeds and headers are clean, but get_profile still
 * returns empty for all four auth methods; the wire details of that
 * first (Bearer) attempt are what we need to diagnose next.
 *
 * v3.7: stale-token hardening. v3.6 diagnostics LOOKED like get_profile
 * was using a stale token (handshake token != profile URL token), but
 * that was a debug artifact — the capture fired once per instance while
 * the auth ladder handshakes once per method. handshake() now resets
 * the profile capture so Copy Debug Info always reflects the CURRENT
 * session, and get() logs "TOKEN MISMATCH!" if the wire token ever
 * differs from the most recent handshake token (real race detection).
 *
 * v3.9: API base = {host}/server/load.php. Dominic's packet capture of
 * OTT Navigator proved the API lives at http://bingeiptv.xyz/server/
 * load.php while the portal URL entered is http://bingeiptv.xyz:80/c/ —
 * the /c/ is the CLIENT path, not the API path (v3.8's {portal}/load.php
 * 404'd). handshake() tries {host}/server/load.php first, then
 * {portal}/load.php, then {portal}/portal.php, keeping the working
 * endpoint in apiBase for all subsequent calls.
 *
 * v4.0: packet-capture-verified MAG format. Dominic's HTTP Sniffer
 * capture of OTT Navigator's actual request headers PROVED it sends a
 * full MAG200 fingerprint (NOT an OTT UA):
 *   User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3
 *     (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3
 *   X-User-Agent: Model: MAG250; Link: WiFi
 *   Referer: http://mag.tiger-ott.net:80/c/
 *   Cookie: mac=...; stb_lang=en; timezone=GMT
 *   Authorization: Bearer <token>
 * v3.3–v3.5's "clean native app" changes were all WRONG (OTT UA, removed
 * X-User-Agent, removed stb_lang/timezone, removed Referer). Reverted to
 * the exact wire format OTT uses. Kept: /server/load.php (v3.9), Bearer
 * auth (v2.4), JsHttpRequest=1-xml (v3.8) — all capture-confirmed.
 *
 * v4.1: skip stb/get_profile entirely — OTT's packet-captured flow goes
 * handshake → itv/get_ordered_list DIRECTLY (no get_profile call was ever
 * captured on bingeiptv.xyz or wafasiad.com). bingeiptv.xyz returns EMPTY
 * for get_profile even with the v4.0 MAG format, while get_ordered_list
 * works. The auth ladder ([tryAuthMethod]) and session validation
 * ([getProfile]) now probe itv/get_ordered_list page 1 for real channel
 * data instead of stb/get_profile for a profile id. "MAC not registered"
 * fires only when all four auth methods fail to return channel data.
 */
class StalkerApi(
    portalUrl: String,
    private val mac: String,
    context: android.content.Context
) {

    /**
     * v2.5: [isAuthFailure] marks auth/session failures that warrant a
     * re-handshake + retry. The portal invalidates tokens (a handshake from
     * elsewhere, or OTT reconnecting, kills our token) and answers with
     * HTTP 200 + empty body — or a profile with id:null — instead of a 401.
     * (v2.6 fix: was a private subclass of StalkerException, which the
     * Kotlin compiler rejected as "cannot inherit from final type".)
     */
    open class StalkerException(
        message: String,
        val isAuthFailure: Boolean = false
    ) : Exception(message)

    /** True when [e] looks like a dead/invalid session (not a network blip). */
    private fun isAuthFailure(e: StalkerException): Boolean {
        if (e.isAuthFailure) return true
        val msg = e.message.orEmpty()
        return msg.contains("Empty response from portal") ||
            msg.contains("Invalid JSON from portal") ||
            msg.contains("Portal error")
    }

    private val baseUrl: String = portalUrl.trim().trimEnd('/')

    /**
     * v3.9: host root extracted from the portal URL (scheme + host + port,
     * no path). Dominic's packet capture proved OTT Navigator hits
     * http://bingeiptv.xyz/server/load.php while the portal URL entered
     * is http://bingeiptv.xyz:80/c/ — the /c/ is the CLIENT path, not the
     * API path. The native-app API lives at {host}/server/load.php.
     */
    private val hostBase: String = run {
        val u = ("$baseUrl/").toHttpUrlOrNull()
        if (u != null) {
            val portPart =
                if (u.port != HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
            "${u.scheme}://${u.host}$portPart"
        } else baseUrl
    }

    /**
     * v3.9: candidate API endpoints, tried in order by [handshake].
     * 1. {host}/server/load.php — OTT-verified (packet capture)
     * 2. {portal}/load.php — v3.8 behavior (some panels serve it there)
     * 3. {portal}/portal.php — v3.7 behavior (last resort)
     */
    private fun apiBaseCandidates(): List<String> = listOf(
        "$hostBase/server/load.php",
        "$baseUrl/load.php",
        "$baseUrl/portal.php"
    )

    /** v3.9: the currently active API endpoint (set by [handshake]). */
    @Volatile
    private var apiBase: String = "$hostBase/server/load.php"

    /** Base portal URL (shown in the on-device Portal API Probe header). */
    val probePortalUrl: String get() = baseUrl

    /** v3.9: active API base (shown in Copy Debug Info). */
    val probeApiBase: String get() = apiBase

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

    /**
     * v3.7: the token issued by the most recent successful [handshake].
     * The freshness anchor for the TOKEN MISMATCH assertion in [get]:
     * every API call must go out with exactly this token. A mismatch
     * means a concurrent handshake() invalidated the token between our
     * handshake and this request (one-session-per-MAC portals kill the
     * old token the moment a new handshake lands).
     */
    @Volatile
    private var lastHandshakeToken: String? = null

    /**
     * v3.1: which wire format carries the session token. BEARER (v2.4,
     * stock Ministra) sends `Authorization: Bearer <token>`; TOKEN_PARAM
     * (older panel variants) sends `token=<token>` as a query parameter.
     * The fallback is chosen once per portal (see [withSession]); the
     * working method is kept on this instance, and StalkerSession drops
     * the instance (resetting to BEARER) whenever the portal URL/MAC
     * changes or [StalkerSession.reset] is called.
     *
     * v3.2: two more methods. TOKEN_COOKIE sends the token as a `token=`
     * cookie (some panels are cookie-session based). MAC_ONLY sends NO
     * token anywhere — just the MAC cookie + standard headers (panels
     * where the token is informational and the session is cookie-bound).
     * Tried in order: BEARER → TOKEN_PARAM → TOKEN_COOKIE → MAC_ONLY.
     */
    private enum class AuthMethod { BEARER, TOKEN_PARAM, TOKEN_COOKIE, MAC_ONLY }

    @Volatile
    private var authMethod: AuthMethod = AuthMethod.BEARER

    /**
     * v3.4: on-device diagnostics for the Copy Debug Info button
     * (Settings). Redacted token — safe to paste in chat.
     */
    @Volatile var debugHandshakeUrl: String? = null
        private set
    @Volatile var debugHandshakeHeaders: String? = null
        private set
    @Volatile var debugHandshakeResponse: String? = null
        private set

    /**
     * v3.6: get_profile request diagnostics (FIRST attempt only — the
     * Bearer attempt, the standard wire format). Captured in [get] before
     * any success/empty-body check so we see the HTTP code and body even
     * when the portal returns 200-with-empty (the "MAC not registered"
     * false-positive shape). Redacted token — safe to paste in chat.
     *
     * v4.1: captures the first itv/get_ordered_list instead — OTT's flow
     * skips stb/get_profile entirely, and our auth validation now uses
     * the channel list (see [tryAuthMethod]/[getProfile]).
     */
    @Volatile var debugProfileUrl: String? = null
        private set
    @Volatile var debugProfileHeaders: String? = null
        private set
    @Volatile var debugProfileHttpCode: Int? = null
        private set
    @Volatile var debugProfileBody: String? = null
        private set

    /** v3.4: one-line redacted header dump for Copy Debug Info. */
    private fun debugHeaderDump(headers: okhttp3.Headers = buildHeaders()): String {
        val sb = StringBuilder()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            var value = headers.value(i)
            if (name.equals("Authorization", ignoreCase = true)) {
                value = "Bearer <redacted>"
            }
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append("$name: $value")
        }
        // cookies actually sent
        val url = ("$baseUrl/").toHttpUrlOrNull()
        if (url != null) {
            val cookies = cookieJar.loadForRequest(url)
                .joinToString("; ") { "${it.name}=${it.value.take(12)}${if (it.value.length > 12) "…" else ""}" }
            if (cookies.isNotEmpty()) sb.append("; Cookie: $cookies")
        }
        return sb.toString()
    }

    /** v3.4: assembles the Copy Debug Info text (called from Settings). */
    fun buildDebugInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("Portal: $baseUrl")
        sb.appendLine("API base: $apiBase")
        sb.appendLine("MAC: $mac")
        sb.appendLine("Auth method: $authMethod")
        sb.appendLine()
        sb.appendLine("Handshake URL:")
        sb.appendLine(debugHandshakeUrl ?: "(none yet)")
        sb.appendLine()
        sb.appendLine("Handshake headers:")
        sb.appendLine(debugHandshakeHeaders ?: "(none yet)")
        sb.appendLine()
        sb.appendLine("Handshake response (1000 chars):")
        sb.appendLine(debugHandshakeResponse ?: "(none yet)")
        sb.appendLine()
        sb.appendLine("Channel Validation (first itv/get_ordered_list attempt):")
        sb.appendLine("URL: ${debugProfileUrl ?: "(none yet)"}")
        sb.appendLine("Headers: ${debugProfileHeaders ?: "(none yet)"}")
        sb.appendLine("HTTP code: ${debugProfileHttpCode?.toString() ?: "(none yet)"}")
        sb.appendLine("Response (500 chars): ${debugProfileBody ?: "(none yet)"}")
        return sb.toString()
    }

    /**
     * v2.7: serializes handshakes. The portal enforces one session per MAC —
     * every new handshake invalidates the previous token. Without this lock,
     * concurrent auth-failure recoveries (multiple activities/threads) each
     * handshake, invalidating each other's tokens in a livelock. All
     * handshake() call sites in the recovery paths go through
     * [handshakeIfStale], which skips the handshake when another thread
     * already refreshed the token.
     */
    private val handshakeMutex = Mutex()

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        // v4.0: packet-capture-verified MAG format. Dominic's HTTP Sniffer
        // capture of OTT Navigator PROVED it sends:
        //   Cookie: mac=...; stb_lang=en; timezone=GMT
        // (v3.4's removal of stb_lang/timezone was WRONG — OTT sends them.)
        val portalHttp = ("$baseUrl/").toHttpUrlOrNull()
        if (portalHttp != null) {
            cookieJar.seed(
                portalHttp,
                listOf(
                    Cookie.Builder().name("mac").value(mac)
                        .domain(portalHttp.host).path("/").build(),
                    Cookie.Builder().name("stb_lang").value("en")
                        .domain(portalHttp.host).path("/").build(),
                    Cookie.Builder().name("timezone").value("GMT")
                        .domain(portalHttp.host).path("/").build()
                )
            )
        }
    }

    // ------------------------------------------------------------------ HTTP

    /** The client fingerprint headers sent with every portal request.
     * v4.0: packet-capture-verified MAG format. Dominic's HTTP Sniffer
     * capture of OTT Navigator PROVED it sends a full MAG200 fingerprint:
     *   User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3
     *     (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3
     *   X-User-Agent: Model: MAG250; Link: WiFi
     *   Referer: http://mag.tiger-ott.net:80/c/
     *   Cookie: mac=...; stb_lang=en; timezone=GMT
     *   Authorization: Bearer <token>
     * v3.3–v3.5's "clean native app" changes were WRONG — OTT Navigator
     * PRETENDS TO BE A MAG BOX for API calls; it never uses an OTT UA.
     * Reverted: MAG200 UA (v1.3), X-User-Agent (v1.3), Referer (v3.5),
     * stb_lang/timezone cookies (v3.4). Kept: Bearer auth (v2.4),
     * /server/load.php API base (v3.9), JsHttpRequest=1-xml (v3.8) —
     * all confirmed by the capture. */
    private fun buildHeaders(): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                    "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
            )
            // v4.0: restored (packet-capture verified — OTT sends this).
            // v1.3 added it; v3.3 wrongly removed it.
            .add("X-User-Agent", "Model: MAG250; Link: WiFi")
            // v4.0: restored (packet-capture verified — OTT sends Referer:
            // http://mag.tiger-ott.net:80/c/). v3.5 wrongly removed it.
            .add("Referer", "$baseUrl/")
        // v2.4: the session token travels as an Authorization: Bearer header
        // (stock Ministra behavior, Wireshark-verified) — never as a token=
        // query param. The MAC travels in the mac cookie only.
        // v3.1: TOKEN_PARAM portals get the token as a query param instead
        // (see buildUrl) — no Authorization header for those.
        // v3.2: TOKEN_COOKIE portals get the token as a `token=` cookie
        // (synced into the cookie jar by syncTokenCookie()) — no
        // Authorization header. MAC_ONLY portals get NO token anywhere;
        // the session is cookie-bound (MAC + handshake session cookies).
        if (authMethod == AuthMethod.BEARER) {
            token?.takeIf { it.isNotBlank() }?.let { b.add("Authorization", "Bearer $it") }
        }
        return b.build()
    }

    private fun buildUrl(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): HttpUrl {
        // v3.9: apiBase — {host}/server/load.php (OTT-verified), with
        // fallback to {portal}/load.php then {portal}/portal.php (see
        // handshake(), which sets apiBase to the working endpoint).
        val builder = apiBase.toHttpUrlOrNull()?.newBuilder()
            ?: throw StalkerException("Invalid portal URL")
        builder.addQueryParameter("type", type)
        builder.addQueryParameter("action", action)
        // v3.8: load.php (NOT portal.php) — the native-app API endpoint.
        // IPTV Stalker Player v1.43 uses load.php; portal.php is the MAG
        // web-UI endpoint that rejects non-MAG clients ("Your STB is not
        // supported"). The working app SENDS JsHttpRequest=1-xml with
        // load.php, so we include it (v3.5's removal was portal.php-specific).
        // v3.8: JsHttpRequest=1-xml — the working native app (IPTV Stalker
        // v1.43) sends this with load.php on every request.
        builder.addQueryParameter("JsHttpRequest", "1-xml")
        // v2.4: NO token=/mac= query params. Token goes in the Authorization:
        // Bearer header (see buildHeaders()); MAC goes in the mac cookie.
        // Stock Ministra behavior — sending them as query params makes the
        // portal unable to associate requests with the session (id:null).
        // v3.1: TOKEN_PARAM portals are the exception — they get
        // token=<token> as a query param (see below); buildHeaders() then
        // omits the Authorization header.
        // v2.3: no device IDs (sn/device_id/device_id2/signature).
        // v2.6: extra params (notably the channel `cmd` for create_link, which
        // is itself a URL) are sent UNENCODED. v2.2 proved this portal does
        // naive query parsing without URL-decoding — an encoded cmd like
        // "ffmpeg%20http%3A%2F%2F..." would never match server-side.
        for ((key, value) in extra) {
            builder.addEncodedQueryParameter(key, value)
        }
        // v3.1: token-as-query-param auth (older panel variants).
        // The handshake runs with a blank token, so it never carries one.
        if (authMethod == AuthMethod.TOKEN_PARAM) {
            token?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("token", it) }
        }
        return builder.build()
    }

    // v2.3: device ID params removed (see buildUrl()) — both helpers deleted.

    private suspend fun get(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = buildUrl(type, action, extra)
        val headers = buildHeaders()
        // v3.7: stale-token assertion. The token on the wire must be the
        // one from the most recent handshake() — a mismatch means a
        // concurrent handshake() (another coroutine's auth-failure
        // recovery) invalidated our token between our handshake and this
        // request. One-session-per-MAC portals answer the dead token with
        // HTTP 200 + empty body, which withSession then heals via retry.
        val wireToken = token
        val freshToken = lastHandshakeToken
        if (wireToken != freshToken) {
            android.util.Log.w(
                "StalkerApi",
                "TOKEN MISMATCH! wire=${wireToken?.take(8)}... " +
                    "lastHandshake=${freshToken?.take(8)}... " +
                    "action=$action authMethod=$authMethod"
            )
        }
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()
            // v4.1: capture the first itv/get_ordered_list AFTER each
            // handshake() (the session-validation call — see [getProfile]
            // and [tryAuthMethod]). OTT's packet-captured flow skips
            // stb/get_profile entirely, so get_profile diagnostics are
            // no longer useful. handshake() resets these fields, so Copy
            // Debug Info always shows the CURRENT session's attempt.
            // (v3.6–v3.7 captured get_profile; v4.1 supersedes that.)
            if (type == "itv" && action == "get_ordered_list" && debugProfileUrl == null) {
                debugProfileUrl = url.toString()
                debugProfileHeaders = debugHeaderDump(headers)
                debugProfileHttpCode = code
                debugProfileBody = if (body.isBlank()) "(empty)"
                else body.replace(Regex("\\s+"), " ").take(500)
            }
            if (!response.isSuccessful) {
                throw StalkerException("Portal HTTP $code")
            }
            if (body.isBlank()) {
                throw StalkerException("Empty response from portal (HTTP $code)")
            }
            try {
                JSONObject(body)
            } catch (e: Exception) {
                val snippet = body.replace(Regex("\\s+"), " ").take(150)
                throw StalkerException("Invalid JSON from portal (HTTP $code): $snippet")
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
            // v2.7: serialize — the probe must not invalidate the app's
            // session token with a concurrent handshake.
            if (token.isNullOrBlank()) {
                handshakeMutex.withLock {
                    if (token.isNullOrBlank()) handshake()
                }
            }
            val url = apiBase.toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext ProbeResult(-1, 0, "", "Invalid portal URL")
            url.addQueryParameter("type", type)
            url.addQueryParameter("action", action)
            // v3.8: load.php + JsHttpRequest=1-xml (see buildUrl)
            url.addQueryParameter("JsHttpRequest", "1-xml")
            // v2.4: no token=/mac= query params (Bearer header + mac cookie)
            // v3.1: ...except on TOKEN_PARAM portals, which get token=.
            // v2.3: no device IDs
            for ((k, v) in extra) {
                url.addQueryParameter(k, v)
            }
            if (authMethod == AuthMethod.TOKEN_PARAM) {
                token?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("token", it) }
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
     * POST variant of [probe]: sends type/action as a form body
     * instead of query params (token via Bearer header, MAC via cookie).
     * Some panels only accept POST for data actions. Never throws.
     */
    suspend fun probePost(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            // v2.7: serialize (see probe()).
            if (token.isNullOrBlank()) {
                handshakeMutex.withLock {
                    if (token.isNullOrBlank()) handshake()
                }
            }
            val form = okhttp3.FormBody.Builder()
                .add("type", type)
                .add("action", action)
            // v3.8: load.php + JsHttpRequest=1-xml (see buildUrl)
            form.add("JsHttpRequest", "1-xml")
            // v2.4: no token/mac in body (Bearer header + mac cookie)
            // v3.1: ...except on TOKEN_PARAM portals, which get token=.
            // v2.3: no device IDs
            for ((k, v) in extra) form.add(k, v)
            if (authMethod == AuthMethod.TOKEN_PARAM) {
                token?.takeIf { it.isNotBlank() }?.let { form.add("token", it) }
            }
            val req = Request.Builder()
                .url(apiBase)
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
     * v3.2: logs the raw handshake response (first 500 chars) to logcat
     * so we can verify token parsing on panels with non-standard shapes.
     * @return the new token
     */
    suspend fun handshake(): String {
        token = null
        // v4.1: reset the per-session validation capture (first
        // itv/get_ordered_list — see [get]). The StalkerSession singleton
        // reuses this instance across connects, and the auth ladder
        // handshakes once per method — without this reset, Copy Debug Info
        // would show a previous session/ladder step's validation URL next
        // to the current handshake's token: misleading diagnostics.
        // (v3.7 reset the get_profile capture; v4.1 validates via the
        // channel list instead.)
        debugProfileUrl = null
        debugProfileHeaders = null
        debugProfileHttpCode = null
        debugProfileBody = null
        syncTokenCookie() // drop any stale token cookie before handshaking
        // v3.4: NO stb_type param — clean OTT-like request (was MAG250).
        // v3.8: handshake includes empty token= param, matching the working
        // native app: load.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml
        // v3.9: try the API bases in order — {host}/server/load.php
        // (OTT-verified via packet capture), then {portal}/load.php, then
        // {portal}/portal.php. A 404/empty response means the wrong path,
        // so we fall through; anything else (auth errors, bad JSON)
        // rethrows immediately. apiBase keeps the working endpoint for all
        // subsequent calls.
        var lastError: StalkerException? = null
        for (candidate in apiBaseCandidates()) {
            apiBase = candidate
            try {
                debugHandshakeUrl =
                    buildUrl("stb", "handshake", mapOf("token" to "")).toString()
                debugHandshakeHeaders = debugHeaderDump()
                val raw = getRaw("stb", "handshake", mapOf("token" to ""))
                debugHandshakeResponse = raw.replace(Regex("\\s+"), " ").take(1000)
                android.util.Log.i(
                    "StalkerApi",
                    "handshake: OK via $candidate, " +
                        "raw=${raw.replace(Regex("\\s+"), " ").take(500)}"
                )
                val response = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    throw StalkerException("Invalid JSON from portal (handshake)")
                }
                val js = jsPayload(response)
                val newToken = js.optString("token", "")
                if (newToken.isBlank()) {
                    throw StalkerException("Handshake failed: no token issued")
                }
                token = newToken
                // v3.7: freshness anchor — set IMMEDIATELY after parsing,
                // before any other API call can run. get() asserts the wire
                // token matches this (TOKEN MISMATCH log otherwise).
                lastHandshakeToken = newToken
                syncTokenCookie() // TOKEN_COOKIE: publish the fresh token
                return newToken
            } catch (e: StalkerException) {
                val msg = e.message.orEmpty()
                val wrongPath = msg.contains("Portal HTTP 404") ||
                    msg.contains("Empty response from portal")
                if (wrongPath) {
                    android.util.Log.w(
                        "StalkerApi",
                        "handshake: $candidate failed (${msg.take(60)}), trying next base"
                    )
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: StalkerException("Handshake failed on all API bases")
    }

    /**
     * v3.2: raw GET returning the body string (for handshake logging).
     * Same request shape as [get] but without JSON parsing.
     */
    private suspend fun getRaw(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
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
            body
        }
    }

    /**
     * v2.7: performs [handshake] only if the token is still [staleToken]
     * (or blank). Callers capture the token that just failed; if another
     * thread refreshed it while we waited for [handshakeMutex], we skip the
     * handshake and retry with the fresh token instead of invalidating it.
     *
     * v3.0: pass [force]=true to handshake unconditionally. The portal
     * allows one session per MAC, so a token that looks current locally may
     * already be dead server-side (e.g. the on-device Probe handshook in
     * another flow) — skipping the handshake would retry with a dead token.
     *
     * @return true if a handshake was performed.
     */
    private suspend fun handshakeIfStale(
        staleToken: String?,
        force: Boolean = false
    ): Boolean {
        return handshakeMutex.withLock {
            if (force || token == staleToken || token.isNullOrBlank()) {
                handshake()
                true
            } else {
                false
            }
        }
    }

    /** v3.0: exponential backoff between self-heal retries (1s, 2s, 4s). */
    private fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 1000L
        2 -> 2000L
        else -> 4000L
    }

    /**
     * v3.1: tries the token-as-query-param auth method. Some panels (older
     * variants, e.g. ultvtivon.site) expect `token=<token>` in the query
     * string instead of the v2.4 `Authorization: Bearer` header — with the
     * header they return id:null, which used to be misread as "MAC not
     * registered" (false positive: OTT works with the same MAC).
     *
     * v3.2: generalized to try any [AuthMethod]. Switches [authMethod],
     * does a clean handshake (no token on the handshake request itself —
     * syncTokenCookie() drops the stale cookie first), then probes
     * stb/get_profile with the new method.
     *
     * v4.1: validation now uses itv/get_ordered_list page 1 instead of
     * stb/get_profile. Dominic's packet capture proved OTT Navigator NEVER
     * calls stb/get_profile — its flow is handshake → get_ordered_list
     * directly. bingeiptv.xyz returns EMPTY for get_profile even with the
     * v4.0 MAG format, while get_ordered_list works. Real channel data
     * ({"js":{"data":[... with >= 1 item) is the proof the session works.
     *
     * @return true when the probe yields channel data — the caller keeps
     *         the method; false leaves [authMethod] for the caller to
     *         restore.
     */
    private suspend fun tryAuthMethod(method: AuthMethod): Boolean {
        return try {
            authMethod = method
            handshakeMutex.withLock {
                token = null // clean handshake — no stale token on the wire
                handshake() // syncTokenCookie() runs inside handshake()
            }
            val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
            val data = js.optJSONArray("data")
            val ok = data != null && data.length() > 0
            android.util.Log.i(
                "StalkerApi",
                "tryAuthMethod($method): channel data valid = $ok " +
                    "(items=${data?.length() ?: 0})"
            )
            ok
        } catch (e: Exception) {
            android.util.Log.w(
                "StalkerApi",
                "tryAuthMethod($method) failed: ${(e.message ?: e.javaClass.simpleName).take(60)}"
            )
            false
        }
    }

    /**
     * Runs [block], performing a handshake first when there is no token and
     * self-healing when the portal rejects the session.
     *
     * v2.5 self-healing: the portal invalidates tokens (a handshake from
     * elsewhere, or OTT reconnecting, kills our token) and answers with
     * HTTP 200 + empty body instead of 401. When an auth failure is
     * detected — empty body, portal error, or a profile with id:null — we
     * re-handshake ([buildHeaders] picks up the new token for the retry)
     * and retry the call.
     *
     * v3.0 aggressive self-healing: auth failures are retried up to 3 times
     * with exponential backoff (1s/2s/4s), and every retry FORCES a fresh
     * handshake — a token that looks current locally may already be dead
     * server-side (the on-device Probe handshook in another flow, killing
     * the app's token under the portal's one-session-per-MAC rule). The
     * v2.7 handshake mutex keeps concurrent handshakes serialized. Retries
     * are logged to logcat. If the call still fails with an auth failure
     * after fresh handshakes, the MAC is reported as not registered.
     *
     * v3.2: auth-method ladder. If fresh handshakes with the current method
     * keep failing, the app walks BEARER → TOKEN_PARAM → TOKEN_COOKIE →
     * MAC_ONLY (see [tryAuthMethod]), keeping the first method that yields
     * valid channel data (v4.1: itv/get_ordered_list page 1 — OTT's flow
     * skips stb/get_profile entirely). Only when ALL four methods fail is
     * the MAC reported as not registered.
     *
     * Network-level (IOException) recovery: evict the pool, back off, retry
     * with the existing token; a subsequent auth failure on retry flows
     * into the forced-handshake path above.
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        if (token.isNullOrBlank()) {
            // v2.7: serialize even the first handshake — several activities
            // starting at once must not handshake concurrently.
            handshakeMutex.withLock {
                if (token.isNullOrBlank()) handshake()
            }
        }
        // v3.0: up to 3 self-heal retries (not just 1).
        val maxRetries = 3
        var attempt = 0
        var firstWasAuthFailure = false
        // v3.2: index into the auth-method ladder. We start with the
        // cached/current method and walk forward on exhaustion.
        var methodIndex = AuthMethod.values().indexOf(authMethod).coerceAtLeast(0)
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                // Transient network failure: retry on a fresh connection with
                // the existing token. Re-handshakes happen only if the retry
                // then reports an auth failure (see below).
                attempt++
                if (attempt > maxRetries) throw e
                android.util.Log.w(
                    "StalkerApi",
                    "withSession: network error (attempt $attempt/$maxRetries), " +
                        "backing off: ${(e.message ?: e.javaClass.simpleName).take(60)}"
                )
                client.connectionPool.evictAll()
                delay(backoffMs(attempt))
            } catch (e: StalkerException) {
                if (!isAuthFailure(e)) throw e
                if (attempt == 0) firstWasAuthFailure = true
                attempt++
                if (attempt > maxRetries) {
                    // v3.2: walk the auth-method ladder. The current method's
                    // fresh handshakes didn't help — try the next wire
                    // format (Bearer → token= param → token cookie →
                    // MAC-only) before concluding the MAC is unregistered.
                    // Each tryAuthMethod does its own clean handshake +
                    // channel-list probe (v4.1: itv/get_ordered_list page 1
                    // — OTT's flow skips stb/get_profile entirely); we keep
                    // the first method that yields real channel data.
                    // v3.7: ladder order verified — AuthMethod enum ordinal
                    // is BEARER(0) → TOKEN_PARAM(1) → TOKEN_COOKIE(2) →
                    // MAC_ONLY(3), and methodIndex starts at the current
                    // method (BEARER on a fresh instance), so the Bearer
                    // header is always tried FIRST. The v3.6 "first
                    // attempt showed token=" confusion was the stale
                    // per-instance capture (fixed: handshake() now resets
                    // the capture), not a ladder-order bug.
                    val methods = AuthMethod.values()
                    android.util.Log.w(
                        "StalkerApi",
                        "withSession: ladder starting from ${methods[methodIndex]}, " +
                            "order=${methods.joinToString("→")}"
                    )
                    var foundWorking = false
                    while (methodIndex < methods.size - 1 && !foundWorking) {
                        methodIndex++
                        val next = methods[methodIndex]
                        android.util.Log.w(
                            "StalkerApi",
                            "withSession: ${methods[methodIndex - 1]} exhausted, " +
                                "trying $next"
                        )
                        if (tryAuthMethod(next)) {
                            authMethod = next
                            foundWorking = true
                        }
                    }
                    if (foundWorking) {
                        // New method works — retry the call with it.
                        attempt = 0
                        firstWasAuthFailure = false
                        // Restart the ladder from the working method for
                        // any future exhaustion in this session.
                        methodIndex = AuthMethod.values().indexOf(authMethod)
                        continue
                    }
                    // ALL auth methods failed after fresh handshakes: the
                    // token isn't the problem — the MAC itself isn't
                    // registered. (Preserves the v2.5/v2.7 "MAC not
                    // registered" detection, now a true last resort.)
                    if (firstWasAuthFailure) {
                        throw StalkerException("MAC not registered on this portal")
                    }
                    throw e
                }
                android.util.Log.w(
                    "StalkerApi",
                    "withSession: auth failure (attempt $attempt/$maxRetries), " +
                        "forcing fresh handshake: ${(e.message ?: "?").take(60)}"
                )
                // v3.0: FORCE — the stored token may be dead server-side
                // even though it looks current locally (probe/app session
                // conflict). The mutex keeps concurrent handshakes
                // serialized (v2.7).
                handshakeIfStale(null, force = true)
                delay(backoffMs(attempt))
            }
        }
    }

    // -------------------------------------------------------------- actions

    /**
     * Returns the raw profile JSON object of the box.
     *
     * v4.1: validates via itv/get_ordered_list page 1 instead of
     * stb/get_profile. Dominic's packet capture proved OTT Navigator NEVER
     * calls stb/get_profile — its flow is handshake → get_ordered_list
     * directly. bingeiptv.xyz returns EMPTY for get_profile even with the
     * v4.0 packet-capture-verified MAG format, while get_ordered_list
     * works. Real channel data ({"js":{"data":[... with >= 1 item) is the
     * proof the session works; a synthetic profile (id = the MAC) is
     * returned so [isMacRegistered] keeps working for callers.
     *
     * Graceful degradation: when the portal's get_profile is broken but
     * channels load, the session is valid — profile/account detail screens
     * show MAC + portal instead of crashing.
     */
    suspend fun getProfile(): JSONObject = withSession {
        val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
        val data = js.optJSONArray("data")
        // v2.5: no channel data means the portal doesn't associate this
        // session with a user — the token is dead OR the MAC isn't
        // registered. Throw the marker so withSession re-handshakes and
        // retries (then walks the auth-method ladder) before concluding
        // the MAC is unregistered.
        if (data == null || data.length() == 0) {
            throw StalkerException(
                "Portal returned no channel data",
                isAuthFailure = true
            )
        }
        android.util.Log.i(
            "StalkerApi",
            "getProfile: session valid, channel page 1 has ${data.length()} items"
        )
        // Synthetic profile — the portal's stb/get_profile is unreliable
        // (returns empty on some panels), but channel data proves the MAC
        // is registered. Callers (MainActivity connect flow) check
        // isMacRegistered(), which passes on this non-blank id.
        JSONObject().put("id", mac).put("mac", mac)
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
     * v2.8: Paginated channel loading via itv/get_ordered_list (OTT-style).
     * get_all_channels returns a single ~25MB JSON (20k+ channels) —
     * unreliable over mobile networks; the portal may truncate or kill huge
     * responses, surfacing as "Empty response from portal (HTTP 200)".
     * This fetches small pages (~16KB, 14 items) instead, each through
     * [withSession] (Bearer header + self-healing + handshake lock).
     *
     * @param genreId TV genre ID to filter by (sent as genre param; also
     *   applied client-side as a safety net), or null for all channels.
     * @param maxPages safety cap on pages to fetch (14 items/page).
     * @return accumulated channels, sorted by channel number.
     */
    suspend fun getChannelsPaginated(
        genreId: String? = null,
        maxPages: Int = 20
    ): List<Channel> {
        val all = ArrayList<Channel>()
        var page = 1
        var lastPage = false
        while (page <= maxPages && !lastPage) {
            val extra = mutableMapOf("p" to page.toString())
            if (!genreId.isNullOrBlank()) extra["genre"] = genreId
            val (channels, isLast) = withSession {
                val js = jsPayload(get("itv", "get_ordered_list", extra))
                val data = js.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    return@withSession Pair(emptyList<Channel>(), true)
                }
                val maxItems = js.optInt("max_page_items", 14)
                val list = ArrayList<Channel>(data.length())
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    val chGenreId = o.optString("tv_genre_id")
                    // Client-side filter: the portal may ignore the genre
                    // param and return unfiltered pages.
                    if (!genreId.isNullOrBlank() && chGenreId != genreId) continue
                    list.add(
                        Channel(
                            id = o.optString("id"),
                            number = o.optString("number"),
                            name = o.optString("name"),
                            cmd = o.optString("cmd"),
                            logo = o.optString("logo"),
                            genreId = chGenreId
                        )
                    )
                }
                Pair(list as List<Channel>, data.length() < maxItems)
            }
            all.addAll(channels)
            lastPage = isLast
            page++
        }
        return all.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
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
            // v2.7: don't swallow the evidence — log the input cmd and the
            // raw portal reply for logcat debugging (UI message stays clean).
            android.util.Log.w(
                "StalkerApi",
                "createLink: empty stream URL; inCmd=${cmd.take(150)} " +
                    "js=${js.toString().take(300)}"
            )
            throw StalkerException("Portal returned no stream URL")
        }
        for (prefix in arrayOf("ffmpeg ", "ffprobe ")) {
            if (link.startsWith(prefix)) {
                link = link.removePrefix(prefix).trim()
                break
            }
        }
        // v2.9: sanitize mangled stream URLs. This portal sometimes returns
        // URLs with the hostname embedded mid-path, e.g.
        //   http://h/p1/p2/d.com:80/p1/p2/303367?play_token=X
        // (note "d.com:80" = truncated host). Rebuild cleanly so ExoPlayer
        // gets a playable URL.
        link = sanitizeStreamUrl(link)
        link
    }

    /**
     * v2.9: repairs portal-mangled stream URLs. Detects a path segment that
     * looks like an embedded host:port (contains ':') and rebuilds the URL
     * as scheme://host/<segments before the bad one>/<last segment>?query.
     * Returns the input unchanged when no mangling is detected or parsing
     * fails.
     */
    private fun sanitizeStreamUrl(url: String): String {
        val httpUrl = try {
            url.toHttpUrlOrNull() ?: return url
        } catch (e: Exception) {
            return url
        }
        val segments = httpUrl.pathSegments
        val badIndex = segments.indexOfFirst { it.contains(":") }
        if (badIndex < 0) return url
        android.util.Log.i(
            "StalkerApi",
            "sanitizeStreamUrl: mangled URL detected, rebuilding: ${url.take(200)}"
        )
        val builder = HttpUrl.Builder()
            .scheme(httpUrl.scheme)
            .host(httpUrl.host)
        val defaultPort = HttpUrl.defaultPort(httpUrl.scheme)
        if (httpUrl.port != defaultPort) builder.port(httpUrl.port)
        // Keep segments before the embedded host, then the final segment
        // (the stream ID). e.g. [p1, p2, d.com:80, p1, p2, 303367]
        // becomes [p1, p2, 303367].
        val head = segments.subList(0, badIndex)
        for (seg in head) {
            builder.addPathSegment(seg)
        }
        // Append the final segment (stream ID) unless it IS the bad segment.
        if (badIndex < segments.size - 1) {
            val last = segments.last()
            if (head.lastOrNull() != last) {
                builder.addPathSegment(last)
            }
        }
        for (i in 0 until httpUrl.querySize) {
            builder.addQueryParameter(
                httpUrl.queryParameterName(i),
                httpUrl.queryParameterValue(i)
            )
        }
        httpUrl.fragment?.let { builder.fragment(it) }
        return builder.build().toString()
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

    /**
     * v3.2: keeps the `token=` cookie in sync with [authMethod]/[token].
     * Called after every handshake and whenever the auth method changes.
     * For TOKEN_COOKIE the token travels as a cookie; for all other
     * methods any stale token cookie is removed so it can't confuse
     * the portal.
     */
    private fun syncTokenCookie() {
        val portalHttp = ("$baseUrl/").toHttpUrlOrNull() ?: return
        if (authMethod == AuthMethod.TOKEN_COOKIE) {
            token?.takeIf { it.isNotBlank() }?.let { t ->
                cookieJar.seed(
                    portalHttp,
                    listOf(
                        Cookie.Builder().name("token").value(t)
                            .domain(portalHttp.host).path("/").build()
                    )
                )
                android.util.Log.i("StalkerApi", "syncTokenCookie: token cookie set")
            }
        } else {
            cookieJar.removeCookie("token")
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        /** Pre-loads cookies (e.g. the STB fingerprint cookies) before any request. */
        fun seed(url: HttpUrl, cookies: List<Cookie>) {
            saveFromResponse(url, cookies)
        }

        /** v3.2: removes a cookie by name (e.g. stale token cookie). */
        fun removeCookie(name: String) {
            store.removeAll { it.name == name }
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
