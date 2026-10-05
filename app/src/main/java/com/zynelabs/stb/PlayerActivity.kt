package com.zynelabs.stb

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.zynelabs.stb.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/**
 * Player screen: resolves the channel cmd via create_link, then plays the
 * stream URL with Media3 ExoPlayer. The PlayerView controller handles
 * D-pad OK (show/hide controls) natively.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_NAME = "name"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (cmd.isBlank()) {
            finish()
            return
        }
        binding.tvTitle.text = name

        resolveAndPlay(cmd)
    }

    private fun resolveAndPlay(cmd: String) {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false

        lifecycleScope.launch {
            try {
                val api = StalkerApi(
                    Prefs.getPortalUrl(this@PlayerActivity),
                    Prefs.getMac(this@PlayerActivity)
                )
                val streamUrl = api.createLink(cmd)
                initPlayer(streamUrl)
            } catch (e: Exception) {
                binding.tvError.text =
                    getString(R.string.error_stream_failed, e.message.orEmpty())
                binding.tvError.isVisible = true
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun initPlayer(url: String) {
        releasePlayer()
        val newPlayer = ExoPlayer.Builder(this).build()
        player = newPlayer
        binding.playerView.player = newPlayer
        newPlayer.setMediaItem(MediaItem.fromUri(url))
        newPlayer.prepare()
        newPlayer.play()
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
