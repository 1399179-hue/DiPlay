package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.net.Inet4Address

/**
 * Binds the process to the head unit's own Wi-Fi link and keeps that radio at full performance.
 *
 * Two things have to be true for wireless CarPlay to survive the AirPlay session, and neither is
 * free:
 *
 *  1. The process must be bound to the hotspot network (`bindProcessToNetwork`). Without the bind
 *     the sockets follow the default route, so Bonjour replies and the AirPlay listener leave
 *     through the wrong interface and the handset never reaches them.
 *  2. A `WIFI_MODE_FULL_HIGH_PERF` lock must be held. The platform otherwise lets the radio drop
 *     into power save while the screen is off, which shows up as the AirPlay audio stream stalling
 *     a couple of seconds in. The Lynk build holds exactly one such lock.
 *
 * The previous process binding is restored on [close], and the bind is only released when this
 * object still owns it: a teardown must not undo a bind somebody else took over.
 */
internal class LynkTcamWifiConnection(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {

    private val appContext = context.applicationContext

    private val connectivity: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager is unavailable")

    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    private val lock = Any()
    private val performanceLocks = mutableListOf<WifiManager.WifiLock>()
    private val reportedFailures = mutableSetOf<ExistingWifiFailure>()

    private var previousBinding: Network? = null
    private var ownedBinding: Network? = null

    /** Set when the single candidate is accepted on its address policy despite an unreadable SSID. */
    private var acceptedByAddress = false

    @Volatile
    private var closed = false

    private enum class ExistingWifiFailure(val label: String) {
        NO_WIFI("no-wifi"),
        NAME_UNREADABLE("name-unreadable"),
        NAME_MISMATCH("name-mismatch"),
        ADDRESS_NOT_READY("address-not-ready"),
        AMBIGUOUS_NETWORK("ambiguous-network"),
        INSPECTION_EXCEPTION("inspection-exception"),
        BIND_FAILED("bind-failed"),
        BIND_EXCEPTION("bind-exception"),
    }

    private data class ExistingWifi(
        val network: Network? = null,
        val failure: ExistingWifiFailure? = null,
    )

    /**
     * Finds and binds the already-connected network whose SSID is [ssid].
     *
     * Returns null when nothing suitable is up yet; the caller then asks the platform to connect,
     * because on this head unit the hotspot link is not always established before the app starts.
     */
    fun connectExisting(ssid: String): Network? {
        synchronized(lock) {
            check(!closed) { "LynkTcamWifiConnection is closed" }
            var network: Network? = null
            val existing = try {
                existingConnection(ssid)
            } catch (failure: Exception) {
                reportFailure(ExistingWifiFailure.INSPECTION_EXCEPTION, failure)
                ExistingWifi()
            }
            existing.failure?.let { reportFailure(it) }
            if (acceptedByAddress && existing.network != null) {
                acceptedByAddress = false
                runCatching {
                    onDiagnostic("TCAM existing Wi-Fi accepted on its address policy; platform reports no SSID")
                }
            }
            val candidate = existing.network
            if (candidate != null) {
                val bound = try {
                    val ok = bind(candidate)
                    if (!ok) reportFailure(ExistingWifiFailure.BIND_FAILED)
                    ok
                } catch (failure: Exception) {
                    reportFailure(ExistingWifiFailure.BIND_EXCEPTION, failure)
                    false
                }
                if (bound) network = candidate
            }
            if (network != null) holdWifiPerformance() else releaseWifiPerformance()
            return network
        }
    }

    /** True when a usable network for [ssid] is already connected. Never binds. */
    fun hasExistingConnection(ssid: String): Boolean = synchronized(lock) {
        if (closed) {
            false
        } else {
            runCatching { existingConnection(ssid).network != null }.getOrDefault(false)
        }
    }

    /** Binds the process to a network the platform just handed us. */
    fun connectRequested(network: Network): Network? {
        synchronized(lock) {
            check(!closed) { "LynkTcamWifiConnection is closed" }
            try {
                if (!bind(network)) return null
                if (LynkTcamNetwork.resolveConnected(appContext, network) == null) return null
                holdWifiPerformance()
            } catch (failure: Exception) {
                reportFailure(ExistingWifiFailure.BIND_EXCEPTION, failure)
                return null
            }
            return network
        }
    }

    /**
     * The one candidate network carrying [ssid], or the reason there was none.
     *
     * A firmware that hides the SSID is handled by falling back to `WifiManager.connectionInfo`
     * when there is exactly one candidate: guessing among several would be worse than reporting
     * that the name could not be read.
     */
    private fun existingConnection(ssid: String): ExistingWifi {
        if (ssid.isBlank()) return ExistingWifi(failure = ExistingWifiFailure.NAME_UNREADABLE)

        val candidates = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        if (candidates.isEmpty()) return ExistingWifi(failure = ExistingWifiFailure.NO_WIFI)

        val matches = mutableListOf<Network>()
        var nameMatched = false
        var nameUnreadable = false
        for (network in candidates) {
            val capabilities = connectivity.getNetworkCapabilities(network)
            val reported = (capabilities?.transportInfo as? WifiInfo)?.ssid
            var normalized = normalizeSsid(reported)
            if (normalized == null) {
                normalized = if (candidates.size == 1) {
                    normalizeSsid(wifi?.connectionInfo?.ssid)
                } else {
                    null
                }
            }
            val linkProperties = connectivity.getLinkProperties(network)
            val selected = linkProperties?.let { properties ->
                LynkTcamNetworkPolicy.selectConnected(
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    model = Build.MODEL.orEmpty(),
                    network = LynkTcamNetworkPolicy.ConnectedNetwork(
                        interfaceName = properties.interfaceName,
                        wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                        vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                        notVpn = capabilities
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
                        addresses = properties.linkAddresses.mapNotNull { it.address as? Inet4Address },
                    ),
                )
            }
            // The head unit is often the AP itself (the TCAM hotspot lives in the module), and the
            // platform then reports no SSID for its own interface — an unreadable name is not a
            // reason to reject a network the address policy already proved is the car's link. Only
            // the single-candidate case is relaxed: with several Wi-Fi links up, the name is what
            // tells them apart, so guessing would be worse than failing.
            if (normalized == null) {
                nameUnreadable = true
                if (candidates.size == 1 && selected != null) {
                    acceptedByAddress = true
                    matches.add(network)
                }
                continue
            }
            if (normalized != ssid) continue

            nameMatched = true
            if (selected != null) matches.add(network)
        }

        return when {
            matches.size == 1 -> ExistingWifi(network = matches.single())
            matches.size > 1 -> ExistingWifi(failure = ExistingWifiFailure.AMBIGUOUS_NETWORK)
            nameMatched -> ExistingWifi(failure = ExistingWifiFailure.ADDRESS_NOT_READY)
            nameUnreadable -> ExistingWifi(failure = ExistingWifiFailure.NAME_UNREADABLE)
            else -> ExistingWifi(failure = ExistingWifiFailure.NAME_MISMATCH)
        }
    }

    /** Reports each distinct reason once: the poll loop would otherwise flood the log. */
    private fun reportFailure(reason: ExistingWifiFailure, failure: Exception? = null) {
        if (!reportedFailures.add(reason)) return
        val detail = failure?.let { " error=${it.javaClass.simpleName}" }.orEmpty()
        val message = "TCAM existing Wi-Fi unavailable reason=${reason.label}$detail"
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun bind(network: Network): Boolean {
        val current = connectivity.boundNetworkForProcess
        val owned = ownedBinding
        if (owned != null && current != owned) return false
        if (connectivity.getNetworkCapabilities(network) == null) return false
        if (!connectivity.bindProcessToNetwork(network)) return false
        if (ownedBinding == null) previousBinding = current
        ownedBinding = network
        return true
    }

    /**
     * Holds a `WIFI_MODE_FULL_HIGH_PERF` lock.
     *
     * `WIFI_MODE_FULL` is deliberately not used: the audio path only needs the radio kept awake at
     * full rate, and a low-latency lock changes the power/beacon behaviour the car firmware
     * expects. The Lynk build requests exactly mode 3 (HIGH_PERF) with
     * `lowLatencyRequested=false`, so this does too.
     */
    private fun holdWifiPerformance() {
        val manager = wifi ?: return
        if (performanceLocks.isNotEmpty()) return
        for (mode in listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF)) {
            var held: WifiManager.WifiLock? = null
            try {
                val created = manager.createWifiLock(mode, "DiPlay:TCAM:$mode")
                held = created
                created.setReferenceCounted(false)
                created.acquire()
                if (created.isHeld) performanceLocks.add(created)
            } catch (failure: Exception) {
                held?.let { lockToRelease ->
                    runCatching { if (lockToRelease.isHeld) lockToRelease.release() }
                }
                Log.w(
                    TAG,
                    "TCAM Wi-Fi performance lock unavailable mode=$mode" +
                        " error=${failure.javaClass.simpleName}",
                )
            }
        }
        val message = "TCAM Wi-Fi performance locks held=${performanceLocks.size}" +
            " requestedMode=HIGH_PERF lowLatencyRequested=false"
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun releaseWifiPerformance() {
        val held = performanceLocks.toList()
        performanceLocks.clear()
        for (lockToRelease in held) {
            val result = runCatching { if (lockToRelease.isHeld) lockToRelease.release() }
            result.exceptionOrNull()?.let { failure ->
                Log.w(
                    TAG,
                    "TCAM Wi-Fi performance lock release failed" +
                        " error=${failure.javaClass.simpleName}",
                )
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                val owned = ownedBinding
                if (owned != null && connectivity.boundNetworkForProcess == owned) {
                    if (!connectivity.bindProcessToNetwork(previousBinding)) {
                        connectivity.bindProcessToNetwork(null)
                    }
                }
            } finally {
                releaseWifiPerformance()
                ownedBinding = null
                previousBinding = null
            }
        }
    }

    private fun normalizeSsid(value: String?): String? {
        val unquoted = value?.removeSurrounding("\"") ?: return null
        if (unquoted.isBlank()) return null
        if (unquoted == "<unknown ssid>") return null
        return unquoted
    }

    private companion object {
        const val TAG = "xcertplay-usb"
    }
}
