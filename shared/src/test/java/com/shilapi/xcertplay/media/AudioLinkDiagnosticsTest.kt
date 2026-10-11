package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks down the audio-link verdict table. The point of the table is that a field report ("the
 * music is stuttering") turns into a specific answer, so the mapping from counters to verdict must
 * not drift silently: each case here corresponds to one failure shape we actually need to tell
 * apart.
 */
class AudioLinkDiagnosticsTest {
    @Test
    fun silentStreamBeforeAnyPacketNamesTheWaitingStage() {
        assertEquals(
            "stream-established-but-silent(no packets yet)",
            verdictAt(receivedPackets = 0, trackCreated = false),
        )
    }

    @Test
    fun packetsArrivingWithoutAnAudioTrackIsDistinctFromSilence() {
        assertEquals(
            "packets-arrived-but-no-audiotrack",
            verdictAt(receivedPackets = 500, trackCreated = false),
        )
    }

    @Test
    fun decodingFailureIsNamedBeforePlaybackFailure() {
        assertEquals(
            "decoding-no-pcm-yet",
            verdictAt(receivedPackets = 500, trackCreated = true, pcmBuffers = 0),
        )
        assertEquals(
            "pcm-produced-but-playback-not-started",
            verdictAt(
                receivedPackets = 500,
                trackCreated = true,
                pcmBuffers = 12,
                playbackStarted = false,
            ),
        )
    }

    @Test
    fun aZeroLengthWriteIsNamedAsBackpressureNotAsAStall() {
        // A non-blocking write returning zero means the track had no room this round; the backlog
        // stays pending and the next round retries it. The same 2 s stream reported
        // zeroWrites=0 and zeroWrites=22 on two consecutive runs of the same build, so calling
        // this a stall - and ranking it above every other stage - sent a field investigation after
        // the AudioTrack when the handset was the one that had gone quiet.
        assertEquals(
            "audiotrack-backpressure(zeroWrites=3)",
            verdict(healthy(), zeroWrites = 3),
        )
    }

    @Test
    fun aHandsetThatStoppedFeedingOutranksEveryLocalStage() {
        // The shape of the field failure: two seconds of audio arrived, everything downstream
        // worked, and then the handset simply stopped writing to the data port while it kept
        // polling /feedback. `receivedPackets` is cumulative, so without the idle interval every
        // counter still reads "healthy" and there is nothing left to point at.
        assertEquals(
            "handset-stopped-feeding(idleMs=2000)",
            verdict(healthy(), handsetIdleMs = AudioLinkDiagnostics.HANDSET_IDLE_MS),
        )
        assertEquals(
            "handset-stopped-feeding(idleMs=25300)",
            verdict(healthy(), handsetIdleMs = 25_300),
        )
    }

    @Test
    fun aHandsetStillFeedingIsNotBlamed() {
        // Below the idle window the stream is simply in flight, and the local counters are the
        // only thing worth reporting.
        assertEquals("healthy", verdict(healthy(), handsetIdleMs = 120))
        assertEquals(
            "healthy",
            verdict(healthy(), handsetIdleMs = AudioLinkDiagnostics.HANDSET_IDLE_MS - 1),
        )
        // -1 means "no packet ever arrived", which is the no-packets branch's business, not this
        // one; the caller turns it into null so the ladder falls through as before.
        assertEquals("healthy", verdict(healthy(), handsetIdleMs = null))
    }

    @Test
    fun aStoppedHandsetIsNamedBeforeTheTrackIsBlamedForIt() {
        // Both signals present is the normal shape of the field failure - the abandoned backlog
        // fills the track, so zeroWrites rises as a *consequence*. The cause must win.
        assertEquals(
            "handset-stopped-feeding(idleMs=8718)",
            verdict(healthy(zeroWrites = 22, stalledHeadWindows = 41), handsetIdleMs = 8_718),
        )
    }

    @Test
    fun negativeWritesAreReportedSeparatelyFromZeroWrites() {
        assertEquals(
            "audiotrack-write-errors(errors=2)",
            verdict(healthy(), writeErrors = 2),
        )
    }

    @Test
    fun aFrozenPlaybackHeadIsAnUnderrunNotSilence() {
        // Sound is queued and the track exists, but the device stopped draining the buffer. This is
        // the case that looked identical to "no audio" before this table existed.
        assertEquals(
            "underrun(4 windows where the playback head stopped) [media]",
            verdict(healthy(stalledHeadWindows = 4)),
        )
    }

    @Test
    fun mediaAndNonMediaUnderrunsAreDistinguishable() {
        assertEquals(
            "underrun(1 windows where the playback head stopped) [media]",
            verdict(healthy(stalledHeadWindows = 1), isMedia = true),
        )
        assertEquals(
            "underrun(1 windows where the playback head stopped) [non-media]",
            verdict(healthy(stalledHeadWindows = 1), isMedia = false),
        )
    }

    @Test
    fun aPausedHandsetIsNotReportedAsABrokenLink() {
        assertEquals(
            "handset-not-playing(status=PAUSED)",
            verdict(healthy(), handsetStatus = "PAUSED"),
        )
    }

    @Test
    fun everythingWorkingReportsHealthy() {
        assertEquals("healthy", verdict(healthy(), handsetStatus = "PLAYING"))
        // With no NowPlayingUpdate seen at all the link is still healthy: absence of iAP2 state is
        // not evidence of a fault, so it must not be reported as one.
        assertEquals("healthy", verdict(healthy(), handsetStatus = null))
    }

    @Test
    fun missingStreamIsDistinguishedFromAPlayingHandset() {
        assertEquals("no-nowplaying-updates-received", AudioLinkDiagnostics.noStreamVerdict(null))
        assertEquals(
            "handset-not-playing(status=STOPPED)",
            AudioLinkDiagnostics.noStreamVerdict("STOPPED"),
        )
        assertEquals(
            "handset-playing-but-no-audio-stream",
            AudioLinkDiagnostics.noStreamVerdict("PLAYING"),
        )
    }

    /** The counters for a stream where every stage worked: packets in, track made, PCM out, playing. */
    private fun healthy(
        zeroWrites: Long = 0,
        writeErrors: Long = 0,
        stalledHeadWindows: Long = 0,
        handsetStatus: String? = "PLAYING",
        isMedia: Boolean = true,
    ) = Healthy(
        zeroWrites = zeroWrites,
        writeErrors = writeErrors,
        stalledHeadWindows = stalledHeadWindows,
        handsetStatus = handsetStatus,
        isMedia = isMedia,
    )

    private data class Healthy(
        val zeroWrites: Long,
        val writeErrors: Long,
        val stalledHeadWindows: Long,
        val handsetStatus: String?,
        val isMedia: Boolean,
    )

    /** Applies an override to an otherwise healthy stream and classifies the result. */
    private fun verdict(
        healthy: Healthy,
        zeroWrites: Long = healthy.zeroWrites,
        writeErrors: Long = healthy.writeErrors,
        stalledHeadWindows: Long = healthy.stalledHeadWindows,
        handsetStatus: String? = healthy.handsetStatus,
        isMedia: Boolean = healthy.isMedia,
        handsetIdleMs: Long? = null,
    ): String = AudioLinkDiagnostics.verdict(
        receivedPackets = 1_000,
        trackCreated = true,
        pcmBuffers = 100,
        playbackStarted = true,
        zeroWrites = zeroWrites,
        writeErrors = writeErrors,
        stalledHeadWindows = stalledHeadWindows,
        handsetStatus = handsetStatus,
        isMedia = isMedia,
        handsetIdleMs = handsetIdleMs,
    )

    /** Classifies a stream at an explicitly named stage, for the cases that are not "healthy". */
    private fun verdictAt(
        receivedPackets: Long,
        trackCreated: Boolean,
        pcmBuffers: Long = 100,
        playbackStarted: Boolean = true,
        handsetStatus: String? = "PLAYING",
    ): String = AudioLinkDiagnostics.verdict(
        receivedPackets = receivedPackets,
        trackCreated = trackCreated,
        pcmBuffers = pcmBuffers,
        playbackStarted = playbackStarted,
        zeroWrites = 0,
        writeErrors = 0,
        stalledHeadWindows = 0,
        handsetStatus = handsetStatus,
        isMedia = true,
    )
}