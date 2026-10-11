package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Asks the platform to join the car's factory hotspot.
 *
 * The head unit starts as a Wi-Fi client that is not yet on the hotspot, so somebody has to issue
 * the association. `WifiNetworkSpecifier` does it without a visible Settings screen, but on this
 * firmware the platform routes the approval through `com.android.settings.wifi.action
 * .NETWORK_REQUEST`, and that activity is missing from the vendor image. When it is missing the
 * request is reported as `unsupported-ui` and the caller falls back to a
 * [LynkTcamWifiSuggestion], which the platform services silently.
 *
 * The request is deliberately a *local* link: `NET_CAPABILITY_INTERNET` is removed, because the
 * car's hotspot usually has no internet and a request that demands it is never satisfied.
 *
 * A successful request keeps its callback registered: unregistering it would drop the network the
 * AirPlay session is about to run on. Only [close] releases it.
 */
internal class LynkTcamWifiRequest(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {

    private val appContext = context.applicationContext

    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    /**
     * `java.lang.Object`, not `Any`: Kotlin's `Any` has no `wait`/`notifyAll`, and this monitor is
     * waited on by the connect loop and notified from the callback thread.
     */
    private val lock = Object()

    private var callback: RequestCallback? = null

    @Volatile
    private var closed = false

    private var started = false

    /**
     * Connects to [ssid] and returns the resulting network, or null when the platform refused,
     * never showed a request dialog, or the budget ran out. Must not run on the main thread. Only
     * the first call does anything.
     */
    fun connect(ssid: String, password: String, timeoutMillis: Long): Network? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LynkTcamWifiRequest.connect must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val startedNanos = System.nanoTime()
        val budgetNanos = TimeUnit.MILLISECONDS.toNanos(
            timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()),
        )

        synchronized(lock) {
            if (closed || started) return null
            started = true
        }

        val manager = connectivity
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || manager == null) {
            diagnostic(UNSUPPORTED)
            return null
        }

        val request = try {
            prepareRequest(ssid, password) ?: return null
        } catch (failure: Exception) {
            diagnostic("configuration-unavailable", failure)
            return null
        }

        val pending = synchronized(lock) {
            if (closed) return null
            RequestCallback().also { callback = it }
        }

        try {
            diagnostic("requesting")
            if (!synchronized(lock) { !closed && callback === pending }) {
                release(pending)
                return null
            }
            try {
                manager.requestNetwork(request, pending)
            } finally {
                val stale = synchronized(lock) {
                    pending.registrationFinished = true
                    pending.registered = true
                    if (closed || callback !== pending) {
                        // Registered but nobody will ever release it: undo it here.
                        pending.registered = false
                        true
                    } else {
                        false
                    }
                }
                if (stale) unregister(pending)
            }

            val network = synchronized(lock) {
                var ready: Network? = null
                while (!closed && callback === pending && !pending.unavailable) {
                    val remainingNanos = budgetNanos - (System.nanoTime() - startedNanos)
                    if (remainingNanos <= 0) break
                    ready = pending.readyNetwork()
                    if (ready != null) break
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                }
                ready
            }

            if (network == null) {
                val reason = synchronized(lock) {
                    when {
                        closed || callback !== pending -> "cancelled"
                        pending.unavailable -> "unavailable"
                        else -> "timed-out"
                    }
                }
                diagnostic(reason)
                release(pending)
                return null
            }
            diagnostic("ready")
            if (!synchronized(lock) { !closed && callback === pending }) {
                release(pending)
                return null
            }
            return network
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            diagnostic("interrupted")
            release(pending)
            return null
        } catch (failure: Exception) {
            diagnostic("request-failed", failure)
            release(pending)
            return null
        }
    }

    /**
     * Validates everything the platform would otherwise reject silently, and names the reason.
     *
     * Returns null when this build cannot make the request at all (see `unsupported-ui`).
     */
    private fun prepareRequest(ssid: String, password: String): NetworkRequest? {
        val wifiManager = wifi
        if (wifiManager == null) {
            diagnostic("wifi-unavailable")
            return null
        }
        if (!wifiManager.isWifiEnabled) {
            diagnostic("wifi-disabled")
            return null
        }
        if (!LynkTcamNetworkPolicy.supportsHeadUnit(
                Build.MANUFACTURER.orEmpty(),
                Build.MODEL.orEmpty(),
            )
        ) {
            diagnostic("unsupported-head-unit")
            return null
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !hasNetworkRequestUi()) {
            diagnostic("unsupported-ui")
            return null
        }
        val validSsid = ssid.isNotBlank() &&
            '\u0000' !in ssid &&
            ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES
        val validPassword = password.length in 8..63 && '\u0000' !in password
        if (!validSsid || !validPassword) {
            diagnostic("invalid-configuration")
            return null
        }
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build(),
            )
            .build()
    }

    /**
     * True when the platform has somewhere to show the "join this network?" confirmation.
     *
     * On Android 11 with no such activity, `requestNetwork` never calls back at all and the caller
     * would burn its whole budget for nothing; the suggestion path is the only one that works.
     */
    private fun hasNetworkRequestUi(): Boolean = appContext.packageManager.resolveActivity(
        Intent(NETWORK_REQUEST_ACTION).addCategory(Intent.CATEGORY_DEFAULT),
        PackageManager.MATCH_DEFAULT_ONLY,
    ) != null

    private fun release(pending: RequestCallback) {
        val shouldUnregister = synchronized(lock) {
            if (callback === pending) callback = null
            lock.notifyAll()
            val registered = pending.registrationFinished && pending.registered
            if (registered) pending.registered = false
            registered
        }
        if (shouldUnregister) unregister(pending)
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            val active = callback
            callback = null
            lock.notifyAll()
            active
        }
        if (pending != null) release(pending)
    }

    private fun unregister(pending: RequestCallback) {
        runCatching { connectivity?.unregisterNetworkCallback(pending) }
    }

    private fun diagnostic(state: String, failure: Exception? = null) {
        val detail = failure?.let { " error=${it.javaClass.simpleName}" }.orEmpty()
        val message = "TCAM Wi-Fi request state=$state$detail"
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    /**
     * Tracks one `requestNetwork` registration.
     *
     * The network only counts as ready when its capabilities, its interface and its IPv4 address
     * are all known *and* the link passes [LynkTcamNetworkPolicy]: `onAvailable` fires before the
     * lease exists, and reporting that would advertise an address the iPhone cannot reach.
     */
    private inner class RequestCallback : ConnectivityManager.NetworkCallback() {

        var registrationFinished = false
        var registered = false
        var unavailable = false

        private var network: Network? = null
        private var capabilities: NetworkCapabilities? = null
        private var properties: AddressSnapshot? = null
        private var blocked = false

        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (!closed && callback === this && !unavailable) {
                    this.network = network
                    capabilities = null
                    properties = null
                    blocked = false
                    lock.notifyAll()
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            synchronized(lock) {
                if (!closed && callback === this && !unavailable && this.network == network) {
                    capabilities = NetworkCapabilities(networkCapabilities)
                    lock.notifyAll()
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                if (!closed && callback === this && !unavailable && this.network == network) {
                    properties = AddressSnapshot(
                        interfaceName = linkProperties.interfaceName,
                        addresses = linkProperties.linkAddresses
                            .map { it.address }
                            .filterIsInstance<Inet4Address>(),
                    )
                    lock.notifyAll()
                }
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            synchronized(lock) {
                if (!closed && callback === this && !unavailable && this.network == network) {
                    this.blocked = blocked
                    lock.notifyAll()
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (!closed && callback === this && this.network == network) {
                    this.network = null
                    capabilities = null
                    properties = null
                    lock.notifyAll()
                }
            }
        }

        override fun onUnavailable() {
            synchronized(lock) {
                if (!closed && callback === this) {
                    unavailable = true
                    network = null
                    lock.notifyAll()
                }
            }
        }

        /** Caller must hold [lock]. */
        fun readyNetwork(): Network? {
            if (unavailable || blocked) return null
            val candidate = network ?: return null
            val caps = capabilities ?: return null
            val snapshot = properties ?: return null
            val selected = LynkTcamNetworkPolicy.selectConnected(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                network = LynkTcamNetworkPolicy.ConnectedNetwork(
                    interfaceName = snapshot.interfaceName,
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    addresses = snapshot.addresses,
                ),
            )
            return if (selected != null) candidate else null
        }
    }

    private data class AddressSnapshot(
        val interfaceName: String?,
        val addresses: List<Inet4Address>,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val UNSUPPORTED = "unsupported"
        const val MAX_SSID_BYTES = 32
        const val NETWORK_REQUEST_ACTION = "com.android.settings.wifi.action.NETWORK_REQUEST"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
