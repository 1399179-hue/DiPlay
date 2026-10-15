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

    private fun samples(pcm: ByteArray): List<Int> =
        pcm.asList().chunked(2).map { (it[0].toInt() and 0xff) or (it[1].toInt() shl 8) }
            .map { if (it > 32767) it - 65536 else it }

    @Test fun quietSpeechIsLiftedTowardsTwoTimes() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // RMS 100: below BOOST_RMS but above QUIET_RMS, so a partial boost.
        val frame = pcm16(100, -100, 100, -100)
        repeat(200) { boost.apply(frame, enabled = true) }
        assertTrue(boost.enabled)
        val gained = samples(frame).first()
        assertTrue("gained=$gained", gained in 101..200)
    }

    @Test fun disabledLeavesPcmUntouchedAndResetsGain() {
        val boost = MicrophoneInputBoost(48_000, 1)
        val frame = pcm16(100, -100)
        boost.apply(frame, enabled = true)
        boost.apply(frame, enabled = false)
        assertEquals(listOf(100, -100), samples(frame))
        assertTrue(boost.diagnosticSuffix().contains("inputBoostEnabled=false"))
    }

    @Test fun loudAudioIsNeverClipped() {
        val boost = MicrophoneInputBoost(48_000, 1)
        // Near full scale: the gain must stay at unity, not push past 32767.
        val frame = pcm16(30_000, -30_000, 30_000, -30_000)
        repeat(50) { boost.apply(frame, enabled = true) }
        val out = samples(frame)
        assertTrue(out.all { it in -32768..32767 })
        assertTrue(out.none { it == 32767 || it == -32768 })
    }

    @Test fun stereoChannelsAreBothGained() {
        val boost = MicrophoneInputBoost(48_000, 2)
        val frame = pcm16(120, 120, -120, -120)
        repeat(200) { boost.apply(frame, enabled = true) }
        val out = samples(frame)
        assertEquals(4, out.size)
        assertFalse(out.all { it == 120 || it == -120 })
    }
}
