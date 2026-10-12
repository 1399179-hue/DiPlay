package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep a small-but-not-minimal buffer: the
 * phone sends those streams in bursts too (up to ~100 ms between packets observed), and on head
 * units whose minBuffer grants only ~300 ms a burst that lands while the prompt is playing drains
 * the track and the prompt stutters, so the non-media floor is one burst above the minBuffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    val presets = listOf(DEFAULT_MILLIS, 500, 1000)

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    /**
     * Track capacity floor for the non-media streams. The low-latency plan alone held ~300 ms on
     * the Lynk 03 head unit and navigation prompts underran as soon as a burst followed a gap;
     * 500 ms rides out the observed bursts without making prompts feel delayed.
     */
    private const val NON_MEDIA_FLOOR_MILLIS = 500

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /**
     * AudioTrack capacity and the amount to queue before play() for one output stream.
     *
     * [negotiatedLatencyMs] is the SETUP `audioLatencyMs` the handset announced. It is folded in as
     * a floor because the phone sends that much audio before waiting for our playout position: a
     * track sized below the announcement cannot hold it, and the overflow is heard as a dropout.
     * The wired real-time stream announces tens of milliseconds, the buffered wireless stream about
     * a second, so this matters most on wireless.
     */
    fun plan(
        isMedia: Boolean,
        sampleRate: Int,
        channels: Int,
        minBufferBytes: Int,
        mediaMillis: Int,
        negotiatedLatencyMs: Int = 0,
    ): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) {
            // Prompts ride out one network burst: lift the capacity floor, keep the start low.
            val floorBytes = (sampleRate.toLong() * channels.coerceIn(1, 2) * 2 * NON_MEDIA_FLOOR_MILLIS / 1000).toInt()
            return Plan(
                trackBufferBytes = maxOf(lowLatency.trackBufferBytes, floorBytes),
                startBytes = lowLatency.startBytes,
            )
        }
        val durationMillis = maxOf(sanitize(mediaMillis), negotiatedLatencyMs.coerceAtLeast(0))
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * durationMillis / 1000).toInt()
        val capacity = (bytesPerSecond * (durationMillis + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}
