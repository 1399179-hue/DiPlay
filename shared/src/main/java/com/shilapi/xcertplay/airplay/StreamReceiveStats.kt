package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    /**
     * RTP timestamp rate, so a sequence gap can be reported as the milliseconds of audio it lost.
     * Zero suppresses the millisecond fields for callers that do not know the stream's rate.
     */
    private val rtpClockRate: Int = 0,
    // Kept last: callers pass the injected clock as a trailing lambda.
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var maxSequenceGapMs = 0L
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0
    // Totals for the whole stream. flush() resets the window counters and must never reset these:
    // a five-second window answers "what happened just now", and only the total answers "how much
    // audio did this drive lose", which is what a single report has to say.
    private var sessionLostPackets = 0
    private var sessionGapEvents = 0
    private var sessionMaxSequenceGap = 0
    private var sessionLostMs = 0L
    private var sessionLateOrDuplicate = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    val gapMs = gapMillis(previousTimestamp = lastTimestamp, timestamp = timestamp, missing = delta)
                    maxSequenceGapMs = maxOf(maxSequenceGapMs, gapMs)
                    sessionGapEvents++
                    sessionLostPackets += delta
                    sessionMaxSequenceGap = maxOf(sessionMaxSequenceGap, delta)
                    sessionLostMs += gapMs
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"} missingMs=$gapMs"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else {
                lateOrDuplicate++
                sessionLateOrDuplicate++
            }
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    /** One payload opened in [durationNs]; reported only for streams that call it. */
    fun decrypted(durationNs: Long, size: Int) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap$gapMillisField " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
            "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms" +
            (if (ended) sessionSummary() else "") + decryptSummary()) }
        windowStart = now
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        maxSequenceGapMs = 0L
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    /**
     * Audio the gap lost, taken from the RTP timestamp jump rather than the packet count, so a
     * reader does not have to know the codec's samples per packet to tell 6 lost packets at 44.1 kHz
     * (139 ms, audible) from 6 at 8 kHz (768 ms, a hole in the song).
     *
     * The jump spans [missing] lost packets plus the one that did arrive, so dividing it by that
     * count recovers the frame count of a single packet. A jump that does not divide evenly is not
     * a contiguous stretch; fall back to the whole jump so the field still reports something.
     */
    private fun gapMillis(previousTimestamp: Int?, timestamp: Int?, missing: Int): Long {
        if (previousTimestamp == null || timestamp == null || rtpClockRate <= 0) return 0L
        // Unsigned: the RTP timestamp wraps every ~27 hours at 48 kHz.
        val jump = (timestamp.toLong() - previousTimestamp.toLong()) and 0xffff_ffffL
        if (jump == 0L || jump > rtpClockRate.toLong() * MAX_GAP_SECONDS) return 0L
        val framesPerPacket = jump / (missing + 1L)
        val lostFrames = if (framesPerPacket in 1..rtpClockRate.toLong()) {
            missing * framesPerPacket
        } else jump
        return lostFrames * 1000L / rtpClockRate
    }

    /**
     * Omitted entirely without a clock rate, so a stream that cannot measure the lost duration
     * reports nothing rather than a zero that reads as "no audio was lost".
     */
    private val gapMillisField: String
        get() = if (rtpClockRate > 0) " seqGapMsMax=$maxSequenceGapMs" else ""

    /** Whole-stream loss, reported once when the stream ends: [flush] never resets these. */
    private fun sessionSummary(): String =
        " sessionLostPackets=$sessionLostPackets sessionGapEvents=$sessionGapEvents" +
            " sessionGapMax=$sessionMaxSequenceGap sessionLostMs=$sessionLostMs" +
            " sessionLateOrDuplicate=$sessionLateOrDuplicate"

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond"
    }

    private companion object {
        const val LONG_READ_NS = 250_000_000L
        /** A timestamp jump beyond this many seconds is a restarted stream, not a lost stretch. */
        const val MAX_GAP_SECONDS = 10L
    }

}
