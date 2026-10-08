package com.zynelabs.stb

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityCategoriesBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isTv = (resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val span = if (isTv) 5 else 3
        binding.recyclerView.layoutManager = GridLayoutManager(this, span)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { loadGenres() }
        binding.btnBack.setOnClickListener { finish() }

        loadGenres()
    }

    private fun loadGenres() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false
        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@CategoriesActivity)
                val genres = api.getGenres()
                if (genres.isEmpty()) {
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

    // ------------------------------------------------------------ adapter

    private class GenreAdapter(
        private val onClick: (StalkerApi.Genre) -> Unit
    ) : RecyclerView.Adapter<GenreAdapter.VH>() {

        private var items: List<StalkerApi.Genre> = emptyList()

        fun submitList(list: List<StalkerApi.Genre>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChannelCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        private inner class VH(
            private val b: ItemChannelCardBinding
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(genre: StalkerApi.Genre) {
                // Reuse the channel card: letter tile + title.
                b.tvName.text = genre.title
                val letter = genre.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                b.tvLogoLetter.text = letter
                b.ivPoster.isVisible = false
                b.tvLogoLetter.isVisible = true
                b.root.setOnClickListener { onClick(genre) }
                b.root.isFocusable = true
            }
        }
    }
}
