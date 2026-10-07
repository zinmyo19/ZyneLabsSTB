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

    companion object {
        /** When true, always show the portal form (used from Settings). */
        const val EXTRA_SETUP = "setup"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already configured? Go straight to the home carousel.
        if (!intent.getBooleanExtra(EXTRA_SETUP, false) && Prefs.isConfigured(this)) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etPortalUrl.setText(Prefs.getPortalUrl(this))
        binding.etMac.setText(Prefs.getMac(this))

        binding.btnConnect.setOnClickListener { connect() }

        // Settings reachable without a successful connection.
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
                StalkerSession.reset() // URL/MAC may have changed on the setup screen
                val api = StalkerSession.get(this@MainActivity)
                api.handshake()
                api.getProfile() // validates the session works
                startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                finish()
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
