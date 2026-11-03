package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guidance is a pure mapping from a failure to a string resource, so the mapping is tested
 * against the resource constants directly. Rendering the strings needs an Android context and is
 * verified on the device instead.
 */
class ConnectionFailureGuidanceTest {
    private fun guide(
        message: String,
        wireless: Boolean = true,
        reset: Boolean = false,
        startup: WirelessStartupFailure? = null,
        known: ConnectionFailureCause? = null,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    ): Int = ConnectionFailureGuidance.message(
        CarPlayStatus.Failed(message, startupFailure = startup, wifiResetRequired = reset, knownCause = known),
        wireless,
        mode,
    )

    private val channelRejected = "Wi-Fi Direct channel 149 is not allowed beside the hotspot"
    private val carHotspotOff = "The car hotspot is turned off"
    private val hotspotNotReady = "the manual hotspot interface was not found"

    @Test fun typedFailuresHavePriorityOverMessages() {
        assertEquals(R.string.connection_help_reset_wifi,
            guide("unknown", reset = true, startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
        assertEquals(R.string.connection_help_bluetooth_unusable,
            guide("usb permission denied", startup = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(R.string.connection_help_wifi_direct_beside_hotspot, guide("Wi-Fi P2P createGroup failed",
            startup = WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT, mode = WirelessHotspotMode.WIFI_P2P))
        assertEquals(R.string.connection_help_hotspot,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_NOT_READY))
        assertEquals(R.string.connection_help_hotspot,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(R.string.connection_help_iphone_wifi,
            guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
    }

    @Test fun aKnownCauseNamesItsOwnHelp() {
        assertEquals(R.string.connection_help_chosen_iphone_not_paired, guide(
            "The selected iPhone is no longer paired. Choose it again in DiPlay.",
            known = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED))
        assertEquals(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c, guide(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
            known = ConnectionFailureCause.NO_PAIRED_IPHONE))
        assertEquals(R.string.connection_help_choose_iphone, guide(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
            known = ConnectionFailureCause.IPHONE_NOT_CHOSEN))
        assertEquals(R.string.connection_help_wifi_direct_channel, guide(channelRejected,
            known = ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE, mode = WirelessHotspotMode.WIFI_P2P))
    }

    @Test fun aHotspotCauseNamesItsOwnHelp() {
        assertEquals(R.string.connection_help_car_hotspot_off, guide(carHotspotOff,
            startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_OFF))
        assertEquals(R.string.connection_help_car_hotspot_details_differ,
            guide("Manual hotspot SSID does not match the active local AP configuration: 'Other'",
                startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER))
        assertEquals(R.string.connection_help_hotspot_interface_not_found, guide(hotspotNotReady,
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND))
        assertEquals(R.string.connection_help_hotspot, guide("Timed out waiting for the car hotspot",
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT))
        assertEquals(R.string.connection_help_hotspot, guide("The car could not start its hotspot",
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.CAR_HOTSPOT_START_FAILED))
    }

    @Test fun aResetComesFirstAndAKnownCauseBeforeAStartupFailure() {
        assertEquals(R.string.connection_help_reset_wifi,
            guide(channelRejected, reset = true, known = ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE))
        assertEquals(R.string.connection_help_hotspot,
            guide(carHotspotOff, startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(R.string.connection_help_car_hotspot_off, guide(carHotspotOff,
            startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_OFF))
    }

    @Test fun messageHintsAreTransportAwareAndSafeForUnknownErrors() {
        assertEquals(R.string.connection_help_permissions, guide("USB permission was denied", wireless = false))
        assertEquals(R.string.connection_help_usb, guide("NCM data path failed", wireless = false))
        assertEquals(R.string.connection_help_bluetooth, guide("RFCOMM socket closed"))
        assertEquals(R.string.connection_help_hotspot, guide("createGroup failed"))
        assertEquals(R.string.connection_help_iphone_wifi, guide("AirPlay TCP timeout"))
        assertEquals(R.string.connection_help_general, guide("opaque error"))
        assertEquals(R.string.connection_help_general, guide("opaque error", wireless = false))
        assertEquals(R.string.connection_help_general, guide("Bluetooth error", wireless = false))
    }

    @Test fun onlyTheCarHotspotAsksTheDriverToJoinANetworkOnTheIphone() {
        for (mode in listOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)) {
            assertEquals(R.string.connection_help_wifi_direct,
                guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT, mode = mode))
            assertEquals(R.string.connection_help_wifi_direct, guide("createGroup failed", mode = mode))
            assertEquals(R.string.connection_help_wifi_direct, guide("AirPlay TCP timeout", mode = mode))
        }
        assertEquals(R.string.connection_help_existing_wifi,
            guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT, mode = WirelessHotspotMode.EXISTING_WIFI))
        assertEquals(R.string.connection_help_existing_wifi,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, mode = WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun aPermissionAndroidRefusesItselfIsNotOneTheDriverCanGrant() {
        // #671: MIUI 13 refused RFCOMM with "lacks permission android.permission.BLUETOOTH".
        assertEquals(R.string.connection_help_general, guide(
            "Could not connect RFCOMM to [address] <- SecurityException: UID 10211 / PID 12347 lacks permission android.permission.BLUETOOTH"))
        assertEquals(R.string.connection_help_permissions, guide("Hotspot control permission is missing"))
    }

    @Test fun everyCauseNamesAStageLine() {
        for (cause in ConnectionFailureCause.entries) {
            assertEquals(cause.name, true, ConnectionFailureGuidance.stage(cause) != 0)
        }
    }
}
