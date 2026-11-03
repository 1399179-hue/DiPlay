package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A head unit whose
 * DAC plays slightly fast drains the start buffer and pauses music to refill; one that plays slow
 * lets the buffer grow until latency is audible. After each write the renderer reports how many
 * frames are queued; a slow average of that level drives a PI controller that, once a second, trims
 * the track's playback rate so the level stays at its target. The trim is bounded by [MAX_TRIM] and
 * settles at the head unit's own drift, which is a hardware constant, so the next music stream
 * starts from the trim this one learned.
 *
 * The level music holds once it plays is not the start threshold. Buffered music (#1031) is fed a
 * second ahead of real time, and realtime music over Wi-Fi comes in bursts, so playback starts
 * partway through one and the rest lands on top (#1068). With [measuresTarget] the controller first
 * watches how deep the queue actually settles and adopts that as its target.
 *
 * Ported from the DiPlay 0.2.20 build (#610), with the #814 backlog corrections (the level includes
 * the compressed packets still waiting for the decoder, and decoder congestion never teaches a
 * permanent clock correction) and the #807/#1074 burst corrections: the level is the mean over the
 * time since the last write, not the peak right after it, and audio that arrived while the track was
 * full counts from its own arrival.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private var targetFrames: Long,
    private var integral: Double = learnedTrim,
    private val measuresTarget: Boolean = false,
) {
    private class Measuring(val halfStartNs: Long? = null, val earlierHalfMean: Double? = null) {
        var levelFrameNs = 0.0
    }

    private var level: Double? = null

    /** When the level was last sampled. */
    var lastSampleNs = 0L
        private set

    /**
     * Frames queued at the last sample. A later sample starts from this and subtracts what the
     * track drained in between, because writes arrive in bursts and the raw reading after one is
     * its peak, not the level that was actually held in between.
     */
    private var lastBuffered = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var measuring: Measuring? = if (measuresTarget) Measuring() else null

    /** Restarts target measurement; called when a packet was dropped or playback restarts. */
    fun measureAgain() {
        if (measuresTarget) measuring = Measuring()
    }

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** The frames the controller holds the queue at; [measuresTarget] can change it after playback starts. */
    internal val targetFramesForTest: Long
        get() = targetFrames

    /**
     * Records the queued level while music plays.
     *
     * [compressedFrames] is the audio still sitting in the packet queue. Without it a slow decoder
     * looks like "too little audio queued" and the controller slows playback, which grows the
     * packet queue further — the 0.2.20 backlog spiral (#806) that made music pile up 4 s deep and
     * slowed the rate to its bound on every head unit.
     *
     * [headMoving] is false while the track's head has not advanced since playback began, so the
     * drain estimate is meaningless. [arrivedFrameNs] sums, over each frame that arrived since the
     * last sample, how long it has been queued.
     */
    fun sample(
        queuedFrames: Long,
        nowNs: Long,
        compressedFrames: Long = 0L,
        headMoving: Boolean = true,
        arrivedFrameNs: Double = 0.0,
    ) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        compressedBacklog = compressedFrames > targetFrames
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * MAX_BACKLOG_SECONDS)
        val elapsedNs = nowNs - lastSampleNs
        // Samples come only after writes, and over Wi-Fi the writes come in bursts 0.4 s apart: the
        // level just after one is its peak. Average what was queued over the time since the last
        // write, while the track drained it, or a 300 ms target reads as overfull and the trim
        // plays fast (#807).
        // A write that finds the track full blocks while more audio arrives; that audio counts from
        // its arrival, or a full track reads up to 300 ms low and the trim wanders for minutes
        // (#1074).
        val meanSinceLastWrite = drainedMean(lastBuffered.toDouble(), rate * elapsedNs / 1e9) +
            if (elapsedNs > 0) arrivedFrameNs / elapsedNs else 0.0
        val measure = measuring
        if (measure != null) {
            if (!headMoving) return
            addToMeasurement(measure, nowNs, elapsedNs, meanSinceLastWrite)
        } else {
            val previous = level
            level = if (previous == null || elapsedNs > STALE_NS) error(buffered.toDouble())
            else previous + (error(meanSinceLastWrite) - previous) * (1.0 - exp(-elapsedNs.toDouble() / AVERAGE_NS))
        }
        lastBuffered = buffered
        lastSampleNs = nowNs
    }

    /**
     * Adopts the level the queue actually holds once two consecutive halves agree. Disagreement is
     * a navigation prompt or a delivered-ahead stretch, not the level music settles at.
     */
    private fun addToMeasurement(
        measure: Measuring,
        nowNs: Long,
        elapsedNs: Long,
        meanSinceLastWrite: Double,
    ) {
        val halfStartNs = measure.halfStartNs
        if (halfStartNs == null) {
            measuring = Measuring(nowNs)
            return
        }
        measure.levelFrameNs += meanSinceLastWrite * elapsedNs
        if (nowNs - halfStartNs < MEASURE_HALF_NS) return
        val halfMean = measure.levelFrameNs / (nowNs - halfStartNs)
        val earlierHalfMean = measure.earlierHalfMean
        if (earlierHalfMean == null || abs(halfMean - earlierHalfMean) > sampleRate * HALVES_AGREE_SECONDS) {
            measuring = Measuring(nowNs, halfMean)
            return
        }
        targetFrames = earlierHalfMean.roundToLong()
        level = 0.0
        measuring = null
    }

    /**
     * The level error the controller works on.
     *
     * Farthest the queue level may push the controller: [MAX_CONTROL_ERROR] keeps the P term within
     * ±0.75 % — more than the ~0.7 % clock seen on the Lynk 03 — so real drift still converges while
     * supply gaps ride the buffer headroom instead of saturating the trim.
     */
    private fun error(frames: Double): Double =
        ((frames - targetFrames) / targetFrames).coerceIn(-MAX_CONTROL_ERROR, MAX_CONTROL_ERROR)

    /** The mean of a level that starts at [start] and drains [drained] frames at a steady pace, stopping at 0. */
    private fun drainedMean(start: Double, drained: Double): Double = when {
        drained <= 0.0 -> start
        drained <= start -> start - drained / 2
        else -> start * start / (2 * drained)
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        if (measuring != null) {
            // Measuring the target: hold the learned drift so the level observed is not also being
            // moved by a changing rate.
            val learned = (sampleRate * (1.0 + integral)).roundToInt()
            if (learned == rate) return null
            rate = learned
            return learned
        }
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
        /** Farthest the queue level may push the controller; see [error]. */
        const val MAX_CONTROL_ERROR = 0.25
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0
        const val MEASURE_HALF_NS = 1_500_000_000L
        const val HALVES_AGREE_SECONDS = 0.2
        /** A packet backlog longer than this is nonsense from a timestamp reset, not audio. */
        const val MAX_BACKLOG_SECONDS = 16L

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
