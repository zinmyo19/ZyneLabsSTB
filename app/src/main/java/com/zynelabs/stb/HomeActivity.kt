package com.zynelabs.stb

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.1 home rebuilt to Dominic's mockup EXACTLY:
 * - Top header: cube logo + "ZyneLabs STB" wordmark | date/time + ZONE 1 • User + profile (TV)
 * - Left sidebar "NAVIGATION" with teal-pill active row (TV)
 * - Featured banner: NOW LIVE + LIVE badges, big title, meta line, teal progress bar
 * - "Popular Channels" + "See all →" with photo-thumbnail cards, glowing teal focus ring
 * - Bottom D-pad hint bar (TV)
 * Phone: same visual language, banner + popular + rows + bottom nav.
 * Backend (StalkerApi) untouched. Caching logic kept from v4.9.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var isTv = false
    private var section = Section.LIVE_TV
    private var loadJob: Job? = null
    private var featuredChannel: Channel? = null

    private val rowsAdapter = RowsAdapter()
    private val popularAdapter = CardsAdapter()
    private val navSectionViews = mutableMapOf<Section, View>()

    private enum class Section { LIVE_TV, MOVIES, SERIES }

    private data class HomeRow(
        val title: String,
        val cards: List<CardItem>,
        /** v5.3: genre id for "see all in genre" → ChannelListActivity. */
        val genreId: String? = null
    )

    private sealed class CardItem {
        data class Live(val channel: Channel) : CardItem()
        data class Vod(
            val category: StalkerApi.VodCategory,
            val mode: String,
            val vodType: String
        ) : CardItem()
    }

    private data class NavEntry(
        val iconRes: Int,
        val labelRes: Int,
        val section: Section?,
        val onPick: () -> Unit
    )

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v6.3.14: provider sanity — if the saved active id points to a
        // provider that no longer exists (deleted/corrupted DB), clear it
        // so the app prompts for a valid provider instead of silently
        // using the wrong source type (e.g. Stalker for an M3U provider).
        val activeId = ProviderStore.getActiveId(this)
        if (activeId.isNotBlank() && ProviderStore.get(this, activeId) == null) {
            Log.w(
                "HomeActivity",
                "stale active provider id=$activeId — clearing"
            )
            ProviderStore.setActive(this, "")
            SourceManager.reset()
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isTv = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        binding.sidebar.isVisible = isTv
        binding.bottomNav.isVisible = !isTv
        binding.headerUserBox.isVisible = isTv
        binding.headerProfile.isVisible = isTv
        binding.tvDpadHints.isVisible = isTv
        binding.tvPortalInfo.text = providerInfoLine()

        // v5.1: header date like mockup ("Thu, 12 Dec • 20:17").
        binding.tvHeaderDate.text = SimpleDateFormat(
            "EEE, d MMM • HH:mm", Locale.getDefault()
        ).format(Date())

        binding.rowsRecycler.layoutManager = LinearLayoutManager(this)
        binding.rowsRecycler.adapter = rowsAdapter
        binding.popularRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.popularRecycler.adapter = popularAdapter

        buildNav()
        binding.btnRetry.setOnClickListener { loadSection(forceRefresh = true) }
        binding.banner.setOnClickListener { playFeatured() }
        // v5.3: "See all" opens the FULL channel list (all pages, no cap).
        binding.tvSeeAll.setOnClickListener { openFullList(null, null) }
        // v5.6: provider switcher chip in header.
        binding.btnProvider.setOnClickListener { showProviderDialog() }
        refreshProviderChip()

        selectSection(Section.LIVE_TV)
    }

    /** v5.6: refresh provider chip when returning from Add Provider. */
    override fun onResume() {
        super.onResume()
        refreshProviderChip()
    }

    /** v5.6: show the active provider host on the header chip. */
    private fun refreshProviderChip() {
        val p = ProviderStore.getActive(this)
        binding.btnProvider.text = p?.let { ProviderStore.displayName(it) } ?: "—"
    }

    /**
     * v5.6: provider dialog — switch active provider, add new, delete.
     * D-pad navigable list. Switching reloads home on the new portal.
     */
    private fun showProviderDialog() {
        val providers = ProviderStore.list(this).toMutableList()
        val activeId = ProviderStore.getActiveId(this)
        val items = providers.map {
            (if (it.id == activeId) "● " else "○ ") + ProviderStore.displayName(it)
        }.toMutableList()
        items.add("+ Add provider…")
        android.app.AlertDialog.Builder(this)
            .setTitle("Provider")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < providers.size) {
                    val picked = providers[which]
                    if (picked.id != activeId) {
                        ProviderStore.setActive(this, picked.id)
                        ProviderStore.applyActive(this)
                        SourceManager.clearAllCaches(this)
                        refreshProviderChip()
                        binding.tvPortalInfo.text = providerInfoLine()
                        loadSection(forceRefresh = true)
                    }
                } else {
                    startActivity(
                        android.content.Intent(this, AddProviderActivity::class.java)
                    )
                }
            }
            .setNeutralButton("Delete…") { _, _ -> showDeleteProviderDialog(providers) }
            .show()
    }

    /** v5.6: delete-provider picker. */
    private fun showDeleteProviderDialog(providers: List<Provider>) {
        if (providers.isEmpty()) return
        val items = providers.map { ProviderStore.displayName(it) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete provider")
            .setItems(items) { _, which ->
                val doomed = providers[which]
                ProviderStore.delete(this, doomed.id)
                if (ProviderStore.getActiveId(this) == doomed.id) {
                    val next = ProviderStore.list(this).firstOrNull()
                    if (next != null) {
                        ProviderStore.setActive(this, next.id)
                    }
                }
                ProviderStore.applyActive(this)
                ListCache.invalidateAll()
                refreshProviderChip()
                loadSection(forceRefresh = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** v5.3: open ChannelListActivity for all channels or one genre. */
    private fun openFullList(genreId: String?, genreTitle: String?) {
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_GENRE_ID, genreId)
                .putExtra(ChannelListActivity.EXTRA_GENRE_TITLE, genreTitle)
        )
    }

    // ------------------------------------------------------------------ nav

    private fun buildNav() {
        val entries = listOf(
            NavEntry(R.drawable.ic_nav_tv, R.string.nav_live_tv, Section.LIVE_TV) {
                selectSection(Section.LIVE_TV)
            },
            NavEntry(R.drawable.ic_nav_movies, R.string.nav_movies, Section.MOVIES) {
                selectSection(Section.MOVIES)
            },
            NavEntry(R.drawable.ic_nav_series, R.string.nav_series, Section.SERIES) {
                selectSection(Section.SERIES)
            },
            NavEntry(R.drawable.ic_nav_categories, R.string.nav_categories, null) {
                startActivity(Intent(this, CategoriesActivity::class.java))
            },
            NavEntry(R.drawable.ic_nav_guide, R.string.nav_guide, null) {
                startActivity(Intent(this, GuideActivity::class.java))
            },
            NavEntry(R.drawable.ic_nav_settings, R.string.nav_settings, null) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        val inflater = LayoutInflater.from(this)
        val container: ViewGroup = if (isTv) binding.navContainer else binding.bottomNav
        for (e in entries) {
            val b = ItemNavBinding.inflate(inflater, container, false)
            b.ivNavIcon.setImageResource(e.iconRes)
            b.tvNavLabel.text = getString(e.labelRes)
            if (!isTv) {
                b.root.orientation = LinearLayout.VERTICAL
                b.root.gravity = android.view.Gravity.CENTER
                b.root.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                )
                b.tvNavLabel.textSize = 11f
                (b.tvNavLabel.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = 0
                }
            }
            b.root.setOnClickListener { e.onPick() }
            container.addView(b.root)
            e.section?.let { navSectionViews[it] = b.root }
        }
    }

    /** v5.2: active nav row = gold pill (Imperial), inactive = transparent. */
    private fun selectSection(s: Section) {
        val force = (s == section && rowsAdapter.itemCount > 0)
        section = s
        highlightNav()
        loadSection(force)
    }

    private fun highlightNav() {
        val gold = ContextCompat.getColor(this, R.color.gold)
        val white = ContextCompat.getColor(this, R.color.text_primary)
        val darkText = ContextCompat.getColor(this, R.color.background)
        val pill = ContextCompat.getDrawable(this, R.drawable.nav_item_active)
        val plain = ContextCompat.getDrawable(this, R.drawable.nav_item_selector)
        for ((sec, view) in navSectionViews) {
            val active = sec == section
            view.background = (if (active) pill else plain)?.constantState?.newDrawable()
            view.findViewById<android.widget.TextView>(R.id.tvNavLabel)
                ?.setTextColor(if (active) darkText else white)
            // v5.2: dim the icon on inactive rows (gold icons stay gold when active)
            view.findViewById<android.widget.ImageView>(R.id.ivNavIcon)
                ?.setAlpha(if (active) 1.0f else 0.55f)
        }
    }

    // ------------------------------------------------------------------ loading

    /** v6.3: header line shows the active provider (was: portal MAC). */
    private fun providerInfoLine(): String {
        val p = ProviderStore.getActive(this)
        return if (p == null) {
            getString(R.string.no_provider)
        } else {
            "${ProviderStore.displayName(p)} • ${ProviderStore.typeLabel(p)}"
        }
    }

    private fun loadSection(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        binding.errorBox.isVisible = false

        // v6.3: never show another provider's (or no provider's) channels.
        if (!SourceManager.hasProvider(this)) {
            binding.progressBar.isVisible = false
            showError(getString(R.string.add_provider_hint))
            return
        }

        val cachedRows = peekCachedRows()
        if (!cachedRows.isNullOrEmpty()) {
            onRowsLoaded(cachedRows, fromCache = true)
            if (!forceRefresh && isCacheFresh()) return
        } else {
            binding.progressBar.isVisible = true
            binding.rowsRecycler.isVisible = false
        }

        loadJob = lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@HomeActivity)
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
                onRowsLoaded(rows, fromCache = false)
                if (rows.isEmpty()) {
                    showError(getString(R.string.error_no_content))
                } else if (isTv) {
                    binding.banner.requestFocus()
                }
            } catch (e: Exception) {
                if (rowsAdapter.itemCount == 0) {
                    showError(getString(R.string.error_load_failed, e.message.orEmpty()))
                }
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun onRowsLoaded(rows: List<HomeRow>, fromCache: Boolean) {
        rowsAdapter.submit(rows)
        binding.rowsRecycler.isVisible = true
        binding.progressBar.isVisible = false
        updateBanner(rows)
        updatePopular(rows)
    }

    /** v5.1: "Popular Channels" = first 12 cards of the loaded section. */
    private fun updatePopular(rows: List<HomeRow>) {
        val cards = rows.flatMap { it.cards }.take(12)
        val has = cards.isNotEmpty()
        binding.popularHeader.isVisible = has
        binding.popularRecycler.isVisible = has
        if (has) popularAdapter.submit(cards)
    }

    private fun sectionCacheKey(): String {
        // v6.3: provider-scoped key (was portal_url|mac — broke for M3U/Xtream).
        val provider = SourceManager.cacheKey(this)
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

    private suspend fun loadLiveRows(api: PlaylistSource): List<HomeRow> = coroutineScope {
        val genres = api.getGenres()
        if (genres.isEmpty()) {
            val all = api.getChannelsPaginated(maxPages = 2)
            return@coroutineScope listOf(
                HomeRow(getString(R.string.all_channels), all.map { CardItem.Live(it) })
            )
        }
        // v5.3: home previews stay fast (1 page/genre); tapping a row title
        // or "See all" opens the FULL paginated list.
        genres.take(8).map { g ->
            async {
                val chans = api.getChannelsPaginated(genreId = g.id, maxPages = 1)
                Triple(g.id, g.title, chans)
            }
        }.awaitAll()
            .filter { it.third.isNotEmpty() }
            .map { (id, title, chans) ->
                HomeRow(title, chans.map { CardItem.Live(it) }, genreId = id)
            }
    }

    private suspend fun loadVodRows(
        api: PlaylistSource,
        mode: String,
        title: String
    ): List<HomeRow> {
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
            HomeRow(title, cats.map { CardItem.Vod(it, mode, vodType) })
        )
    }

    private fun looksLikeSeries(title: String): Boolean {
        val t = title.lowercase()
        return "series" in t || "serial" in t || "show" in t ||
            "season" in t || "episode" in t
    }

    /** v5.1: banner per mockup — big title, meta line, teal progress. */
    private fun updateBanner(rows: List<HomeRow>) {
        val total = rows.sumOf { it.cards.size }
        val firstLive = rows.asSequence()
            .flatMap { it.cards.asSequence() }
            .filterIsInstance<CardItem.Live>()
            .firstOrNull()
        featuredChannel = firstLive?.channel
        if (firstLive != null) {
            binding.tvBannerTitle.text = firstLive.channel.name.ifBlank {
                getString(R.string.unknown_channel)
            }
            binding.tvBannerSubtitle.text = getString(R.string.subtitle_live_tv, total)
            binding.tvBannerMeta.text = getString(R.string.banner_meta_live, total)
            // v5.6: replace the partial preview count with the REAL total
            // (one cheap total_items read) once it arrives.
            lifecycleScope.launch {
                try {
                    val api = SourceManager.get(this@HomeActivity)
                    val real = api.getTotalChannelCount()
                    if (real > 0) {
                        binding.tvBannerSubtitle.text =
                            getString(R.string.subtitle_live_tv, real)
                        binding.tvBannerMeta.text =
                            getString(R.string.banner_meta_live, real)
                    }
                } catch (_: Exception) {
                }
            }
        } else {
            binding.tvBannerTitle.text = getString(
                if (section == Section.MOVIES) R.string.nav_movies else R.string.nav_series
            )
            binding.tvBannerSubtitle.text = getString(R.string.subtitle_vod, total)
            binding.tvBannerMeta.text = getString(R.string.banner_meta_vod, total)
        }
        binding.tvBannerTime.text = ""
        binding.bannerProgress.progress = 40
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
                .putExtra(PlayerActivity.EXTRA_GENRE_ID, channel.genreId)
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
            // v5.3: tapping a genre row title opens its FULL channel list.
            holder.b.tvRowTitle.setOnClickListener {
                if (row.genreId != null) openFullList(row.genreId, row.title)
            }
            holder.b.tvRowTitle.isFocusable = row.genreId != null
            // v6.2: "See all" after every row (D-pad focusable) — opens
            // the full ChannelListActivity for this genre (or All).
            holder.b.tvRowSeeAll.setOnClickListener {
                openFullList(row.genreId, row.title)
            }
            // See all is always focusable (works for All + every genre).
            holder.b.tvRowSeeAll.isFocusable = true
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
                    holder.b.tvLogoLetter.visibility = View.VISIBLE
                    holder.b.tvLogoLetter.text =
                        name.trim().firstOrNull()?.uppercase() ?: "?"
                    holder.b.ivPoster.visibility = View.GONE
                    // v6.3: quality badge on channel cards; hidden if none.
                    val q = c.channel.quality
                    holder.b.tvQuality.text = q
                    holder.b.tvQuality.isVisible = q.isNotEmpty()
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
                    // v6.3: category cards are not channels — badge stays gone.
                    holder.b.tvQuality.isVisible = false
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
