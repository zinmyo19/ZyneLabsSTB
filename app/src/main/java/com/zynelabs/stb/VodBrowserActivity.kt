package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zynelabs.stb.databinding.ActivityListBinding
import kotlinx.coroutines.launch

/**
 * Video Club / TV Series browser (STBEmu-style).
 * Level 1: VOD categories. Level 2: items inside a category.
 * TV Series mode filters categories whose title looks like series.
 */
class VodBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private var categoryId: String? = null
    private var categoryTitle: String? = null
    private var mode: String = MODE_MOVIES
    /** v4.9: which portal module serves this browser ("vod" or "series"). */
    private var vodType: String = "vod"

    private val adapter = RowAdapter { item ->
        if (categoryId == null) {
            // Drill into the category (v4.9: preserve the vod module).
            startActivity(
                Intent(this, VodBrowserActivity::class.java)
                    .putExtra(EXTRA_MODE, mode)
                    .putExtra(EXTRA_VOD_TYPE, vodType)
                    .putExtra(EXTRA_CATEGORY_ID, item.id)
                    .putExtra(EXTRA_CATEGORY_TITLE, item.title)
            )
        } else {
            openPlayer(item.id, item.title)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VOD_TYPE = "vod_type"
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_CATEGORY_TITLE = "category_title"
        const val MODE_MOVIES = "movies"
        const val MODE_SERIES = "series"
    }

    /** cmd cache: item id -> cmd (create_link needs the cmd, not the id). */
    private val cmdById = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MOVIES
        vodType = intent.getStringExtra(EXTRA_VOD_TYPE)?.ifBlank { "vod" } ?: "vod"
        categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        categoryTitle = intent.getStringExtra(EXTRA_CATEGORY_TITLE)

        binding.tvTitle.text = categoryTitle
            ?: getString(if (mode == MODE_SERIES) R.string.section_series else R.string.section_videoclub)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { load() }

        load()
    }

    /**
     * v4.9: cached-first loading (same pattern as HomeActivity) + posters.
     * Cache keys are per provider + vod module + category.
     */
    private fun load() {
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false

        // Show cached rows instantly when available.
        val cached = peekCachedRows()
        if (!cached.isNullOrEmpty()) {
            showRows(cached)
            binding.progressBar.isVisible = false
            if (isCacheFresh()) return
        } else {
            binding.progressBar.isVisible = true
        }

        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@VodBrowserActivity)
                val rows: List<RowItem>
                val catId = categoryId
                if (catId == null) {
                    val cats = fetchCategories(api)
                    cacheCats(cats)
                    rows = cats.map { RowItem(it.id, it.title) }
                } else {
                    val items = fetchItems(api, catId)
                    cacheItems(catId, items)
                    cmdById.clear()
                    for (it in items) cmdById[it.id] = it.cmd
                    rows = items.map { RowItem(it.id, it.name, posterUrl = it.posterUrl) }
                }
                showRows(rows)
                if (rows.isEmpty()) {
                    binding.tvError.text = getString(R.string.error_no_vod)
                    binding.tvError.isVisible = true
                }
            } catch (e: Exception) {
                // v4.9: keep cached rows on screen instead of an error
                // when we already show something usable.
                if (adapter.itemCount == 0) {
                    binding.tvError.text =
                        getString(R.string.error_load_failed, e.message.orEmpty())
                    binding.tvError.isVisible = true
                    binding.btnRetry.isVisible = true
                    binding.btnRetry.requestFocus()
                }
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun showRows(rows: List<RowItem>) {
        adapter.submitList(rows) {
            if (rows.isNotEmpty()) binding.recyclerView.requestFocus()
        }
    }

    /** v4.9: series falls back to title-filtered VOD when the series module is empty. */
    private suspend fun fetchCategories(
        api: StalkerApi
    ): List<StalkerApi.VodCategory> {
        var cats = api.getVodCategories(vodType)
        if (cats.isEmpty() && vodType == "series") {
            val filtered = api.getVodCategories("vod").filter { looksLikeSeries(it.title) }
            if (filtered.isNotEmpty()) {
                vodType = "vod"
                cats = filtered
            }
        } else if (mode == MODE_SERIES && vodType == "vod") {
            // Launched directly without a vodType: keep the old title filter.
            val filtered = cats.filter { looksLikeSeries(it.title) }
            if (filtered.isNotEmpty()) cats = filtered
        }
        return cats
    }

    private suspend fun fetchItems(
        api: StalkerApi,
        catId: String
    ): List<StalkerApi.VodItem> = api.getVodList(vodType, catId)

    // ------------------------------------------------------------ caching

    private fun cacheKey(suffix: String): String {
        val provider = (Prefs.getPortalUrl(this) + "|" + Prefs.getMac(this)).hashCode()
            .toString(16)
        return "vod_${provider}_${vodType}_$suffix"
    }

    private fun peekCachedRows(): List<RowItem>? {
        return try {
            val key = cacheKey(categoryId ?: "cats")
            val json = ListCache.getStale(this, key) ?: return null
            if (categoryId == null) {
                ListCache.vodCatsFromJson(json).map { RowItem(it.id, it.title) }
            } else {
                val items = ListCache.vodItemsFromJson(json)
                // Rebuild the cmd map so cached items can play.
                cmdById.clear()
                for (it in items) cmdById[it.id] = it.cmd
                items.map { RowItem(it.id, it.name, posterUrl = it.posterUrl) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isCacheFresh(): Boolean {
        val key = cacheKey(categoryId ?: "cats")
        return ListCache.getFresh(this, key) != null
    }

    private fun cacheCats(cats: List<StalkerApi.VodCategory>) {
        try {
            ListCache.put(this, cacheKey("cats"), ListCache.vodCatsToJson(cats), persist = true)
        } catch (e: Exception) { /* best-effort */
        }
    }

    private fun cacheItems(catId: String, items: List<StalkerApi.VodItem>) {
        try {
            ListCache.put(
                this, cacheKey(catId), ListCache.vodItemsToJson(items), persist = false
            )
        } catch (e: Exception) { /* best-effort */
        }
    }

    private fun looksLikeSeries(title: String): Boolean {
        val t = title.lowercase()
        return "series" in t || "serial" in t || "show" in t ||
            "season" in t || "episode" in t || "tv" in t
    }

    private fun openPlayer(itemId: String, name: String) {
        val cmd = cmdById[itemId] ?: return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CMD, cmd)
                .putExtra(PlayerActivity.EXTRA_NAME, name)
                .putExtra(PlayerActivity.EXTRA_CMD_TYPE, PlayerActivity.TYPE_VOD)
        )
    }
}
