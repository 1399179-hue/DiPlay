package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address

/**
 * Turns a bound [Network] into the interface name and IPv4 address to advertise to the iPhone.
 *
 * The read is only trusted while the process is still bound to that exact network: the CarPlay
 * tunnel, a teardown, or the platform moving the default route would otherwise let the receiver
 * announce an address it no longer owns. Two consecutive reads must also agree, so a lease that
 * changes underneath the caller is treated as "not ready" instead of being reported half-applied.
 */
internal object LynkTcamNetwork {

    private const val TAG = "xcertplay-usb"

    /** The hotspot link the head unit joined, as the iAP2 wireless payload needs it. */
    data class Backhaul(
        val interfaceName: String,
        val address: Inet4Address,
        val viaWifi: Boolean = false,
    )

    fun resolveConnected(context: Context, network: Network): Backhaul? =
        resolveBound(context, network) { connectivity -> readConnected(connectivity, network) }

    private fun readConnected(connectivity: ConnectivityManager, network: Network): Backhaul? {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val linkProperties = connectivity.getLinkProperties(network) ?: return null
        return LynkTcamNetworkPolicy.selectConnected(
            manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
            model = android.os.Build.MODEL.orEmpty(),
            network = LynkTcamNetworkPolicy.ConnectedNetwork(
                interfaceName = linkProperties.interfaceName,
                wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                notVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                addresses = linkProperties.linkAddresses.mapNotNull { it.address as? Inet4Address },
            ),
        )
    }

    private fun resolveBound(
        context: Context,
        network: Network,
        select: (ConnectivityManager) -> Backhaul?,
    ): Backhaul? {
        if (!supportsHeadUnit()) return null
        return try {
            val connectivity = context.applicationContext
                .getSystemService(ConnectivityManager::class.java) ?: return null
            if (connectivity.boundNetworkForProcess != network) return null
            val first = select(connectivity) ?: return null
            if (select(connectivity) != first) return null
            if (connectivity.boundNetworkForProcess != network) return null
            first
        } catch (failure: Exception) {
            Log.w(TAG, "TCAM network read failed type=${failure.javaClass.simpleName}")
            null
        }
    }

    private fun supportsHeadUnit(): Boolean = LynkTcamNetworkPolicy.supportsHeadUnit(
        android.os.Build.MANUFACTURER.orEmpty(),
        android.os.Build.MODEL.orEmpty(),
    )
}
