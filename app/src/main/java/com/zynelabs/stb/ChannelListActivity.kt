package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zynelabs.stb.databinding.ActivityChannelsBinding
import com.zynelabs.stb.databinding.ItemChannelBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV channel list with numbers + side EPG panel (STBEmu-style).
 * D-pad: UP/DOWN moves, OK opens the player. Focusing a channel loads
 * its EPG (now/next) into the side panel — best-effort, never crashes.
 */
class ChannelListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelsBinding
    private var epgJob: Job? = null

    // v6.3.2: shared per-row "Now/Next" EPG line cache, keyed by channel id.
    // Value is the display line; "" means the fetch failed → row stays clean.
    private val epgLineCache = mutableMapOf<String, String>()

    private val adapter = ChannelAdapter(
        onClick = { channel -> openPlayer(channel) },
        onFocus = { channel -> loadEpgPanel(channel) }
    )

    companion object {
        const val EXTRA_GENRE_ID = "genre_id"
        const val EXTRA_GENRE_TITLE = "genre_title"

        fun todayString(): String =
            SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val genreTitle = intent.getStringExtra(EXTRA_GENRE_TITLE)
            .orEmpty().ifBlank { getString(R.string.all_channels) }
        binding.tvTitle.text = genreTitle

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { loadChannels() }
        binding.btnGuide.setOnClickListener {
            startActivity(
                Intent(this, GuideActivity::class.java)
                    .putExtra(
                        GuideActivity.EXTRA_GENRE_ID,
                        intent.getStringExtra(EXTRA_GENRE_ID)
                    )
                    .putExtra(GuideActivity.EXTRA_GENRE_TITLE, genreTitle)
            )
        }

        loadChannels()
    }

    private fun loadChannels() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false
        // v5.3: show live load progress ("Loading… 1,240 channels").
        binding.tvTitle.text = intent.getStringExtra(EXTRA_GENRE_TITLE)
            .orEmpty().ifBlank { getString(R.string.all_channels) }

        // v6.3: don't show another provider's (or no provider's) channels.
        if (!SourceManager.hasProvider(this)) {
            binding.progressBar.isVisible = false
            showError(getString(R.string.add_provider_hint))
            return
        }
        lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@ChannelListActivity)
                val genreId = intent.getStringExtra(EXTRA_GENRE_ID)
                // v5.3: FULL pagination — loops pages until an empty page,
                // no artificial cap, so all 21k channels load (fixes the
                // "only 98 channels" report vs OTT).
                val channels = api.getChannelsPaginated(
                    genreId = genreId?.ifBlank { null },
                    onProgress = { _, soFar ->
                        binding.tvTitle.text = getString(
                            R.string.loading_channels_count, soFar
                        )
                    }
                )
                // Restore the real title once done.
                binding.tvTitle.text = intent.getStringExtra(EXTRA_GENRE_TITLE)
                    .orEmpty().ifBlank { getString(R.string.all_channels) }
                adapter.submitList(channels) {
                    if (channels.isNotEmpty()) {
                        binding.recyclerView.requestFocus()
                        loadEpgPanel(channels[0])
                    }
                }
                if (channels.isEmpty()) {
                    showError(getString(R.string.error_no_channels))
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_load_failed, e.message.orEmpty()))
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.isVisible = true
        binding.btnRetry.isVisible = true
        binding.btnRetry.requestFocus()
    }

    /** Loads now/next EPG for the focused channel into the side preview panel. */
    private fun loadEpgPanel(channel: Channel) {
        epgJob?.cancel()
        // v6.3.2: preview pane — "name • QUALITY" header + channel logo.
        binding.tvEpgChannel.text = if (channel.quality.isNotBlank()) {
            "${channel.name} • ${channel.quality}"
        } else {
            channel.name
        }
        binding.ivPreviewLogo.isVisible = false
        if (channel.logo.isNotBlank()) {
            binding.ivPreviewLogo.isVisible = true
            ImageLoader.load(channel.logo, binding.ivPreviewLogo, onFail = {
                binding.ivPreviewLogo.isVisible = false
            })
        }
        binding.tvEpgNow.text = getString(R.string.epg_loading)
        binding.tvEpgNext.text = ""
        binding.tvEpgDesc.text = ""
        epgJob = lifecycleScope.launch {
            delay(350) // debounce fast D-pad scrolling
            try {
                val api = SourceManager.get(this@ChannelListActivity)
                val programs = api.getEpg(channel.id, todayString())
                if (programs.isEmpty()) {
                    binding.tvEpgNow.text = getString(R.string.epg_none)
                    return@launch
                }
                val now = programs.getOrNull(0)
                val next = programs.getOrNull(1)
                binding.tvEpgNow.text = getString(
                    R.string.epg_now, now?.name.orEmpty()
                )
                binding.tvEpgNext.text = if (next != null) {
                    getString(R.string.epg_next, next.name)
                } else {
                    ""
                }
                binding.tvEpgDesc.text = now?.descr.orEmpty()
            } catch (e: Exception) {
                binding.tvEpgNow.text = getString(R.string.epg_none)
                binding.tvEpgNext.text = ""
                binding.tvEpgDesc.text = ""
            }
        }
    }

    private fun openPlayer(channel: Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CMD, channel.cmd)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_CMD_TYPE, PlayerActivity.TYPE_ITV)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_GENRE_ID, channel.genreId)
        )
    }

    // ------------------------------------------------------------ adapter

    /** v6.3.2: DIFF lives here (not in a companion) because
     * ChannelAdapter is inner (needs epgLineCache/lifecycleScope)
     * and inner classes cannot declare companions. */
    private val channelDiff = object : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(old: Channel, new: Channel): Boolean =
            old.id == new.id

        override fun areContentsTheSame(old: Channel, new: Channel): Boolean =
            old == new
    }

    private inner class ChannelAdapter(
        private val onClick: (Channel) -> Unit,
        private val onFocus: (Channel) -> Unit
    ) : ListAdapter<Channel, ChannelAdapter.ViewHolder>(channelDiff) {

        inner class ViewHolder(
            private val binding: ItemChannelBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            // v6.3.2: per-row EPG job — MUST be per-holder, not the
            // activity's epgJob (that one drives the side preview panel;
            // sharing it made rows cancel the panel and each other).
            private var rowEpgJob: Job? = null

            private var epgJob: Job? = null

            init {
                binding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onClick(getItem(position))
                    }
                }
                binding.root.onFocusChangeListener =
                    android.view.View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            val position = bindingAdapterPosition
                            if (position != RecyclerView.NO_POSITION) {
                                onFocus(getItem(position))
                            }
                        }
                    }
            }

            fun bind(channel: Channel) {
                binding.tvNumber.text = channel.number
                binding.tvName.text = channel.name.ifBlank {
                    binding.root.context.getString(R.string.unknown_channel)
                }
                // v6.3: quality badge, hidden when the name carries none.
                val q = channel.quality
                binding.tvQuality.text = q
                binding.tvQuality.isVisible = q.isNotEmpty()

                // v6.3.2: logo — hidden when blank or when the load fails,
                // so rows without logos still look clean.
                binding.ivLogo.isVisible = false
                if (channel.logo.isNotBlank()) {
                    binding.ivLogo.isVisible = true
                    ImageLoader.load(channel.logo, binding.ivLogo, onFail = {
                        binding.ivLogo.isVisible = false
                    })
                }

                // v6.3.2: per-row now/next EPG line — cached, debounced,
                // best-effort. The position guard stops recycled views
                // from showing another channel's line.
                rowEpgJob?.cancel()
                val cached = epgLineCache[channel.id]
                if (cached != null) {
                    binding.tvEpgLine.text = cached
                    binding.tvEpgLine.isVisible = cached.isNotEmpty()
                } else {
                    binding.tvEpgLine.isVisible = false
                    val tag = channel.id
                    rowEpgJob = lifecycleScope.launch {
                        delay(300) // let fast D-pad scrolling settle
                        try {
                            val api = SourceManager.get(this@ChannelListActivity)
                            val programs = api.getEpg(tag, todayString())
                            val ctx = binding.root.context
                            val line = if (programs.isEmpty()) {
                                ctx.getString(R.string.epg_none)
                            } else {
                                buildString {
                                    val nowName = programs.getOrNull(0)?.name
                                        .orEmpty()
                                    val nextName = programs.getOrNull(1)?.name
                                    if (nowName.isNotEmpty()) {
                                        append(
                                            ctx.getString(
                                                R.string.epg_now, nowName
                                            )
                                        )
                                    }
                                    if (!nextName.isNullOrEmpty()) {
                                        if (isNotEmpty()) append(" • ")
                                        append(
                                            ctx.getString(
                                                R.string.epg_next, nextName
                                            )
                                        )
                                    }
                                    if (isEmpty()) {
                                        append(ctx.getString(R.string.epg_none))
                                    }
                                }
                            }
                            epgLineCache[tag] = line
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION &&
                                getItem(pos).id == tag
                            ) {
                                binding.tvEpgLine.text = line
                                binding.tvEpgLine.isVisible = line.isNotEmpty()
                            }
                        } catch (e: Exception) {
                            epgLineCache[tag] = ""
                        }
                    }
                }
            }

            fun cancelEpg() {
                rowEpgJob?.cancel()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemChannelBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.cancelEpg()
            super.onViewRecycled(holder)
        }
    }
}
