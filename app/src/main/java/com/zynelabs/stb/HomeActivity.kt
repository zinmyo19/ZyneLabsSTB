package com.zynelabs.stb

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityHomeBinding
import com.zynelabs.stb.databinding.ItemChannelCardBinding
import com.zynelabs.stb.databinding.ItemHomeRowBinding
import com.zynelabs.stb.databinding.ItemNavBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * v4.8 unified leanback home (approved template #2) for TV + phone.
 * TV (UiModeManager = television): left sidebar nav, fully D-pad navigable.
 * Phone: same card UI with a bottom navigation bar, touch-friendly.
 *
 * Sections: Live TV (genre rows of channel cards), Movies / Series
 * (VOD category cards), Guide, Settings. Backend (StalkerApi v4.7)
 * is untouched.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var isTv = false
    private var section = Section.LIVE_TV
    private var loadJob: Job? = null
    private var featuredChannel: Channel? = null

    private val rowsAdapter = RowsAdapter()
    /** Section -> its nav view, for the active-section highlight. */
    private val navSectionViews = mutableMapOf<Section, View>()

    private enum class Section { LIVE_TV, MOVIES, SERIES }

    /** One content row: title + cards. */
    private data class HomeRow(val title: String, val cards: List<CardItem>)

    /** Card content: either a live channel or a VOD category. */
    private sealed class CardItem {
        data class Live(val channel: Channel) : CardItem()
        data class Vod(
            val category: StalkerApi.VodCategory,
            val mode: String,
            val vodType: String
        ) : CardItem()
    }

    private data class NavEntry(
        val icon: String,
        val labelRes: Int,
        val section: Section?,
        val onPick: () -> Unit
    )

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isTv = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        binding.sidebar.isVisible = isTv
        binding.bottomNav.isVisible = !isTv
        binding.tvPortalInfo.text = getString(R.string.home_portal_info, Prefs.getMac(this))

        binding.rowsRecycler.layoutManager = LinearLayoutManager(this)
        binding.rowsRecycler.adapter = rowsAdapter

        buildNav()
        binding.btnRetry.setOnClickListener { loadSection(forceRefresh = true) }
        binding.banner.setOnClickListener { playFeatured() }

        selectSection(Section.LIVE_TV)
    }

    // ------------------------------------------------------------------ nav

    private fun buildNav() {
        val entries = listOf(
            NavEntry("\uD83D\uDCFA", R.string.nav_live_tv, Section.LIVE_TV) {
                selectSection(Section.LIVE_TV)
            },
            NavEntry("\uD83C\uDFAC", R.string.nav_movies, Section.MOVIES) {
                selectSection(Section.MOVIES)
            },
            NavEntry("\uD83D\uDCFC", R.string.nav_series, Section.SERIES) {
                selectSection(Section.SERIES)
            },
            NavEntry("\uD83D\uDCC5", R.string.nav_guide, null) {
                startActivity(Intent(this, GuideActivity::class.java))
            },
            NavEntry("⚙️", R.string.nav_settings, null) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        val inflater = LayoutInflater.from(this)
        val container: ViewGroup = if (isTv) binding.navContainer else binding.bottomNav
        for (e in entries) {
            val b = ItemNavBinding.inflate(inflater, container, false)
            b.tvNavIcon.text = e.icon
            b.tvNavLabel.text = getString(e.labelRes)
            if (!isTv) {
                // Bottom bar: five equal buttons, icon above label.
                b.root.orientation = LinearLayout.VERTICAL
                b.root.gravity = android.view.Gravity.CENTER
                b.root.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                )
                b.tvNavLabel.textSize = 11f
                (b.tvNavIcon.layoutParams as LinearLayout.LayoutParams).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                (b.tvNavLabel.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = 0
                }
            }
            b.root.setOnClickListener { e.onPick() }
            container.addView(b.root)
            e.section?.let { navSectionViews[it] = b.root }
        }
    }

    private fun selectSection(s: Section) {
        // v4.9: re-tapping the active section forces a refresh.
        val force = (s == section && rowsAdapter.itemCount > 0)
        section = s
        highlightNav()
        loadSection(force)
    }

    private fun highlightNav() {
        val teal = ContextCompat.getColor(this, R.color.teal)
        val white = ContextCompat.getColor(this, R.color.text_primary)
        for ((sec, view) in navSectionViews) {
            view.findViewById<android.widget.TextView>(R.id.tvNavLabel)
                ?.setTextColor(if (sec == section) teal else white)
        }
    }

    // ------------------------------------------------------------------ loading

    /**
     * v4.9: cached-first loading. Shows cached rows instantly (even stale)
     * so back-navigation from the player never spins; refreshes silently
     * in the background when the cache is stale (>5 min) or [forceRefresh].
     * The full-screen spinner only appears when there is nothing cached.
     */
    private fun loadSection(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        binding.errorBox.isVisible = false

        val cachedRows = peekCachedRows()
        if (!cachedRows.isNullOrEmpty()) {
            rowsAdapter.submit(cachedRows)
            binding.rowsRecycler.isVisible = true
            updateBanner(cachedRows)
            binding.progressBar.isVisible = false
            if (!forceRefresh && isCacheFresh()) return
            // Stale or forced: silent background refresh, no spinner.
        } else {
            binding.progressBar.isVisible = true
            binding.rowsRecycler.isVisible = false
        }

        loadJob = lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@HomeActivity)
                val rows: List<HomeRow> = when (section) {
                    Section.LIVE_TV -> loadLiveRows(api)
                    Section.MOVIES -> loadVodRows(
                        api, VodBrowserActivity.MODE_MOVIES, getString(R.string.nav_movies)
                    )
                    Section.SERIES -> loadVodRows(
                        api, VodBrowserActivity.MODE_SERIES, getString(R.string.nav_series)
                    )
                }
                cacheRows(rows)
                rowsAdapter.submit(rows)
                binding.rowsRecycler.isVisible = true
                updateBanner(rows)
                if (rows.isEmpty()) {
                    showError(getString(R.string.error_no_content))
                } else if (isTv) {
                    binding.banner.requestFocus()
                }
            } catch (e: Exception) {
                // v4.9: if we already show cached rows, don't replace them
                // with an error — the data on screen is still usable.
                if (rowsAdapter.itemCount == 0) {
                    showError(getString(R.string.error_load_failed, e.message.orEmpty()))
                }
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    /** Cache key is per provider (portal+MAC) and section. */
    private fun sectionCacheKey(): String {
        val provider = (Prefs.getPortalUrl(this) + "|" + Prefs.getMac(this)).hashCode()
            .toString(16)
        val sec = when (section) {
            Section.LIVE_TV -> "live"
            Section.MOVIES -> "movies"
            Section.SERIES -> "series"
        }
        return "rows_${provider}_$sec"
    }

    private fun peekCachedRows(): List<HomeRow>? {
        return try {
            ListCache.getStale(this, sectionCacheKey())?.let { rowsFromJson(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun isCacheFresh(): Boolean =
        ListCache.getFresh(this, sectionCacheKey()) != null

    private fun cacheRows(rows: List<HomeRow>) {
        try {
            ListCache.put(this, sectionCacheKey(), rowsToJson(rows), persist = true)
        } catch (e: Exception) {
            // Caching is best-effort; never break the UI.
        }
    }

    private fun rowsToJson(rows: List<HomeRow>): String {
        val arr = org.json.JSONArray()
        for (row in rows) {
            val cards = org.json.JSONArray()
            for (card in row.cards) {
                when (card) {
                    is CardItem.Live -> cards.put(
                        org.json.JSONObject()
                            .put("t", "live")
                            .put("ch", ListCache.channelToJson(card.channel))
                    )
                    is CardItem.Vod -> cards.put(
                        org.json.JSONObject()
                            .put("t", "vod")
                            .put("cat", ListCache.vodCategoryToJson(card.category))
                            .put("mode", card.mode)
                            .put("vodType", card.vodType)
                    )
                }
            }
            arr.put(org.json.JSONObject().put("title", row.title).put("cards", cards))
        }
        return arr.toString()
    }

    private fun rowsFromJson(json: String): List<HomeRow> {
        val out = ArrayList<HomeRow>()
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val ro = arr.getJSONObject(i)
            val cards = ArrayList<CardItem>()
            val ca = ro.getJSONArray("cards")
            for (j in 0 until ca.length()) {
                val co = ca.getJSONObject(j)
                when (co.optString("t")) {
                    "live" -> cards.add(
                        CardItem.Live(ListCache.channelFromJson(co.getJSONObject("ch")))
                    )
                    "vod" -> cards.add(
                        CardItem.Vod(
                            ListCache.vodCategoryFromJson(co.getJSONObject("cat")),
                            co.optString("mode"),
                            co.optString("vodType", "vod")
                        )
                    )
                }
            }
            out.add(HomeRow(ro.optString("title"), cards))
        }
        return out
    }

    /** Genre rows, one page (14) of channels each, fetched in parallel. */
    private suspend fun loadLiveRows(api: StalkerApi): List<HomeRow> = coroutineScope {
        val genres = api.getGenres()
        if (genres.isEmpty()) {
            val all = api.getChannelsPaginated(maxPages = 2)
            return@coroutineScope listOf(
                HomeRow(getString(R.string.all_channels), all.map { CardItem.Live(it) })
            )
        }
        genres.take(8).map { g ->
            async {
                val chans = api.getChannelsPaginated(genreId = g.id, maxPages = 1)
                g.title to chans
            }
        }.awaitAll()
            .filter { it.second.isNotEmpty() }
            .map { (title, chans) -> HomeRow(title, chans.map { CardItem.Live(it) }) }
    }

    /**
     * v4.9: series tries the portal's "series" module first, falling back
     * to title-filtered VOD categories (many panels keep series under VOD).
     */
    private suspend fun loadVodRows(
        api: StalkerApi,
        mode: String,
        title: String
    ): List<HomeRow> {
        // v4.9: vodType tracks the module that ACTUALLY served the
        // categories, so the drill-down browser queries the same one.
        var vodType = "vod"
        val cats: List<StalkerApi.VodCategory> = if (mode == VodBrowserActivity.MODE_SERIES) {
            val series = api.getVodCategories("series")
            if (series.isNotEmpty()) {
                vodType = "series"
                series
            } else {
                api.getVodCategories("vod").filter { looksLikeSeries(it.title) }
            }
        } else {
            api.getVodCategories("vod")
        }
        if (cats.isEmpty()) return emptyList()
        return listOf(
            HomeRow(
                title,
                cats.map { CardItem.Vod(it, mode, vodType) }
            )
        )
    }

    private fun looksLikeSeries(title: String): Boolean {
        val t = title.lowercase()
        return "series" in t || "serial" in t || "show" in t ||
            "season" in t || "episode" in t
    }

    private fun updateBanner(rows: List<HomeRow>) {
        val firstLive = rows.asSequence()
            .flatMap { it.cards.asSequence() }
            .filterIsInstance<CardItem.Live>()
            .firstOrNull()
        featuredChannel = firstLive?.channel
        if (firstLive != null) {
            binding.tvBannerTitle.text = firstLive.channel.name.ifBlank {
                getString(R.string.unknown_channel)
            }
            binding.tvBannerSubtitle.text = getString(
                R.string.subtitle_live_tv,
                rows.sumOf { r -> r.cards.size }
            )
        } else {
            binding.tvBannerTitle.text = getString(
                if (section == Section.MOVIES) R.string.nav_movies else R.string.nav_series
            )
            binding.tvBannerSubtitle.text = getString(
                R.string.subtitle_vod,
                rows.sumOf { r -> r.cards.size }
            )
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.errorBox.isVisible = true
        binding.btnRetry.requestFocus()
    }

    // ------------------------------------------------------------------ playback

    private fun playFeatured() {
        val ch = featuredChannel ?: return
        openPlayer(ch)
    }

    private fun openPlayer(channel: Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CMD, channel.cmd)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_CMD_TYPE, PlayerActivity.TYPE_ITV)
        )
    }

    private fun onCardClick(card: CardItem) {
        when (card) {
            is CardItem.Live -> openPlayer(card.channel)
            is CardItem.Vod -> startActivity(
                Intent(this, VodBrowserActivity::class.java)
                    .putExtra(VodBrowserActivity.EXTRA_MODE, card.mode)
                    .putExtra(VodBrowserActivity.EXTRA_VOD_TYPE, card.vodType)
                    .putExtra(VodBrowserActivity.EXTRA_CATEGORY_ID, card.category.id)
                    .putExtra(VodBrowserActivity.EXTRA_CATEGORY_TITLE, card.category.title)
            )
        }
    }

    // ------------------------------------------------------------------ adapters

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.RowVH>() {
        private var rows: List<HomeRow> = emptyList()

        fun submit(newRows: List<HomeRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        inner class RowVH(val b: ItemHomeRowBinding) : RecyclerView.ViewHolder(b.root) {
            val cardsAdapter = CardsAdapter()

            init {
                b.rowRecycler.layoutManager = LinearLayoutManager(
                    b.root.context, LinearLayoutManager.HORIZONTAL, false
                )
                b.rowRecycler.adapter = cardsAdapter
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val b = ItemHomeRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return RowVH(b)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val row = rows[position]
            holder.b.tvRowTitle.text = row.title
            holder.cardsAdapter.submit(row.cards)
        }

        override fun getItemCount(): Int = rows.size
    }

    private inner class CardsAdapter : RecyclerView.Adapter<CardsAdapter.CardVH>() {
        private var cards: List<CardItem> = emptyList()

        fun submit(newCards: List<CardItem>) {
            cards = newCards
            notifyDataSetChanged()
        }

        inner class CardVH(val b: ItemChannelCardBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onCardClick(cards[pos])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val b = ItemChannelCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return CardVH(b)
        }

        override fun onBindViewHolder(holder: CardVH, position: Int) {
            when (val c = cards[position]) {
                is CardItem.Live -> {
                    val name = c.channel.name.ifBlank {
                        holder.itemView.context.getString(R.string.unknown_channel)
                    }
                    holder.b.tvName.text = name
                    // v4.9: channel logo poster; letter tile is the fallback.
                    holder.b.tvLogoLetter.visibility = View.VISIBLE
                    holder.b.tvLogoLetter.text =
                        name.trim().firstOrNull()?.uppercase() ?: "?"
                    holder.b.ivPoster.visibility = View.GONE
                    ImageLoader.load(
                        c.channel.logo.ifBlank { null },
                        holder.b.ivPoster,
                        onFail = {
                            holder.b.ivPoster.visibility = View.GONE
                            holder.b.tvLogoLetter.visibility = View.VISIBLE
                        },
                        onSuccess = {
                            holder.b.tvLogoLetter.visibility = View.GONE
                        }
                    )
                }
                is CardItem.Vod -> {
                    holder.b.tvName.text = c.category.title
                    holder.b.tvLogoLetter.visibility = View.VISIBLE
                    holder.b.tvLogoLetter.text = "\uD83D\uDCC1"
                    holder.b.ivPoster.visibility = View.GONE
                    ImageLoader.cancel(holder.b.ivPoster)
                }
            }
        }

        override fun onViewRecycled(holder: CardVH) {
            super.onViewRecycled(holder)
            ImageLoader.cancel(holder.b.ivPoster)
        }

        override fun getItemCount(): Int = cards.size
    }
}
