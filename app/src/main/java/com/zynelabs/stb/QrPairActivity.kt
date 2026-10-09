package com.zynelabs.stb

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v6.3: QR pairing — the TV displays a QR code; the phone scans it and
 * submits a playlist through the worker web page; the TV polls until the
 * payload is ready, then saves it as a provider, activates it and finishes.
 * Pairing window is 5 minutes (worker TTL).
 */
class QrPairActivity : AppCompatActivity() {

    companion object {
        const val WORKER_BASE = "https://zyne-stb-pair.zynelabs.workers.dev"
        private const val QR_SIZE = 512
        private const val POLL_MS = 5000L
        private const val TIMEOUT_MS = 5 * 60 * 1000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private lateinit var ivQr: ImageView
    private lateinit var tvCode: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: Button

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_pair)
        ivQr = findViewById(R.id.ivQr)
        tvCode = findViewById(R.id.tvCode)
        tvHint = findViewById(R.id.tvHint)
        tvStatus = findViewById(R.id.tvStatus)
        btnBack = findViewById(R.id.btnBack)
        tvHint.text = getString(R.string.qr_pair_hint)
        tvStatus.text = getString(R.string.qr_pair_creating)
        btnBack.setOnClickListener { finish() }
        btnBack.requestFocus()
        requestCode()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    /** Step 1: POST /api/new to get a pairing code. */
    private fun requestCode() {
        lifecycleScope.launch {
            val code = try {
                withContext(Dispatchers.IO) { postNewCode() }
            } catch (_: Exception) {
                null
            }
            if (isFinishing || isDestroyed) return@launch
            if (code.isNullOrBlank()) {
                tvStatus.text = getString(R.string.qr_pair_create_failed)
                return@launch
            }
            showCode(code)
            startPolling(code)
        }
    }

    private fun postNewCode(): String? {
        val req = Request.Builder()
            .url("$WORKER_BASE/api/new")
            .post("{}".toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return try {
                JSONObject(resp.body.string()).optString("code").ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Step 2: render QR for {base}/p/{code} + show the code text. */
    private fun showCode(code: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = renderQr("$WORKER_BASE/p/$code")
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (bmp != null) ivQr.setImageBitmap(bmp)
                tvCode.text = code
                tvStatus.text = getString(R.string.qr_pair_waiting)
            }
        }
    }

    private fun renderQr(text: String): Bitmap? = try {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val bmp = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565)
        for (x in 0 until QR_SIZE) {
            for (y in 0 until QR_SIZE) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }

    /** Step 3: poll GET /api/status/{code} every 5s until ready/expired/5min. */
    private fun startPolling(code: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        pollJob = lifecycleScope.launch {
            while (System.currentTimeMillis() < deadline && !done) {
                when (val res = withContext(Dispatchers.IO) { fetchStatus(code) }) {
                    is StatusResult.Ready -> {
                        onPayloadReady(res.payload)
                        return@launch
                    }
                    is StatusResult.Expired -> {
                        onExpired()
                        return@launch
                    }
                    is StatusResult.Pending -> {
                        // keep waiting
                    }
                    is StatusResult.NetworkError -> {
                        tvStatus.text = getString(R.string.qr_pair_no_network)
                    }
                }
                delay(POLL_MS)
            }
            if (!done) onExpired()
        }
    }

    private sealed class StatusResult {
        object Pending : StatusResult()
        object Expired : StatusResult()
        object NetworkError : StatusResult()
        class Ready(val payload: JSONObject) : StatusResult()
    }

    private fun fetchStatus(code: String): StatusResult {
        val req = Request.Builder()
            .url("$WORKER_BASE/api/status/$code")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return StatusResult.Expired
                if (!resp.isSuccessful) return StatusResult.Pending
                val obj = try {
                    JSONObject(resp.body.string())
                } catch (_: Exception) {
                    return StatusResult.Pending
                }
                if (obj.optString("status") == "ready") {
                    val payload = obj.optJSONObject("payload") ?: return StatusResult.Pending
                    StatusResult.Ready(payload)
                } else {
                    StatusResult.Pending
                }
            }
        } catch (_: Exception) {
            StatusResult.NetworkError
        }
    }

    /** Step 4: ready — build Provider, save, activate, clear caches, finish. */
    private fun onPayloadReady(payload: JSONObject) {
        done = true
        pollJob?.cancel()
        val provider = when (payload.optString("kind")) {
            "m3u" -> Provider(
                ProviderStore.newId(),
                payload.optString("name"),
                payload.optString("url"),
                "",
                ProviderStore.TYPE_M3U_URL
            )
            "xtream" -> Provider(
                ProviderStore.newId(),
                payload.optString("name"),
                payload.optString("url"),
                "",
                ProviderStore.TYPE_XTREAM,
                payload.optString("username"),
                payload.optString("password")
            )
            else -> {
                tvStatus.text = getString(R.string.qr_pair_bad_payload)
                return
            }
        }
        ProviderStore.save(this, provider)
        ProviderStore.setActive(this, provider.id)
        ProviderStore.applyActive(this)
        SourceManager.clearAllCaches(this)
        Toast.makeText(this, R.string.qr_pair_received, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Step 5: expired (404 or 5-minute window elapsed) — stop polling. */
    private fun onExpired() {
        done = true
        pollJob?.cancel()
        tvStatus.text = getString(R.string.qr_pair_expired)
        ivQr.setImageResource(android.R.color.transparent)
    }
}
