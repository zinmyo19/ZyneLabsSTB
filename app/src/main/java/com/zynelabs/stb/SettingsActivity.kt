package com.zynelabs.stb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zynelabs.stb.databinding.ActivityListBinding
import kotlinx.coroutines.launch

/**
 * Settings screen (STBEmu-style): clean sections, no duplication.
 * v6.3: provider management moved to the Providers hub
 * (ProviderListActivity) — "Portal settings", "Provider settings" and
 * "Add provider…" are gone. Every row is D-pad focusable;
 * OK cycles the value for cycling rows.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val adapter = RowAdapter { item -> onRowClick(item.id) }

    companion object {
        // v6.3: section ids all start with "header_" (non-clickable).
        private const val ID_HDR_PROVIDERS = "header_providers"
        private const val ID_PROVIDERS = "providers"
        private const val ID_QR_PAIR = "qr_pair"
        private const val ID_PROVIDER_DETAILS = "provider_details"
        private const val ID_RECONNECT = "provider_reconnect"
        private const val ID_RESET = "reset"

        private const val ID_HDR_PLAYBACK = "header_playback"
        private const val ID_ASPECT = "aspect"
        private const val ID_PLAYER = "player"
        private const val ID_SUBTITLES = "subtitles"
        private const val ID_AUDIO = "audio"

        private const val ID_HDR_PLAYER = "header_player"
        private const val ID_SPEED = "speed"
        private const val ID_SUB_SIZE = "sub_size"
        private const val ID_SUB_COLOR = "sub_color"
        private const val ID_BUFFER = "buffer"
        private const val ID_SLEEP = "sleep"

        private const val ID_HDR_DEVELOPER = "header_developer"
        private const val ID_PROBE = "probe"
        private const val ID_DEBUG = "debug"
        private const val ID_RESET_CAP = "reset_cap"
        private const val ID_CLEAR_CACHE = "clear_cache"
        private const val ID_VERSION = "version"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.progressBar.isVisible = false // static list, nothing to load

        binding.tvTitle.text = getString(R.string.section_settings)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val active = ProviderStore.getActive(this)
        val rows = arrayListOf(
            // v6.3: PROVIDERS hub (replaces the old Portal section).
            RowItem(ID_HDR_PROVIDERS, getString(R.string.section_providers), header = true),
            RowItem(
                ID_PROVIDERS,
                getString(R.string.provider_list_title),
                active?.let { ProviderStore.displayName(it) }
                    ?: getString(R.string.no_provider)
            ),
            RowItem(ID_QR_PAIR, getString(R.string.pair_qr_title), ""),
            RowItem(ID_PROVIDER_DETAILS, getString(R.string.provider_details_row), ""),
            RowItem(ID_RECONNECT, getString(R.string.provider_reconnect_row), ""),
            RowItem(ID_RESET, getString(R.string.reset_connection_row), ""),

            RowItem(ID_HDR_PLAYBACK, getString(R.string.section_playback), header = true),
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
            ),

            RowItem(ID_HDR_PLAYER, getString(R.string.section_player), header = true),
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
                ID_BUFFER,
                getString(R.string.setting_buffer),
                Prefs.getBufferSize(this)
            ),
            RowItem(
                ID_SLEEP,
                getString(R.string.setting_sleep),
                Prefs.getSleepTimer(this)
            ),

            RowItem(ID_HDR_DEVELOPER, getString(R.string.section_developer), header = true),
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
        adapter.submitList(rows)
    }

    private fun onRowClick(id: String) {
        // Section headers are not interactive.
        if (id.startsWith("header_")) return
        when (id) {
            ID_PROVIDERS -> {
                startActivity(Intent(this, ProviderListActivity::class.java))
                return
            }
            ID_QR_PAIR -> {
                // v6.3: QR pairing activity (parallel agent adds QrPairActivity).
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
