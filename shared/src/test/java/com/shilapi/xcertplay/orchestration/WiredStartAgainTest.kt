package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain
import org.junit.Assert.assertEquals
import org.junit.Test

class WiredStartAgainTest {
    private val sent = 1_000_000_000L
    private val quiet = WiredStartAgain.QUIET_MILLIS * 1_000_000L

    // The link opened 1 ms after the request, so the window is counted from `opened`, not `sent`.
    private val opened = sent + 1_000_000L

    @Test
    fun waitsUntilTheLinkHasStayedSilentForTheQuietWindow() {
        assertEquals(StartAgain.WAIT, decide(now = opened + quiet - 1))
    }

    @Test
    fun asksAgainOnceTheLinkStayedSilentForTheQuietWindow() {
        assertEquals(StartAgain.SEND, decide(now = opened + quiet))
    }

    @Test
    fun waitsWhileTheLinkHasNotOpenedYet() {
        assertEquals(StartAgain.WAIT, decide(now = opened + quiet, linkOpened = null))
    }

    @Test
    fun asksNothingOnceTheSessionIsUp() {
        assertEquals(
            StartAgain.DONE,
            decide(now = opened + quiet, sessionActive = true),
        )
    }

    @Test
    fun asksNothingWhenTheIphoneAlreadyTriedAfterTheLinkOpened() {
        assertEquals(
            StartAgain.DONE,
            decide(now = opened + quiet, triedSinceLink = true),
        )
    }

    @Test
    fun asksNothingWhenTheLinkWasAlreadyOpenBeforeTheRequestWentOut() {
        // The request went out with the link open, so the iPhone's own attempt got through.
        assertEquals(StartAgain.DONE, decide(now = opened + quiet, linkOpened = sent - 1))
    }

    private fun decide(
        now: Long,
        linkOpened: Long? = opened,
        sessionActive: Boolean = false,
        triedSinceLink: Boolean = false,
    ): StartAgain = WiredStartAgain.decide(
        nowNanos = now,
        startSentAtNanos = sent,
        linkOpenedAtNanos = linkOpened,
        sessionActive = sessionActive,
        connectionAttemptSinceLinkOpened = triedSinceLink,
    )
}
