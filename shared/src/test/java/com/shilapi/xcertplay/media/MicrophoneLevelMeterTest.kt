package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneLevelMeterTest {
    private fun pcm16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    @Test fun countsRmsPeakAndClippingPerChannel() {
        val meter = MicrophoneLevelMeter(1)
        meter.accept(pcm16(1000, -1000, 32767, -32768))
        val window = meter.takeWindow()
        assertEquals(4L, window.counts[0])
        assertEquals(32767, window.peaks[0])
        assertEquals(2L, window.clipped[0])
        assertTrue(window.rms[0] > 1000)
    }

    @Test fun windowResetsAfterTake() {
        val meter = MicrophoneLevelMeter(1)
        meter.accept(pcm16(500))
        meter.takeWindow()
        val next = meter.takeWindow()
        assertEquals(0L, next.counts[0])
        assertEquals(0, next.peaks[0])
    }

    @Test fun aSampleSplitAcrossBuffersIsCounted() {
        val meter = MicrophoneLevelMeter(1)
        // 0x1234 little-endian: low byte 0x34 arrives first, then 0x12.
        meter.accept(byteArrayOf(0x34))
        assertEquals(0L, meter.takeWindow().counts[0])
        meter.accept(byteArrayOf(0x12, 0x00, 0x00))
        val window = meter.takeWindow()
        assertEquals(2L, window.counts[0])
        assertEquals(0x1234, window.peaks[0])
    }

    @Test fun silentWindowIsReportedAsIdle() {
        val meter = MicrophoneLevelMeter(1)
        val window = meter.takeWindow()
        assertTrue(window.diagnosticSuffix("input").contains("inputLevel=idle"))
    }

    @Test fun reportLineCarriesRmsPeakAndClipped() {
        val meter = MicrophoneLevelMeter(1)
        meter.accept(pcm16(1000, 32767))
        val suffix = meter.takeWindow().diagnosticSuffix("uplink")
        assertTrue(suffix, suffix.contains("uplinkPeak=32767"))
        assertTrue(suffix, suffix.contains("uplinkClipped=1"))
        assertTrue(suffix, suffix.contains("uplinkRms="))
    }
}
