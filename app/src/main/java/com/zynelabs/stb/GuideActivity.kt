package com.zynelabs.stb

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityGuideBinding
import com.zynelabs.stb.databinding.ItemGuideRowBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * TV Guide: date selector (7 days) + per-channel now/next programs.
 * EPG loads lazily per row with a cache; everything degrades to
 * "No program info" when the portal lacks EPG data.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private val adapter = GuideAdapter()

    private val epgCache = mutableMapOf<String, List<StalkerApi.EpgProgram>>()
    private var channels: List<Channel> = emptyList()
    private var selectedDate: String = ChannelListActivity.todayString()

    companion object {
        const val EXTRA_GENRE_ID = "genre_id"
        const val EXTRA_GENRE_TITLE = "genre_title"
        private const val MAX_ROWS = 80
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        buildDateRow()
        loadChannels()
    }

    private fun buildDateRow() {
        val cal = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.US)
        val apiFmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        for (i in 0 until 7) {
            val label = if (i == 0) getString(R.string.today)
            else dayFmt.format(cal.time)
            val dateStr = apiFmt.format(cal.time)
            val btn = Button(this)
            btn.text = label
            btn.setTextColor(getColor(R.color.text_primary))
            btn.background = getDrawable(R.drawable.channel_item_background)
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            btn.setPadding(28, 16, 28, 16)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(6, 6, 6, 6)
            btn.layoutParams = lp
            btn.setOnClickListener {
                selectedDate = dateStr
                epgCache.clear()
                adapter.submitList(channels.map { GuideRow(it, selectedDate) })
                binding.recyclerView.requestFocus()
            }
            binding.dateRow.addView(btn)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun loadChannels() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false

        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@GuideActivity)
                val genreId = intent.getStringExtra(EXTRA_GENRE_ID)
                // v2.8: paginated (see ChannelListActivity) — MAX_ROWS caps
                // the guide rows after fetching.
                val list = api.getChannelsPaginated(
                    genreId = genreId?.ifBlank { null }
                )
                channels = list.take(MAX_ROWS)
                adapter.submitList(channels.map { GuideRow(it, selectedDate) }) {
                    if (channels.isNotEmpty()) binding.recyclerView.requestFocus()
                }
                if (channels.isEmpty()) {
                    binding.tvError.text = getString(R.string.error_no_channels)
                    binding.tvError.isVisible = true
                }
            } catch (e: Exception) {
                binding.tvError.text =
                    getString(R.string.error_load_failed, e.message.orEmpty())
                binding.tvError.isVisible = true
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    /** Loads (and caches) EPG for one row. */
    private fun loadRowEpg(row: GuideRow, holder: GuideAdapter.ViewHolder) {
        val key = row.channel.id + "|" + row.date
        val cached = epgCache[key]
        if (cached != null) {
            holder.showPrograms(row.channel, cached)
            return
        }
        holder.showLoading(row.channel)
        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@GuideActivity)
                val programs = api.getEpg(row.channel.id, row.date)
                epgCache[key] = programs
                holder.showPrograms(row.channel, programs)
            } catch (e: Exception) {
                holder.showPrograms(row.channel, emptyList())
            }
        }
    }

    data class GuideRow(val channel: Channel, val date: String)

    private val guideDiff = object : DiffUtil.ItemCallback<GuideRow>() {
        override fun areItemsTheSame(old: GuideRow, new: GuideRow): Boolean =
            old.channel.id == new.channel.id && old.date == new.date

        override fun areContentsTheSame(old: GuideRow, new: GuideRow): Boolean =
            old == new
    }

    private inner class GuideAdapter :
        ListAdapter<GuideRow, GuideAdapter.ViewHolder>(guideDiff) {

        inner class ViewHolder(
            private val binding: ItemGuideRowBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun showLoading(channel: Channel) {
                binding.tvChannel.text = channel.name
                binding.tvNow.text = itemView.context.getString(R.string.epg_loading)
                binding.tvNext.text = ""
            }

            fun showPrograms(channel: Channel, programs: List<StalkerApi.EpgProgram>) {
                binding.tvChannel.text = channel.name
                if (programs.isEmpty()) {
                    binding.tvNow.text = itemView.context.getString(R.string.epg_none)
                    binding.tvNext.text = ""
                    return
                }
                val now = programs[0]
                binding.tvNow.text = itemView.context.getString(
                    R.string.epg_now,
                    listOf(now.start, now.name).filter { it.isNotBlank() }
                        .joinToString(" - ")
                )
                val next = programs.getOrNull(1)
                binding.tvNext.text = if (next != null) {
                    itemView.context.getString(
                        R.string.epg_next,
                        listOf(next.start, next.name).filter { it.isNotBlank() }
                            .joinToString(" - ")
                    )
                } else {
                    ""
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemGuideRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            // Only fetch when the row is actually bound (visible).
            loadRowEpg(row, holder)
        }
    }
}
