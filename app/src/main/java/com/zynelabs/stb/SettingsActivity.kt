package com.zynelabs.stb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zynelabs.stb.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * Settings screen (STBEmu-style): clean sections, no duplication.
 * v6.3: provider management moved to the Providers hub
 * (ProviderListActivity) — "Portal settings", "Provider settings" and
 * "Add provider…" are gone. Every row is D-pad focusable;
 * OK cycles the value for cycling rows.
 * v6.3.1: real tabs — Providers / Playback / Player / Developer — instead
 * of one long scrolling list. Tab bar is D-pad navigable (LEFT/RIGHT
 * between tabs, DOWN into the list).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val adapter = RowAdapter { item -> onRowClick(item.id) }

    companion object {
        private const val TAB_PROVIDERS = 0
        private const val TAB_PLAYBACK = 1
        private const val TAB_PLAYER = 2
        private const val TAB_DEVELOPER = 3

        private const val ID_PROVIDERS = "providers"
        private const val ID_QR_PAIR = "qr_pair"
        private const val ID_PROVIDER_DETAILS = "provider_details"
        private const val ID_RECONNECT = "provider_reconnect"
        private const val ID_RESET = "reset"

        private const val ID_ASPECT = "aspect"
        private const val ID_PLAYER = "player"
        private const val ID_SUBTITLES = "subtitles"
        private const val ID_AUDIO = "audio"

        private const val ID_SPEED = "speed"
        private const val ID_SUB_SIZE = "sub_size"
        private const val ID_SUB_COLOR = "sub_color"
        private const val ID_BUFFER = "buffer"
        private const val ID_SLEEP = "sleep"

        private const val ID_PROBE = "probe"
        private const val ID_DEBUG = "debug"
        private const val ID_RESET_CAP = "reset_cap"
        private const val ID_CLEAR_CACHE = "clear_cache"
        private const val ID_VERSION = "version"
    }

    private var selectedTab: Int = TAB_PROVIDERS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.progressBar.isVisible = false // static list, nothing to load

        binding.tvTitle.text = getString(R.string.section_settings)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        selectedTab = Prefs.getSettingsTab(this)

        binding.tabProviders.setOnClickListener { selectTab(TAB_PROVIDERS) }
        binding.tabPlayback.setOnClickListener { selectTab(TAB_PLAYBACK) }
        binding.tabPlayer.setOnClickListener { selectTab(TAB_PLAYER) }
        binding.tabDeveloper.setOnClickListener { selectTab(TAB_DEVELOPER) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun selectTab(tab: Int) {
        if (tab == selectedTab) return
        selectedTab = tab
        Prefs.setSettingsTab(this, tab)
        refresh()
    }

    /** v6.3.1: highlight the active tab (gold fill), dim the rest. */
    private fun styleTabs() {
        val tabs = listOf(
            binding.tabProviders,
            binding.tabPlayback,
            binding.tabPlayer,
            binding.tabDeveloper
        )
        tabs.forEachIndexed { i, btn ->
            styleTabButton(btn, i == selectedTab)
        }
    }

    private fun styleTabButton(btn: Button, selected: Boolean) {
        if (selected) {
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFD4AF37.toInt())
            btn.setTextColor(0xFF0A0F14.toInt())
        } else {
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF14202E.toInt())
            btn.setTextColor(0xFFD4AF37.toInt())
        }
    }

    private fun refresh() {
        styleTabs()
        val rows = when (selectedTab) {
            TAB_PLAYBACK -> playbackRows()
            TAB_PLAYER -> playerRows()
            TAB_DEVELOPER -> developerRows()
            else -> providerRows()
        }
        adapter.submitList(rows)
        binding.recyclerView.scrollToPosition(0)
    }

    private fun providerRows(): List<RowItem> {
        val active = ProviderStore.getActive(this)
        return listOf(
            RowItem(
                ID_PROVIDERS,
                getString(R.string.provider_list_title),
                active?.let { ProviderStore.displayName(it) }
                    ?: getString(R.string.no_provider)
            ),
            RowItem(ID_QR_PAIR, getString(R.string.pair_qr_title), ""),
            RowItem(ID_PROVIDER_DETAILS, getString(R.string.provider_details_row), ""),
            RowItem(ID_RECONNECT, getString(R.string.provider_reconnect_row), ""),
            RowItem(ID_RESET, getString(R.string.reset_connection_row), "")
        )
    }

    private fun playbackRows(): List<RowItem> = listOf(
        RowItem(
            ID_ASPECT,
            getString(R.string.setting_aspect),
            Prefs.getAspectRatio(this)
        ),
        RowItem(
            ID_PLAYER,
            getString(R.string.setting_player),
            getString(R.string.player_exo)
        ),
        RowItem(
            ID_SUBTITLES,
            getString(R.string.setting_subtitles),
            getString(
                if (Prefs.getSubtitlesEnabled(this)) R.string.on else R.string.off
            )
        ),
        RowItem(
            ID_AUDIO,
            getString(R.string.setting_audio_lang),
            Prefs.getAudioLangLabel(this)
        )
    )

    private fun playerRows(): List<RowItem> = listOf(
        RowItem(
            ID_SPEED,
            getString(R.string.setting_speed),
            Prefs.getPlaybackSpeedLabel(this)
        ),
        RowItem(
            ID_SUB_SIZE,
            getString(R.string.setting_subtitle_size),
            Prefs.getSubtitleSize(this)
        ),
        RowItem(
            ID_SUB_COLOR,
            getString(R.string.setting_subtitle_color),
            Prefs.getSubtitleColor(this)
        ),
        RowItem(
            ID_SLEEP,
            getString(R.string.setting_sleep),
            Prefs.getSleepTimer(this)
        ),
        RowItem(
            ID_BUFFER,
            getString(R.string.setting_buffer),
            Prefs.getBufferSize(this)
        )
    )

    private fun developerRows(): List<RowItem> = listOf(
        RowItem(ID_PROBE, getString(R.string.probe_row), ""),
        RowItem(ID_DEBUG, getString(R.string.debug_row), ""),
        RowItem(ID_RESET_CAP, getString(R.string.reset_cap_row), ""),
        RowItem(ID_CLEAR_CACHE, getString(R.string.clear_cache_row), ""),
        RowItem(
            ID_VERSION,
            getString(R.string.setting_version),
            BuildConfig.VERSION_NAME
        )
    )

    private fun onRowClick(id: String) {
        when (id) {
            ID_PROVIDERS -> {
                startActivity(Intent(this, ProviderListActivity::class.java))
                return
            }
            ID_QR_PAIR -> {
                startActivity(Intent(this, QrPairActivity::class.java))
                return
            }
            ID_PROVIDER_DETAILS -> {
                showProviderDetails()
                return
            }
            ID_RECONNECT -> {
                reconnect()
                return
            }
            ID_ASPECT -> Prefs.cycleAspectRatio(this)
            ID_SUBTITLES -> Prefs.setSubtitlesEnabled(
                this, !Prefs.getSubtitlesEnabled(this)
            )
            ID_AUDIO -> Prefs.cycleAudioLang(this)
            ID_SPEED -> Prefs.cyclePlaybackSpeed(this)
            ID_SUB_SIZE -> Prefs.cycleSubtitleSize(this)
            ID_SUB_COLOR -> Prefs.cycleSubtitleColor(this)
            ID_BUFFER -> Prefs.cycleBufferSize(this)
            ID_SLEEP -> Prefs.cycleSleepTimer(this)
            ID_PROBE -> {
                startActivity(Intent(this, ProbeActivity::class.java))
                return
            }
            ID_DEBUG -> {
                // Copy last handshake/profile diagnostics to clipboard.
                val info = SourceManager.get(this).buildDebugInfo()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ZyneLabs STB debug", info))
                Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
                return
            }
            ID_RESET_CAP -> {
                // Testing escape hatch — clears ONLY the handshake
                // timestamps (resets the 1-hour cap), keeps the session.
                SourceManager.get(this).resetHandshakeCap()
                Toast.makeText(this, R.string.cap_reset, Toast.LENGTH_SHORT).show()
                return
            }
            ID_CLEAR_CACHE -> {
                // v6.3: full cache wipe (memory + disk list caches).
                SourceManager.clearAllCaches(this)
                Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                return
            }
            ID_RESET -> {
                // Manual escape hatch — clears the cached session
                // (memory + prefs) AND the handshake timestamps, so the
                // next Connect starts completely fresh (no waiting out
                // a poisoned rate-limit cap).
                SourceManager.get(this).resetConnection()
                Toast.makeText(this, R.string.connection_reset, Toast.LENGTH_SHORT).show()
                return
            }
            else -> {
                return
            }
        }
        refresh()
    }

    /** v6.3: one dialog with the active provider's portal info. */
    private fun showProviderDetails() {
        if (!SourceManager.hasProvider(this)) {
            Toast.makeText(
                this, R.string.no_provider, Toast.LENGTH_SHORT
            ).show()
            return
        }
        lifecycleScope.launch {
            val info = try {
                SourceManager.get(this@SettingsActivity).getPortalInfo()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    (e.message ?: "Failed").take(100),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val lines = listOf(
                "${getString(R.string.provider_info_name)}: " +
                    info.providerName.ifBlank { "—" },
                "${getString(R.string.provider_info_type)}: " +
                    info.typeLabel.ifBlank { "—" },
                "${getString(R.string.provider_info_expires)}: " +
                    info.expireDate.ifBlank { "—" },
                "${getString(R.string.provider_info_channels)}: " +
                    if (info.channelCount >= 0) info.channelCount.toString() else "—",
                "${getString(R.string.provider_info_detail)}: " +
                    info.detail.ifBlank { "—" }
            ).joinToString("\n")
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.provider_details_row)
                .setMessage(lines)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /**
     * Reconnect — drops the cached session (memory + prefs) and
     * forces a fresh handshake RIGHT NOW via getProfile(), then clears
     * the channel cache so lists reload on the new session. The manual
     * escape hatch for the empty-list bug (stale cached token, portal
     * answers 200 + empty data, no auth error to trigger self-healing).
     * Cap-checked inside the handshake — a "Too many connection
     * attempts" error surfaces as a toast, not a crash.
     */
    private fun reconnect() {
        lifecycleScope.launch {
            try {
                SourceManager.get(this@SettingsActivity).resetConnection()
                // resetConnection() drops the shared instance — this get()
                // builds a fresh source, and getProfile() handshakes.
                SourceManager.get(this@SettingsActivity).getProfile()
                ListCache.invalidateAll()
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.provider_reconnected),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    (e.message ?: "Reconnect failed").take(100),
                    Toast.LENGTH_LONG
                ).show()
            }
            refresh()
        }
    }

}
