package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joins the car's factory hotspot through a network suggestion.
 *
 * This is the fallback the Lynk firmware actually uses: the vendor image has no
 * `NETWORK_REQUEST` activity, so `WifiNetworkSpecifier` can never be confirmed, while
 * `addNetworkSuggestions` is serviced by the platform without any UI. A scan is started afterwards
 * because the suggestion is only acted on when the platform next scans.
 *
 * Stale suggestions for the same SSID are removed so an old passphrase cannot win the race. The
 * lock is process-wide: two suggestions submitted concurrently would leave the platform with a
 * mixture of old and new credentials for the same network.
 */
internal class LynkTcamWifiSuggestion(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {

    private val wifi: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private val closed = AtomicBoolean(false)
    private val submitted = AtomicBoolean(false)

    /** True when the suggestion was accepted by the platform. Only the first call does anything. */
    fun submit(ssid: String, password: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LynkTcamWifiSuggestion.submit must not run on the main thread"
        }
        if (closed.get() || !submitted.compareAndSet(false, true)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            diagnostic(UNSUPPORTED)
            return false
        }
        if (!LynkTcamNetworkPolicy.supportsHeadUnit(
                Build.MANUFACTURER.orEmpty(),
                Build.MODEL.orEmpty(),
            )
        ) {
            diagnostic(UNSUPPORTED)
            return false
        }
        val manager = wifi
        if (manager == null) {
            diagnostic("unavailable")
            return false
        }
        try {
            if (!manager.isWifiEnabled) {
                diagnostic("wifi-disabled")
                return false
            }
            if (ssid.isBlank() || '\u0000' in ssid ||
                ssid.toByteArray(Charsets.UTF_8).size > MAX_SSID_BYTES
            ) {
                throw IllegalArgumentException("ssid must be 1..32 UTF-8 bytes without U+0000")
            }
            require(password.length in 8..63 && '\u0000' !in password) {
                "password must be 8..63 characters without U+0000"
            }
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            synchronized(updatesLock) {
                if (closed.get()) return false
                val existing = manager.networkSuggestions
                if (closed.get()) return false
                val added = manager.addNetworkSuggestions(listOf(suggestion))
                if (added != 0) {
                    diagnostic("add-rejected", added)
                    return false
                }
                if (closed.get()) return false
                val stale = existing.filter { it != suggestion }
                if (stale.isNotEmpty()) {
                    try {
                        val removed = manager.removeNetworkSuggestions(stale)
                        if (removed != 0) diagnostic("stale-remove-rejected", removed)
                    } catch (failure: Exception) {
                        diagnostic("stale-remove-unavailable", null, failure)
                    }
                }
                if (closed.get()) return false
                diagnostic("submitted-awaiting-network")
                try {
                    diagnostic(if (manager.startScan()) "scan-requested" else "scan-not-started")
                } catch (failure: Exception) {
                    diagnostic("scan-unavailable", null, failure)
                }
                return !closed.get()
            }
        } catch (failure: Exception) {
            diagnostic("unavailable", null, failure)
            return false
        }
    }

    override fun close() {
        closed.set(true)
    }

    private fun diagnostic(state: String, code: Int? = null, error: Exception? = null) {
        val codeText = code?.let { " code=$it" }.orEmpty()
        val errorText = error?.let { " error=${it.javaClass.simpleName}" }.orEmpty()
        val message = "TCAM Wi-Fi suggestion state=$state$codeText$errorText"
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val UNSUPPORTED = "unsupported"
        const val MAX_SSID_BYTES = 32

        /** Serialises `add`/`remove` across every instance in the process. */
        val updatesLock = Any()
    }
}
