package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zynelabs.stb.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Portal settings screen: Stalker portal URL + box MAC address.
 * Saved to SharedPreferences; validated before connecting.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etPortalUrl.setText(Prefs.getPortalUrl(this))
        binding.etMac.setText(Prefs.getMac(this))

        binding.btnConnect.setOnClickListener { connect() }
    }

    private fun connect() {
        val portalUrl = binding.etPortalUrl.text.toString()
        val mac = binding.etMac.text.toString()

        if (!Prefs.isValidPortalUrl(portalUrl)) {
            binding.tvStatus.text = getString(R.string.error_invalid_url)
            binding.etPortalUrl.requestFocus()
            return
        }
        if (!Prefs.isValidMac(mac)) {
            binding.tvStatus.text = getString(R.string.error_invalid_mac)
            binding.etMac.requestFocus()
            return
        }

        Prefs.save(this, portalUrl, mac)
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = StalkerApi(
                    Prefs.getPortalUrl(this@MainActivity),
                    Prefs.getMac(this@MainActivity)
                )
                api.handshake()
                api.getProfile() // validates the session works
                startActivity(Intent(this@MainActivity, ChannelListActivity::class.java))
                binding.tvStatus.text = ""
            } catch (e: Exception) {
                binding.tvStatus.text =
                    getString(R.string.error_connect_failed, e.message.orEmpty())
            } finally {
                binding.btnConnect.isEnabled = true
            }
        }
    }
}
