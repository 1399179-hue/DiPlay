package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredStartWatchdogTest {
    private val pending = linkedMapOf<Long, () -> Unit>()
    private var holds = 0
    private var timeouts = 0
    private var canStillGoOut = false

    private fun watchdog(timeoutMillis: Long = WiredStartWatchdog.TIMEOUT_MILLIS) = WiredStartWatchdog(
        schedule = { delay, action ->
            pending[delay] = action
            { pending.remove(delay) }
        },
        onTimeout = { timeouts++ },
        secondRequestCanStillGoOut = { canStillGoOut },
        onHold = { holds++ },
        timeoutMillis = timeoutMillis,
    )

    private fun fire(delay: Long) {
        val action = pending.remove(delay) ?: error("nothing scheduled for $delay ms")
        action()
    }

    @Test
    fun nothingIsScheduledUntilTheRequestGoesOut() {
        watchdog()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun theFirstRequestStartsTheLimit() {
        watchdog().startSessionSent()
        assertEquals(setOf(WiredStartWatchdog.TIMEOUT_MILLIS), pending.keys)
    }

    @Test
    fun arepeatedRequestKeepsTheSameLimit() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.startSessionSent()
        assertEquals(1, pending.size)
    }

    @Test
    fun theLimitExpiresIntoATimeout() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        fire(WiredStartWatchdog.TIMEOUT_MILLIS)
        assertEquals(1, timeouts)
        assertEquals(0, holds)
    }

    @Test
    fun aLiveSessionStopsTheLimit() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.sessionActive()
        assertTrue(pending.isEmpty())
        assertEquals(0, timeouts)
    }

    @Test
    fun theSecondRequestGetsAFullLimitAgain() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.startSessionSentAgain()
        assertEquals(setOf(WiredStartWatchdog.TIMEOUT_MILLIS), pending.keys)
    }

    @Test
    fun theSecondRequestIsGrantedOnlyOnce() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.startSessionSentAgain()
        fire(WiredStartWatchdog.TIMEOUT_MILLIS)
        assertEquals(1, timeouts)
    }

    @Test
    fun theLimitIsHeldOnceWhileTheSecondRequestCanStillGoOut() {
        canStillGoOut = true
        val watchdog = watchdog()
        watchdog.startSessionSent()
        fire(WiredStartWatchdog.TIMEOUT_MILLIS)
        assertEquals(1, holds)
        assertEquals(0, timeouts)
        assertEquals(
            setOf(WiredStartWatchdog.HELD_TIMEOUT_MILLIS - WiredStartWatchdog.TIMEOUT_MILLIS),
            pending.keys,
        )
    }

    @Test
    fun theHeldLimitSettlesIntoATimeout() {
        canStillGoOut = true
        val watchdog = watchdog()
        watchdog.startSessionSent()
        fire(WiredStartWatchdog.TIMEOUT_MILLIS)
        fire(WiredStartWatchdog.HELD_TIMEOUT_MILLIS - WiredStartWatchdog.TIMEOUT_MILLIS)
        assertEquals(1, timeouts)
    }

    @Test
    fun aLimitThatWasAlreadyHeldIsNotHeldTwice() {
        canStillGoOut = true
        val watchdog = watchdog()
        watchdog.startSessionSent()
        fire(WiredStartWatchdog.TIMEOUT_MILLIS)
        fire(WiredStartWatchdog.HELD_TIMEOUT_MILLIS - WiredStartWatchdog.TIMEOUT_MILLIS)
        assertEquals(1, holds)
    }

    @Test
    fun terminatingClearsTheTimer() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.terminate()
        assertTrue(pending.isEmpty())
        assertEquals(0, timeouts)
    }

    @Test
    fun aTimerThatFiresAfterTerminationDoesNothing() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        val action = pending.remove(WiredStartWatchdog.TIMEOUT_MILLIS)!!
        watchdog.terminate()
        action()
        assertEquals(0, timeouts)
    }
}
