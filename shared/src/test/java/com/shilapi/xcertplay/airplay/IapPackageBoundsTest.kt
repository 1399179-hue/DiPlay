package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.Socket

/**
 * A tunnel package header carries a 32-bit size the peer chooses. A size that cannot be real means the stream has
 * desynchronised, and the old code simply waited for that many bytes to arrive.
 *
 * The parser is private and runs on the tunnel's own reader thread, so it is driven directly by reflection rather
 * than through a socket: the refusal is what matters, not the threading around it.
 */
class IapPackageBoundsTest {
    // A concrete (non-any-local) address so a client that connects to loopback really presents 127.0.0.1.
    private val loopback = InetAddress.getLoopbackAddress()

    private fun parse(tunnel: IapTunnel, bytes: ByteArray): ByteArray {
        val method = IapTunnel::class.java.getDeclaredMethod("parsePackages", ByteArray::class.java)
            .apply { isAccessible = true }
        return method.invoke(tunnel, bytes) as ByteArray
    }

    private fun packageHeader(size: Int): ByteArray {
        val header = ByteArray(32)
        header[0] = (size ushr 24).toByte()
        header[1] = (size ushr 16).toByte()
        header[2] = (size ushr 8).toByte()
        header[3] = size.toByte()
        return header
    }

    @Test
    fun aSizeSmallerThanTheHeaderEndsTheConnection() {
        val tunnel = IapTunnel(ByteArray(32), loopback)
        try {
            val bytes = packageHeader(PACKAGE_HEADER_LEN - 1) + ByteArray(4)
            val error = assertThrows(InvocationTargetException::class.java) { parse(tunnel, bytes) }
            assertEquals(IllegalStateException::class.java, error.targetException.javaClass)
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun aSizeOverTheCapEndsTheConnection() {
        val tunnel = IapTunnel(ByteArray(32), loopback)
        try {
            val bytes = packageHeader(MAX_PACKAGE + 1) + ByteArray(4)
            val error = assertThrows(InvocationTargetException::class.java) { parse(tunnel, bytes) }
            assertEquals(IllegalStateException::class.java, error.targetException.javaClass)
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun anEmptyPackageIsLegalAndConsumed() {
        val tunnel = IapTunnel(ByteArray(32), loopback)
        try {
            val remaining = parse(tunnel, packageHeader(PACKAGE_HEADER_LEN))
            assertEquals(0, remaining.size)
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun anIncompletePackageIsRetainedForTheNextRead() {
        val tunnel = IapTunnel(ByteArray(32), loopback)
        try {
            val bytes = packageHeader(PACKAGE_HEADER_LEN + 10) + ByteArray(4)
            val remaining = parse(tunnel, bytes)
            assertEquals(bytes.size, remaining.size)
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun aPeerOutsideTheControlSessionNeverBecomesTheTunnelPeer() {
        val other = InetAddress.getByName("127.0.0.2")
        val tunnel = IapTunnel(ByteArray(32), loopback)
        try {
            val port = tunnel.listen(object : IapTunnel.Listener {})
            // Wait until the stray peer has been accepted and refused, otherwise the assertion below would
            // pass merely because the listener thread had not run yet.
            Socket().use { socket ->
                // Bind the source explicitly: connecting to 127.0.0.1 without a bind can present ::1 on a
                // dual-stack host, which is not comparable to the expected IPv4 address.
                socket.bind(java.net.InetSocketAddress(other, 0))
                socket.connect(java.net.InetSocketAddress(loopback, port), 1_000)
                socket.soTimeout = 2_000
                runCatching { while (socket.getInputStream().read() >= 0) { } }
            }
            assertEquals(false, tunnel.awaitPeerConnection(300))
        } finally {
            tunnel.close()
        }
    }

    private companion object {
        const val PACKAGE_HEADER_LEN = 32
        const val MAX_PACKAGE = 4 * 1024 * 1024
    }
}
