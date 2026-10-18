package com.shilapi.xcertplay.network

import android.os.Build
import com.shilapi.xcertplay.platform.HeadUnitProfiles
import java.net.Inet4Address

/**
 * Decides which connected network may be used as the wireless CarPlay backhaul.
 *
 * The head unit reaches the car's factory hotspot as a Wi-Fi *client*: the AP lives in the TCAM
 * module, `wlan0` on the head unit gets a plain DHCP lease. Selecting it therefore needs a real
 * policy, not "the first interface that is up":
 *
 *  - only on the Lynk & Co 03 (DesaySV CS11_A4), the one head unit this is proven on;
 *  - transport Wi-Fi, not VPN, and `NET_CAPABILITY_NOT_VPN` present (so the CarPlay VPN is never
 *    mistaken for the hotspot link);
 *  - exactly one usable unicast IPv4 address. `0.0.0.0`, loopback, link-local, multicast and the
 *    all-ones broadcast are rejected, and so is a link with several addresses: the receiver
 *    reports this address to the iPhone as `0x4301 wirelessIP`, so guessing would be worse than
 *    failing.
 *
 * Announcing the scoped link-local IPv6 instead leaves the session associated and then quiet,
 * which is why an IPv4 address is required rather than merely preferred.
 */
internal object LynkTcamNetworkPolicy {

    class ConnectedNetwork(
        val interfaceName: String?,
        val wifi: Boolean,
        val vpn: Boolean,
        val notVpn: Boolean,
        val addresses: List<Inet4Address>,
    )

    /**
     * True when the connected head unit is the one this backhaul is proven on.
     *
     * Delegates to [HeadUnitProfiles] so detection and this policy cannot drift apart. The earlier
     * exact `DesaySV` + `CS11_A4` match meant a firmware that renamed `Build.MODEL` while keeping the
     * `cs11_a4` fingerprint token silently switched the whole wireless path off.
     *
     * The remaining fields default to the running device, so callers that only hold manufacturer and
     * model still get the full detection.
     */
    fun supportsHeadUnit(
        manufacturer: String,
        model: String,
        brand: String = Build.BRAND.orEmpty(),
        device: String = Build.DEVICE.orEmpty(),
        product: String = Build.PRODUCT.orEmpty(),
        fingerprint: String = Build.FINGERPRINT.orEmpty(),
    ): Boolean = HeadUnitProfiles.detect(
        brand = brand,
        manufacturer = manufacturer,
        model = model,
        device = device,
        product = product,
        fingerprint = fingerprint,
    ) == HeadUnitProfiles.LYNK_03

    fun selectConnected(
        manufacturer: String,
        model: String,
        network: ConnectedNetwork,
    ): LynkTcamNetwork.Backhaul? {
        if (!supportsHeadUnit(manufacturer, model)) return null
        if (!network.wifi || network.vpn || !network.notVpn) return null
        val interfaceName = network.interfaceName?.takeIf { it.isNotBlank() } ?: return null
        val address = network.addresses
            .distinct()
            .filter { isUsableUnicast(it) }
            .singleOrNull() ?: return null
        return LynkTcamNetwork.Backhaul(interfaceName, address, viaWifi = true)
    }

    private fun isUsableUnicast(address: Inet4Address): Boolean {
        if (address.isAnyLocalAddress) return false
        if (address.isLoopbackAddress) return false
        if (address.isLinkLocalAddress) return false
        if (address.isMulticastAddress) return false
        // Reject 255.255.255.255, which is none of the above.
        return address.address.any { (it.toInt() and 0xFF) != 0xFF }
    }
}
