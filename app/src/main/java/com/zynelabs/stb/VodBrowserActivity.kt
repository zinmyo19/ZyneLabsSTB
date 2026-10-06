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

    private val adapter = RowAdapter { item ->
        if (categoryId == null) {
            // Drill into the category.
            startActivity(
                Intent(this, VodBrowserActivity::class.java)
                    .putExtra(EXTRA_MODE, mode)
                    .putExtra(EXTRA_CATEGORY_ID, item.id)
                    .putExtra(EXTRA_CATEGORY_TITLE, item.title)
            )
        } else {
            openPlayer(item.id, item.title)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
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
        categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        categoryTitle = intent.getStringExtra(EXTRA_CATEGORY_TITLE)

        binding.tvTitle.text = categoryTitle
            ?: getString(if (mode == MODE_SERIES) R.string.section_series else R.string.section_videoclub)
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
                val api = StalkerApi(
                    Prefs.getPortalUrl(this@VodBrowserActivity),
                    Prefs.getMac(this@VodBrowserActivity)
                )
                val rows: List<RowItem>
                val catId = categoryId
                if (catId == null) {
                    var cats = api.getVodCategories()
                    if (mode == MODE_SERIES) {
                        val filtered = cats.filter { looksLikeSeries(it.title) }
                        if (filtered.isNotEmpty()) cats = filtered
                    }
                    rows = cats.map { RowItem(it.id, it.title) }
                } else {
                    val items = api.getVodList(catId)
                    cmdById.clear()
                    for (it in items) cmdById[it.id] = it.cmd
                    rows = items.map { RowItem(it.id, it.name) }
                }
                adapter.submitList(rows) {
                    if (rows.isNotEmpty()) binding.recyclerView.requestFocus()
                }
                if (rows.isEmpty()) {
                    binding.tvError.text = getString(R.string.error_no_vod)
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
