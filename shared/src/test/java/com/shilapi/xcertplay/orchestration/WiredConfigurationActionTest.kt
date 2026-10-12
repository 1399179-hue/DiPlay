package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A configuration without Apple Ethernet is used only after a CarPlay-mode request was tried (#634). */
class WiredConfigurationActionTest {
    private val maximum = 3

    @Test fun carPlayModeConfigurationIsUsedAtOnce() {
        assertEquals(
            WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(found = true, carPlayMode = true, transitionsTried = 0, maximumTransitions = maximum),
        )
    }

    @Test fun usbMuxNcmWithoutAppleEthernetRequestsTheCarPlayModeFirst() {
        assertEquals(
            WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(found = true, carPlayMode = false, transitionsTried = 0, maximumTransitions = maximum),
        )
        // And the fallback is armed, because the iPhone already offers USBMUX and NCM.
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = false))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = true))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = false, carPlayMode = false))
    }

    @Test fun afterAModeRequestWasTriedThePlainConfigurationIsUsed() {
        assertEquals(
            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET,
            WiredConfigurationAction.choose(found = true, carPlayMode = false, transitionsTried = 1, maximumTransitions = maximum),
        )
    }

    @Test fun nothingFoundStillRequestsUntilTheLimitThenRejects() {
        assertEquals(
            WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(found = false, carPlayMode = false, transitionsTried = 1, maximumTransitions = maximum),
        )
        assertEquals(
            WiredConfigurationAction.REJECT,
            WiredConfigurationAction.choose(found = false, carPlayMode = false, transitionsTried = maximum, maximumTransitions = maximum),
        )
    }
}
