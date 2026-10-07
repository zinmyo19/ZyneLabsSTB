package com.zynelabs.stb

import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zynelabs.stb.databinding.ActivityProbeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Portal API Probe: discovers which portal.php actions the connected portal
 * actually implements. The app (on the user's phone) is the only thing that
 * can reach these portals, so the probing runs on-device.
 *
 * English-only, D-pad navigable. One result line per probed action.
 *
 * Never dies silently: a progress line is printed BEFORE every blocking
 * network call, and every step is wrapped in try/catch, so the screen always
 * shows what is happening even when the portal RSTs or times out.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { startProbe() }
        binding.btnStart.requestFocus()
    }

    private fun startProbe() {
        binding.btnStart.isEnabled = false
        binding.tvResults.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appendLine("Portal: " + Prefs.getPortalUrl(this@ProbeActivity))

                val api: StalkerApi
                try {
                    api = StalkerSession.get(this@ProbeActivity)
                } catch (e: Exception) {
                    appendLine("Session FAILED: ${(e.message ?: e.javaClass.simpleName).take(120)}")
                    appendLine("Done.")
                    return@launch
                }

                appendLine("Handshake ...")
                try {
                    api.handshake()
                    appendLine("Handshake OK, token acquired")
                } catch (e: Exception) {
                    appendLine(
                        "Handshake FAILED: " +
                            (e.message ?: e.javaClass.simpleName).toString().take(120)
                    )
                }

                val probes = listOf(
                    Triple("itv", "get_genres", emptyMap<String, String>()),
                    Triple("itv", "get_genres", mapOf("fav" to "0")),
                    Triple("itv", "get_all_channels", mapOf("p" to "1")),
                    Triple("itv", "get_ordered_list", mapOf("p" to "1")),
                    Triple("itv", "get_short_epg", emptyMap()),
                    Triple("vod", "get_categories", emptyMap()),
                    Triple("vod", "get_ordered_list", mapOf("p" to "1")),
                    Triple("series", "get_categories", emptyMap()),
                    Triple("series", "get_ordered_list", mapOf("p" to "1"))
                )
                for ((type, action, extra) in probes) {
                    val extraStr = if (extra.isEmpty()) "" else
                        extra.entries.joinToString(" ", prefix = " ") { "${it.key}=${it.value}" }
                    appendLine("$type/$action$extraStr ...")
                    try {
                        val r = api.probe(type, action, extra)
                        if (r.error.isNotEmpty()) {
                            appendLine("$type/$action$extraStr -> ERROR ${r.error}")
                        } else {
                            appendLine(
                                "$type/$action$extraStr -> HTTP ${r.httpCode} " +
                                    "len=${r.bodyLength} ${r.snippet}"
                            )
                        }
                    } catch (e: Exception) {
                        appendLine(
                            "$type/$action$extraStr -> ERROR " +
                                (e.message ?: e.javaClass.simpleName).toString().take(80)
                        )
                    }
                }
                appendLine("Done.")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnStart.isEnabled = true
                    binding.btnStart.requestFocus()
                }
            }
        }
    }

    private suspend fun appendLine(line: String) {
        withContext(Dispatchers.Main) {
            binding.tvResults.append(line + "\n")
            binding.scrollView.post {
                binding.scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
