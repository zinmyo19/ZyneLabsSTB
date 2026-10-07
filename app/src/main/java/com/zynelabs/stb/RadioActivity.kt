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
 * Radio section: channels whose name or genre looks like radio/music.
 * Plays through the same ExoPlayer screen.
 */
class RadioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding

    private val adapter = RowAdapter { item ->
        val cmd = cmdById[item.id] ?: return@RowAdapter
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CMD, cmd)
                .putExtra(PlayerActivity.EXTRA_NAME, item.title)
                .putExtra(PlayerActivity.EXTRA_CMD_TYPE, PlayerActivity.TYPE_ITV)
        )
    }

    private val cmdById = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = getString(R.string.section_radio)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { load() }

        load()
    }

    private fun load() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false

        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@RadioActivity)
                val genres = try {
                    api.getGenres().associate { it.id to it.title.lowercase() }
                } catch (e: Exception) {
                    emptyMap()
                }
                val stations = api.getAllChannels().filter { ch ->
                    val name = ch.name.lowercase()
                    val genre = genres[ch.genreId].orEmpty()
                    "radio" in name || "radio" in genre ||
                        "music" in name || "fm" in name
                }
                cmdById.clear()
                for (s in stations) cmdById[s.id] = s.cmd
                val rows = stations.map { RowItem(it.id, it.name) }
                adapter.submitList(rows) {
                    if (rows.isNotEmpty()) binding.recyclerView.requestFocus()
                }
                if (rows.isEmpty()) {
                    binding.tvError.text = getString(R.string.error_no_radio)
                    binding.tvError.isVisible = true
                }
            } catch (e: Exception) {
                binding.tvError.text =
                    getString(R.string.error_load_failed, e.message.orEmpty())
                binding.tvError.isVisible = true
                binding.btnRetry.isVisible = true
                binding.btnRetry.requestFocus()
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }
}
