package com.shilapi.xcertplay.orchestration

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHandoffTest {
    @Test
    fun activeTunnelKeepsHandoffAliveAfterBluetoothBootstrapCloses() {
        assertTrue(
            isWirelessHandoffInProgress(
                handoffRequested = false,
                tunnelActive = true,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun ordinaryBootstrapLossStillFailsWithoutHandoffState() {
        assertFalse(
            isWirelessHandoffInProgress(
                handoffRequested = false,
                tunnelActive = false,
                sessionActive = false,
            ),
        )
    }

    @Test
    fun rfcommEofAfterASessionKeepsTheTunnel() {
        assertTrue(
            isRfcommEofAfterAirPlaySession(
                IOException("iAP2 link ended"),
                sessionActive = true,
                frameRendered = false,
            ),
        )
        assertTrue(
            isRfcommEofAfterAirPlaySession(
                IOException("iAP2 link ended"),
                sessionActive = false,
                frameRendered = true,
            ),
        )
        assertTrue(
            isRfcommEofAfterAirPlaySession(
                IOException("iAP2 link ended"),
                sessionActive = true,
                frameRendered = true,
            ),
        )
    }

    @Test
    fun earlyRfcommEofStillFailsBringUp() {
        // Before a session or a rendered frame the tunnel is unproven: bring-up must still retry.
        assertFalse(
            isRfcommEofAfterAirPlaySession(
                IOException("iAP2 link ended"),
                sessionActive = false,
                frameRendered = false,
            ),
        )
    }

    @Test
    fun otherFailuresStayFatalAfterASessionExists() {
        assertFalse(
            isRfcommEofAfterAirPlaySession(
                IOException("Wireless CarPlay control channel closed before tunnel iAP2 ready"),
                sessionActive = true,
                frameRendered = true,
            ),
        )
        assertFalse(
            isRfcommEofAfterAirPlaySession(
                IOException("iAP2 inbound frame exceeds 65535 bytes"),
                sessionActive = true,
                frameRendered = true,
            ),
        )
        assertFalse(
            isRfcommEofAfterAirPlaySession(
                IllegalStateException("iAP2 link ended"),
                sessionActive = true,
                frameRendered = true,
            ),
        )
    }
}
