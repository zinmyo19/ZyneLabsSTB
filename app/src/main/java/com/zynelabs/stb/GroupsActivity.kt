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
 * TV section entry: portal genre/category list (STBEmu-style groups).
 * OK on a group opens the channel list filtered to that genre.
 * Falls back to "All Channels" when the portal has no genres.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val adapter = RowAdapter { item ->
        if (item.id == ALL_ID) {
            openChannels(null, getString(R.string.all_channels))
        } else {
            openChannels(item.id, item.title)
        }
    }

    companion object {
        private const val ALL_ID = "__all__"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = getString(R.string.tv_groups)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { loadGenres() }

        loadGenres()
    }

    private fun loadGenres() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false

        lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@GroupsActivity)
                val genres = api.getGenres()
                val rows = ArrayList<RowItem>(genres.size + 1)
                rows.add(RowItem(ALL_ID, getString(R.string.all_channels)))
                for (g in genres) {
                    rows.add(RowItem(g.id, g.title))
                }
                adapter.submitList(rows) {
                    if (rows.isNotEmpty()) binding.recyclerView.requestFocus()
                }
            } catch (e: Exception) {
                // Genre fetch failed (some portals don't implement get_genres):
                // genres are optional, so stay silent and just offer the
                // unfiltered channel list. No error text — the "All Channels"
                // row is always present and is the real functionality.
                adapter.submitList(
                    listOf(RowItem(ALL_ID, getString(R.string.all_channels)))
                )
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun openChannels(genreId: String?, genreTitle: String) {
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_GENRE_ID, genreId)
                .putExtra(ChannelListActivity.EXTRA_GENRE_TITLE, genreTitle)
        )
    }
}
