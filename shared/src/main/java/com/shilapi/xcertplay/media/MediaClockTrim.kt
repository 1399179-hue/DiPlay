package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music.
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A head unit whose
 * DAC plays slightly fast drains the start buffer and pauses music to refill; one that plays slow
 * lets the buffer grow until latency is audible. After each write the renderer reports how many
 * frames are queued; a slow average of that level drives a PI controller that, once a second, trims
 * the track's playback rate so the level stays at the start threshold. The trim is bounded by
 * [MAX_TRIM] and settles at the head unit's own drift, which is a hardware constant, so the next
 * music stream starts from the trim this one learned.
 *
 * Ported from the DiPlay 0.2.20 build (#610, 0.76 % fast on a Humax BHCN-T3103C), with the #814
 * backlog corrections: the level includes the compressed packets still waiting for the decoder, and
 * decoder congestion never teaches a permanent clock correction.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var level: Double? = null
    private var lastSampleNs = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /**
     * Records the queued level while music plays.
     *
     * [compressedFrames] is the audio still sitting in the packet queue. Without it a slow decoder
     * looks like "too little audio queued" and the controller slows playback, which grows the
     * packet queue further — the 0.2.20 backlog spiral (#806) that made music pile up 4 s deep and
     * slowed the rate to its bound on every head unit.
     */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        compressedBacklog = compressedFrames > targetFrames
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * MAX_BACKLOG_SECONDS)
        val error = ((buffered - targetFrames).toDouble() / targetFrames)
            .coerceIn(-MAX_CONTROL_ERROR, MAX_CONTROL_ERROR)
        val previous = level
        // After a pause, a rebuffer or the first write the average restarts from what is queued now.
        level = if (previous == null || nowNs - lastSampleNs > STALE_NS) error
        else previous + (error - previous) * (1.0 - exp(-(nowNs - lastSampleNs).toDouble() / AVERAGE_NS))
        lastSampleNs = nowNs
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        if (!compressedBacklog) {
            // Anti-windup: once the bound is reached the integral stops charging, so clearing a
            // transient backlog does not leave minutes of pitch error to unwind.
            val next = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            if (next != integral) {
                integral = next
                learnedTrim = integral
            }
        }
        val next = (sampleRate * (1.0 + (KP * error + integral).coerceIn(-MAX_TRIM, MAX_TRIM))).roundToInt()
        if (next == rate) return null
        rate = next
        return next
    }

    internal companion object {
        /**
         * At most 3 % slower or faster. 1.5 % covered the 0.76 % clock seen on #610, but the
         * Lynk 03 head unit's DAC runs ~0.7 % fast against the iPhone *and* its AudioTrack write
         * path throttles to the delivered rate, so the trim saturated at its 1.5 % bound and the
         * leftover overflow was absorbed by dropping a packet a second — an audible tick. 3 %
         * still stays well inside a musical semitone (5.9 %).
         */
        const val MAX_TRIM = 0.03
        const val KP = 0.03
        /**
         * Farthest the queue level may push the controller. 0.25 keeps the P term within
         * ±0.75 % — more than the ~0.7 % clock seen on the Lynk 03 — so real drift still
         * converges while supply gaps ride the buffer headroom instead of saturating the trim.
         */
        const val MAX_CONTROL_ERROR = 0.25
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        const val STALE_NS = 250_000_000L
        const val MAX_STEP_SECONDS = 2.0
        /** A packet backlog longer than this is nonsense from a timestamp reset, not audio. */
        const val MAX_BACKLOG_SECONDS = 16L

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
