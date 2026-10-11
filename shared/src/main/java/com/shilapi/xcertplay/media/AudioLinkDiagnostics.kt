package com.shilapi.xcertplay.media

import android.util.Log
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers the one question a "music is stuttering" report cannot answer from SETUP/TEARDOWN lines
 * alone: is the handset actually asking us to play audio, and how far did that audio get?
 *
 * A dropped audio stream looks identical from the outside whether the phone stopped sending,
 * the stream was torn down, or we are receiving packets we never turned into sound. This tracker
 * joins the two halves that only exist in different subsystems:
 *
 *  * the iAP2 NowPlaying state (what the handset says is playing), fed in by the controller via
 *    [setHandsetPlayback], and
 *  * the per-stream renderer counters (what we actually received, decoded and played), fed in by
 *    [AndroidMediaSink].
 *
 * [log] prints a single line combining both, so one log line tells you which of the four failure
 * shapes is in play: handset idle, stream never established, packets arriving but not rendering,
 * or healthy.
 *
 * Ported from the Lynk & Co build via xcertplay-nomfi. Its verdict table is pure so it can be
 * unit tested without Android.
 *
 * State writers are lock-free and never allocate on the media path; [log] is called from the
 * diagnostics ticker and may allocate freely.
 */
class AudioLinkDiagnostics(
    private val tag: String = TAG,
) {
    /** One audio stream's worth of receive/decode/play counters. */
    internal class StreamState {
        val packetsReceived = AtomicLong()
        val packetsDropped = AtomicLong()
        val inputQueued = AtomicLong()
        val inputDropped = AtomicLong()
        val outputBuffers = AtomicLong()
        val zeroWrites = AtomicLong()
        val writeErrors = AtomicLong()
        val trackCreated = AtomicBoolean()
        val playbackStarted = AtomicBoolean()
        @Volatile var lastArrivalNs: Long = 0L
        @Volatile var maxGapMs: Long = 0L
        @Volatile var trackBytesPerFrame: Int = 0
        @Volatile var writtenFrames: Long = 0L

        /**
         * Counted by the caller when the playback head stops advancing while PCM is queued: the
         * AudioTrack buffer holds sound the device never played, which is what a user hears as a
         * gap rather than as silence.
         */
        @Volatile var stalledHeadWindows: Long = 0L

        /** Last playback-head value seen by the writer, used to detect a non-advancing head. */
        @Volatile var lastObservedHeadFrames: Long = -1L

        /**
         * Decoded PCM the writer could not hand to the track because the device refused it.
         *
         * A figure that rises and then stays above zero is the signature of a wedged output route:
         * the handset has already sent the audio and we decoded it, but the track never drains. The
         * writer keeps the tail instead of blocking on it, so this is the backlog it is holding.
         */
        @Volatile var pendingBytes: Int = 0

        /**
         * Writes skipped because another source owned the output.
         *
         * Counted by the writer when the focus coordinator says the CarPlay track may not play.
         * Rising on its own is healthy - it means the arbiter moved the output away and we are
         * waiting instead of pushing PCM into a route we do not own.
         */
        val focusGatedWrites = AtomicLong()

        /**
         * Tracks torn down and rebuilt because their route stopped consuming PCM.
         *
         * The last-resort recovery: a track whose device never drains is released and re-created,
         * which is the only thing that can bring audio back once the route itself is broken.
         */
        val rebuiltTracks = AtomicLong()

        /**
         * Bytes discarded from the front of the backlog because it grew past its bound.
         *
         * Only a route that has stopped draining can grow the backlog this far, and only the oldest
         * bytes are dropped, so a non-zero figure here means the stream is already unrecoverable -
         * it is a bound on memory, not a tuning signal.
         */
        @Volatile var stalePcmDroppedBytes: Long = 0L
    }

    private val streams = ConcurrentHashMap<AudioStreamId, StreamState>()

    /**
     * What the handset last told us over iAP2. `null` means "no NowPlayingUpdate seen yet", which
     * is itself diagnostic: a session that never subscribes to 0x5001 cannot distinguish "the user
     * is not playing anything" from "we are not listening".
     */
    private data class HandsetPlayback(
        val status: String,
        val title: String? = null,
        val artist: String? = null,
        val appName: String? = null,
        val elapsedMillis: Long = 0L,
    )

    private val handset = AtomicReference<HandsetPlayback?>(null)
    private val handsetUpdateCount = AtomicLong()

    /** Human-readable verdict for the most recent [log], so a UI can surface it without parsing. */
    @Volatile
    var lastVerdict: String = "no-audio-stream"
        private set

    /**
     * The counter block for [id], created on first use. Exposed to [AndroidMediaSink] so the media
     * thread can bump counters without a lookup per packet.
     */
    internal fun counters(id: AudioStreamId): StreamState = streams.computeIfAbsent(id) { StreamState() }

    fun forgetAll() {
        streams.clear()
        handset.set(null)
        handsetUpdateCount.set(0L)
    }

    /** Called from [AndroidMediaSink] when a stream is torn down by the handset. */
    fun onStreamStopped(id: AudioStreamId) {
        val state = streams.remove(id) ?: return
        Log.i(
            tag,
            "audio link stream stopped ${
                format(
                    state,
                    ended = true,
                )
            }",
        )
    }

    /**
     * The handset's latest NowPlaying state, or null when nothing has arrived. [status] is the
     * wire-level playback status name (PLAYING/PAUSED/STOPPED/UNKNOWN).
     */
    fun setHandsetPlayback(
        status: String,
        title: String? = null,
        artist: String? = null,
        appName: String? = null,
        elapsedMillis: Long = 0L,
    ) {
        handset.set(HandsetPlayback(status, title, artist, appName, elapsedMillis))
        handsetUpdateCount.incrementAndGet()
    }

    fun setHandsetIdle() {
        handset.set(HandsetPlayback(STATUS_IDLE))
    }

    /**
     * Prints the combined handset + renderer view. Safe to call on a timer; the line is bounded so
     * an exported diagnostic report cannot truncate it.
     */
    fun log(reason: String) {
        val playback = handset.get()
        if (streams.isEmpty()) {
            lastVerdict = verdictForNoStream(playback)
            Log.i(
                tag,
                "audio link no-stream reason=$reason handset=${handsetSummary(playback)} " +
                    "handsetUpdates=$handsetUpdateCount verdict=$lastVerdict",
            )
            return
        }
        // The media ("music") stream is the one users mean by "the music"; navigation prompts ride
        // the same renderer with a different audioType, so keep them separable.
        val media = streams.entries.filter { it.key.audioType == AUDIO_TYPE_MEDIA }
        val target = media.maxByOrNull { it.value.packetsReceived.get() } ?: streams.entries.first()
        val (id, state) = target
        val isMedia = id.audioType == AUDIO_TYPE_MEDIA
        lastVerdict = verdictFor(state, playback, isMedia)
        Log.i(
            tag,
            "audio link reason=$reason ${format(state, ended = false)} " +
                "handset=${handsetSummary(playback)} verdict=$lastVerdict",
        )
    }

    private fun format(state: StreamState, ended: Boolean): String {
        val sinceArrivalMs = sinceArrivalMs(state)
        return "rx=${state.packetsReceived.get()} rxTotalDropped=${state.packetsDropped.get()} " +
            "decoderIn=${state.inputQueued.get()} decoderDropped=${state.inputDropped.get()} " +
            "pcmOut=${state.outputBuffers.get()} writtenFrames=${state.writtenFrames} " +
            "track=${state.trackCreated.get()} playing=${state.playbackStarted.get()} " +
            "headStalls=${state.stalledHeadWindows} " +
            "zeroWrites=${state.zeroWrites.get()} " +
            "writeErrors=${state.writeErrors.get()} " +
            "pendingPcm=${state.pendingBytes} focusGated=${state.focusGatedWrites.get()} " +
            "trackRebuilds=${state.rebuiltTracks.get()} stalePcmDropped=${state.stalePcmDroppedBytes} " +
            "maxGapMs=${state.maxGapMs} " +
            "sinceRxMs=$sinceArrivalMs ended=$ended"
    }

    /**
     * Milliseconds since the last packet on this stream, or -1 when none ever arrived.
     *
     * Negative must stay negative rather than becoming "infinitely long ago": a stream that was
     * never fed is a different fault from one that was fed and then abandoned, and the verdict
     * table distinguishes the two.
     */
    private fun sinceArrivalMs(state: StreamState): Long {
        val lastArrival = state.lastArrivalNs
        return if (lastArrival == 0L) -1L else (System.nanoTime() - lastArrival) / 1_000_000L
    }

    private fun verdictFor(
        state: StreamState,
        playback: HandsetPlayback?,
        isMedia: Boolean,
    ): String = verdict(
        receivedPackets = state.packetsReceived.get(),
        trackCreated = state.trackCreated.get(),
        pcmBuffers = state.outputBuffers.get(),
        playbackStarted = state.playbackStarted.get(),
        zeroWrites = state.zeroWrites.get(),
        writeErrors = state.writeErrors.get(),
        stalledHeadWindows = state.stalledHeadWindows,
        handsetStatus = playback?.status,
        isMedia = isMedia,
        handsetIdleMs = sinceArrivalMs(state).takeIf { it >= 0L },
    )

    private fun verdictForNoStream(playback: HandsetPlayback?): String = noStreamVerdict(
        handsetStatus = playback?.status,
    )

    private fun handsetSummary(playback: HandsetPlayback?): String {
        if (playback == null) return "unknown"
        val title = playback.title?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "-"
        val artist = playback.artist?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "-"
        val app = playback.appName?.takeIf { it.isNotBlank() } ?: "-"
        return "status=${playback.status} title=$title artist=$artist app=$app " +
            "elapsedMs=${playback.elapsedMillis}"
    }

    internal companion object {
        const val TAG = "xcertplay-usb"
        const val AUDIO_TYPE_MEDIA = "media"
        const val STATUS_PLAYING = "PLAYING"
        const val STATUS_IDLE = "IDLE"

        /**
         * How long a stream must be quiet before the handset - not the local pipeline - is named as
         * the reason the audio stopped.
         *
         * Matches the idle window [com.shilapi.xcertplay.airplay.AudioStream] uses to declare a data
         * port abandoned, so the two never disagree about whether a stream is still being fed.
         */
        const val HANDSET_IDLE_MS = 2_000L

        /**
         * Classifies one audio stream's health. Order matters: it walks the pipeline from the
         * handset outwards (did packets arrive, did we make an AudioTrack, did we decode, did we
         * play) so the first failing stage is the one reported, rather than a downstream symptom.
         *
         * Pure and side-effect free so the verdict table can be unit tested without Android.
         */
        internal fun verdict(
            receivedPackets: Long,
            trackCreated: Boolean,
            pcmBuffers: Long,
            playbackStarted: Boolean,
            zeroWrites: Long,
            writeErrors: Long,
            stalledHeadWindows: Long,
            handsetStatus: String?,
            isMedia: Boolean,
            handsetIdleMs: Long? = null,
        ): String = when {
            receivedPackets == 0L && !trackCreated ->
                "stream-established-but-silent(no packets yet)"
            receivedPackets == 0L -> "no-packets-received"
            !trackCreated -> "packets-arrived-but-no-audiotrack"
            pcmBuffers == 0L -> "decoding-no-pcm-yet"
            !playbackStarted -> "pcm-produced-but-playback-not-started"
            // Checked before every local stage, because a handset that stopped feeding the data
            // port makes all of them look broken and none of them is the cause. `receivedPackets`
            // is cumulative, so a stream that was fed for two seconds and then abandoned still
            // reads as "packets arrived, track made, PCM out, playing" - the counters cannot tell
            // it apart from a healthy stream, and the local verdict used to blame the AudioTrack.
            // Field: 2026-10-09 wireless sessions stop after ~2.0 s of audio every time
            // (`writtenFrames` 94208 / 99328 / 90112), the handset keeps sending `/feedback` for
            // another 25 s, and the interval since the last packet is the only signal that says so.
            handsetIdleMs != null && handsetIdleMs >= HANDSET_IDLE_MS ->
                "handset-stopped-feeding(idleMs=$handsetIdleMs)"
            // A non-blocking write that returns zero is ordinary back-pressure, not a stall: the
            // remainder stays pending and the next round retries it. Measured on the same build,
            // the same 2 s stream reports zeroWrites=0 in one session and 22 in the next purely on
            // arrival burstiness, so this must not outrank a real cause.
            zeroWrites > 0L -> "audiotrack-backpressure(zeroWrites=$zeroWrites)"
            writeErrors > 0L -> "audiotrack-write-errors(errors=$writeErrors)"
            stalledHeadWindows > 0L ->
                "underrun($stalledHeadWindows windows where the playback head stopped)" +
                    if (isMedia) " [media]" else " [non-media]"
            handsetStatus != null && handsetStatus != STATUS_PLAYING ->
                "handset-not-playing(status=$handsetStatus)"
            else -> "healthy"
        }

        /** The verdict when no audio stream exists at all, which is itself a diagnosable state. */
        internal fun noStreamVerdict(handsetStatus: String?): String = when {
            handsetStatus == null -> "no-nowplaying-updates-received"
            handsetStatus != STATUS_PLAYING -> "handset-not-playing(status=$handsetStatus)"
            else -> "handset-playing-but-no-audio-stream"
        }
    }
}