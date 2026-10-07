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
            val api = StalkerSession.get(this@ProbeActivity)
            try {
                api.handshake()
                appendLine("handshake -> OK")
            } catch (e: Exception) {
                appendLine("handshake -> ERROR ${(e.message ?: e.javaClass.simpleName).take(80)}")
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
                val r = api.probe(type, action, extra)
                val extraStr = if (extra.isEmpty()) "" else " " +
                    extra.entries.joinToString(" ") { "${it.key}=${it.value}" }
                val line = if (r.error.isNotEmpty()) {
                    "$type/$action$extraStr -> ERROR ${r.error}"
                } else {
                    "$type/$action$extraStr -> HTTP ${r.httpCode} " +
                        "len=${r.bodyLength} ${r.snippet}"
                }
                appendLine(line)
            }
            appendLine("done.")
            withContext(Dispatchers.Main) {
                binding.btnStart.isEnabled = true
                binding.btnStart.requestFocus()
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
