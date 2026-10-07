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
 * Sequence: header (portal + MAC) -> portal index page fetch -> handshake ->
 * stb/get_profile -> the 9 content probes -> itv/get_genres via POST.
 *
 * English-only, D-pad navigable. One result line per step.
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
                val api: StalkerApi
                try {
                    api = StalkerSession.get(this@ProbeActivity)
                } catch (e: Exception) {
                    appendLine("Session FAILED: ${(e.message ?: e.javaClass.simpleName).take(120)}")
                    appendLine("Done.")
                    return@launch
                }

                appendLine("Portal: " + api.probePortalUrl)
                appendLine("MAC: " + api.probeBoxMac)
                appendLine("Device IDs: " + api.probeDeviceIds)

                // Step 0 — fetch the portal index page to identify the system
                // (Ministra portal page? something else? empty?).
                appendLine("Index ...")
                try {
                    val (code, detail, err) = api.probeRaw(api.probePortalUrl + "/")
                    if (err.isNotEmpty()) {
                        appendLine("Index -> ERROR $err")
                    } else {
                        appendLine("Index -> HTTP $code $detail")
                    }
                } catch (e: Exception) {
                    appendLine(
                        "Index -> ERROR " +
                            (e.message ?: e.javaClass.simpleName).toString().take(80)
                    )
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

                // get_profile right after handshake: content actions may
                // require the profile call first in sequence.
                appendLine("stb/get_profile ...")
                try {
                    val r = api.probe("stb", "get_profile", snippetLen = 300)
                    if (r.error.isNotEmpty()) {
                        appendLine("stb/get_profile -> ERROR ${r.error}")
                    } else {
                        appendLine(
                            "stb/get_profile -> HTTP ${r.httpCode} " +
                                "len=${r.bodyLength} ${r.snippet}"
                        )
                    }
                } catch (e: Exception) {
                    appendLine(
                        "stb/get_profile -> ERROR " +
                            (e.message ?: e.javaClass.simpleName).toString().take(80)
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
                // POST variant: some panels only accept POST for data actions.
                appendLine("itv/get_genres via POST ...")
                try {
                    val r = api.probePost("itv", "get_genres")
                    if (r.error.isNotEmpty()) {
                        appendLine("itv/get_genres via POST -> ERROR ${r.error}")
                    } else {
                        appendLine(
                            "itv/get_genres via POST -> HTTP ${r.httpCode} " +
                                "len=${r.bodyLength} ${r.snippet}"
                        )
                    }
                } catch (e: Exception) {
                    appendLine(
                        "itv/get_genres via POST -> ERROR " +
                            (e.message ?: e.javaClass.simpleName).toString().take(80)
                    )
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
