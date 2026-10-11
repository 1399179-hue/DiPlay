package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SETUP `audioLatencyMs` is a floor, not a hint: the handset sends that much audio before it
 * waits for our playout position, so a track sized below it drops sound. The wired stream announces
 * tens of milliseconds while the buffered wireless stream announces about a second, which is the
 * case this guards.
 */
class MediaAudioBufferNegotiatedLatencyTest {
    private val bytesPerMs = 48_000L * 2 * 2 / 1000 // 48 kHz stereo PCM16

    @Test fun announcedWirelessLatencyRaisesTheTrackAboveTheUserSetting() {
        val plan = MediaAudioBuffer.plan(
            isMedia = true,
            sampleRate = 48_000,
            channels = 2,
            minBufferBytes = 7_680,
            mediaMillis = MediaAudioBuffer.DEFAULT_MILLIS,
            negotiatedLatencyMs = 1_000,
        )

        // 1000 ms of audio plus the fixed headroom must fit in the track.
        assertTrue(plan.trackBufferBytes >= (bytesPerMs * 1_000).toInt())
        assertTrue(plan.startBytes >= (bytesPerMs * 1_000).toInt())
    }

    @Test fun aSmallWiredLatencyDoesNotShrinkTheUserSetting() {
        val withAnnouncement = MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 500, negotiatedLatencyMs = 32)
        val withoutAnnouncement = MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 500)

        assertEquals(withoutAnnouncement, withAnnouncement)
    }

    @Test fun nonMediaStreamsIgnoreTheAnnouncement() {
        val guidance = MediaAudioBuffer.plan(false, 16_000, 1, 1_280, 1_000, negotiatedLatencyMs = 1_000)

        assertEquals(
            MediaAudioBuffer.plan(false, 16_000, 1, 1_280, 1_000),
            guidance,
        )
    }

    @Test fun theUserSettingStillWinsWhenItIsLarger() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 1_000, negotiatedLatencyMs = 100)

        assertTrue(plan.trackBufferBytes >= (bytesPerMs * 1_000).toInt())
    }
}
