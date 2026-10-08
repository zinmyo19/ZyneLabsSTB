package com.zynelabs.stb

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
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
import androidx.recyclerview.widget.LinearLayoutManager
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
        const val EXTRA_GENRE_ID = "genre_id"
        const val TYPE_ITV = "itv"
        const val TYPE_VOD = "vod"
        private const val HIDE_DELAY_MS = 4000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var sleepJob: Job? = null
    private var channelName = ""
    private var isVod = false
    private var currentCmdType = TYPE_ITV

    // v5.3: drawers (channels + settings), PiP.
    private var drawerOpen: String? = null // "channels", "settings", or null
    private val drawerChannelAdapter = RowAdapter { item -> onDrawerChannelClick(item.id) }
    private val drawerGenreAdapter = RowAdapter { item -> onDrawerGenreClick(item.id) }
    private val drawerSettingsAdapter = RowAdapter { item -> onDrawerSettingClick(item.id) }
    private var drawerGenres: List<StalkerApi.Genre> = emptyList()
    private var drawerChannels: List<Channel> = emptyList()
    private var drawerGenreId: String? = null
    private var drawerLoadJob: Job? = null
    private var inPip = false

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
        currentCmdType = intent.getStringExtra(EXTRA_CMD_TYPE) ?: TYPE_ITV
        drawerGenreId = intent.getStringExtra(EXTRA_GENRE_ID)

        binding.tvTitle.text = channelName.ifBlank { getString(R.string.unknown_channel) }
        binding.tvInfoName.text = binding.tvTitle.text
        binding.tvInfoMeta.text = if (isVod) "VOD" else "Live TV"

        // Top bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnTracks.setOnClickListener { showAudioTracks() }
        // v5.3: PiP + drawers.
        binding.btnPip.setOnClickListener { enterPip() }
        binding.btnDrawerSettings.setOnClickListener { openDrawer("settings") }
        binding.btnDrawerChannels.setOnClickListener { openDrawer("channels") }

        setupDrawers()

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
                KeyEvent.KEYCODE_BACK -> {
                    // v5.3: BACK closes drawers first, then exits.
                    if (isDrawerOpen()) {
                        closeDrawers()
                        bumpHideTimer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!binding.bottomControls.isVisible && !isDrawerOpen()) {
                        showControls()
                        return true
                    }
                    bumpHideTimer()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // v5.3: DOWN opens the channels drawer (standing
                    // TV-remote scheme) — unless focus is inside the bottom
                    // controls where DOWN navigates to the transport row.
                    if (isDrawerOpen()) return super.dispatchKeyEvent(event)
                    if (!binding.bottomControls.isVisible) {
                        showControls()
                        return true
                    }
                    val focused = currentFocus
                    val inBottom = focused != null && isDescendantOf(focused, binding.bottomControls)
                    if (!inBottom && !inPip) {
                        openDrawer("channels")
                        return true
                    }
                    bumpHideTimer()
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!binding.bottomControls.isVisible && !isDrawerOpen()) {
                        showControls()
                        return true
                    }
                    bumpHideTimer()
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (isDrawerOpen()) {
                        closeDrawers()
                    } else {
                        toggleControls()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(view: View, parent: ViewGroup): Boolean {
        var p = view.parent
        while (p != null) {
            if (p == parent) return true
            p = p.parent
        }
        return false
    }

    // ------------------------------------------------------------ v5.3 drawers

    private fun setupDrawers() {
        binding.drawerGenreList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.drawerGenreList.adapter = drawerGenreAdapter
        binding.drawerChannelList.layoutManager = LinearLayoutManager(this)
        binding.drawerChannelList.adapter = drawerChannelAdapter
        binding.drawerSettingsList.layoutManager = LinearLayoutManager(this)
        binding.drawerSettingsList.adapter = drawerSettingsAdapter
        binding.drawerScrim.setOnClickListener { closeDrawers() }
    }

    private fun openDrawer(which: String) {
        closeDrawers()
        drawerOpen = which
        binding.drawerScrim.isVisible = true
        val target = if (which == "channels") binding.drawerChannels else binding.drawerSettings
        target.isVisible = true
        // Slide in from the right.
        target.translationX = target.width.toFloat().takeIf { it > 0 } ?: 340f
        target.animate().translationX(0f).setDuration(220).start()
        if (which == "channels") {
            loadDrawerGenres()
            binding.drawerChannelList.requestFocus()
        } else {
            refreshDrawerSettings()
            binding.drawerSettingsList.requestFocus()
        }
        uiHandler.removeCallbacks(hideRunnable) // keep controls state while drawer open
    }

    private fun closeDrawers() {
        drawerOpen = null
        binding.drawerScrim.isVisible = false
        binding.drawerChannels.isVisible = false
        binding.drawerSettings.isVisible = false
        drawerLoadJob?.cancel()
    }

    private fun isDrawerOpen(): Boolean = drawerOpen != null

    // ----- channels drawer -----

    private fun loadDrawerGenres() {
        drawerLoadJob?.cancel()
        drawerLoadJob = lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@PlayerActivity)
                drawerGenres = api.getGenres()
                val items = drawerGenres.map {
                    RowItem("g_${it.id}", it.title)
                }
                drawerGenreAdapter.submitList(items)
                // Pre-select the playing channel's genre (or first).
                val sel = drawerGenres.find { it.id == drawerGenreId }
                    ?: drawerGenres.firstOrNull()
                if (sel != null) loadDrawerChannels(sel.id)
            } catch (e: Exception) {
                // Genres optional — fall back to unfiltered list.
                loadDrawerChannels(null)
            }
        }
    }

    private fun onDrawerGenreClick(id: String) {
        val genreId = id.removePrefix("g_").ifBlank { null }
        loadDrawerChannels(genreId)
    }

    private fun loadDrawerChannels(genreId: String?) {
        drawerGenreId = genreId
        drawerLoadJob?.cancel()
        binding.drawerProgress.isVisible = true
        drawerLoadJob = lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@PlayerActivity)
                // v5.3: full pagination — every channel in the genre.
                drawerChannels = api.getChannelsPaginated(genreId = genreId)
                val items = drawerChannels.map {
                    RowItem(it.id, it.name.ifBlank { getString(R.string.unknown_channel) }, it.number)
                }
                drawerChannelAdapter.submitList(items)
            } catch (e: Exception) {
                drawerChannelAdapter.submitList(emptyList())
            } finally {
                binding.drawerProgress.isVisible = false
            }
        }
    }

    private fun onDrawerChannelClick(channelId: String) {
        val ch = drawerChannels.find { it.id == channelId } ?: return
        switchChannel(ch)
    }

    private fun switchChannel(ch: Channel) {
        closeDrawers()
        channelName = ch.name
        binding.tvTitle.text = channelName.ifBlank { getString(R.string.unknown_channel) }
        binding.tvInfoName.text = binding.tvTitle.text
        binding.tvInfoMeta.text = if (isVod) "VOD" else "Live TV"
        drawerGenreId = ch.genreId.ifBlank { drawerGenreId }
        showControls()
        resolveAndPlay(ch.cmd, currentCmdType)
    }

    // ----- settings drawer -----

    private fun refreshDrawerSettings() {
        val rows = listOf(
            RowItem("d_speed", getString(R.string.setting_speed), Prefs.getPlaybackSpeedLabel(this)),
            RowItem("d_subtitles", getString(R.string.setting_subtitles),
                getString(if (Prefs.getSubtitlesEnabled(this)) R.string.on else R.string.off)),
            RowItem("d_sub_size", getString(R.string.setting_subtitle_size), Prefs.getSubtitleSize(this)),
            RowItem("d_sub_color", getString(R.string.setting_subtitle_color), Prefs.getSubtitleColor(this)),
            RowItem("d_audio", getString(R.string.setting_audio_lang), Prefs.getAudioLangLabel(this)),
            RowItem("d_aspect", getString(R.string.setting_aspect), Prefs.getAspectRatio(this)),
            RowItem("d_buffer", getString(R.string.setting_buffer), Prefs.getBufferSize(this)),
            RowItem("d_sleep", getString(R.string.setting_sleep), Prefs.getSleepTimer(this)),
            RowItem("d_pip", getString(R.string.setting_pip), "")
        )
        drawerSettingsAdapter.submitList(rows)
    }

    private fun onDrawerSettingClick(id: String) {
        when (id) {
            "d_speed" -> {
                val label = Prefs.cyclePlaybackSpeed(this)
                player?.setPlaybackSpeed(Prefs.playbackSpeedValue(this))
            }
            "d_subtitles" -> {
                Prefs.setSubtitlesEnabled(this, !Prefs.getSubtitlesEnabled(this))
                applySubtitleTrack()
            }
            "d_sub_size" -> {
                Prefs.cycleSubtitleSize(this)
                applySubtitleTrack()
            }
            "d_sub_color" -> {
                Prefs.cycleSubtitleColor(this)
                applySubtitleTrack()
            }
            "d_audio" -> {
                Prefs.cycleAudioLang(this)
                applySubtitleTrack()
            }
            "d_aspect" -> {
                Prefs.cycleAspectRatio(this)
                applyAspectRatio()
            }
            "d_buffer" -> {
                Prefs.cycleBufferSize(this)
                Toast.makeText(this, getString(R.string.buffer_restart_note), Toast.LENGTH_SHORT).show()
            }
            "d_sleep" -> {
                Prefs.cycleSleepTimer(this)
                startSleepTimer()
            }
            "d_pip" -> {
                closeDrawers()
                enterPip()
                return
            }
        }
        refreshDrawerSettings()
        refreshStripLabels()
    }

    // ------------------------------------------------------------ v5.3 PiP

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.pip_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pip_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // PiP: hide everything but video.
            closeDrawers()
            binding.topBar.isVisible = false
            binding.infoBox.isVisible = false
            binding.bottomControls.isVisible = false
            uiHandler.removeCallbacks(hideRunnable)
            uiHandler.removeCallbacks(progressRunnable)
        } else {
            showControls()
        }
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

    private fun resolveAndPlay(cmd: String, type: String = currentCmdType) {
        binding.progressBar.isVisible = true
        binding.tvError.isVisible = false

        lifecycleScope.launch {
            try {
                val api = StalkerSession.get(this@PlayerActivity)
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
