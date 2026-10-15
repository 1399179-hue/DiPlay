package com.shilapi.xcertplay.media

/**
 * Per-channel level meter for the microphone uplink.
 *
 * Counts the samples, RMS and peak of each channel and how many hit full scale, so a report can
 * tell a microphone that hears nothing (all zero) from one that is overdriven (clipping). Samples
 * are 16-bit little-endian and may split across buffers: a trailing odd byte is carried into the
 * next [accept]. Ported from the 领克03 (Lynk 03) build's level meter.
 */
internal class MicrophoneLevelMeter(private val channels: Int) {
    init {
        require(channels in 1..8) { "Unsupported microphone channel count" }
    }

    private val counts = LongArray(channels)
    private val squares = DoubleArray(channels)
    private val peaks = IntArray(channels)
    private val clipped = LongArray(channels)
    private var channel = 0
    private var lowByte: Int? = null

    fun accept(input: ByteArray, offset: Int = 0, length: Int = input.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= input.size - length) { "Invalid PCM input range" }
        var index = offset
        val end = offset + length
        // Finish the sample whose low byte arrived in the previous buffer.
        lowByte?.let { low ->
            if (index < end) {
                count(((input[index].toInt() shl 8) or low).toShort().toInt())
                lowByte = null
                index++
            }
        }
        while (index < end - 1) {
            count(((input[index].toInt() and 0xff) or (input[index + 1].toInt() shl 8)).toShort().toInt())
            index += 2
        }
        if (index < end) lowByte = input[index].toInt() and 0xff
    }

    /** Copies the window's statistics and resets the accumulators. */
    fun takeWindow(): Window {
        val rms = IntArray(channels) { index ->
            val count = counts[index]
            if (count == 0L) 0 else kotlin.math.sqrt(squares[index] / count).toInt()
        }
        val window = Window(counts.copyOf(), rms, peaks.copyOf(), clipped.copyOf())
        counts.fill(0L)
        squares.fill(0.0)
        peaks.fill(0)
        clipped.fill(0L)
        return window
    }

    private fun count(sample: Int) {
        counts[channel]++
        squares[channel] += sample.toDouble() * sample
        peaks[channel] = maxOf(peaks[channel], kotlin.math.abs(sample))
        if (sample == -32768 || sample == 32767) clipped[channel]++
        channel = (channel + 1) % channels
    }

    /** One measurement window: per-channel sample count, RMS, peak and clipped-sample count. */
    data class Window(
        val counts: LongArray,
        val rms: IntArray,
        val peaks: IntArray,
        val clipped: LongArray,
    ) {
        /** A compact per-window line for the diagnostic report, tagged with [label]. */
        fun diagnosticSuffix(label: String): String {
            if (counts.isEmpty() || counts.all { it == 0L }) return " ${label}Level=idle"
            val peak = peaks.maxOrNull() ?: 0
            val rms = if (rms.isEmpty()) 0 else rms.maxOrNull() ?: 0
            val clipped = clipped.sum()
            return " ${label}Rms=$rms ${label}Peak=$peak ${label}PeakDbfs=${peakDbfs(peak)}" +
                " ${label}Clipped=$clipped ${label}Samples=${counts.sum()}"
        }

        private fun peakDbfs(peak: Int): String =
            if (peak <= 0) "silent" else Math.round(20 * kotlin.math.log10(peak / 32768.0)).toString()
    }
}
