package com.zynelabs.stb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * TamperGuard — anti-repackaging protection (v6.3.24).
 *
 * Verifies at launch that the APK is still signed with ZyneLabs' own
 * certificate. If someone decompiles the APK, renames it, strips the
 * contact info and re-signs with their own key, this check fails and
 * the app refuses to run.
 *
 * NOTE: this stops casual repackagers (~95%). A skilled reverser can
 * always patch a client-side check out — no client check is 100%.
 * Keep the app signed with the same keystore or the check will fail
 * on legitimate builds too.
 */
object TamperGuard {

    // Expected SHA-256 of the ZyneLabs signing certificate, split so the
    // full fingerprint is not sitting as one searchable literal.
    private const val P1 = "2126894b4c678030"
    private const val P2 = "c1011d970c2ac244"
    private const val P3 = "9421d994adf34a0a"
    private const val P4 = "eee660c3acb8a00a"

    private fun expected(): String = P1 + P2 + P3 + P4

    fun verify(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            val pkg = ctx.packageName
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: return false
                val si = pi.signingInfo ?: return false
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                    ?: return false
                pi.signatures
            }
            if (sigs.isNullOrEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            for (s in sigs) {
                val d = md.digest(s.toByteArray())
                val sb = StringBuilder(d.size * 2)
                for (b in d) {
                    sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                    sb.append(Character.forDigit(b.toInt() and 0xF, 16))
                }
                if (sb.toString().equals(expected(), ignoreCase = true)) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
