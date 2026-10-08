package com.zynelabs.stb

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.zynelabs.stb.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v5.1 FlowPlay-style player UI (custom controller, PlayerView's is OFF):
 * - Transparent floating top bar: back, title, LIVE badge, Tracks
 * - Info box with channel name + meta (shown with controls)
 * - Bottom transport: play/pause, seek bar (VOD), position/duration
 * - Inline settings strip: speed / audio / subtitles / aspect — wired to
 *   v5.0 Prefs and applied live
 * - Controls auto-hide after 4s; any D-pad key resets the timer; OK on the
 *   video surface toggles controls (standing TV-remote scheme)
 * Engine: Media3 ExoPlayer (unchanged). create_link resolution unchanged.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_NAME = "name"
        const val EXTRA_CMD_TYPE = "cmd_type"
        const val TYPE_ITV = "itv"
        const val TYPE_VOD = "vod"
        private const val HIDE_DELAY_MS = 4000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var sleepJob: Job? = null
    private var channelName = ""
    private var isVod = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty()
        channelName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (cmd.isBlank()) {
            finish()
            return
        }
        isVod = intent.getStringExtra(EXTRA_CMD_TYPE) == TYPE_VOD

        binding.tvTitle.text = channelName.ifBlank { getString(R.string.unknown_channel) }
        binding.tvInfoName.text = binding.tvTitle.text
        binding.tvInfoMeta.text = if (isVod) "VOD" else "Live TV"

        // Top bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnTracks.setOnClickListener { showAudioTracks() }

        // Transport
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) bumpHideTimer()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                uiHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = player ?: return
                val dur = p.duration
                if (dur > 0) p.seekTo(dur * binding.seekBar.progress / 1000)
                bumpHideTimer()
            }
        })

        // Settings strip (wired to v5.0 Prefs, applied live)
        refreshStripLabels()
        binding.btnSpeed.setOnClickListener {
            val label = Prefs.cyclePlaybackSpeed(this)
            player?.setPlaybackSpeed(Prefs.playbackSpeedValue(this))
            binding.btnSpeed.text = label
            bumpHideTimer()
        }
        binding.btnAudio.setOnClickListener { showAudioTracks() }
        binding.btnSubtitles.setOnClickListener {
            val on = !Prefs.getSubtitlesEnabled(this)
            Prefs.setSubtitlesEnabled(this, on)
            applySubtitleTrack()
            refreshStripLabels()
            bumpHideTimer()
        }
        binding.btnAspect.setOnClickListener {
            val label = Prefs.cycleAspectRatio(this)
            applyAspectRatio()
            binding.btnAspect.text = label
            bumpHideTimer()
        }

        // OK on the surface toggles controls (standing TV scheme).
        binding.playerRoot.setOnClickListener { toggleControls() }

        showControls()
        resolveAndPlay(cmd)
    }

    // ------------------------------------------------------------ controls

    private fun showControls() {
        binding.topBar.isVisible = true
        binding.infoBox.isVisible = true
        binding.bottomControls.isVisible = true
        bumpHideTimer()
        uiHandler.post(progressRunnable)
        if (isTvFocus()) binding.btnPlayPause.requestFocus()
    }

    private fun hideControls() {
        binding.topBar.isVisible = false
        binding.infoBox.isVisible = false
        binding.bottomControls.isVisible = false
        uiHandler.removeCallbacks(progressRunnable)
    }

    private fun toggleControls() {
        if (binding.bottomControls.isVisible) hideControls() else showControls()
    }

    private fun bumpHideTimer() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun isTvFocus(): Boolean {
        return resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Every D-pad key keeps the overlay alive (FlowPlay pattern). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!binding.bottomControls.isVisible) {
                        showControls()
                        return true
                    }
                    bumpHideTimer()
                }
                KeyEvent.KEYCODE_MENU -> {
                    toggleControls()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ transport

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseIcon()
        bumpHideTimer()
    }

    private fun updatePlayPauseIcon() {
        binding.btnPlayPause.text = if (player?.isPlaying == true) "⏸" else "▶"
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (isVod && dur > 0) {
            binding.seekBar.isVisible = true
            binding.seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            binding.tvPosition.text = fmtTime(pos)
            binding.tvDuration.text = fmtTime(dur)
        } else {
            // Live: no seeking.
            binding.seekBar.isVisible = false
            binding.tvPosition.text = fmtTime(pos)
            binding.tvDuration.text = "LIVE"
        }
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------ settings

    private fun refreshStripLabels() {
        binding.btnSpeed.text = Prefs.getPlaybackSpeedLabel(this)
        binding.btnAudio.text = Prefs.getAudioLangLabel(this)
        binding.btnSubtitles.text =
            if (Prefs.getSubtitlesEnabled(this)) "Subs: On" else "Subs: Off"
        binding.btnAspect.text = Prefs.getAspectRatio(this)
    }

    private fun showAudioTracks() {
        val p = player ?: return
        TrackSelectionDialogBuilder(
            this,
            getString(R.string.tracks_audio_title),
            p,
            C.TRACK_TYPE_AUDIO
        ).build().show()
        bumpHideTimer()
    }

    private fun applySubtitleTrack() {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Prefs.getSubtitlesEnabled(this))
        val audioLang = Prefs.getAudioLangCode(this)
        if (audioLang.isNotEmpty()) params.setPreferredAudioLanguage(audioLang)
        p.trackSelectionParameters = params.build()
        binding.playerView.subtitleView?.let { sv ->
            sv.setFractionalTextSize(Prefs.subtitleSizeFraction(this))
            sv.setStyle(
                CaptionStyleCompat(
                    Prefs.subtitleColorInt(this),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    null
                )
            )
        }
    }

    // ------------------------------------------------------------ playback

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

        val (minBufferMs, maxBufferMs) = Prefs.bufferDurationsMs(this)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        val newPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        player = newPlayer
        binding.playerView.player = newPlayer

        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon()
            }
        })

        applySubtitleTrack()
        newPlayer.setPlaybackSpeed(Prefs.playbackSpeedValue(this))
        applyAspectRatio()

        newPlayer.setMediaItem(MediaItem.fromUri(url))
        newPlayer.prepare()
        newPlayer.play()
        updatePlayPauseIcon()

        startSleepTimer()
    }

    private fun startSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        val minutes = Prefs.sleepTimerMinutes(this)
        if (minutes <= 0) return
        sleepJob = lifecycleScope.launch {
            delay(minutes * 60_000L)
            player?.pause()
            updatePlayPauseIcon()
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.sleep_timer_done),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyAspectRatio() {
        val ratio = Prefs.getAspectRatio(this)
        val parent = binding.playerView.parent as? android.widget.FrameLayout ?: return
        val params = binding.playerView.layoutParams as? android.widget.FrameLayout.LayoutParams
            ?: android.widget.FrameLayout.LayoutParams(
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
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.removeCallbacks(progressRunnable)
        sleepJob?.cancel()
        sleepJob = null
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        player?.play()
        updatePlayPauseIcon()
    }

    override fun onStop() {
        player?.pause()
        updatePlayPauseIcon()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
