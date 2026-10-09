package com.zynelabs.stb

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v6.3: portal details shown in Settings → Provider details.
 */
data class PortalInfo(
    val providerName: String,
    val typeLabel: String,
    /** Human-readable expiry ("12 Jan 2027"); "—" when unknown. */
    val expireDate: String,
    /** -1 when unknown. */
    val channelCount: Int,
    /** Extra line (server host, user, …). */
    val detail: String
)

/**
 * v6.3: unified playlist API across provider types
 * (Stalker portal / M3U link / M3U file / Xtream Codes).
 * Method shapes mirror StalkerApi so activities switch receivers
 * with minimal churn.
 */
interface PlaylistSource {

    suspend fun getGenres(): List<StalkerApi.Genre>

    suspend fun getChannelsPaginated(
        genreId: String? = null,
        maxPages: Int = Int.MAX_VALUE,
        onProgress: ((pagesDone: Int, channelsSoFar: Int) -> Unit)? = null
    ): List<Channel>

    suspend fun getTotalChannelCount(): Int

    /** Resolves a channel cmd to a playable stream URL. */
    suspend fun createLink(cmd: String, type: String = "itv"): String

    fun streamHeaders(): Map<String, String>

    suspend fun getProfile(): JSONObject

    suspend fun getEpg(chId: String, date: String): List<StalkerApi.EpgProgram>

    suspend fun getVodCategories(vodType: String = "vod"): List<StalkerApi.VodCategory>

    suspend fun getVodList(
        vodType: String = "vod",
        categoryId: String
    ): List<StalkerApi.VodItem>

    suspend fun getPortalInfo(): PortalInfo

    fun buildDebugInfo(): String

    fun resetHandshakeCap()

    fun resetConnection()
}

// ---------------------------------------------------------------- stalker

/** v6.3: Stalker/MAC portal backed by the existing StalkerApi. */
class StalkerSource(private val api: StalkerApi) : PlaylistSource {

    override suspend fun getGenres(): List<StalkerApi.Genre> = api.getGenres()

    override suspend fun getChannelsPaginated(
        genreId: String?,
        maxPages: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Channel> = api.getChannelsPaginated(genreId, maxPages, onProgress)

    override suspend fun getTotalChannelCount(): Int = api.getTotalChannelCount()

    override suspend fun createLink(cmd: String, type: String): String =
        api.createLink(cmd, type)

    override fun streamHeaders(): Map<String, String> = api.streamHeaders()

    override suspend fun getProfile(): JSONObject = api.getProfile()

    override suspend fun getEpg(chId: String, date: String): List<StalkerApi.EpgProgram> =
        api.getEpg(chId, date)

    override suspend fun getVodCategories(vodType: String): List<StalkerApi.VodCategory> =
        api.getVodCategories(vodType)

    override suspend fun getVodList(
        vodType: String,
        categoryId: String
    ): List<StalkerApi.VodItem> = api.getVodList(vodType, categoryId)

    override suspend fun getPortalInfo(): PortalInfo {
        var expire = "—"
        var detail = ""
        try {
            val p = api.getProfile()
            val js = p.optJSONObject("js") ?: p
            expire = js.optString("exp_date")
                .ifBlank { js.optString("expire") }
                .ifBlank { js.optString("end_date") }
                .ifBlank { "—" }
            detail = js.optString("phone").ifBlank { js.optString("mac") }
        } catch (_: Exception) {
        }
        val count = try {
            getTotalChannelCount()
        } catch (_: Exception) {
            -1
        }
        return PortalInfo(
            providerName = "",
            typeLabel = "Stalker",
            expireDate = expire,
            channelCount = count,
            detail = detail
        )
    }

    override fun buildDebugInfo(): String = api.buildDebugInfo()

    override fun resetHandshakeCap() = api.resetHandshakeCap()

    override fun resetConnection() {
        api.resetConnection()
        SourceManager.reset()
    }
}

// --------------------------------------------------------------------- m3u

/**
 * v6.3: M3U playlist source (remote URL or local file).
 * Parsed once and held in memory; genres come from group-title.
 */
class M3uSource(
    private val appCtx: Context,
    private val provider: Provider
) : PlaylistSource {

    private var entries: List<M3uParser.Entry>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun load(): List<M3uParser.Entry> {
        entries?.let { return it }
        val text = withContext(Dispatchers.IO) {
            if (provider.type == ProviderStore.TYPE_M3U_FILE) {
                try {
                    appCtx.contentResolver.openInputStream(Uri.parse(provider.filePath))
                        ?.bufferedReader()?.readText()
                        ?: throw StalkerApi.StalkerException("Cannot read M3U file")
                } catch (e: SecurityException) {
                    // v6.3.1: URI permission lost (e.g. file picked with the old
                    // GetContent picker, or permission revoked) — tell the user
                    // to re-pick the file instead of a cryptic Permission Denial.
                    throw StalkerApi.StalkerException(
                        "Lost access to the M3U file — open Providers, edit it and choose the file again"
                    )
                }
            } else {
                val req = Request.Builder()
                    .url(provider.url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw StalkerApi.StalkerException("M3U HTTP ${resp.code}")
                    }
                    resp.body?.string().orEmpty()
                }
            }
        }
        val parsed = M3uParser.parse(text)
        if (parsed.isEmpty()) throw StalkerApi.StalkerException("Empty M3U playlist")
        entries = parsed
        return parsed
    }

    private fun entryToChannel(e: M3uParser.Entry, id: String): Channel = Channel(
        id = id,
        number = "",
        name = e.name,
        cmd = e.url,
        logo = e.logo,
        genreId = e.group
    )

    override suspend fun getGenres(): List<StalkerApi.Genre> {
        val entries = load()
        val groups = entries.map { it.group }.filter { it.isNotBlank() }.distinct()
        // v6.3.11: debug — diagnose "only All Channels" reports.
        Log.d("M3U", "getGenres: ${entries.size} entries, ${groups.size} groups: $groups")
        return groups.map { StalkerApi.Genre(id = it, title = it) }
    }

    override suspend fun getChannelsPaginated(
        genreId: String?,
        maxPages: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Channel> {
        val all = load()
        val filtered = if (genreId.isNullOrBlank()) all else all.filter { it.group == genreId }
        val list = filtered.mapIndexed { i, e -> entryToChannel(e, "m3u_$i") }
        onProgress?.invoke(1, list.size)
        return list
    }

    override suspend fun getTotalChannelCount(): Int = load().size

    override suspend fun createLink(cmd: String, type: String): String {
        if (cmd.isBlank()) throw StalkerApi.StalkerException("Empty stream URL")
        return cmd // M3U entries already carry the playable URL
    }

    override fun streamHeaders(): Map<String, String> = emptyMap()

    override suspend fun getProfile(): JSONObject = JSONObject()

    override suspend fun getEpg(chId: String, date: String): List<StalkerApi.EpgProgram> =
        emptyList()

    override suspend fun getVodCategories(vodType: String): List<StalkerApi.VodCategory> =
        emptyList()

    override suspend fun getVodList(
        vodType: String,
        categoryId: String
    ): List<StalkerApi.VodItem> = emptyList()

    override suspend fun getPortalInfo(): PortalInfo {
        val count = try {
            load().size
        } catch (_: Exception) {
            -1
        }
        return PortalInfo(
            providerName = provider.name,
            typeLabel = ProviderStore.typeLabel(provider),
            expireDate = "—",
            channelCount = count,
            detail = if (provider.type == ProviderStore.TYPE_M3U_FILE) {
                "Local file"
            } else {
                provider.url
            }
        )
    }

    override fun buildDebugInfo(): String =
        "Type: ${ProviderStore.typeLabel(provider)}\n" +
            "URL: ${provider.url}\n" +
            "Channels: ${entries?.size ?: "?"} (loaded: ${entries != null})"

    override fun resetHandshakeCap() { /* no-op */
    }

    override fun resetConnection() {
        entries = null
        SourceManager.reset()
    }
}

// ------------------------------------------------------------------ xtream

/**
 * v6.3: Xtream Codes source (player_api.php).
 * Live TV + VOD movies fully; series are flattened to episodes.
 */
class XtreamSource(private val provider: Provider) : PlaylistSource {

    private val xtream = XtreamApi(provider.url, provider.username, provider.password)

    private var liveCache: List<JSONObject>? = null

    private suspend fun liveAll(): List<JSONObject> {
        liveCache?.let { return it }
        val list = xtream.liveStreams()
        liveCache = list
        return list
    }

    private fun channelFrom(o: JSONObject): Channel {
        val id = o.optString("stream_id")
        return Channel(
            id = id,
            number = o.optString("num").ifBlank { "" },
            name = o.optString("name"),
            cmd = xtream.liveUrl(id),
            logo = o.optString("stream_icon"),
            genreId = o.optString("category_id")
        )
    }

    override suspend fun getGenres(): List<StalkerApi.Genre> =
        xtream.liveCategories().map {
            StalkerApi.Genre(
                id = it.optString("category_id"),
                title = it.optString("category_name")
            )
        }

    override suspend fun getChannelsPaginated(
        genreId: String?,
        maxPages: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Channel> {
        val all = liveAll()
        val filtered = if (genreId.isNullOrBlank()) {
            all
        } else {
            all.filter { it.optString("category_id") == genreId }
        }
        val list = filtered.map { channelFrom(it) }
        onProgress?.invoke(1, list.size)
        return list
    }

    override suspend fun getTotalChannelCount(): Int = liveAll().size

    override suspend fun createLink(cmd: String, type: String): String {
        if (cmd.isBlank()) throw StalkerApi.StalkerException("Empty stream URL")
        return cmd // Xtream URLs are built directly
    }

    override fun streamHeaders(): Map<String, String> = emptyMap()

    override suspend fun getProfile(): JSONObject = xtream.userInfo()

    override suspend fun getEpg(chId: String, date: String): List<StalkerApi.EpgProgram> =
        emptyList()

    override suspend fun getVodCategories(vodType: String): List<StalkerApi.VodCategory> {
        val cats = if (vodType == "series") xtream.seriesCategories() else xtream.vodCategories()
        return cats.map {
            StalkerApi.VodCategory(
                id = it.optString("category_id"),
                title = it.optString("category_name")
            )
        }
    }

    override suspend fun getVodList(
        vodType: String,
        categoryId: String
    ): List<StalkerApi.VodItem> {
        if (vodType == "series") return seriesEpisodes(categoryId)
        return xtream.vodStreams(categoryId).map {
            val id = it.optString("stream_id")
            StalkerApi.VodItem(
                id = id,
                name = it.optString("name"),
                cmd = xtream.movieUrl(id, it.optString("container_extension")),
                posterUrl = it.optString("stream_icon")
            )
        }
    }

    /**
     * Flattens series → episodes ("Name S01E02"). Series info calls run
     * in bounded-parallel batches so big categories stay responsive.
     */
    private suspend fun seriesEpisodes(categoryId: String): List<StalkerApi.VodItem> =
        coroutineScope {
            val series = xtream.seriesList(categoryId)
            val out = ArrayList<StalkerApi.VodItem>()
            // batches of 8 to avoid hammering the server
            for (chunk in series.chunked(8)) {
                val jobs = chunk.map { s ->
                    async {
                        try {
                            val sid = s.optString("series_id")
                            val info = xtream.seriesInfo(sid)
                            val infoObj = info.optJSONObject("info")
                            val sName = infoObj?.optString("name")
                                ?.ifBlank { s.optString("name") }
                                ?: s.optString("name")
                            val cover = infoObj?.optString("cover")
                                ?.ifBlank { s.optString("cover") }
                                ?: s.optString("cover")
                            val eps = ArrayList<StalkerApi.VodItem>()
                            val seasons = info.optJSONObject("episodes")
                            if (seasons != null) {
                                val keys = seasons.keys()
                                while (keys.hasNext()) {
                                    val season = keys.next()
                                    val arr = seasons.optJSONArray(season)
                                        ?: continue
                                    for (i in 0 until arr.length()) {
                                        val ep = arr.optJSONObject(i) ?: continue
                                        val eid = ep.optString("id")
                                        val epNum = ep.optString("episode_num")
                                        eps.add(
                                            StalkerApi.VodItem(
                                                id = eid,
                                                name = "$sName S${season.padStart(2, '0')}" +
                                                    "E${epNum.padStart(2, '0')}",
                                                cmd = xtream.episodeUrl(
                                                    eid,
                                                    ep.optString("container_extension")
                                                ),
                                                posterUrl = cover
                                            )
                                        )
                                    }
                                }
                            }
                            eps
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
                for (j in jobs) out.addAll(j.await())
            }
            out
        }

    override suspend fun getPortalInfo(): PortalInfo {
        var expire = "—"
        var user = ""
        try {
            val info = xtream.userInfo()
            val u = info.optJSONObject("user_info")
            user = u?.optString("username").orEmpty()
            val exp = u?.optLong("exp_date", 0L) ?: 0L
            if (exp > 0) {
                expire = SimpleDateFormat("dd MMM yyyy", Locale.US)
                    .format(Date(exp * 1000))
            }
        } catch (_: Exception) {
        }
        val count = try {
            liveAll().size
        } catch (_: Exception) {
            -1
        }
        return PortalInfo(
            providerName = provider.name,
            typeLabel = "Xtream",
            expireDate = expire,
            channelCount = count,
            detail = user.ifBlank { xtream.baseUrl }
        )
    }

    override fun buildDebugInfo(): String =
        "Type: Xtream\n" +
            "Server: ${xtream.baseUrl}\n" +
            "User: ${xtream.username}\n" +
            "Live cached: ${liveCache?.size ?: "no"}"

    override fun resetHandshakeCap() { /* no-op */
    }

    override fun resetConnection() {
        liveCache = null
        SourceManager.reset()
    }
}

// ---------------------------------------------------------------- manager

/**
 * v6.3: returns the [PlaylistSource] for the active provider,
 * rebuilding when the provider (or its type) changes.
 */
object SourceManager {

    private var cached: PlaylistSource? = null
    private var cachedKey: String = ""

    fun hasProvider(ctx: Context): Boolean =
        ProviderStore.getActive(ctx) != null

    fun get(ctx: Context): PlaylistSource {
        val p = ProviderStore.getActive(ctx)
        val key = listOf(
            p?.id.orEmpty(), p?.type.orEmpty(), p?.url.orEmpty(),
            p?.username.orEmpty(), p?.filePath.orEmpty()
        ).joinToString("|")
        if (cached == null || cachedKey != key) {
            cached = when (p?.type) {
                ProviderStore.TYPE_XTREAM -> XtreamSource(p)
                ProviderStore.TYPE_M3U_URL, ProviderStore.TYPE_M3U_FILE ->
                    M3uSource(ctx.applicationContext, p)
                else -> StalkerSource(StalkerSession.get(ctx))
            }
            cachedKey = key
        }
        return cached!!
    }

    fun reset() {
        cached = null
        cachedKey = ""
    }

    /**
     * v6.3: full cache wipe — memory + disk list caches — plus dropping
     * the cached source. Fixes "channels remain after provider deleted".
     */
    fun clearAllCaches(ctx: Context) {
        ListCache.invalidateAll()
        ListCache.clearDisk(ctx)
        reset()
    }

    /**
     * v6.3: provider-scoped cache key. The old portal_url|mac key broke
     * for M3U/Xtream providers (both empty) — every provider now keys
     * its caches by its own id.
     */
    fun cacheKey(ctx: Context): String {
        val p = ProviderStore.getActive(ctx)
        return "p_${p?.id ?: "none"}".hashCode().toString(16)
    }
}
