package com.shilapi.xcertplay.media

/**
 * User-selectable microphone gain applied to captured PCM before it is encoded and sent.
 *
 * Wireless CarPlay on some head units is either too quiet (the driver is heard faintly) or
 * overloaded (the handset's own AGC then clips). Neither is fixable from the settings the phone
 * exposes, so the gain is applied here, in the receiver, on the PCM16 frames the microphone
 * produces. The transform is pure integer maths with no Android dependency so the level test and
 * the uplink share exactly one implementation.
 */
object MicrophoneGain {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 10

    fun sanitize(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /**
     * Scales every 16-bit little-endian sample in `buffer[0, count)` in place and returns the peak
     * absolute sample value seen after scaling. An odd trailing byte is left untouched.
     */
    internal fun applyPcm16InPlace(buffer: ByteArray, count: Int, percent: Int): Int {
        require(count in 0..buffer.size)
        val gain = sanitize(percent)
        var peak = 0
        var offset = 0
        while (offset + 1 < count) {
            val sample = (
                (buffer[offset].toInt() and 0xff) or
                    (buffer[offset + 1].toInt() shl 8)
                ).toShort().toInt()
            val amplified =
                (sample * gain / 100).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[offset] = amplified.toByte()
            buffer[offset + 1] = (amplified ushr 8).toByte()
            peak = maxOf(peak, kotlin.math.abs(amplified))
            offset += 2
        }
        return peak
    }

    /** Peak absolute sample expressed as 0..100 % of full scale, for a level meter. */
    internal fun peakPercent(peak: Int): Int =
        (peak.coerceIn(0, 32_768) * 100 / 32_768).coerceIn(0, 100)
}
