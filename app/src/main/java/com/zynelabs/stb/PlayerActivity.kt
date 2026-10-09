package com.zynelabs.stb

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
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
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val TYPE_ITV = "itv"
        const val TYPE_VOD = "vod"
        private const val HIDE_DELAY_MS = 4000L
        // v5.4: gesture zones.
        private const val GESTURE_NONE = 0
        private const val GESTURE_VOLUME = 1
        private const val GESTURE_BRIGHTNESS = 2
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

    // v5.4: FlowPlay functions — recording, mute, zoom, favorites, prev/next.
    private var recorder: StreamRecorder? = null
    private var isRecording = false
    private var muted = false
    private var videoScale = 1.0f
    private var currentChannel: Channel? = null
    private var currentStreamUrl = ""

    // v5.4: swipe gestures (phone only) — right side = volume (bar on LEFT),
    // left side = brightness (bar on RIGHT). Swapped per his explicit choice.
    private var audioManager: AudioManager? = null
    private var gestureMode: Int = GESTURE_NONE // 0 none, 1 volume, 2 brightness
    private var gestureStartY = 0f
    private var gestureStartValue = 0f
    private var gestureDragged = false
    private val gestureHideRunnable = Runnable { hideGestureBars() }

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
        // v5.4: seed currentChannel so prev/next + favorite work immediately.
        currentChannel = Channel(
            id = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty().ifBlank { cmd },
            number = "",
            name = channelName,
            cmd = cmd,
            genreId = drawerGenreId.orEmpty()
        )

        binding.tvTitle.text = channelName.ifBlank { getString(R.string.unknown_channel) }
        binding.tvInfoName.text = binding.tvTitle.text
        binding.tvInfoMeta.text = if (isVod) "VOD" else "Live TV"

        // Top bar
        binding.btnBack.setOnClickListener { finish() }
        binding.btnTracks.setOnClickListener { showAudioTracks() }
        // v6.3: info button — channel/quality/provider/stream dialog.
        binding.btnInfo.setOnClickListener { showInfoDialog() }
        // v5.3: PiP + drawers.
        binding.btnPip.setOnClickListener { enterPip() }
        // v5.4: record button (FlowPlay function).
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnDrawerSettings.setOnClickListener { openDrawer("settings") }
        binding.btnDrawerChannels.setOnClickListener { openDrawer("channels") }

        // v5.4: swipe gestures (phone only — TV uses D-pad).
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        setupGestures()

        setupDrawers()

        // Transport
        // v5.5: prev / play-pause / next buttons (gold vectors).
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnPrev.setOnClickListener { prevChannel(); bumpHideTimer() }
        binding.btnNext.setOnClickListener { nextChannel(); bumpHideTimer() }
        // v5.5: remaining FlowPlay transport functions.
        binding.btnRewind.setOnClickListener { seekBy(-10_000); bumpHideTimer() }
        binding.btnForward.setOnClickListener { seekBy(10_000); bumpHideTimer() }
        binding.btnAspectTransport.setOnClickListener {
            val label = Prefs.cycleAspectRatio(this)
            applyAspectRatio()
            binding.btnAspect.text = label
            bumpHideTimer()
        }
        binding.btnVolume.setOnClickListener { toggleMute(); updateVolumeIcon() }
        binding.btnLock.setOnClickListener { setLocked(true) }
        updateVolumeIcon()
        // v5.5: ±10s seek buttons are VOD-only (FlowPlay pattern).
        binding.btnRewind.isVisible = isVod
        binding.btnForward.isVisible = isVod
        // v5.5: lock overlay — tap to unlock.
        binding.lockOverlay.setOnClickListener { setLocked(false) }
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

        // v5.4: preload drawer channel list in background so prev/next
        // works without opening the drawer first.
        lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@PlayerActivity)
                drawerChannels = api.getChannelsPaginated(genreId = drawerGenreId)
            } catch (e: Exception) {
                // drawer will load on open; ignore
            }
        }

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

    /**
     * v5.6: UP on D-pad shows just the info box (channel info panel) —
     * not the full transport. Auto-hides with the normal timer.
     */
    private fun showInfoBox() {
        binding.infoBox.isVisible = true
        bumpHideTimer()
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
            // v5.5: locked — any key unlocks (BACK included).
            if (locked) {
                setLocked(false)
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // v5.3: BACK closes drawers first, then exits.
                    if (isDrawerOpen()) {
                        closeDrawers()
                        bumpHideTimer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isDrawerOpen()) {
                        toggleControls()
                        return true
                    }
                    bumpHideTimer()
                }
                // v5.6: UP = info box (Dominic: D-pad arrows during playback).
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isDrawerOpen()) return super.dispatchKeyEvent(event)
                    if (!binding.infoBox.isVisible) {
                        showInfoBox()
                    } else {
                        bumpHideTimer()
                    }
                    return true
                }
                // v5.6: LEFT/RIGHT = prev/next channel when controls hidden.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!binding.bottomControls.isVisible && !isDrawerOpen()) {
                        prevChannel()
                        return true
                    }
                    bumpHideTimer()
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!binding.bottomControls.isVisible && !isDrawerOpen()) {
                        nextChannel()
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
                val api = SourceManager.get(this@PlayerActivity)
                drawerGenres = api.getGenres()
            } catch (e: Exception) {
                drawerGenres = emptyList()
            }
            // v5.5: ALWAYS show at least the "All" chip — previously an
            // empty/failed genre list left the drawer with no chips and
            // no channels. "All" clears the genre filter.
            val items = listOf(RowItem("g_", getString(R.string.all_channels))) +
                drawerGenres.map { RowItem("g_${it.id}", it.title) }
            drawerGenreAdapter.submitList(items)
            // Pre-select the playing channel's genre (or All).
            val sel = drawerGenres.find { it.id == drawerGenreId }
            loadDrawerChannels(sel?.id)
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
                val api = SourceManager.get(this@PlayerActivity)
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
        currentChannel = ch
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
            RowItem("d_pip", getString(R.string.setting_pip), ""),
            // v5.4: FlowPlay functions.
            RowItem("d_record", getString(R.string.setting_record),
                getString(if (isRecording) R.string.recording_on else R.string.recording_off)),
            RowItem("d_mute", getString(R.string.setting_mute),
                getString(if (muted) R.string.on else R.string.off)),
            RowItem("d_favorite", getString(R.string.setting_favorite),
                getString(if (Prefs.isFavorite(this, currentChannel?.id.orEmpty())) R.string.on else R.string.off)),
            RowItem("d_zoom", getString(R.string.setting_zoom), "${(videoScale * 100).toInt()}%"),
            RowItem("d_prev", getString(R.string.setting_prev_channel), ""),
            RowItem("d_next", getString(R.string.setting_next_channel), ""),
            RowItem("d_external", getString(R.string.setting_external), ""),
            // v5.5: playback stats (FlowPlay).
            RowItem("d_stats", getString(R.string.stats_title), "")
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
            // v5.4: FlowPlay functions.
            "d_record" -> toggleRecording()
            "d_mute" -> toggleMute()
            "d_favorite" -> toggleFavorite()
            "d_zoom" -> cycleZoom()
            "d_prev" -> {
                closeDrawers()
                prevChannel()
                return
            }
            "d_next" -> {
                closeDrawers()
                nextChannel()
                return
            }
            "d_external" -> openExternal()
            // v5.5: playback stats (FlowPlay).
            "d_stats" -> showStatsDialog()
        }
        refreshDrawerSettings()
        refreshStripLabels()
    }

    /** v5.5: playback stats dialog (FlowPlay) — bitrate, resolution, codec. */
    private fun showStatsDialog() {
        val p = player
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val bitrate = p?.let {
            try { it.currentTracks.groups
                .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i) } }
                .maxOfOrNull { f -> f.bitrate } ?: 0
            } catch (e: Exception) { 0 }
        } ?: 0
        val msg = buildString {
            appendLine("Channel: $channelName")
            appendLine("Type: ${if (isVod) "VOD" else "Live"}")
            if (vf != null) {
                appendLine("Video: ${vf.width}x${vf.height} ${vf.sampleMimeType?.substringAfter("/") ?: ""}")
                if (vf.frameRate > 0) appendLine("Framerate: ${"%.1f".format(vf.frameRate)} fps")
            } else appendLine("Video: —")
            if (af != null) {
                appendLine("Audio: ${af.sampleMimeType?.substringAfter("/") ?: ""} ${af.channelCount}ch")
            } else appendLine("Audio: —")
            if (bitrate > 0) appendLine("Bitrate: ${bitrate / 1000} kbps")
            appendLine("Speed: ${Prefs.getPlaybackSpeedLabel(this@PlayerActivity)}")
        }.trimEnd()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.stats_title))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        bumpHideTimer()
    }

    /** v6.3: "info" dialog — channel, quality badge, provider, stream URL. */
    private fun showInfoDialog() {
        val quality = QualityBadge.fromName(channelName).ifBlank { "—" }
        val active = ProviderStore.getActive(this)
        val providerText = if (active != null)
            "${ProviderStore.displayName(active)} • ${ProviderStore.typeLabel(active)}"
        else "—"
        val url = currentStreamUrl
        val streamText = when {
            url.isBlank() -> "—"
            url.length > 120 -> url.take(120) + "…"
            else -> url
        }
        val msg = buildString {
            appendLine("${getString(R.string.info_label_channel)}: ${channelName.ifBlank { getString(R.string.unknown_channel) }}")
            appendLine("${getString(R.string.info_label_quality)}: $quality")
            appendLine("${getString(R.string.info_label_provider)}: $providerText")
            appendLine("${getString(R.string.info_label_stream)}: $streamText")
        }.trimEnd()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.info_dialog_title))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        bumpHideTimer()
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

    // ------------------------------------------------------------ v5.4 gestures
    //
    // Phone only (TV uses D-pad). Vertical drag on the RIGHT third of the
    // screen = volume, bar shown on the LEFT. Vertical drag on the LEFT
    // third = brightness, bar shown on the RIGHT. (Swapped per his choice.)
    // Bars are gold Imperial overlays that fade after ~1.5s.

    private fun setupGestures() {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        binding.playerRoot.setOnTouchListener { _, event ->
            if (isTvFocus()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val w = binding.playerRoot.width.toFloat()
                    gestureMode = when {
                        event.x > w * 2f / 3f -> GESTURE_VOLUME
                        event.x < w / 3f -> GESTURE_BRIGHTNESS
                        else -> GESTURE_NONE
                    }
                    gestureStartY = event.y
                    gestureDragged = false
                    gestureStartValue = when (gestureMode) {
                        GESTURE_VOLUME -> currentVolume().toFloat()
                        GESTURE_BRIGHTNESS -> currentBrightness()
                        else -> 0f
                    }
                    false // let tap-through to click still work
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gestureMode == GESTURE_NONE) return@setOnTouchListener false
                    // Only engage after passing touch slop — a tap stays a tap.
                    if (!gestureDragged &&
                        kotlin.math.abs(event.y - gestureStartY) < touchSlop
                    ) {
                        return@setOnTouchListener false
                    }
                    gestureDragged = true
                    val h = binding.playerRoot.height.toFloat().coerceAtLeast(1f)
                    val dy = gestureStartY - event.y // up = positive
                    val frac = (dy / h).coerceIn(-1f, 1f)
                    when (gestureMode) {
                        GESTURE_VOLUME -> {
                            val am = audioManager ?: return@setOnTouchListener true
                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (gestureStartValue + frac * max)
                                .toInt().coerceIn(0, max)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            showVolumeBar(v, max)
                        }
                        GESTURE_BRIGHTNESS -> {
                            val v = (gestureStartValue + frac).coerceIn(0.05f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = v
                            window.attributes = lp
                            showBrightnessBar(v)
                        }
                    }
                    true // consume the drag
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = gestureDragged
                    gestureMode = GESTURE_NONE
                    gestureDragged = false
                    // Schedule bar fade; a tap (no drag) falls through to click.
                    uiHandler.removeCallbacks(gestureHideRunnable)
                    uiHandler.postDelayed(gestureHideRunnable, 1500)
                    consumed
                }
                else -> false
            }
        }
    }

    private fun currentVolume(): Int {
        val am = audioManager ?: return 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0) 0.5f else b // <0 = system default; assume mid
    }

    private fun showVolumeBar(v: Int, max: Int) {
        binding.volumeBar.isVisible = true
        binding.volumeProgress.max = max.coerceAtLeast(1)
        binding.volumeProgress.progress = v
        uiHandler.removeCallbacks(gestureHideRunnable)
        uiHandler.postDelayed(gestureHideRunnable, 1500)
    }

    private fun showBrightnessBar(v: Float) {
        binding.brightnessBar.isVisible = true
        binding.brightnessProgress.progress = (v * 100).toInt()
        uiHandler.removeCallbacks(gestureHideRunnable)
        uiHandler.postDelayed(gestureHideRunnable, 1500)
    }

    private fun hideGestureBars() {
        binding.volumeBar.isVisible = false
        binding.brightnessBar.isVisible = false
    }

    // ------------------------------------------------------------ v5.4 recording
    // Ported from FlowPlay's StreamRecorder (HLS segment stitching + raw
    // copy), with Stalker MAG headers so gated streams record.

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
            return
        }
        val url = currentStreamUrl
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.record_no_stream), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val api = SourceManager.get(this@PlayerActivity)
                val headers = api.streamHeaders()
                val rec = StreamRecorder()
                rec.setExtraHeaders(headers)
                val name = StreamRecorder.fileNameFor(channelName.ifBlank { "channel" })
                rec.start(this@PlayerActivity, url, name, object : StreamRecorder.Listener {
                    override fun onStarted(f: String) {
                        runOnUiThread {
                            isRecording = true
                            updateRecordButton()
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.record_started, f),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    override fun onStopped(f: String, bytes: Long) {
                        runOnUiThread {
                            isRecording = false
                            updateRecordButton()
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.record_saved, f),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    override fun onError(msg: String) {
                        runOnUiThread {
                            isRecording = false
                            updateRecordButton()
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.record_failed, msg),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
                recorder = rec
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.record_failed, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // ignore
        }
        recorder = null
        isRecording = false
        updateRecordButton()
    }

    private fun updateRecordButton() {
        // v5.5: btnRecord is now an ImageButton (vector) — tint red while
        // recording, gold otherwise.
        binding.btnRecord.setColorFilter(
            if (isRecording) Color.RED else getColor(R.color.gold),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }

    // ------------------------------------------------------------ v5.4 FlowPlay extras

    /** Quick mute toggle (FlowPlay). */
    private fun toggleMute() {
        muted = !muted
        try {
            player?.volume = if (muted) 0f else 1f
        } catch (e: Exception) {
            // ignore
        }
        // v5.5: keep the transport volume button in sync.
        updateVolumeIcon()
        Toast.makeText(
            this,
            getString(if (muted) R.string.muted else R.string.unmuted),
            Toast.LENGTH_SHORT
        ).show()
        bumpHideTimer()
    }

    /** Hand the stream URL to another video app (FlowPlay). */
    private fun openExternal() {
        val url = currentStreamUrl
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.record_no_stream), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val mime = if (url.lowercase().contains(".m3u8")) "application/x-mpegURL"
            else "video/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
            startActivity(Intent.createChooser(i, getString(R.string.open_with)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_for_stream), Toast.LENGTH_SHORT).show()
        }
        bumpHideTimer()
    }

    /** Previous channel in the drawer list (FlowPlay). */
    private fun prevChannel() {
        stepChannel(-1)
    }

    /** Next channel in the drawer list (FlowPlay). */
    private fun nextChannel() {
        stepChannel(1)
    }

    private fun stepChannel(dir: Int) {
        if (drawerChannels.isEmpty()) return
        val cur = currentChannel
        val idx = drawerChannels.indexOfFirst { it.id == cur?.id }
            .takeIf { it >= 0 } ?: 0
        val next = drawerChannels[
            (idx + dir + drawerChannels.size) % drawerChannels.size
        ]
        switchChannel(next)
    }

    /** Favorite toggle (FlowPlay). */
    private fun toggleFavorite() {
        val ch = currentChannel ?: return
        val nowFav = Prefs.toggleFavorite(this, ch.id)
        Toast.makeText(
            this,
            getString(if (nowFav) R.string.fav_added else R.string.fav_removed, ch.name),
            Toast.LENGTH_SHORT
        ).show()
        bumpHideTimer()
    }

    /** Video zoom/scale (FlowPlay's setVideoScale). Cycles 100% → 125% → 150% → 100%. */
    private fun cycleZoom() {
        videoScale = when {
            videoScale < 1.1f -> 1.25f
            videoScale < 1.4f -> 1.5f
            else -> 1.0f
        }
        binding.playerView.scaleX = videoScale
        binding.playerView.scaleY = videoScale
        Toast.makeText(
            this,
            getString(R.string.zoom_level, (videoScale * 100).toInt()),
            Toast.LENGTH_SHORT
        ).show()
        bumpHideTimer()
    }

    // ------------------------------------------------------------ transport

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseIcon()
        bumpHideTimer()
    }

    private fun updatePlayPauseIcon() {
        // v5.5: vector icons (the ⏸/▶ text glyphs may not exist on TV fonts).
        binding.btnPlayPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /** v5.5: ±10s seek (FlowPlay) — VOD only. */
    private fun seekBy(ms: Long) {
        val p = player ?: return
        if (!isVod) return
        val dur = p.duration
        val target = (p.currentPosition + ms).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
        p.seekTo(target)
    }

    /** v5.5: volume/mute button icon follows mute state (FlowPlay SpeakerIcon). */
    private fun updateVolumeIcon() {
        binding.btnVolume.setImageResource(
            if (muted) R.drawable.ic_volume_mute else R.drawable.ic_volume
        )
    }

    // ----- v5.5: screen lock (FlowPlay) -----

    private var locked = false

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            hideControls()
            closeDrawers()
            binding.lockOverlay.isVisible = true
            binding.lockOverlay.requestFocus()
        } else {
            binding.lockOverlay.isVisible = false
            showControls()
        }
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
                val api = SourceManager.get(this@PlayerActivity)
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
        currentStreamUrl = url

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
        uiHandler.removeCallbacks(gestureHideRunnable)
        sleepJob?.cancel()
        sleepJob = null
        // v5.4: stop any active recording.
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // ignore
        }
        recorder = null
        isRecording = false
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
