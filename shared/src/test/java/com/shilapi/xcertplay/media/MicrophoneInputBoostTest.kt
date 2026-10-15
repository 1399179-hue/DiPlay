package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneInputBoostTest {
    private fun pcm16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    private fun firstSample(pcm: ByteArray): Int =
        ((pcm[0].toInt() and 0xff) or (pcm[1].toInt() shl 8)).toShort().toInt()

    @Test fun quietSpeechIsLifted() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // RMS 100 sits between QUIET_RMS (64) and BOOST_RMS (256): a partial boost, well under 2x.
        val frame = pcm16(100, -100, 100, -100)
        repeat(1_000) { boost.apply(frame, enabled = true) }
        assertTrue(boost.enabled)
        val gained = firstSample(frame)
        assertTrue("gained=$gained", gained in 101..200)
    }

    @Test fun disabledLeavesPcmUntouchedAndResetsGain() {
        val boost = MicrophoneInputBoost(48_000, 1)
        val frame = pcm16(100, -100)
        boost.apply(frame, enabled = true)
        boost.apply(frame, enabled = false)
        assertEquals(100, firstSample(frame))
        assertTrue(boost.diagnosticSuffix().contains("inputBoostEnabled=false"))
    }

    @Test fun loudAudioIsNeverClipped() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // Near full scale: the gain must stay at unity, not push past 32767.
        val frame = pcm16(30_000, -30_000, 30_000, -30_000)
        repeat(50) { boost.apply(frame, enabled = true) }
        assertEquals(30_000, firstSample(frame))
    }

    @Test fun stereoChannelsAreBothGained() {
        val boost = MicrophoneInputBoost(48_000, 2)
        val frame = pcm16(120, 120, -120, -120)
        repeat(1_000) { boost.apply(frame, enabled = true) }
        val left = firstSample(frame)
        assertTrue("left=$left", left in 121..240)
        // The right channel must move with the left, not be left behind.
        val right = ((frame[2].toInt() and 0xff) or (frame[3].toInt() shl 8)).toShort().toInt()
        assertEquals(left, right)
        assertFalse(left == 120)
    }
}
