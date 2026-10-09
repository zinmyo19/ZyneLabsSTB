package com.zynelabs.stb

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
 *
 * v6.3: four provider types — Stalker portal, M3U link, M3U file,
 * Xtream login — picked with a Spinner at the top; the matching section
 * shows/hides. Type is fixed (spinner disabled) in edit mode. M3U files
 * are picked via the file picker and stored as a content URI string in
 * [Provider.filePath]; a persistable read permission is taken so the URI
 * stays usable across restarts.
 */
class AddProviderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProviderBinding
    private var editId: String? = null
    private var selectedType: String = ProviderStore.TYPE_STALKER
    private var fileUri: Uri? = null

    companion object {
        const val EXTRA_QR_URL = "qr_url"
        const val EXTRA_QR_MAC = "qr_mac"
        const val EXTRA_PROVIDER_ID = "provider_id"
        private const val REQ_QR = 1001
        private const val REQ_CAMERA = 1002

        /** Spinner positions must match the @array/provider_type_labels order. */
        private val TYPE_BY_POSITION = listOf(
            ProviderStore.TYPE_STALKER,
            ProviderStore.TYPE_M3U_URL,
            ProviderStore.TYPE_M3U_FILE,
            ProviderStore.TYPE_XTREAM
        )
    }

    /** v6.3: M3U file picker. */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Provider did not allow persistence; keep the one-shot grant.
        } catch (_: Exception) {
            // Name resolution may fail on odd providers — still usable.
        }
        fileUri = uri
        binding.tvFileName.text = uri.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProviderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?,
                    position: Int, id: Long
                ) {
                    selectedType = TYPE_BY_POSITION.getOrElse(position) {
                        ProviderStore.TYPE_STALKER
                    }
                    showSection(selectedType)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // v6.2/v6.3: edit mode when launched with a provider id.
        editId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val existing = editId?.let { ProviderStore.get(this, it) }
        if (existing != null) {
            selectedType = existing.type
            binding.tvTitle.text = getString(R.string.provider_edit_title)
            binding.spinnerType.setSelection(
                TYPE_BY_POSITION.indexOf(existing.type).takeIf { it >= 0 } ?: 0
            )
            // Type is fixed once created.
            binding.spinnerType.isEnabled = false
            binding.spinnerType.alpha = 0.5f
            when (existing.type) {
                ProviderStore.TYPE_M3U_URL -> binding.etM3uUrl.setText(existing.url)
                ProviderStore.TYPE_M3U_FILE -> {
                    existing.filePath.takeIf { it.isNotBlank() }?.let {
                        fileUri = runCatching { Uri.parse(it) }.getOrNull()
                        binding.tvFileName.text = it
                    }
                }
                ProviderStore.TYPE_XTREAM -> {
                    binding.etServer.setText(existing.url)
                    binding.etUsername.setText(existing.username)
                    binding.etPassword.setText(existing.password)
                }
                else -> { // Stalker
                    binding.etUrl.setText(existing.url)
                    binding.etMac.setText(existing.mac)
                }
            }
            binding.etName.setText(existing.name)
            binding.btnDelete.visibility = View.VISIBLE
            showSection(selectedType)
        } else {
            showSection(selectedType)
        }

        binding.btnScanQr.setOnClickListener { startQrScan() }
        binding.btnChooseFile.setOnClickListener { pickFile.launch("*/*") }
        binding.btnSave.setOnClickListener { saveProvider() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    /** v6.3: show only the section matching the selected provider type. */
    private fun showSection(type: String) {
        binding.sectionStalker.visibility =
            if (type == ProviderStore.TYPE_STALKER) View.VISIBLE else View.GONE
        binding.sectionM3uUrl.visibility =
            if (type == ProviderStore.TYPE_M3U_URL) View.VISIBLE else View.GONE
        binding.sectionM3uFile.visibility =
            if (type == ProviderStore.TYPE_M3U_FILE) View.VISIBLE else View.GONE
        binding.sectionXtream.visibility =
            if (type == ProviderStore.TYPE_XTREAM) View.VISIBLE else View.GONE
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
        val name = binding.etName.text.toString().trim()
        val type = selectedType
        val p = when (type) {
            ProviderStore.TYPE_M3U_URL -> {
                val url = binding.etM3uUrl.text.toString().trim().trimEnd('/')
                if (url.isBlank() || !url.startsWith("http")) {
                    binding.etM3uUrl.error = getString(R.string.provider_err_url)
                    return
                }
                Provider(
                    id = editId ?: ProviderStore.newId(),
                    name = name,
                    url = url,
                    mac = "",
                    type = type
                )
            }
            ProviderStore.TYPE_M3U_FILE -> {
                val uri = fileUri?.toString().orEmpty()
                if (uri.isBlank()) {
                    Toast.makeText(
                        this, getString(R.string.provider_err_choose_file),
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                Provider(
                    id = editId ?: ProviderStore.newId(),
                    name = name,
                    url = "",
                    mac = "",
                    type = type,
                    filePath = uri
                )
            }
            ProviderStore.TYPE_XTREAM -> {
                val server = binding.etServer.text.toString().trim().trimEnd('/')
                val user = binding.etUsername.text.toString().trim()
                val pass = binding.etPassword.text.toString().trim()
                if (server.isBlank() || !server.startsWith("http")) {
                    binding.etServer.error = getString(R.string.provider_err_url)
                    return
                }
                if (user.isBlank()) {
                    binding.etUsername.error =
                        getString(R.string.provider_err_required)
                    return
                }
                if (pass.isBlank()) {
                    binding.etPassword.error =
                        getString(R.string.provider_err_required)
                    return
                }
                Provider(
                    id = editId ?: ProviderStore.newId(),
                    name = name,
                    url = server,
                    mac = "",
                    type = type,
                    username = user,
                    password = pass
                )
            }
            else -> { // Stalker
                val url = binding.etUrl.text.toString().trim().trimEnd('/')
                val mac = binding.etMac.text.toString().trim().uppercase()
                if (url.isBlank() || !url.startsWith("http")) {
                    binding.etUrl.error = getString(R.string.provider_err_url)
                    return
                }
                if (!mac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
                    binding.etMac.error = getString(R.string.provider_err_mac)
                    return
                }
                Provider(
                    id = editId ?: ProviderStore.newId(),
                    name = name,
                    url = url,
                    mac = mac,
                    type = type
                )
            }
        }
        ProviderStore.save(this, p)
        ProviderStore.setActive(this, p.id)
        ProviderStore.applyActive(this)
        SourceManager.clearAllCaches(this)
        Toast.makeText(
            this,
            if (editId != null) getString(R.string.provider_updated)
            else getString(R.string.provider_saved),
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
                SourceManager.clearAllCaches(this)
                Toast.makeText(
                    this, getString(R.string.provider_deleted), Toast.LENGTH_SHORT
                ).show()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
