package com.zynelabs.stb

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * v6.3: launcher router.
 * - No providers saved → Add Provider (pick Stalker / M3U / Xtream).
 * - EXTRA_SETUP → provider management hub.
 * - Otherwise → Home on the active provider.
 *
 * (The old Stalker-only setup form was removed — all provider types are
 * managed in one place: ProviderListActivity, reached from Settings.)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** When true, open provider management instead of Home. */
        const val EXTRA_SETUP = "setup"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v6.3.24: TamperGuard — refuse to run if re-signed by someone else.
        if (!TamperGuard.verify(this)) {
            AlertDialog.Builder(this)
                .setTitle("Unauthorized copy")
                .setMessage("This copy of ZyneLabs STB has been modified and is not authorized. Please install the official version from ZyneLabs.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
            return
        }
        if (ProviderStore.list(this).isEmpty()) {
            startActivity(Intent(this, AddProviderActivity::class.java))
        } else if (intent.getBooleanExtra(EXTRA_SETUP, false)) {
            startActivity(Intent(this, ProviderListActivity::class.java))
        } else {
            ProviderStore.applyActive(this)
            startActivity(Intent(this, HomeActivity::class.java))
        }
        finish()
    }
}
