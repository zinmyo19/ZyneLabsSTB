package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.zynelabs.stb.databinding.ActivityListBinding

/**
 * Settings screen (STBEmu-style): aspect ratio, media player,
 * subtitles, audio language, portal settings, version.
 * Every row is D-pad focusable; OK cycles the value.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val adapter = RowAdapter { item -> onRowClick(item.id) }

    companion object {
        private const val ID_ASPECT = "aspect"
        private const val ID_PLAYER = "player"
        private const val ID_SUBTITLES = "subtitles"
        private const val ID_AUDIO = "audio"
        private const val ID_PORTAL = "portal"
        private const val ID_PROBE = "probe"
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
        val rows = listOf(
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
            RowItem(
                ID_PORTAL,
                getString(R.string.setting_portal),
                Prefs.getMac(this)
            ),
            RowItem(
                ID_PROBE,
                getString(R.string.probe_row),
                ""
            ),
            RowItem(
                ID_VERSION,
                getString(R.string.setting_version),
                BuildConfig.VERSION_NAME
            )
        )
        adapter.submitList(rows)
    }

    private fun onRowClick(id: String) {
        when (id) {
            ID_ASPECT -> Prefs.cycleAspectRatio(this)
            ID_SUBTITLES -> Prefs.setSubtitlesEnabled(
                this, !Prefs.getSubtitlesEnabled(this)
            )
            ID_AUDIO -> Prefs.cycleAudioLang(this)
            ID_PORTAL -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SETUP, true)
                )
                return
            }
            ID_PROBE -> {
                startActivity(Intent(this, ProbeActivity::class.java))
                return
            }
            else -> return
        }
        refresh()
    }
}
