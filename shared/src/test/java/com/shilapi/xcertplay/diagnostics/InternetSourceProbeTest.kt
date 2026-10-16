package com.shilapi.xcertplay.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

/** The exit is judged from the car hotspot's reachability, not from per-packet attribution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class InternetSourceProbeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun network(withInternet: Boolean = true): Network {
        val network = mock(Network::class.java)
        val capabilities = mock(NetworkCapabilities::class.java)
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(withInternet)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Robolectric's manager answers for the shadow; stub it so decide() can read capabilities.
        whenever(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
        return network
    }

    @Test
    fun aReachableHotspotMeansTheCarNetwork() {
        val car = network()
        val result = InternetSourceProbe.decide(context, car, wireless = true, probe = { true })
        assertEquals(InternetSourceProbe.Source.CAR, result.source)
        assertTrue(result.carHasInternet)
        assertTrue(result.wireless)
    }

    @Test
    fun anUnreachableHotspotMeansThePhoneNetwork() {
        val car = network()
        val result = InternetSourceProbe.decide(context, car, wireless = true, probe = { false })
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
        val car = network()
        val result = InternetSourceProbe.decide(context, car, wireless = false, probe = { true })
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
}
