package com.shilapi.xcertplay.network

/**
 * Band and channel conversions for the TCAM payload.
 *
 * The TCAM module reports the *legacy* `WifiConfiguration` band encoding, in which `0` means
 * 2.4 GHz, `1` means 5 GHz and `-1` means "no preference". Android's `SoftApConfiguration` uses a
 * different numbering, so the two cannot be mixed: this file therefore carries its own copies and
 * deliberately does not reuse the helpers in `WirelessHotspotManager.kt`, whose band constants
 * follow `SoftApConfiguration` (1 = 2.4 GHz, 2 = 5 GHz, 3 = 6 GHz).
 *
 * Getting this wrong is silent: the channel and the band label are reported to the iPhone in
 * `0x5703`, and a channel that does not belong to the advertised band makes the handset refuse
 * the network without any error of its own.
 *
 * [legacyHotspotBandToSoftApBand] already exists in `WirelessHotspotManager.kt` with identical
 * behaviour, so it is reused rather than redeclared; the channel/label helpers below are the
 * TCAM-specific ones.
 */

/** Converts a channel to MHz using the `SoftApConfiguration` band encoding this file produces. */
internal fun tcamChannelToFrequencyMhz(channel: Int, band: Int?): Int? = when {
    channel == 0 -> null
    band == 1 && channel in 1..13 -> 2407 + channel * 5
    band == 1 && channel == 14 -> 2484
    band == 2 && channel in 32..177 -> 5000 + channel * 5
    band == 4 && channel in 1..233 -> 5950 + channel * 5
    else -> null
}

/** User-facing label for the `SoftApConfiguration` band encoding this file produces. */
internal fun tcamBandLabel(band: Int?): String? = when (band) {
    1 -> "2.4 GHz"
    2 -> "5 GHz"
    3 -> "2.4 / 5 GHz (auto)"
    4 -> "6 GHz"
    else -> null
}
