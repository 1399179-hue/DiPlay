package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lifts a quiet microphone so the iPhone hears the driver.
 *
 * Some head units feed the app a microphone preamp that is far quieter than a phone's, and the
 * driver has to shout. This applies a smooth automatic gain to the captured PCM: when the window's
 * RMS is below [QUIET_RMS] the target gain rises toward [MAX_GAIN], bounded so the loudest sample
 * cannot clip; it falls back to unity within [RECOVERY_MILLIS] once the voice is loud again. The
 * gain steps every [gainStep] so the change is inaudible. Ported from the 领克03 (Lynk 03) build.
 */
internal class MicrophoneInputBoost(private val sampleRate: Int, private val channels: Int) {
    init {
        require(sampleRate > 0) { "Invalid microphone sample rate" }
        require(channels in 1..2) { "Unsupported microphone channel count" }
    }

    /** Per-sample gain increment that reaches full range over [RECOVERY_MILLIS]. */
    private val gainStep = 1.0 / (sampleRate * RECOVERY_MILLIS / 1000.0)
    private var gain = 1.0

    var enabled = false
        private set

    /** Applies the boost in place when [enabled]; resets to unity when off or on an empty frame. */
    fun apply(pcm: ByteArray, enabled: Boolean) {
        require(pcm.size % (channels * 2) == 0) { "Incomplete microphone PCM frame" }
        this.enabled = enabled
        if (!enabled || pcm.isEmpty()) {
            gain = 1.0
            return
        }
        var sumSquares = 0.0
        var peak = 0
        var index = 0
        while (index < pcm.size) {
            val sample = sampleAt(pcm, index)
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toDouble() * sample
            index += 2
        }
        val rms = sqrt(sumSquares / (pcm.size / 2))
        // Unity at or below QUIET_RMS, rising linearly to MAX_GAIN at BOOST_RMS and above, but
        // never so much that the loudest sample in this frame would clip.
        val byLevel = ((rms - QUIET_RMS) / (BOOST_RMS - QUIET_RMS)).coerceIn(0.0, 1.0) + 1.0
        val byHeadroom = if (peak == 0) 1.0 else (32767.0 / peak).coerceIn(1.0, MAX_GAIN)
        val wanted = min(byLevel, byHeadroom)
        // Never raise the gain while the signal is already loud enough.
        gain = min(gain, wanted)
        if (wanted <= 1.0) return

        index = 0
        while (index < pcm.size) {
            gain = min(wanted, gain + gainStep)
            repeat(channels) {
                val boosted = (sampleAt(pcm, index) * gain).toInt().coerceIn(-32768, 32767)
                pcm[index] = boosted.toByte()
                pcm[index + 1] = (boosted shr 8).toByte()
                index += 2
            }
        }
    }

    fun diagnosticSuffix(): String = " inputBoostEnabled=$enabled inputBoostGainPermille=${(gain * 1000).toInt()}"

    private fun sampleAt(pcm: ByteArray, offset: Int): Int =
        ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()

    companion object {
        const val MAX_GAIN = 2.0
        const val QUIET_RMS = 64.0
        const val BOOST_RMS = 256.0
        const val RECOVERY_MILLIS = 200.0
    }
}
