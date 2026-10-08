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
 * v4.6: session cache TTL — a persisted session older than this is treated
 * as dead and ignored (a fresh auth runs instead).
 */
private const val SESSION_TTL_MS = 12L * 3600L * 1000L
/** v4.7: rolling window for the handshake cap — 15 min (was 1h in v4.6).
 * The 1h window punished users far too long after v4.5's handshake spam. */
private const val CAP_WINDOW_MS = 15L * 60L * 1000L
/** v4.7: max FAILED handshakes per [CAP_WINDOW_MS] per portal+MAC.
 * Only FAILED handshakes count — a handshake that returns a valid token
 * is success, not spam (v4.6 counted every attempt, punishing success). */
private const val CAP_MAX = 3
/** v4.7: versionCode that introduced the fixed rate limiter. The first run
 * of this version wipes poisoned handshake timestamps — v4.5's
 * 14-handshake spam was persisted and blocked v4.6 from ever handshaking. */
private const val RATE_LIMIT_FIX_VERSION = 38

/**
 * v4.6: a persisted working session — token + apiBase + authMethod + flow —
 * cached in SharedPreferences so the app reuses OTT-style long-lived
 * sessions instead of handshaking on every launch.
 */
private data class SavedSession(
    val token: String,
    val apiBase: String,
    val authMethod: String,
    val flow: String,
    val ts: Long
)

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
 * works. The auth ladder ([tryAuthMethodWithToken]) and session validation
 * ([getProfile]) now probe itv/get_ordered_list page 1 for real channel
 * data instead of stb/get_profile for a profile id. "MAC not registered"
 * fires only when all four auth methods fail to return channel data.
 *
 * v4.2: single handshake. v4.1's ladder did a FRESH handshake per auth
 * method — up to 7 handshakes per connect (1 initial + 3 forced retries +
 * 3 ladder handshakes) — and bingeiptv.xyz answered every get_ordered_list
 * with HTTP 200 + empty body, likely rate-limiting the rapid-fire
 * handshakes from the same MAC. OTT Navigator does ONE handshake and
 * reuses the token for everything. The ladder now does ONE handshake,
 * tries all 4 auth methods with that SAME token ([tryAuthMethodWithToken]
 * performs no handshake of its own), and only on total failure does ONE
 * more fresh handshake + a second 4-method round. Max 2 handshakes per
 * connect attempt. Copy Debug Info shows the handshake count.
 *
 * v4.3: OTT-exact get_ordered_list URL. v4.2 sent
 * ?type=itv&action=get_ordered_list&JsHttpRequest=1-xml&p=1 but Dominic's
 * packet captures show OTT ALWAYS sends genre=*&fav=0 with params in order
 * type,action,genre,fav,p,JsHttpRequest — and bingeiptv.xyz answered our
 * shape with HTTP 200 + empty body. buildUrl() now emits the OTT shape for
 * ALL get_ordered_list calls (validation probe, paginated loading, vod).
 * Other actions (handshake, create_link, get_epg, ...) are unchanged.
 *
 * v4.6: SESSION PERSISTENCE — one handshake, keep it alive. v4.5's field
 * test on bingeiptv.xyz was the breakthrough AND the wound: the channel
 * list APPEARED (first time ever), then play failed and the list vanished
 * — debug showed Handshakes: 14. The portal gave us a working session and
 * WE killed it with handshake spam (14 rapid handshakes = abuse → session
 * invalidated). OTT does ONE handshake and stays connected for hours.
 * Now: the working session (token + apiBase + authMethod + flow) is cached
 * in memory AND in SharedPreferences (12h TTL) and reused for EVERYTHING
 * (listing, pagination, create_link, EPG) — never re-handshaking between
 * listing and playing. Re-handshake happens ONLY on explicit auth failure
 * (ONE attempt, then back off). HARD CAP: max 3 handshakes/hour per
 * portal+MAC — beyond that the app says "Too many connection attempts —
 * wait a while" instead of hammering. The aggressive ladders are gone:
 * Retry reuses the cached session first; fresh auth (dual-flow, first-auth
 * only) runs only when the cached session is proven dead AND under cap.
 * Copy Debug Info shows "Session: cached (prefs) / reused (memory) /
 * fresh ..." and "Handshakes this hour: N".
 */
class StalkerApi(
    portalUrl: String,
    private val mac: String,
    context: android.content.Context
) {

    /**
     * v4.6: application context for [SessionStore]'s SharedPreferences.
     * Never hold the caller's Activity — the StalkerSession singleton
     * outlives every screen.
     */
    private val appContext: android.content.Context = context.applicationContext

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
     * v4.4: handshake RESPONSE headers (first 500 chars, redacted) — the
     * cookie hypothesis: if the portal's handshake sets a session cookie
     * via Set-Cookie, it must appear here; if absent, the hypothesis dies.
     */
    @Volatile var debugHandshakeRespHeaders: String? = null
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
     * the channel list (see [tryAuthMethodWithToken]/[getProfile]).
     */
    @Volatile var debugProfileUrl: String? = null
        private set
    @Volatile var debugProfileHeaders: String? = null
        private set
    @Volatile var debugProfileHttpCode: Int? = null
        private set
    @Volatile var debugProfileBody: String? = null
        private set

    /**
     * v4.2: counts handshake() calls on this instance (successful or not).
     * Shown in Copy Debug Info — the v4.1 ladder did up to 7 handshakes per
     * connect, which likely triggered portal rate-limiting; v4.2 caps it
     * at 2 per connect attempt (see [withSession]).
     */
    @Volatile var debugHandshakeCount: Int = 0
        private set

    /**
     * v4.6: where the current session came from — "none" (no session yet),
     * "cached (prefs)" (restored from SharedPreferences), "reused (memory)"
     * (in-memory token from earlier in this process), "fresh (full auth)"
     * (dual-flow first auth), "fresh (re-handshake)" (single recovery
     * handshake after a dead cached session). Shown in Copy Debug Info.
     */
    @Volatile var debugSessionSource: String = "none"
        private set

    /**
     * v4.5: which auth flow the last [getProfile] used. "A" = v2.4's proven
     * {portal}/portal.php + stb/get_profile (tried FIRST); "B" = v4.x
     * {host}/server/load.php + get_ordered_list ladder (fallback). Shown
     * in Copy Debug Info so a failure can be attributed to a flow.
     */
    @Volatile var debugFlow: String = "A"
        private set

    /** v3.4: one-line redacted header dump for Copy Debug Info.
     * v4.4: Cookie header values are name-shown/value-truncated (the token
     * cookie for TOKEN_COOKIE portals must not leak in full). */
    private fun debugHeaderDump(headers: okhttp3.Headers = buildHeaders()): String {
        val sb = StringBuilder()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            var value = headers.value(i)
            if (name.equals("Authorization", ignoreCase = true)) {
                value = "Bearer <redacted>"
            } else if (name.equals("Cookie", ignoreCase = true)) {
                value = redactCookieHeader(value)
            }
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append("$name: $value")
        }
        // cookies actually sent — v4.4: buildHeaders() now emits the merged
        // Cookie header itself, so only append the jar dump when no Cookie
        // header is present (avoids double-reporting).
        val hasCookieHeader =
            (0 until headers.size).any { headers.name(it).equals("Cookie", ignoreCase = true) }
        if (!hasCookieHeader) {
            val url = ("$baseUrl/").toHttpUrlOrNull()
            if (url != null) {
                val cookies = cookieJar.loadForRequest(url)
                    .joinToString("; ") { "${it.name}=${it.value.take(12)}${if (it.value.length > 12) "…" else ""}" }
                if (cookies.isNotEmpty()) sb.append("; Cookie: $cookies")
            }
        }
        return sb.toString()
    }

    /** v4.4: shows cookie names with truncated values (safe to paste). */
    private fun redactCookieHeader(value: String): String =
        value.split(";").joinToString("; ") { part ->
            val p = part.trim()
            val n = p.substringBefore("=")
            val v = p.substringAfter("=", "")
            "$n=${v.take(12)}${if (v.length > 12) "…" else ""}"
        }

    /**
     * v4.4: builds the explicit `Cookie:` request header — the
     * packet-capture-verified MAG values (mac, stb_lang, timezone) FIRST,
     * then any server cookies held by the jar (e.g. a session cookie from
     * the handshake's Set-Cookie). Manual values win on name conflicts.
     * OkHttp's bridge skips jar injection when a Cookie header is already
     * present, so merging here is what lets server cookies ride along.
     */
    private fun buildCookieHeader(): String {
        val parts = LinkedHashMap<String, String>()
        parts["mac"] = mac
        parts["stb_lang"] = "en"
        parts["timezone"] = "GMT"
        val url = apiBase.toHttpUrlOrNull() ?: ("$baseUrl/").toHttpUrlOrNull()
        if (url != null) {
            for (c in cookieJar.loadForRequest(url)) {
                parts.putIfAbsent(c.name, c.value)
            }
        }
        return parts.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    /**
     * v4.4: one-line redacted dump of RESPONSE headers for Copy Debug
     * Info. Set-Cookie values are name-shown/value-truncated — enough to
     * confirm the cookie hypothesis without leaking session material.
     */
    private fun buildRespHeaderDump(headers: okhttp3.Headers): String {
        val sb = StringBuilder()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            var value = headers.value(i)
            if (name.equals("set-cookie", ignoreCase = true)) {
                val pair = value.substringBefore(";").trim()
                val n = pair.substringBefore("=")
                val v = pair.substringAfter("=", "")
                value = "$n=${v.take(12)}${if (v.length > 12) "…" else ""} (attrs stripped)"
            } else if (name.equals("authorization", ignoreCase = true)) {
                value = "<redacted>"
            }
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append("$name: $value")
        }
        return sb.toString().take(500)
    }

    /** v3.4: assembles the Copy Debug Info text (called from Settings). */
    fun buildDebugInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("Portal: $baseUrl")
        sb.appendLine("API base: $apiBase")
        sb.appendLine("MAC: $mac")
        sb.appendLine("Auth method: $authMethod")
        sb.appendLine("Flow: $debugFlow")
        sb.appendLine("Handshakes: $debugHandshakeCount")
        // v4.6: session persistence diagnostics.
        sb.appendLine("Session: $debugSessionSource")
        // v4.7: cap counts FAILED handshakes in a 15-min window now.
        sb.appendLine("Failed handshakes (15 min): ${sessionStore.failedHandshakesInWindow()}")
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
        sb.appendLine("Handshake response headers (500 chars):")
        sb.appendLine(debugHandshakeRespHeaders ?: "(none yet)")
        sb.appendLine()
        // v4.5: label reflects which flow produced the validation call —
        // Flow A validates via stb/get_profile, Flow B via itv/get_ordered_list.
        val validationLabel = if (debugFlow == "A") "stb/get_profile" else "itv/get_ordered_list"
        sb.appendLine("Validation (Flow $debugFlow, first $validationLabel attempt):")
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

    /**
     * v4.6: persistent session cache + handshake rate-limiting (see
     * [SessionStore]). The working session survives process death so the
     * app reuses OTT-style long-lived sessions instead of handshaking on
     * every launch.
     */
    private val sessionStore = SessionStore()

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
        // v4.7: first run of the fixed rate limiter — wipe poisoned
        // handshake timestamps. v4.5's 14-handshake spam was persisted and
        // blocked v4.6 from ever handshaking (cap hit with 0 new
        // handshakes). Everyone gets a fresh cap on upgrade.
        val meta = appContext.getSharedPreferences(
            "zynelabs_stb_session", android.content.Context.MODE_PRIVATE
        )
        if (meta.getInt("last_version_code", 0) < RATE_LIMIT_FIX_VERSION) {
            sessionStore.clearAllTimestamps()
            meta.edit().putInt("last_version_code", RATE_LIMIT_FIX_VERSION).apply()
            android.util.Log.i(
                "StalkerApi",
                "v4.7 upgrade: cleared poisoned handshake timestamps"
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
            // v4.4: explicit Cookie header — the verified MAG values merged
            // with jar-held server cookies (handshake Set-Cookie). OkHttp
            // skips its own jar injection when this header is present, so
            // the merge in buildCookieHeader() is what carries the session.
            .add("Cookie", buildCookieHeader())
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
        if (action == "get_ordered_list") {
            // v4.3: OTT-exact URL shape — Dominic's packet captures
            // (bingeiptv.xyz, wafasiad.com) show OTT ALWAYS sends
            // genre=*&fav=0, with params in order:
            // type, action, genre, fav, p, JsHttpRequest.
            // v4.2 omitted genre/fav and put JsHttpRequest before p —
            // bingeiptv.xyz answered HTTP 200 + empty body. Match OTT exactly.
            builder.addQueryParameter("genre", "*")
            builder.addQueryParameter("fav", "0")
            for ((key, value) in extra) {
                builder.addEncodedQueryParameter(key, value)
            }
            builder.addQueryParameter("JsHttpRequest", "1-xml")
        } else {
            // v3.8: load.php (NOT portal.php) — the native-app API endpoint.
            // IPTV Stalker Player v1.43 uses load.php; portal.php is the MAG
            // web-UI endpoint that rejects non-MAG clients ("Your STB is not
            // supported"). The working app SENDS JsHttpRequest=1-xml with
            // load.php, so we include it (v3.5's removal was portal.php-specific).
            // v3.8: JsHttpRequest=1-xml — the working native app (IPTV Stalker
            // v1.43) sends this with load.php on every request.
            builder.addQueryParameter("JsHttpRequest", "1-xml")
            // v2.4: NO token/mac query params. Token goes in the Authorization
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
            // and [tryAuthMethodWithToken]). OTT's packet-captured flow skips
            // stb/get_profile entirely, so get_profile diagnostics are
            // no longer useful. handshake() resets these fields, so Copy
            // Debug Info always shows the CURRENT session's attempt.
            // (v3.6–v3.7 captured get_profile; v4.1 supersedes that.)
            // v4.5: Flow A validates via stb/get_profile (portal.php),
            // Flow B via itv/get_ordered_list — capture whichever flow
            // is active so Copy Debug Info shows the real validation call.
            val isValidationCall =
                (debugFlow == "A" && type == "stb" && action == "get_profile") ||
                    (debugFlow == "B" && type == "itv" && action == "get_ordered_list")
            if (isValidationCall && debugProfileUrl == null) {
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
            // v4.6: restore the persisted session first — zero handshakes
            // when a valid token exists from the last run.
            if (token.isNullOrBlank()) {
                handshakeMutex.withLock {
                    if (token.isNullOrBlank() && !restoreSession()) handshake()
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
            // v4.6: restore the persisted session first (see probe()).
            if (token.isNullOrBlank()) {
                handshakeMutex.withLock {
                    if (token.isNullOrBlank() && !restoreSession()) handshake()
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
    suspend fun handshake(): String = handshakeOn(apiBaseCandidates())

    /**
     * v4.5: handshake against an explicit candidate list. [handshake]
     * keeps the v3.9 behavior (try {host}/server/load.php, then
     * {portal}/load.php, then {portal}/portal.php); Flow A passes a
     * single-element list ({portal}/portal.php) to pin the v2.4 endpoint.
     */
    private suspend fun handshakeOn(candidates: List<String>): String {
        // v4.7: HARD CAP — max 3 FAILED handshakes per 15 min per
        // portal+MAC (was: every attempt counted, 1h window — v4.5's
        // 14-handshake spam poisoned the persisted cap and blocked v4.6).
        // checkCap() throws a NON-auth-failure StalkerException so
        // recovery ladders don't catch it — the UI tells the user the
        // actual wait time instead of hammering.
        sessionStore.checkCap()
        // v4.6: keep the old token — if THIS handshake fails (network
        // blip), the caller retries with the previous session instead of
        // starting from nothing (restored in the catch below).
        val prevToken = token
        token = null
        // v4.2: count every handshake attempt (see debugHandshakeCount).
        debugHandshakeCount++
        // v4.7: NO pre-recording — only a FAILED handshake counts against
        // the cap. A handshake that returns a valid token is success,
        // not spam. (v4.6 recorded before attempting, so even successful
        // handshakes burned the cap.)
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
        debugHandshakeRespHeaders = null
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
        try {
            for (candidate in candidates) {
                apiBase = candidate
                try {
                debugHandshakeUrl =
                    buildUrl("stb", "handshake", mapOf("token" to "")).toString()
                debugHandshakeHeaders = debugHeaderDump()
                val (raw, respHeaders) = getRaw("stb", "handshake", mapOf("token" to ""))
                debugHandshakeResponse = raw.replace(Regex("\\s+"), " ").take(1000)
                // v4.4: capture response headers — Set-Cookie here confirms
                // the cookie hypothesis; its absence kills it.
                debugHandshakeRespHeaders = buildRespHeaderDump(respHeaders)
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
                // v4.4: log the jar after handshake — shows whether the
                // portal set a session cookie (cookie hypothesis).
                android.util.Log.i("StalkerApi", "handshake: jar={${cookieJar.dump()}}")
                syncTokenCookie() // TOKEN_COOKIE: publish the fresh token
                // v4.6: persist the working session immediately — the token
                // is reused for everything from now on (no re-handshake
                // between listing and playing). The auth method may be
                // refined by the ladder afterwards; persistSession() is
                // called again whenever it settles.
                persistSession()
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
        } catch (e: Exception) {
            // v4.7: THIS handshake FAILED — record it against the cap
            // (success is not spam). checkCap()'s own throw never reaches
            // here (it fires before the try), so cap-blocks don't
            // self-extend.
            sessionStore.recordFailedHandshake()
            // v4.6: don't destroy a possibly-good token when THIS handshake
            // fails (network blip, all bases 404) — restore the previous
            // token so the caller retries with the old session instead of
            // starting from nothing.
            if (token.isNullOrBlank() && !prevToken.isNullOrBlank()) {
                token = prevToken
                android.util.Log.w(
                    "StalkerApi",
                    "handshakeOn: failed, restored previous token"
                )
            }
            throw e
        }
    }

    /**
     * v3.2: raw GET returning the body string (for handshake logging).
     * Same request shape as [get] but without JSON parsing.
     * v4.4: also returns the response headers so handshake() can capture
     * them for the Set-Cookie diagnostic (Copy Debug Info).
     */
    private suspend fun getRaw(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): Pair<String, okhttp3.Headers> = withContext(Dispatchers.IO) {
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
            Pair(body, response.headers)
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
     * v4.2: tries one [AuthMethod] using the CURRENT token — performs NO
     * handshake of its own. v4.1 did a fresh handshake per method (up to 7
     * handshakes per connect), which likely triggered portal
     * rate-limiting; OTT does one handshake and reuses the token. The
     * caller ([withSession] ladder) performs the handshake(s); this just
     * switches the wire format (Bearer header via [buildHeaders],
     * token= query param via [buildUrl], token= cookie via
     * [syncTokenCookie], or nothing for MAC_ONLY) and probes
     * itv/get_ordered_list page 1 for real channel data.
     *
     * @return true when the probe yields channel data — the caller keeps
     *         the method; false leaves [authMethod] for the caller.
     */
    private suspend fun tryAuthMethodWithToken(method: AuthMethod): Boolean {
        return try {
            authMethod = method
            // Publish/remove the token cookie for this wire format. The
            // token itself is unchanged — it came from the ladder's single
            // handshake, not a fresh one.
            syncTokenCookie()
            val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
            val data = js.optJSONArray("data")
            val ok = data != null && data.length() > 0
            android.util.Log.i(
                "StalkerApi",
                "tryAuthMethodWithToken($method): channel data valid = $ok " +
                    "(items=${data?.length() ?: 0}, same token)"
            )
            ok
        } catch (e: Exception) {
            android.util.Log.w(
                "StalkerApi",
                "tryAuthMethodWithToken($method) failed: " +
                    "${(e.message ?: e.javaClass.simpleName).take(60)}"
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
     * v3.2: auth-method ladder. If the current method keeps failing, the
     * app walks BEARER → TOKEN_PARAM → TOKEN_COOKIE → MAC_ONLY (see
     * [tryAuthMethodWithToken]), keeping the first method that yields
     * valid channel data (v4.1: itv/get_ordered_list page 1 — OTT's flow
     * skips stb/get_profile entirely). Only when ALL four methods fail is
     * the MAC reported as not registered.
     *
     * v4.2: single handshake. v4.1 did a fresh handshake per ladder method
     * (up to 7 handshakes per connect); rapid-fire handshakes likely
     * trigger portal rate-limiting. The ladder now tries all 4 methods
     * with ONE token (round 1: remaining methods, same token; round 2:
     * one fresh handshake, all 4 methods). Max 2 handshakes per connect.
     *
     * Network-level (IOException) recovery: evict the pool, back off, retry
     * with the existing token; a subsequent auth failure on retry flows
     * into the forced-handshake path above.
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        if (token.isNullOrBlank()) {
            // v2.7: serialize even the restore+first-handshake — several
            // activities starting at once must not handshake concurrently.
            // v4.6: restore the persisted session FIRST — a valid token
            // from the last run means zero handshakes this launch.
            handshakeMutex.withLock {
                if (token.isNullOrBlank() && !restoreSession()) {
                    handshake() // cap-checked inside handshakeOn
                }
            }
        }
        // v3.0: up to 3 self-heal retries (not just 1) for network errors.
        val maxRetries = 3
        var attempt = 0
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
                // v4.6: recovery is now CHEAP. The v4.5 field test proved
                // handshake spam kills our own session (14 handshakes →
                // portal invalidated it). Round 1: remaining auth methods
                // with the CURRENT token — 0 handshakes. Then ONE
                // re-handshake (cap-checked: max 3/hour) and a SINGLE
                // retry with the previously-working method. No second
                // ladder — the method worked before, only the token was
                // stale.
                val origMethod = authMethod
                val methods = AuthMethod.values()
                var methodIndex = methods.indexOf(origMethod).coerceAtLeast(0)
                android.util.Log.w(
                    "StalkerApi",
                    "withSession: auth failure, cheap round (same token) " +
                        "from ${methods[methodIndex]}, " +
                        "order=${methods.joinToString("→")}"
                )
                var foundWorking = false
                while (methodIndex < methods.size - 1 && !foundWorking) {
                    methodIndex++
                    val next = methods[methodIndex]
                    android.util.Log.w(
                        "StalkerApi",
                        "withSession: trying $next with current token " +
                            "(no new handshake)"
                    )
                    if (tryAuthMethodWithToken(next)) {
                        authMethod = next
                        foundWorking = true
                    }
                }
                if (foundWorking) {
                    // New method works — persist it and retry the call.
                    persistSession()
                    attempt = 0
                    continue
                }
                // Methods exhausted with this token — restore the
                // previously-working method (the ladder leaves authMethod
                // on MAC_ONLY; retrying with that after a fresh handshake
                // would fail even though BEARER+new token works).
                authMethod = origMethod
                syncTokenCookie()
                // v4.6: ONE re-handshake, then ONE retry. handshakeIfStale
                // enforces the hourly cap (throws "Too many connection
                // attempts" — not an auth failure, so no further ladder).
                // v3.0: FORCE — the stored token may be dead server-side
                // even though it looks current locally. Mutex keeps
                // concurrent handshakes serialized (v2.7).
                android.util.Log.w(
                    "StalkerApi",
                    "withSession: methods exhausted, one re-handshake " +
                        "(cap-checked) then single retry with $origMethod"
                )
                handshakeIfStale(null, force = true)
                debugSessionSource = "fresh (re-handshake)"
                persistSession()
                try {
                    return block()
                } catch (e2: StalkerException) {
                    if (!isAuthFailure(e2)) throw e2
                    // Token AND method both fail after a fresh handshake:
                    // the MAC itself isn't registered. Back off — no more
                    // ladders, no more handshakes (v4.5's 14-handshake
                    // self-kill must never recur).
                    throw StalkerException("MAC not registered on this portal")
                }
            }
        }
    }

    // -------------------------------------------------------------- actions

    /**
     * v4.5 Flow A: v2.4's proven auth — {portal}/portal.php, Bearer-only,
     * validated by a REAL stb/get_profile (non-blank id = registered).
     * v2.4 worked on wafasiad.com with exactly this shape; v3.8/v4.1
     * replaced it with load.php + get_ordered_list and wafasiad regressed
     * ("BEARER" selected but no channels). Bounded: up to 2 handshakes.
     *
     * @return the real profile on success, null when this flow yields
     *         nothing (the caller falls back to Flow B). Never throws
     *         "MAC not registered" — that verdict comes only after BOTH
     *         flows fail.
     */
    private suspend fun tryFlowA(): JSONObject? {
        val portalPhp = "$baseUrl/portal.php"
        // v4.6: SINGLE attempt (was repeat(2)). Handshake budget is precious
        // now — max 3/hour — and v2.4 proved one handshake suffices. A
        // network blip here just falls through to Flow B, which has its own
        // retries.
        try {
            // v2.4 style: Bearer only — no auth-method ladder here.
            authMethod = AuthMethod.BEARER
            handshakeMutex.withLock { handshakeOn(listOf(portalPhp)) }
            val js = jsPayload(get("stb", "get_profile"))
            val id = js.optString("id")
            if (id.isNotBlank()) {
                android.util.Log.i(
                    "StalkerApi",
                    "tryFlowA: portal.php + get_profile OK, " +
                        "id=${id.take(16)}"
                )
                return js
            }
            android.util.Log.w("StalkerApi", "tryFlowA: get_profile blank id")
        } catch (e: Exception) {
            android.util.Log.w(
                "StalkerApi",
                "tryFlowA: failed: " +
                    "${(e.message ?: e.javaClass.simpleName).take(80)}"
            )
        }
        android.util.Log.w("StalkerApi", "tryFlowA: no profile, falling back to Flow B")
        return null
    }

    /**
     * Returns the raw profile JSON object of the box.
     *
     * v4.5: DUAL-FLOW auth (simplification, not more complexity).
     * Flow A (tried FIRST): v2.4's proven {portal}/portal.php +
     * stb/get_profile + Bearer — returned a real 5.6KB profile on
     * wafasiad.com. Flow B (fallback): v4.4's {host}/server/load.php +
     * get_ordered_list ladder (packet-capture-verified OTT shape).
     * Different portals need genuinely different flows; the v3.9
     * api-base fallback only varied the PATH, not the auth flow.
     * "MAC not registered" fires only when BOTH flows fail.
     *
     * v4.1 note (Flow B): Dominic's packet capture proved OTT Navigator
     * NEVER calls stb/get_profile — its flow is handshake →
     * get_ordered_list directly. bingeiptv.xyz returns EMPTY for
     * get_profile even with the v4.0 packet-capture-verified MAG format,
     * while get_ordered_list works. Real channel data ({"js":{"data":[...
     * with >= 1 item) is the proof the session works; a synthetic
     * profile (id = the MAC) is returned so [isMacRegistered] keeps
     * working for callers.
     */
    suspend fun getProfile(): JSONObject {
        // v4.6: FAST PATH — reuse the cached session (memory or prefs) and
        // validate it with ONE call. No handshake when we already have a
        // token: this is what keeps the portal from killing our session.
        if (token.isNullOrBlank()) {
            if (restoreSession()) {
                android.util.Log.i(
                    "StalkerApi", "getProfile: using persisted session, validating"
                )
            }
        }
        if (!token.isNullOrBlank()) {
            // v4.6: label the session source for Copy Debug Info —
            // restoreSession() already set "cached (prefs)" when it ran.
            if (debugSessionSource != "cached (prefs)") {
                debugSessionSource = "reused (memory)"
            }
            try {
                return validateSessionOnce().also { persistSession() }
            } catch (e: IOException) {
                // Network blip during validation — don't burn handshakes;
                // let the caller surface/retry it.
                throw e
            } catch (e: Exception) {
                android.util.Log.w(
                    "StalkerApi",
                    "getProfile: cached session failed " +
                        "(${(e.message ?: e.javaClass.simpleName).take(60)}), " +
                        "full auth"
                )
                // Stale persisted session — drop it so fullAuth starts clean
                // (and a later restore doesn't resurrect the dead token).
                sessionStore.clear()
                token = null
                lastHandshakeToken = null
            }
        }
        return fullAuth()
    }

    /**
     * v4.6: validates the CURRENT (cached) session with a single call —
     * no handshake, no ladder. Uses whichever flow the session belongs to:
     * Flow A → stb/get_profile (real profile), Flow B →
     * itv/get_ordered_list page 1 (synthetic profile on channel data).
     * Throws on any failure; the caller decides recovery.
     */
    private suspend fun validateSessionOnce(): JSONObject {
        return if (debugFlow == "A") {
            val js = jsPayload(get("stb", "get_profile"))
            val id = js.optString("id")
            if (id.isBlank()) {
                throw StalkerException(
                    "Portal returned no profile id", isAuthFailure = true
                )
            }
            android.util.Log.i(
                "StalkerApi",
                "validateSessionOnce: Flow A session valid, id=${id.take(16)}"
            )
            js
        } else {
            debugFlow = "B"
            val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
            val data = js.optJSONArray("data")
            if (data == null || data.length() == 0) {
                throw StalkerException(
                    "Portal returned no channel data", isAuthFailure = true
                )
            }
            android.util.Log.i(
                "StalkerApi",
                "validateSessionOnce: Flow B session valid, " +
                    "channel page 1 has ${data.length()} items"
            )
            JSONObject().put("id", mac).put("mac", mac)
        }
    }

    /**
     * v4.6: full dual-flow auth — FIRST-AUTH ONLY (or when the cached
     * session is proven dead). Flow A: v2.4's proven {portal}/portal.php +
     * stb/get_profile + Bearer (1 handshake). Flow B (fallback): v4.4's
     * {host}/server/load.php + get_ordered_list ladder (≤2 handshakes via
     * withSession). Total ≤3 handshakes = the hourly cap. "MAC not
     * registered" fires only when BOTH flows fail.
     */
    private suspend fun fullAuth(): JSONObject {
        debugSessionSource = "fresh (full auth)"
        // Flow A first — v2.4's proven portal.php + get_profile.
        debugFlow = "A"
        tryFlowA()?.let {
            persistSession()
            return it
        }
        // Flow A yielded nothing — reset session state for Flow B.
        // (tryFlowA leaves apiBase pinned to portal.php and possibly a
        // dead token; Flow B must start clean.)
        debugFlow = "B"
        token = null
        lastHandshakeToken = null
        authMethod = AuthMethod.BEARER
        apiBase = "$hostBase/server/load.php"
        return withSession {
            val js = jsPayload(get("itv", "get_ordered_list", mapOf("p" to "1")))
            val data = js.optJSONArray("data")
            // v2.5: no channel data means the portal doesn't associate this
            // session with a user — the token is dead OR the MAC isn't
            // registered. Throw the marker so withSession recovers (cheap
            // method round → one re-handshake) before concluding the MAC
            // is unregistered.
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
            persistSession()
            JSONObject().put("id", mac).put("mac", mac)
        }
    }

    /**
     * Returns true if the portal recognizes this MAC as a registered box.
     * An unregistered MAC gets a default profile with a null/blank "id".
     */
    fun isMacRegistered(profile: JSONObject): Boolean {
        // optString returns "" for JSON null
        return profile.optString("id").isNotBlank()
    }

    /**
     * v4.7: manual escape hatch (Settings → Reset connection). Clears the
     * cached session (memory + prefs) AND the handshake timestamps for
     * this portal+MAC, then drops the shared instance. The next Connect
     * starts completely fresh — no waiting out the cap.
     */
    fun resetConnection() {
        sessionStore.resetConnection()
        token = null
        lastHandshakeToken = null
        authMethod = AuthMethod.BEARER
        debugFlow = "B"
        debugSessionSource = "none"
        debugHandshakeCount = 0
        StalkerSession.reset()
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
                // v4.4: log every server-set cookie — the cookie hypothesis
                // lives or dies on whether the handshake sets one.
                android.util.Log.i(
                    "StalkerApi",
                    "cookieJar: saved ${cookie.name}=${cookie.value.take(12)}… " +
                        "for ${url.host} (path=${cookie.path}, " +
                        "secure=${cookie.secure}, httpOnly=${cookie.httpOnly})"
                )
            }
            store.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }
            return store.toList()
        }

        /** v4.4: diagnostic dump of jar contents (names + truncated values). */
        fun dump(): String =
            store.joinToString("; ") {
                "${it.name}=${it.value.take(12)}${if (it.value.length > 12) "…" else ""}"
            }
    }

    // ------------------------------------------------------- session store

    /**
     * v4.6: persistent session cache + handshake rate limiter.
     *
     * The portal kills sessions when it sees handshake spam (v4.5 field
     * test: 14 handshakes → working session invalidated). OTT does ONE
     * handshake and reuses the token for hours — we now do the same:
     * the working session (token + apiBase + authMethod + flow) is saved
     * to SharedPreferences (12h TTL) and restored on the next launch
     * instead of handshaking again.
     *
     * Handshake timestamps are also persisted: HARD CAP of 3 handshakes
     * per rolling hour per portal+MAC. [checkCap] throws (NOT an auth
     * failure — it must not trigger recovery ladders) when the cap is
     * hit; the UI then tells the user to wait instead of hammering.
     */
    private inner class SessionStore {
        private val prefs = appContext.getSharedPreferences(
            "zynelabs_stb_session", android.content.Context.MODE_PRIVATE
        )
        private val pkey: String =
            "s4_" + (baseUrl + "|" + mac).hashCode().toString(16)
        private val hkey: String =
            "h4_" + (baseUrl + "|" + mac).hashCode().toString(16)

        fun save(token: String, apiBase: String, authMethod: String, flow: String) {
            prefs.edit()
                .putString(pkey + "_t", token)
                .putString(pkey + "_b", apiBase)
                .putString(pkey + "_m", authMethod)
                .putString(pkey + "_f", flow)
                .putLong(pkey + "_ts", System.currentTimeMillis())
                .apply()
        }

        fun load(): SavedSession? {
            val ts = prefs.getLong(pkey + "_ts", 0L)
            if (ts == 0L) return null
            if (System.currentTimeMillis() - ts > SESSION_TTL_MS) {
                clear()
                return null
            }
            val token = prefs.getString(pkey + "_t", "").orEmpty()
            if (token.isBlank()) return null
            return SavedSession(
                token = token,
                apiBase = prefs.getString(pkey + "_b", "").orEmpty(),
                authMethod = prefs.getString(pkey + "_m", "BEARER").orEmpty(),
                flow = prefs.getString(pkey + "_f", "B").orEmpty(),
                ts = ts
            )
        }

        fun clear() {
            prefs.edit()
                .remove(pkey + "_t").remove(pkey + "_b").remove(pkey + "_m")
                .remove(pkey + "_f").remove(pkey + "_ts")
                .apply()
        }

        private fun readTimes(): MutableList<Long> {
            val raw = prefs.getString(hkey, "").orEmpty()
            if (raw.isBlank()) return mutableListOf()
            val now = System.currentTimeMillis()
            return raw.split(",")
                .mapNotNull { it.toLongOrNull() }
                .filter { now - it < CAP_WINDOW_MS }
                .toMutableList()
        }

        /**
         * v4.7: throws when the FAILED-handshake cap is reached — NOT an
         * auth failure, so recovery ladders must not catch this. The
         * message carries the actual wait (from the oldest failure in the
         * window) so the user knows when to retry.
         */
        fun checkCap() {
            val times = readTimes()
            if (times.size >= CAP_MAX) {
                val oldest = times.minOrNull() ?: System.currentTimeMillis()
                val waitMs = CAP_WINDOW_MS - (System.currentTimeMillis() - oldest)
                val waitMin = ((waitMs + 59999L) / 60000L).coerceAtLeast(1L)
                throw StalkerException(
                    "Too many connection attempts — try again in $waitMin min"
                )
            }
        }

        /** v4.7: records a FAILED handshake (success is not spam). */
        fun recordFailedHandshake() {
            val times = readTimes()
            times.add(System.currentTimeMillis())
            prefs.edit().putString(hkey, times.joinToString(",")).apply()
        }

        fun failedHandshakesInWindow(): Int = readTimes().size

        /**
         * v4.7: clears ALL persisted handshake timestamps (every
         * portal+MAC). Called once on upgrade to v4.7 — v4.5's
         * 14-handshake spam was persisted and blocked v4.6 from ever
         * handshaking again.
         */
        fun clearAllTimestamps() {
            val keys = prefs.all.keys.filter { it.startsWith("h4_") }
            if (keys.isEmpty()) return
            val ed = prefs.edit()
            keys.forEach { ed.remove(it) }
            ed.apply()
            android.util.Log.i(
                "StalkerApi",
                "clearAllTimestamps: wiped ${keys.size} poisoned cap key(s)"
            )
        }

        /**
         * v4.7: manual escape hatch (Settings → Reset connection) —
         * clears the cached session AND the handshake timestamps for
         * this portal+MAC. The next Connect starts completely fresh,
         * no waiting out the cap.
         */
        fun resetConnection() {
            clear()
            prefs.edit().remove(hkey).apply()
            android.util.Log.i("StalkerApi", "resetConnection: cleared")
        }
    }

    /** v4.6: persists the current working session (called after a successful
     * handshake and whenever the auth method/flow settles). */
    private fun persistSession() {
        val t = token ?: return
        if (t.isBlank()) return
        sessionStore.save(t, apiBase, authMethod.name, debugFlow)
    }

    /**
     * v4.6: restores the persisted session into this instance (token,
     * apiBase, authMethod, flow). Returns true when a usable session was
     * restored. Sets [debugSessionSource] and syncs the token cookie.
     */
    private fun restoreSession(): Boolean {
        val s = sessionStore.load() ?: return false
        token = s.token
        lastHandshakeToken = s.token
        if (s.apiBase.isNotBlank()) apiBase = s.apiBase
        authMethod = try {
            AuthMethod.valueOf(s.authMethod)
        } catch (e: Exception) {
            AuthMethod.BEARER
        }
        debugFlow = if (s.flow == "A") "A" else "B"
        debugSessionSource = "cached (prefs)"
        syncTokenCookie()
        android.util.Log.i(
            "StalkerApi",
            "restoreSession: token=${s.token.take(8)}… apiBase=${s.apiBase} " +
                "method=${s.authMethod} flow=${s.flow} " +
                "age=${(System.currentTimeMillis() - s.ts) / 1000}s"
        )
        return true
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
