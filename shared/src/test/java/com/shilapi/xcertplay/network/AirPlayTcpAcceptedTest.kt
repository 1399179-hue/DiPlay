package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.acceptFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [ServerSocket.acceptFrom] keeps a listener that only ever talks to the iPhone that opened the control session.
 *
 * Another device on the hotspot (or the LAN, when the bridge is up) must not be able to attach to a stream socket
 * and feed it frames, so the wrong peer is closed and the listener keeps waiting for the real one.
 */
class AirPlayTcpAcceptedTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun aPeerThatMatchesTheControlSessionIsAccepted() {
        ServerSocket(0).use { server ->
            Socket(loopback, server.localPort).use { _ ->
                server.acceptFrom(loopback).use { accepted ->
                    assertEquals(loopback, accepted.inetAddress)
                }
            }
        }
    }

    @Test
    fun aPeerThatDoesNotMatchIsTurnedAwayAndTheMatchingPeerStillArrives() {
        val other = InetAddress.getByName("127.0.0.2")
        ServerSocket(0).use { server ->
            val seen = AtomicReference<InetAddress?>()
            val accepted = AtomicReference<Socket?>()
            val listener = Thread {
                accepted.set(server.acceptFrom(loopback) { seen.set(it) })
            }.apply { isDaemon = true }
            listener.start()

            // Connect from a different loopback address, keeping the client open until the listener has
            // made its decision: a socket closed by the test can make the accept side see a reset before the
            // address is ever examined on some platforms.
            val stray = Socket()
            stray.bind(java.net.InetSocketAddress(other, 0))
            stray.connect(java.net.InetSocketAddress(loopback, server.localPort), 2_000)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (seen.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("the wrong peer was not turned away", seen.get() != null)
            } finally {
                stray.close()
            }

            // Keep the client socket open until acceptFrom returns: its own listener thread closes it, and a
            // socket closed by the test would make the accept side see a reset rather than a peer.
            val matching = Socket(loopback, server.localPort)
            try {
                listener.join(TimeUnit.SECONDS.toMillis(10))
                assertTrue("the matching peer was not accepted", accepted.get() != null)
            } finally {
                accepted.get()?.close()
                matching.close()
            }
        }
    }
}
