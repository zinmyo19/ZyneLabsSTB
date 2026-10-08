package com.zynelabs.stb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
 */
class AddProviderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProviderBinding

    companion object {
        const val EXTRA_QR_URL = "qr_url"
        const val EXTRA_QR_MAC = "qr_mac"
        private const val REQ_QR = 1001
        private const val REQ_CAMERA = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProviderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScanQr.setOnClickListener { startQrScan() }
        binding.btnSave.setOnClickListener { saveProvider() }
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
        val p = Provider(ProviderStore.newId(), name, url, mac)
        ProviderStore.save(this, p)
        ProviderStore.setActive(this, p.id)
        ProviderStore.applyActive(this)
        ListCache.invalidateAll()
        Toast.makeText(this, "Provider saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
