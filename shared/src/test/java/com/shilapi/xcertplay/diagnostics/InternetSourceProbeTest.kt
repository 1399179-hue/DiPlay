package com.shilapi.xcertplay.diagnostics

import android.content.Context
import android.net.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The exit is judged from the car hotspot's reachability, not from per-packet attribution.
 *
 * The probe itself is injected, so these tests cover the decision table without a socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class InternetSourceProbeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A stand-in network; only the injected probe decides, so its identity does not matter. */
    private val anyNetwork: Network get() = mock(Network::class.java)

    @Test
    fun aReachableHotspotMeansTheCarNetwork() {
        val result = InternetSourceProbe.decide(context, anyNetwork, wireless = true, probe = { true })
        assertEquals(InternetSourceProbe.Source.CAR, result.source)
        assertTrue(result.carHasInternet)
        assertTrue(result.wireless)
    }

    @Test
    fun anUnreachableHotspotMeansThePhoneNetwork() {
        val result = InternetSourceProbe.decide(context, anyNetwork, wireless = true, probe = { false })
        assertEquals(InternetSourceProbe.Source.PHONE, result.source)
        assertFalse(result.carHasInternet)
    }

    @Test
    fun noHotspotLinkOnWirelessIsThePhoneNetwork() {
        val result = InternetSourceProbe.decide(context, carNetwork = null, wireless = true, probe = { true })
        assertEquals(InternetSourceProbe.Source.PHONE, result.source)
        assertFalse(result.carHasInternet)
    }

    @Test
    fun aWiredSessionReportsUsbAndTheCarsOwnState() {
        val result = InternetSourceProbe.decide(context, anyNetwork, wireless = false, probe = { true })
        assertEquals(InternetSourceProbe.Source.USB, result.source)
        assertTrue(result.carHasInternet)
        assertFalse(result.wireless)
    }

    @Test
    fun aWiredSessionWithNoCarNetworkStillReportsUsb() {
        val result = InternetSourceProbe.decide(context, carNetwork = null, wireless = false, probe = { true })
        assertEquals(InternetSourceProbe.Source.USB, result.source)
        assertFalse(result.carHasInternet)
    }

    @Test
    fun aWiredSessionStillChecksTheCarsOwnConnectivityForContext() {
        var probed = false
        val result = InternetSourceProbe.decide(context, anyNetwork, wireless = false, probe = { probed = true; true })
        // The exit is the phone's, but the car's own state is reported for context, so it is probed.
        assertTrue(probed)
        assertTrue(result.carHasInternet)
        assertEquals(InternetSourceProbe.Source.USB, result.source)
    }
}
