package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.zynelabs.stb.databinding.ActivityHomeBinding
import com.zynelabs.stb.databinding.ItemHomeTileBinding

/**
 * Main carousel: TV / Video Club / TV Series / Radio / Settings.
 * Horizontal tile rail, 100% D-pad navigable (STBEmu-style home).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private data class Tile(val icon: String, val labelRes: Int, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvPortalInfo.text = getString(R.string.home_portal_info, Prefs.getMac(this))

        val tiles = listOf(
            Tile("\uD83D\uDCFA", R.string.section_tv) {
                startActivity(Intent(this, GroupsActivity::class.java))
            },
            Tile("\uD83C\uDFAC", R.string.section_videoclub) {
                startActivity(
                    Intent(this, VodBrowserActivity::class.java)
                        .putExtra(VodBrowserActivity.EXTRA_MODE, VodBrowserActivity.MODE_MOVIES)
                )
            },
            Tile("\uD83D\uDCFC", R.string.section_series) {
                startActivity(
                    Intent(this, VodBrowserActivity::class.java)
                        .putExtra(VodBrowserActivity.EXTRA_MODE, VodBrowserActivity.MODE_SERIES)
                )
            },
            Tile("\uD83D\uDCFB", R.string.section_radio) {
                startActivity(Intent(this, RadioActivity::class.java))
            },
            Tile("⚙️", R.string.section_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )

        val inflater = LayoutInflater.from(this)
        var firstTile: android.view.View? = null
        for (tile in tiles) {
            val tileBinding = ItemHomeTileBinding.inflate(inflater, binding.tileRow, false)
            tileBinding.tvIcon.text = tile.icon
            tileBinding.tvLabel.text = getString(tile.labelRes)
            tileBinding.root.setOnClickListener { tile.action() }
            binding.tileRow.addView(tileBinding.root)
            if (firstTile == null) firstTile = tileBinding.root
        }
        firstTile?.requestFocus()
    }
}
