package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car-Wi-Fi recovery: prompt once when the bring-up is WAITING, then on the driver's return
 * read the radio and, if the wait had already failed, restart the wireless stack exactly once.
 */
class CarWifiEnableRecoveryTest {

    private class Harness(wifiOn: Boolean = false, launchWorks: Boolean = true) {
        var wifiEnabled = wifiOn
        var launchWorks = launchWorks
        var launches = 0
        var restarts = 0
        val diagnostics = mutableListOf<String>()
        val recovery = CarWifiEnableRecovery(
            launch = { launches++; launchWorks },
            wifiEnabled = { wifiEnabled },
            restart = { restarts++ },
            diagnostic = { diagnostics += it },
        )
        init {
            recovery.onResume(GENERATION)
        }
    }

    @Test fun waitingStagePromptsOnceEvenWhenRepeated() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        assertEquals(1, harness.launches)
    }

    @Test fun noPromptWhenNotForeground() {
        val harness = Harness()
        harness.recovery.onPause()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        assertEquals(0, harness.launches)
    }

    @Test fun otherStageNeverPrompts() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.OTHER)
        assertEquals(0, harness.launches)
    }

    @Test fun failedWithoutAPriorPromptDoesNothing() {
        val harness = Harness(wifiOn = true)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        assertEquals(0, harness.launches)
        assertEquals(0, harness.restarts)
    }

    @Test fun returnWithRadioOnWhileWaitingJustContinues() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        assertEquals(1, harness.launches)
        harness.wifiEnabled = true
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(0, harness.restarts)
    }

    @Test fun returnWithRadioOnAfterTheWaitFailedRestartsOnce() {
        val harness = Harness()
        // The prompt was sent while waiting; the bring-up then timed out to FAILED before the return.
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.wifiEnabled = true
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(1, harness.restarts)
        assertTrue(harness.diagnostics.any { it.contains("restart-once") })
        // A second failed transition must not restart again.
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(1, harness.restarts)
    }

    @Test fun returnWithRadioOffDoesNotRestart() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.wifiEnabled = false
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(0, harness.restarts)
    }

    @Test fun cancelledResultIsIgnored() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.wifiEnabled = true
        harness.recovery.onResult(cancelled = true, currentGeneration = GENERATION)
        assertEquals(0, harness.restarts)
    }

    @Test fun staleGenerationResultIsIgnored() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.wifiEnabled = true
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION + 1)
        assertEquals(0, harness.restarts)
    }

    @Test fun launchThatDoesNotStartLeavesNoPending() {
        val harness = Harness(launchWorks = false)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.FAILED)
        harness.wifiEnabled = true
        assertEquals(1, harness.launches)
        // Nothing is pending, so a stray result cannot restart.
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(0, harness.restarts)
    }

    @Test fun userRequestPromptsEvenAfterAutomaticUsed() {
        val harness = Harness()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        assertEquals(1, harness.launches)
        harness.recovery.onResult(cancelled = true, currentGeneration = GENERATION)
        harness.recovery.requestByUser(GENERATION)
        assertEquals(2, harness.launches)
    }

    @Test fun closedRecoveryDoesNothing() {
        val harness = Harness()
        harness.recovery.close()
        harness.recovery.onStatus(GENERATION, CarWifiEnableRecovery.Stage.WAITING)
        harness.recovery.onResult(cancelled = false, currentGeneration = GENERATION)
        assertEquals(0, harness.launches)
        assertEquals(0, harness.restarts)
    }

    private companion object {
        const val GENERATION = 7
    }
}
