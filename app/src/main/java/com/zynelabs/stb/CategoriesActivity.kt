package com.zynelabs.stb

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityCategoriesBinding
import com.zynelabs.stb.databinding.ItemChannelBinding
import com.zynelabs.stb.databinding.ItemChannelCardBinding
import kotlinx.coroutines.launch

/**
 * v5.4: Categories — browse EVERY portal genre with its FULL channel list.
 * Grid of genre cards; tap → ChannelListActivity (full pagination).
 * TV: D-pad navigable grid. Phone: touch grid.
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val adapter = GenreAdapter { genre -> openGenre(genre) }
    private var isTv = false
    private var catView: String = Prefs.CAT_VIEW_GRID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isTv = (resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        catView = Prefs.getCatView(this)
        applyCatView()
        updateToggleLabel()
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { loadGenres() }
        binding.btnBack.setOnClickListener { finish() }
        // v6.3: grid/list view toggle.
        binding.btnViewToggle.setOnClickListener { toggleCatView() }

        loadGenres()
    }

    /** Applies the saved view mode: grid (span 3 phone / 5 TV) or list. */
    private fun applyCatView() {
        binding.recyclerView.layoutManager =
            if (catView == Prefs.CAT_VIEW_LIST) {
                LinearLayoutManager(this)
            } else {
                GridLayoutManager(this, if (isTv) 5 else 3)
            }
        adapter.viewMode = catView
        adapter.notifyDataSetChanged()
    }

    private fun toggleCatView() {
        catView = if (catView == Prefs.CAT_VIEW_LIST) {
            Prefs.CAT_VIEW_GRID
        } else {
            Prefs.CAT_VIEW_LIST
        }
        Prefs.setCatView(this, catView)
        updateToggleLabel()
        applyCatView()
        binding.recyclerView.requestFocus()
    }

    private fun updateToggleLabel() {
        binding.btnViewToggle.text = getString(
            if (catView == Prefs.CAT_VIEW_LIST) R.string.cat_view_list
            else R.string.cat_view_grid
        )
    }

    private fun loadGenres() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false
        // v6.3: don't show another provider's (or no provider's) categories.
        if (!SourceManager.hasProvider(this)) {
            binding.progressBar.isVisible = false
            binding.tvError.text = getString(R.string.add_provider_hint)
            binding.tvError.isVisible = true
            return
        }
        lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@CategoriesActivity)
                val genres = api.getGenres()
                if (genres.isEmpty()) {
                    // v6.3.14: diagnostics — a stale/unknown-type provider
                    // silently falling back to StalkerSource shows up here
                    // as "No categories found". Log the provider so the
                    // mismatch is visible in on-device logs.
                    val ap = ProviderStore.getActive(this@CategoriesActivity)
                    Log.w(
                        "Categories",
                        "empty genres: provider id=${ap?.id} " +
                            "type='${ap?.type}' name='${ap?.name}' " +
                            "url='${ap?.url}' filePath='${ap?.filePath}' " +
                            "source=${api.javaClass.simpleName}"
                    )
                    binding.tvError.text = getString(R.string.error_no_categories)
                    binding.tvError.isVisible = true
                    binding.btnRetry.isVisible = true
                } else {
                    adapter.submitList(genres)
                    binding.recyclerView.requestFocus()
                }
            } catch (e: Exception) {
                binding.tvError.text =
                    getString(R.string.error_load_failed, e.message.orEmpty())
                binding.tvError.isVisible = true
                binding.btnRetry.isVisible = true
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun openGenre(genre: StalkerApi.Genre) {
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_GENRE_ID, genre.id)
                .putExtra(ChannelListActivity.EXTRA_GENRE_TITLE, genre.title)
        )
    }

    /**
     * v5.5: country flag emoji for a genre title (e.g. "EU|FR|MAX PPV" → 🇫🇷).
     * Parses 2-letter tokens, maps UK→GB, skips non-countries (EU/TV/HD),
     * validates against ISO country list. Zero-size, offline.
     */
    object FlagEmoji {
        private val ALIAS = mapOf("UK" to "GB", "EN" to "GB")
        private val SKIP = setOf("EU", "TV", "HD", "4K", "3D", "VIP", "PPV", "VOD", "XXX", "AD")
        private val ISO by lazy { java.util.Locale.getISOCountries().toSet() }

        fun fromGenreTitle(title: String): String {
            val tokens = title.uppercase().split(Regex("[^A-Z]+"))
                .filter { it.length == 2 }
            for (t in tokens) {
                if (t in SKIP) continue
                val code = ALIAS[t] ?: t
                if (code in SKIP || code !in ISO) continue
                return code.toFlagEmoji()
            }
            return ""
        }

        private fun String.toFlagEmoji(): String =
            map { c ->
                String(Character.toChars(0x1F1E6 + (c.code - 'A'.code)))
            }.joinToString("")
    }

    // ------------------------------------------------------------ adapter

    private class GenreAdapter(
        private val onClick: (StalkerApi.Genre) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_GRID = 0
            private const val TYPE_LIST = 1
        }

        private var items: List<StalkerApi.Genre> = emptyList()

        /** Set by the activity; "grid" or "list" (Prefs.CAT_VIEW_*). */
        var viewMode: String = Prefs.CAT_VIEW_GRID

        fun submitList(list: List<StalkerApi.Genre>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (viewMode == Prefs.CAT_VIEW_LIST) TYPE_LIST else TYPE_GRID

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_LIST) {
                ListVH(ItemChannelBinding.inflate(inflater, parent, false))
            } else {
                GridVH(ItemChannelCardBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val genre = items[position]
            when (holder) {
                is GridVH -> holder.bind(genre)
                is ListVH -> holder.bind(genre)
            }
        }

        override fun getItemCount(): Int = items.size

        private inner class GridVH(
            private val b: ItemChannelCardBinding
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(genre: StalkerApi.Genre) {
                // v5.5: flag emoji tile when a country code is found in
                // the title (e.g. "EU|FR|..." → 🇫🇷); letter fallback.
                b.tvName.text = genre.title
                val flag = FlagEmoji.fromGenreTitle(genre.title)
                b.tvLogoLetter.text = if (flag.isNotEmpty()) {
                    flag
                } else {
                    genre.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                }
                b.ivPoster.isVisible = false
                b.tvLogoLetter.isVisible = true
                // v6.3: genres aren't channels — badge stays gone.
                b.tvQuality.isVisible = false
                b.root.setOnClickListener { onClick(genre) }
                b.root.isFocusable = true
            }
        }

        /** v6.3: list-mode row — reuses the channel row layout (D-pad focusable). */
        private inner class ListVH(
            private val b: ItemChannelBinding
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(genre: StalkerApi.Genre) {
                b.tvNumber.isVisible = false
                b.tvName.text = genre.title
                // genres aren't channels — badge stays gone.
                b.tvQuality.isVisible = false
                b.root.setOnClickListener { onClick(genre) }
            }
        }
    }
}
