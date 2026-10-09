package com.zynelabs.stb

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zynelabs.stb.databinding.ActivityAddProviderBinding

/**
 * v5.6: Add Provider — portal URL + box MAC (+ optional name).
 * "Scan QR" fills the URL from a camera QR scan (QrScanActivity).
 * QR payload: plain portal URL, or "url|mac", or "stb://host/path|mac".
 * Saved provider becomes active immediately and home reloads on it.
 *
 * v6.2: also edits/deletes providers. Launched with [EXTRA_PROVIDER_ID] it
 * pre-fills the form for that provider (edit mode) and shows a Delete
 * button; without it, it's the original add-new flow. This is the single
 * provider-management screen, reached from Settings → Portal → Provider
 * settings (D-pad navigable).
 */
class AddProviderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProviderBinding
    private var editId: String? = null

    companion object {
        const val EXTRA_QR_URL = "qr_url"
        const val EXTRA_QR_MAC = "qr_mac"
        const val EXTRA_PROVIDER_ID = "provider_id"
        private const val REQ_QR = 1001
        private const val REQ_CAMERA = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProviderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v6.2: edit mode when launched with a provider id.
        editId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val existing = editId?.let { ProviderStore.get(this, it) }
        if (existing != null) {
            binding.tvTitle.text = getString(R.string.provider_edit_title)
            binding.etUrl.setText(existing.url)
            binding.etMac.setText(existing.mac)
            binding.etName.setText(existing.name)
            binding.btnDelete.visibility = View.VISIBLE
        }

        binding.btnScanQr.setOnClickListener { startQrScan() }
        binding.btnSave.setOnClickListener { saveProvider() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun startQrScan() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startActivityForResult(
                    Intent(this, QrScanActivity::class.java), REQ_QR
                )
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA
                )
            }
        } else {
            Toast.makeText(this, "No camera on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_QR)
        }
    }

    @Deprecated("use Activity Result API on next touch")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_QR && resultCode == RESULT_OK && data != null) {
            data.getStringExtra(EXTRA_QR_URL)?.takeIf { it.isNotBlank() }?.let {
                binding.etUrl.setText(it)
            }
            data.getStringExtra(EXTRA_QR_MAC)?.takeIf { it.isNotBlank() }?.let {
                binding.etMac.setText(it)
            }
        }
    }

    private fun saveProvider() {
        val url = binding.etUrl.text.toString().trim().trimEnd('/')
        val mac = binding.etMac.text.toString().trim().uppercase()
        val name = binding.etName.text.toString().trim()
        if (url.isBlank() || !url.startsWith("http")) {
            binding.etUrl.error = "Enter a valid portal URL"
            return
        }
        if (!mac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
            binding.etMac.error = "MAC like 00:1A:79:00:00:00"
            return
        }
        // v6.2: edit mode updates the existing provider in place.
        val p = if (editId != null) {
            Provider(editId!!, name, url, mac)
        } else {
            Provider(ProviderStore.newId(), name, url, mac)
        }
        ProviderStore.save(this, p)
        ProviderStore.setActive(this, p.id)
        ProviderStore.applyActive(this)
        ListCache.invalidateAll()
        Toast.makeText(
            this,
            if (editId != null) getString(R.string.provider_updated) else "Provider saved",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    /** v6.2: delete the provider being edited (with confirmation). */
    private fun confirmDelete() {
        val id = editId ?: return
        ProviderStore.get(this, id) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_provider_title)
            .setMessage(getString(R.string.delete_provider_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ProviderStore.delete(this, id)
                if (ProviderStore.getActiveId(this) == id) {
                    ProviderStore.list(this).firstOrNull()?.let {
                        ProviderStore.setActive(this, it.id)
                    }
                }
                ProviderStore.applyActive(this)
                ListCache.invalidateAll()
                Toast.makeText(
                    this, getString(R.string.provider_deleted), Toast.LENGTH_SHORT
                ).show()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
