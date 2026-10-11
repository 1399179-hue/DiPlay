package com.shilapi.xcertplay

import android.graphics.Color

/** Semantic colors for DiPlay-owned chrome. Media canvases and cluster-specific palettes stay separate. */
internal data class DiPlayPalette(
    val background: Int,
    val surface: Int,
    val button: Int,
    val outline: Int,
    val railSelected: Int,
    val railSelectedOutline: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val warning: Int,
    val success: Int,
    val danger: Int,
    val ripple: Int,
    val focusRing: Int,
    val systemBar: Int,
    val systemBarIconsAreDark: Boolean,
    val overlayBackground: Int,
    val overlayPrimaryText: Int,
    val overlaySecondaryText: Int,
    val overlayAccent: Int,
    val overlayOnAccent: Int,
    val overlayAccentTrack: Int,
    val overlayTrackOff: Int,
    val overlayDanger: Int,
) {
    companion object {
        fun of(night: Boolean): DiPlayPalette = if (night) DARK else LIGHT

        val DARK = DiPlayPalette(
            background = Color.rgb(12, 17, 27),
            surface = Color.rgb(21, 30, 44),
            button = Color.rgb(31, 43, 61),
            outline = Color.rgb(42, 56, 75),
            railSelected = Color.rgb(24, 54, 92),
            railSelectedOutline = Color.rgb(42, 82, 130),
            primaryText = Color.rgb(241, 245, 252),
            secondaryText = Color.rgb(168, 182, 202),
            accent = Color.rgb(166, 200, 255),
            onAccent = Color.rgb(12, 17, 27),
            accentTrack = Color.rgb(50, 88, 140),
            trackOff = Color.rgb(42, 56, 75),
            warning = Color.rgb(255, 196, 128),
            success = Color.rgb(127, 205, 154),
            danger = Color.rgb(190, 45, 45),
            ripple = 0x336F9FD9,
            focusRing = Color.rgb(166, 200, 255),
            systemBar = Color.rgb(12, 17, 27),
            systemBarIconsAreDark = false,
            overlayBackground = Color.rgb(12, 16, 19),
            overlayPrimaryText = Color.WHITE,
            overlaySecondaryText = Color.rgb(170, 180, 190),
            overlayAccent = Color.rgb(127, 205, 154),
            overlayOnAccent = Color.rgb(8, 17, 11),
            overlayAccentTrack = Color.rgb(78, 143, 102),
            overlayTrackOff = Color.rgb(64, 74, 80),
            overlayDanger = Color.rgb(190, 45, 45),
        )

        val LIGHT = DiPlayPalette(
            // Apple's iOS light system: a cool grouped background, white cards, and system blue as
            // the single accent. The blue is the darker "accessible" variant so white-on-accent
            // text and accent-on-background both clear WCAG 4.5:1; plain #007AFF does not.
            background = Color.rgb(242, 242, 247),
            surface = Color.rgb(255, 255, 255),
            button = Color.rgb(232, 232, 237),
            outline = Color.rgb(209, 209, 214),
            railSelected = Color.rgb(225, 238, 255),
            railSelectedOutline = Color.rgb(168, 204, 245),
            primaryText = Color.rgb(29, 29, 31),
            secondaryText = Color.rgb(99, 99, 102),
            accent = Color.rgb(0, 104, 214),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(0, 104, 214),
            trackOff = Color.rgb(209, 209, 214),
            warning = Color.rgb(138, 83, 0),
            success = Color.rgb(30, 122, 60),
            danger = Color.rgb(193, 39, 45),
            ripple = 0x330068D6,
            focusRing = Color.rgb(0, 104, 214),
            systemBar = Color.rgb(242, 242, 247),
            systemBarIconsAreDark = true,
            overlayBackground = Color.rgb(247, 247, 250),
            overlayPrimaryText = Color.rgb(29, 29, 31),
            overlaySecondaryText = Color.rgb(90, 90, 95),
            overlayAccent = Color.rgb(10, 110, 46),
            overlayOnAccent = Color.WHITE,
            overlayAccentTrack = Color.rgb(10, 110, 46),
            overlayTrackOff = Color.rgb(199, 199, 204),
            overlayDanger = Color.rgb(193, 39, 45),
        )
    }
}
