package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneInputBoostTest {
    private fun pcm16(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        ((pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)).toShort().toInt()

    /** A fresh quiet frame, as the capture loop produces every 20 ms. */
    private fun quietFrame(size: Int = 960) = pcm16(IntArray(size) { if (it % 2 == 0) 100 else -100 })

    @Test fun quietSpeechIsLiftedTowardsTheLevelTarget() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // Apply to fresh quiet audio repeatedly, as the loop does; the gain ramps to the level target.
        repeat(50) { boost.apply(quietFrame(), enabled = true) }
        val frame = quietFrame()
        boost.apply(frame, enabled = true)
        val gained = kotlin.math.abs(sample(frame, 0))
        // rms 100 -> 1 + (100-64)/(256-64) = 1.1875, so ~119, never past 2x.
        assertTrue("gained=$gained", gained in 110..200)
        assertTrue(boost.diagnosticSuffix().contains("inputBoostEnabled=true"))
    }

    @Test fun disabledLeavesPcmUntouchedAndResetsGain() {
        val boost = MicrophoneInputBoost(48_000, 1)
        val frame = quietFrame()
        boost.apply(frame, enabled = true)
        boost.apply(frame, enabled = false)
        assertEquals(100, kotlin.math.abs(sample(frame, 0)))
        assertTrue(boost.diagnosticSuffix().contains("inputBoostEnabled=false"))
    }

    @Test fun loudAudioIsBoundedByTheHeadroom() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // Near full scale: the gain may rise only to the headroom (32767/peak), never past 32767.
        repeat(20) { boost.apply(pcm16(IntArray(960) { if (it % 2 == 0) 30_000 else -30_000 }), enabled = true) }
        val frame = pcm16(IntArray(960) { if (it % 2 == 0) 30_000 else -30_000 })
        boost.apply(frame, enabled = true)
        val gained = kotlin.math.abs(sample(frame, 0))
        assertTrue("gained=$gained", gained in 30_000..32767)
    }

    @Test fun bothChannelsMoveTogether() {
        val boost = MicrophoneInputBoost(48_000, 2)
        repeat(50) { boost.apply(pcm16(IntArray(960) { 120 }), enabled = true) }
        val frame = pcm16(IntArray(960) { 120 })
        boost.apply(frame, enabled = true)
        assertEquals(sample(frame, 0), sample(frame, 1))
        assertTrue(sample(frame, 0) > 120)
    }
}
