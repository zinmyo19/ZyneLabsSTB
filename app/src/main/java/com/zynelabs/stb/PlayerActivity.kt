package com.zynelabs.stb

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.zynelabs.stb.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/**
 * Player screen: resolves the channel/VOD cmd via create_link, then plays
 * the stream URL with Media3 ExoPlayer. The PlayerView controller handles
 * D-pad OK (show/hide controls) natively.
 *
 * Applies user settings: display aspect ratio, subtitles on/off,
 * preferred audio language.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_NAME = "name"
        const val EXTRA_CMD_TYPE = "cmd_type"
        const val TYPE_ITV = "itv"
        const val TYPE_VOD = "vod"
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
                val api = StalkerSession.get(this@PlayerActivity)
                val type = intent.getStringExtra(EXTRA_CMD_TYPE) ?: TYPE_ITV
                val streamUrl = api.createLink(cmd, type)
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

        // Subtitles on/off + preferred audio language.
        val trackParams = newPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT, !Prefs.getSubtitlesEnabled(this)
            )
        val audioLang = Prefs.getAudioLangCode(this)
        if (audioLang.isNotEmpty()) {
            trackParams.setPreferredAudioLanguage(audioLang)
        }
        newPlayer.trackSelectionParameters = trackParams.build()

        applyAspectRatio()

        newPlayer.setMediaItem(MediaItem.fromUri(url))
        newPlayer.prepare()
        newPlayer.play()
    }

    /**
     * Applies the user's aspect-ratio setting by sizing the PlayerView
     * inside its FrameLayout parent. "Auto" fills and fits; a fixed
     * ratio (16:9, 4:3, …) letterboxes/pillarboxes to that exact ratio.
     */
    private fun applyAspectRatio() {
        val ratio = Prefs.getAspectRatio(this)
        val parent = binding.playerView.parent as? FrameLayout ?: return
        val params = binding.playerView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

        if (ratio == "Auto") {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            val parts = ratio.split(":")
            val w = parts.getOrNull(0)?.toFloatOrNull() ?: 16f
            val h = parts.getOrNull(1)?.toFloatOrNull() ?: 9f
            val pw = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val ph = parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            var vw = pw
            var vh = (pw * h / w).toInt()
            if (vh > ph) {
                vh = ph
                vw = (ph * w / h).toInt()
            }
            params.width = vw
            params.height = vh
            params.gravity = android.view.Gravity.CENTER
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.playerView.layoutParams = params
        binding.playerView.requestLayout()
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
