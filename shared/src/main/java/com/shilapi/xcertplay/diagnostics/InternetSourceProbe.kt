package com.shilapi.xcertplay.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Which network the iPhone's CarPlay apps (maps, music streaming, Siri) reach the internet through.
 *
 * The head unit cannot count those bytes: on wireless the phone talks to the car's TCAM access
 * point (192.168.15.1) directly, so its internet traffic never crosses this app. What the head unit
 * *can* establish is where the exit is, which is what the driver actually asks:
 *
 *  - the car's TCAM hotspot has a working internet exit → the phone prefers that Wi-Fi, so the apps
 *    go out through the car's own 4G ([Car]);
 *  - the hotspot has no exit while the session is wireless → the phone can only use its own
 *    cellular ([Phone]);
 *  - the session is wired over USB → the phone's exit is on its own side and cannot be observed
 *    ([UsbOnly]), with the car's own connectivity reported separately for context.
 *
 * This is a session-granular judgement, not a per-packet attribution: iOS may switch between Wi-Fi
 * and cellular on its own, which no head-unit API exposes. The conclusion follows iOS's own
 * "prefer a Wi-Fi that reaches the internet" rule, which is why a phone in airplane mode still had
 * internet in CarPlay when the car hotspot was up.
 */
object InternetSourceProbe {

    enum class Source {
        /** The car's hotspot reaches the internet, so CarPlay apps go out through the car's 4G. */
        CAR,

        /** The hotspot has no exit on a wireless session, so the phone uses its own cellular. */
        PHONE,

        /** Wired USB session: the phone's exit is on its own side and cannot be observed here. */
        USB,

        /** The probe could not decide (no hotspot link, or the check was inconclusive). */
        UNKNOWN,
    }

    data class Result(
        val source: Source,
        /** Whether the car's own network answered the internet check during this session. */
        val carHasInternet: Boolean,
        /** The transport the CarPlay session used. */
        val wireless: Boolean,
    )

    private const val PROBE_HOST = "captive.apple.com"
    private const val PROBE_PORT = 80
    private const val PROBE_TIMEOUT_MILLIS = 1_500

    /**
     * Decides the exit for one session.
     *
     * [carNetwork] is the network the process is bound to on a wireless session (the TCAM hotspot),
     * or null. [wireless] is the session transport. The probe runs a single TCP connect bound to
     * [carNetwork] so the answer reflects that interface, not whatever the default route happens to be.
     */
    fun decide(
        context: Context,
        carNetwork: Network?,
        wireless: Boolean,
        probe: (Network) -> Boolean = { network -> probeDefault(context, network) },
    ): Result {
        if (!wireless) {
            // Wired CarPlay rides USB; the phone's internet exit is invisible here. Report the car's
            // own connectivity for context only.
            return Result(Source.USB, carHasInternet = carNetwork?.let(probe) ?: false, wireless = false)
        }
        val hasInternet = carNetwork?.let(probe) ?: false
        return Result(
            source = if (hasInternet) Source.CAR else Source.PHONE,
            carHasInternet = hasInternet,
            wireless = true,
        )
    }

    /** One TCP connect through [network]; true when it completes. */
    private fun probeDefault(context: Context, network: Network): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        // A network that reports no internet capability cannot answer; skip the socket round-trip.
        val capabilities = manager.getNetworkCapabilities(network)
        if (capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        return runCatching {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MILLIS)
                true
            }
        }.getOrDefault(false)
    }

    /** A short line for the connection screen and the diagnostic report. */
    fun describe(result: Result): String = when (result.source) {
        Source.CAR -> "car-network (TCAM hotspot has internet)"
        Source.PHONE -> "phone-network (hotspot has no internet on a wireless session)"
        Source.USB -> "usb-session (phone exit not observable); car-internet=${result.carHasInternet}"
        Source.UNKNOWN -> "unknown (no hotspot link or inconclusive probe)"
    }
}
