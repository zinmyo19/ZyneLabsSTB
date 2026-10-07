package com.zynelabs.stb

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.zynelabs.stb.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Portal settings screen: Stalker portal URL + box MAC address.
 * Saved to SharedPreferences; validated before connecting.
 *
 * Provider selector: several portals can be saved (add/edit/delete) and
 * switched without clearing app data. Connect saves the current fields
 * as a provider first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var providers: List<Provider> = emptyList()
    /** null = "+ New provider" selected. */
    private var selectedProviderId: String? = null
    private var suppressSelectionEvent = false

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

        binding.spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                if (suppressSelectionEvent) return
                applyProviderSelection(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.btnDeleteProvider.setOnClickListener { confirmDeleteProvider() }
        binding.btnConnect.setOnClickListener { connect() }

        // Settings reachable without a successful connection.
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshProviderSpinner()
    }

    // ---------------------------------------------------------- providers

    /** Rebuilds the spinner; selects the provider matching current prefs (or "+ New"). */
    private fun refreshProviderSpinner() {
        providers = ProviderStore.list(this)
        val items = ArrayList<String>(providers.size + 1)
        items.add(getString(R.string.provider_new))
        items.addAll(providers.map { ProviderStore.displayName(it) })
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSelectionEvent = true
        binding.spProvider.adapter = adapter
        // Pre-select the provider matching the currently stored portal/MAC.
        val currentUrl = Prefs.getPortalUrl(this)
        val currentMac = Prefs.getMac(this)
        val matchIdx = providers.indexOfFirst {
            it.url.trim().trimEnd('/') == currentUrl.trim().trimEnd('/') &&
                it.mac.trim().uppercase() == currentMac.trim().uppercase()
        }
        if (matchIdx >= 0) {
            selectedProviderId = providers[matchIdx].id
            binding.spProvider.setSelection(matchIdx + 1)
            binding.btnDeleteProvider.isVisible = true
        } else {
            selectedProviderId = null
            binding.spProvider.setSelection(0)
            binding.btnDeleteProvider.isVisible = false
        }
        suppressSelectionEvent = false
    }

    /** position 0 = "+ New provider", otherwise providers[position - 1]. */
    private fun applyProviderSelection(position: Int) {
        if (position <= 0 || position - 1 >= providers.size) {
            selectedProviderId = null
            binding.etPortalUrl.setText("")
            binding.etMac.setText("")
            binding.btnDeleteProvider.isVisible = false
        } else {
            val p = providers[position - 1]
            selectedProviderId = p.id
            binding.etPortalUrl.setText(p.url)
            binding.etMac.setText(p.mac)
            binding.btnDeleteProvider.isVisible = true
        }
    }

    private fun confirmDeleteProvider() {
        val id = selectedProviderId ?: return
        val p = ProviderStore.get(this, id) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_provider_title))
            .setMessage(getString(R.string.delete_provider_msg, ProviderStore.displayName(p)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ProviderStore.delete(this, id)
                selectedProviderId = null
                binding.etPortalUrl.setText("")
                binding.etMac.setText("")
                refreshProviderSpinner()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ connect

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

        // Save the current fields as a provider first (update selected, else insert).
        val id = selectedProviderId ?: ProviderStore.newId()
        ProviderStore.save(
            this,
            Provider(
                id = id,
                name = "",
                url = portalUrl.trim().trimEnd('/'),
                mac = mac.trim().uppercase()
            )
        )
        selectedProviderId = id

        Prefs.save(this, portalUrl, mac)
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                StalkerSession.reset() // URL/MAC may have changed on the setup screen
                val api = StalkerSession.get(this@MainActivity)
                api.handshake()
                val profile = api.getProfile() // validates the session works
                if (!api.isMacRegistered(profile)) {
                    throw StalkerApi.StalkerException("MAC not registered on this portal")
                }
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
