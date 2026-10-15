package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EthernetIpv6CodecTest {
    @Test
    fun `maps IPv6 multicast destination to Ethernet multicast`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        val destination = byteArrayOf(
            0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 1, 0xff.toByte(), 0, 0, 2,
        )
        destination.copyInto(packet, 24)

        assertArrayEquals(
            byteArrayOf(0x33, 0x33, 0xff.toByte(), 0, 0, 2),
            EthernetIpv6Codec.multicastDestinationMac(packet),
        )
    }

    @Test
    fun `does not map IPv6 unicast destination`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        packet[24] = 0xfe.toByte()
        packet[25] = 0x80.toByte()

        assertNull(EthernetIpv6Codec.multicastDestinationMac(packet))
    }

    @Test
    fun `rejects truncated or non IPv6 packets`() {
        assertNull(EthernetIpv6Codec.multicastDestinationMac(ByteArray(39)))
        assertNull(EthernetIpv6Codec.multicastDestinationMac(ByteArray(40)))
    }

    @Test
    fun `adds target link layer option to tun neighbor advertisement`() {
        val packet = ByteArray(64)
        packet[0] = 0x60
        packet[5] = 24
        packet[6] = 58
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
            .copyInto(packet, 8)
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3)
            .copyInto(packet, 24)
        packet[40] = 136.toByte()
        packet[44] = 0x60
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 2)

        val result = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(packet, mac)

        assertEquals(72, result.size)
        assertEquals(32, ((result[4].toInt() and 0xff) shl 8) or (result[5].toInt() and 0xff))
        assertEquals(2, result[64].toInt() and 0xff)
        assertEquals(1, result[65].toInt() and 0xff)
        assertArrayEquals(mac, result.copyOfRange(66, 72))
        assertEquals(0xffff, ipv6IcmpChecksumSum(result))
    }

    @Test
    fun `neighbor advertisement answers a solicitation for our link-local`() {
        val target = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 2 }
        val requester = ByteArray(16).also {
            it[0] = 0xfe.toByte(); it[1] = 0x80.toByte()
            it[8] = 0x08; it[9] = 0x0a; it[10] = 0x3f; it[11] = 0xaa.toByte()
            it[12] = 0xb1.toByte(); it[13] = 0xa3.toByte(); it[14] = 0x7d; it[15] = 0x5b
        }
        val mac = byteArrayOf(0x5a, 0xb9.toByte(), 0x65, 0x80.toByte(), 0xfe.toByte(), 0xdc.toByte())
        val peerMac = byteArrayOf(0x86.toByte(), 0x89.toByte(), 0x7d, 0x15, 0x62, 0xd6.toByte())

        val frame = EthernetIpv6Codec.buildNeighborAdvertisement(target, mac, peerMac, requester)
        val ipv6 = EthernetIpv6Codec.parseIpv6(frame)!!.ipv6

        // icmp6=136, code 0, solicited+override flags at icmp[4], hop limit 255, target and MAC option.
        assertEquals(136, ipv6[40].toInt() and 0xff)
        assertEquals(0, ipv6[41].toInt() and 0xff)
        assertEquals(0x60, ipv6[44].toInt() and 0xff)
        assertEquals(255, ipv6[7].toInt() and 0xff)
        assertArrayEquals(target, ipv6.copyOfRange(48, 64))
        assertArrayEquals(requester, ipv6.copyOfRange(8, 24))
        assertArrayEquals(requester, ipv6.copyOfRange(24, 40))
        assertEquals(2, ipv6[64].toInt() and 0xff)
        assertEquals(1, ipv6[65].toInt() and 0xff)
        assertArrayEquals(mac, ipv6.copyOfRange(66, 72))
        assertEquals(0xffff, ipv6IcmpChecksumSum(ipv6))
    }

    private fun ipv6IcmpChecksumSum(packet: ByteArray): Int {
        val payloadBytes = ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff)
        var sum = 0L
        fun words(start: Int, bytes: Int) {
            var offset = start
            val end = start + bytes
            while (offset + 1 < end) {
                sum += (((packet[offset].toInt() and 0xff) shl 8) or
                    (packet[offset + 1].toInt() and 0xff)).toLong()
                offset += 2
            }
        }
        words(8, 32)
        sum += payloadBytes
        sum += 58
        words(40, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }
}
