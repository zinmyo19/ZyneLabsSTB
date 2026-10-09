package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * v5.6: camera QR scanner for Add Provider.
 * Returns EXTRA_QR_URL (+ EXTRA_QR_MAC when the payload carries one).
 * Accepted payloads: plain URL, "url|mac", "stb://url|mac".
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var scanner: DecoratedBarcodeView
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = DecoratedBarcodeView(this)
        setContentView(scanner)
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text?.trim().orEmpty()
                if (text.isNotBlank() && !done) {
                    done = true
                    val (url, mac) = parsePayload(text)
                    // v6.3.3: local result keys (AddProviderActivity no longer
                    // hosts the camera-scan flow; this activity is unused).
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra("qr_url", url)
                            .putExtra("qr_mac", mac)
                    )
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        scanner.resume()
    }

    override fun onPause() {
        super.onPause()
        scanner.pause()
    }

    private fun parsePayload(text: String): Pair<String, String> {
        var t = text.trim()
        if (t.startsWith("stb://", ignoreCase = true)) {
            t = "http://" + t.removePrefix("stb://").removePrefix("STB://")
        }
        val parts = t.split("|")
        val url = parts.getOrNull(0)?.trim().orEmpty()
        val mac = parts.getOrNull(1)?.trim()?.uppercase().orEmpty()
        return url to mac
    }
}
