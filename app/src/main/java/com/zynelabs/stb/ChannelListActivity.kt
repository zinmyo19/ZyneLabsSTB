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
import com.zynelabs.stb.databinding.ActivityChannelListBinding
import com.zynelabs.stb.databinding.ItemChannelBinding
import kotlinx.coroutines.launch

/**
 * Channel list: D-pad navigable RecyclerView (name + number).
 * DPAD_CENTER / OK on a focused item opens the player.
 */
class ChannelListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelListBinding
    private val adapter = ChannelAdapter { channel -> openPlayer(channel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnRetry.setOnClickListener { loadChannels() }

        loadChannels()
    }

    private fun loadChannels() {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false
        binding.btnRetry.isVisible = false

        lifecycleScope.launch {
            try {
                val api = StalkerApi(
                    Prefs.getPortalUrl(this@ChannelListActivity),
                    Prefs.getMac(this@ChannelListActivity)
                )
                val channels = api.getAllChannels()
                adapter.submitList(channels) {
                    if (channels.isNotEmpty()) {
                        binding.recyclerView.requestFocus()
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

    private fun openPlayer(channel: Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CMD, channel.cmd)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
        )
    }

    // ------------------------------------------------------------ adapter

    private class ChannelAdapter(
        private val onClick: (Channel) -> Unit
    ) : ListAdapter<Channel, ChannelAdapter.ViewHolder>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Channel>() {
                override fun areItemsTheSame(old: Channel, new: Channel): Boolean =
                    old.id == new.id

                override fun areContentsTheSame(old: Channel, new: Channel): Boolean =
                    old == new
            }
        }

        inner class ViewHolder(
            private val binding: ItemChannelBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onClick(getItem(position))
                    }
                }
            }

            fun bind(channel: Channel) {
                binding.tvNumber.text = channel.number
                binding.tvName.text = channel.name.ifBlank {
                    binding.root.context.getString(R.string.unknown_channel)
                }
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
    }
}
