package com.zynelabs.stb

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zynelabs.stb.databinding.ActivityListBinding
import kotlinx.coroutines.launch

/**
 * Settings screen (STBEmu-style): aspect ratio, media player,
 * subtitles, audio language, portal settings, version.
 * Every row is D-pad focusable; OK cycles the value.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val adapter = RowAdapter { item -> onRowClick(item.id) }

    companion object {
        private const val ID_HDR_PLAYBACK = "header_playback"
        private const val ID_ASPECT = "aspect"
        private const val ID_PLAYER = "player"
        private const val ID_SUBTITLES = "subtitles"
        private const val ID_AUDIO = "audio"
        private const val ID_HDR_PORTAL = "header_portal"
        private const val ID_PORTAL = "portal"
        private const val ID_RESET = "reset"
        // v5.7: provider management lives here so the TV remote (D-pad) can
        // reach it — the home header chip is not D-pad-focusable on TV.
        private const val ID_HDR_PROVIDERS = "header_providers"
        private const val ID_PROVIDER_ADD = "provider_add"
        private const val ID_PROVIDER_DELETE = "provider_delete"
        // v5.8: Reconnect — drops the stale cached session and forces a
        // fresh handshake now (empty-list bug: cached token dead
        // server-side, portal answers 200+empty, no auth error).
        private const val ID_RECONNECT = "provider_reconnect"
        private const val PROVIDER_ROW_PREFIX = "provider:"
        private const val ID_HDR_DEVELOPER = "header_developer"
        private const val ID_PROBE = "probe"
        private const val ID_DEBUG = "debug"
        private const val ID_VERSION = "version"
        // v5.0 player settings
        private const val ID_HDR_PLAYER = "header_player"
        private const val ID_SPEED = "speed"
        private const val ID_SUB_SIZE = "sub_size"
        private const val ID_SUB_COLOR = "sub_color"
        private const val ID_BUFFER = "buffer"
        private const val ID_SLEEP = "sleep"
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
        // v5.3: condensed into sections (was one long flat list).
        // v5.7: provider list rows are built dynamically below.
        val rows = arrayListOf(
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
            RowItem(ID_HDR_PORTAL, getString(R.string.section_portal), header = true),
            RowItem(
                ID_PORTAL,
                getString(R.string.setting_portal),
                Prefs.getMac(this)
            ),
            RowItem(
                ID_RESET,
                getString(R.string.reset_connection_row),
                ""
            )
        )
        // v5.7: provider management — switch / add / delete, all D-pad
        // reachable (the home header chip is not reachable by TV remote).
        rows.add(RowItem(ID_HDR_PROVIDERS, getString(R.string.section_providers), header = true))
        val activeId = ProviderStore.getActiveId(this)
        for (p in ProviderStore.list(this)) {
            rows.add(
                RowItem(
                    PROVIDER_ROW_PREFIX + p.id,
                    (if (p.id == activeId) "● " else "○ ") +
                        ProviderStore.displayName(p),
                    p.mac
                )
            )
        }
        rows.add(RowItem(ID_PROVIDER_ADD, getString(R.string.provider_add_row), ""))
        rows.add(RowItem(ID_PROVIDER_DELETE, getString(R.string.provider_delete_row), ""))
        // v5.8: Reconnect sits with the providers (D-pad reachable) —
        // one tap drops the stale cached session and handshakes fresh.
        rows.add(RowItem(ID_RECONNECT, getString(R.string.provider_reconnect_row), ""))
        rows.add(RowItem(ID_HDR_DEVELOPER, getString(R.string.section_developer), header = true))
        rows.add(RowItem(ID_PROBE, getString(R.string.probe_row), ""))
        rows.add(RowItem(ID_DEBUG, getString(R.string.debug_row), ""))
        rows.add(
            RowItem(
                ID_VERSION,
                getString(R.string.setting_version),
                BuildConfig.VERSION_NAME
            )
        )
        adapter.submitList(rows)
    }

    private fun onRowClick(id: String) {
        // v5.3: section headers are not interactive.
        if (id.startsWith("header_")) return
        when (id) {
            ID_ASPECT -> Prefs.cycleAspectRatio(this)
            ID_SUBTITLES -> Prefs.setSubtitlesEnabled(
                this, !Prefs.getSubtitlesEnabled(this)
            )
            ID_AUDIO -> Prefs.cycleAudioLang(this)
            // v5.0 player settings
            ID_SPEED -> Prefs.cyclePlaybackSpeed(this)
            ID_SUB_SIZE -> Prefs.cycleSubtitleSize(this)
            ID_SUB_COLOR -> Prefs.cycleSubtitleColor(this)
            ID_BUFFER -> Prefs.cycleBufferSize(this)
            ID_SLEEP -> Prefs.cycleSleepTimer(this)
            ID_PORTAL -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SETUP, true)
                )
                return
            }
            // v5.7: provider management (D-pad reachable).
            ID_PROVIDER_ADD -> {
                startActivity(Intent(this, AddProviderActivity::class.java))
                return
            }
            ID_PROVIDER_DELETE -> {
                showDeleteProviderDialog()
                return
            }
            // v5.8: Reconnect — D-pad reachable, runs the handshake now.
            ID_RECONNECT -> {
                reconnect()
                return
            }
            ID_PROBE -> {
                startActivity(Intent(this, ProbeActivity::class.java))
                return
            }
            ID_DEBUG -> {
                // v3.4: copy last handshake/profile diagnostics to clipboard
                val info = StalkerSession.get(this).buildDebugInfo()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ZyneLabs STB debug", info))
                Toast.makeText(this, "Debug info copied", Toast.LENGTH_SHORT).show()
                return
            }
            ID_RESET -> {
                // v4.7: manual escape hatch — clears the cached session
                // (memory + prefs) AND the handshake timestamps, so the
                // next Connect starts completely fresh (no waiting out
                // a poisoned rate-limit cap).
                StalkerSession.get(this).resetConnection()
                Toast.makeText(this, "Connection reset", Toast.LENGTH_SHORT).show()
                return
            }
            else -> {
                // v5.7: tapping a saved provider row switches to it.
                if (id.startsWith(PROVIDER_ROW_PREFIX)) {
                    switchProvider(id.removePrefix(PROVIDER_ROW_PREFIX))
                    return
                }
                return
            }
        }
        refresh()
    }

    /** v5.7: switch the active provider (same semantics as the home chip). */
    private fun switchProvider(id: String) {
        val p = ProviderStore.get(this, id) ?: return
        if (ProviderStore.getActiveId(this) == p.id) return
        ProviderStore.setActive(this, p.id)
        ProviderStore.applyActive(this)
        ListCache.invalidateAll()
        Toast.makeText(
            this,
            getString(R.string.provider_switched, ProviderStore.displayName(p)),
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    /**
     * v5.8: Reconnect — drops the cached session (memory + prefs) and
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
                StalkerSession.get(this@SettingsActivity).resetConnection()
                // resetConnection() drops the shared instance — this get()
                // builds a fresh StalkerApi, and getProfile() handshakes.
                StalkerSession.get(this@SettingsActivity).getProfile()
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

    /** v5.7: delete-provider picker (mirrors HomeActivity's dialog). */    private fun showDeleteProviderDialog() {
        val providers = ProviderStore.list(this)
        if (providers.isEmpty()) return
        val items = providers.map { ProviderStore.displayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.provider_delete_title)
            .setItems(items) { _, which ->
                val doomed = providers[which]
                ProviderStore.delete(this, doomed.id)
                if (ProviderStore.getActiveId(this) == doomed.id) {
                    ProviderStore.list(this).firstOrNull()?.let {
                        ProviderStore.setActive(this, it.id)
                    }
                }
                ProviderStore.applyActive(this)
                ListCache.invalidateAll()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
