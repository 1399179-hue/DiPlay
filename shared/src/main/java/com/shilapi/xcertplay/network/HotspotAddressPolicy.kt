package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily != FAMILY_IPV6) {
            return select(addresses)
        }
        for (address in addresses) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                return address
            }
        }
        return select(addresses)
    }

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && !publishedIsIpv6 && linkLocal == null) {
                linkLocal = address
            }
        }
        return if (publishedIsIpv6) null else linkLocal
    }

    /**
     * True when the iPhone can dial this IPv4 address back on the hotspot link.
     *
     * Loopback, `0.0.0.0` and multicast can never be reached, so they are rejected. A
     * `169.254/16` lease is deliberately *accepted*: it is still a plain unicast IPv4 address that
     * iOS reaches over the same link, whereas the alternative - falling back to the interface's
     * scoped `fe80::` - is an address iOS cannot route at all. Announcing `fe80::` leaves the
     * session associated and then silent, with no TCP ever accepted, which is the failure this
     * accepts a link-local lease to avoid.
     */
    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isMulticastAddress
}
