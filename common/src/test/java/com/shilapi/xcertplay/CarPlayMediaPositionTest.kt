package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AVRCP carries the play position in whole seconds, so a tick that only moves the millisecond is not
 * new data to the car. These cases pin the coarsening used by [CarPlayMediaKeys]'s repeat filter.
 */
class CarPlayMediaPositionTest {
    @Test fun playingPositionsCompareInWholeSeconds() {
        assertEquals(12L, avrcpPosition(playing = true, position = 12_000L))
        assertEquals(12L, avrcpPosition(playing = true, position = 12_400L))
        assertEquals(12L, avrcpPosition(playing = true, position = 12_999L))
        assertEquals(13L, avrcpPosition(playing = true, position = 13_000L))
    }

    @Test fun aPausedPositionIsKeptExact() {
        assertEquals(12_400L, avrcpPosition(playing = false, position = 12_400L))
    }

    @Test fun anUnknownPositionStaysUnknown() {
        // PlaybackState.PLAYBACK_POSITION_UNKNOWN is -1; dividing it would make it look like position 0.
        assertEquals(-1L, avrcpPosition(playing = true, position = -1L))
        assertEquals(-1L, avrcpPosition(playing = false, position = -1L))
    }

    @Test fun theStartOfAPlaybackIsPositionZero() {
        assertEquals(0L, avrcpPosition(playing = true, position = 0L))
    }
}
