package com.zynelabs.stb

import android.content.Context
import java.security.SecureRandom

/**
 * Stable per-install MAG hardware identity.
 *
 * OTT Navigator and real MAG boxes send a full hardware fingerprint with
 * every portal request (serial number, device IDs, signature), not just the
 * MAC. Some portals issue a handshake token for a bare MAC but return empty
 * responses for content actions until the full device identity is present
 * (wafasiad.com behaved exactly like this).
 *
 * Generated once with SecureRandom and persisted in SharedPreferences, so
 * the portal always sees the same "box".
 */
data class DeviceIds(
    val sn: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String
) {
    /** Short one-line summary for the on-device Portal API Probe header. */
    fun summary(): String =
        "sn=$sn id=${deviceId.take(8)}... id2=${deviceId2.take(8)}... " +
            "sig=${signature.take(8)}..."

    companion object {
        private const val FILE = "stb_device_ids"
        private const val KEY_SN = "sn"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_ID2 = "device_id2"
        private const val KEY_SIGNATURE = "signature"

        private val HEX = "0123456789abcdef".toCharArray()

        @Volatile
        private var cached: DeviceIds? = null

        fun get(context: Context): DeviceIds {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val prefs = context.applicationContext
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                var sn = prefs.getString(KEY_SN, null)
                var id1 = prefs.getString(KEY_DEVICE_ID, null)
                var id2 = prefs.getString(KEY_DEVICE_ID2, null)
                var sig = prefs.getString(KEY_SIGNATURE, null)
                if (sn.isNullOrBlank() || id1.isNullOrBlank() ||
                    id2.isNullOrBlank() || sig.isNullOrBlank()
                ) {
                    val rnd = SecureRandom()
                    sn = (1..13).map { ('0'..'9').random(rnd) }.joinToString("")
                    id1 = (1..64).map { HEX[rnd.nextInt(16)] }.joinToString("")
                    id2 = (1..64).map { HEX[rnd.nextInt(16)] }.joinToString("")
                    sig = (1..64).map { HEX[rnd.nextInt(16)] }.joinToString("")
                    prefs.edit()
                        .putString(KEY_SN, sn)
                        .putString(KEY_DEVICE_ID, id1)
                        .putString(KEY_DEVICE_ID2, id2)
                        .putString(KEY_SIGNATURE, sig)
                        .apply()
                }
                val ids = DeviceIds(sn!!, id1!!, id2!!, sig!!)
                cached = ids
                return ids
            }
        }
    }
}
