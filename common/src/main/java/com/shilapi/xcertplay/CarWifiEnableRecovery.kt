package com.shilapi.xcertplay

/**
 * Drives the driver to the head unit's Wi-Fi settings and then verifies the radio actually came up.
 *
 * Ported from the 领克03 (Lynk 03) build. On that firmware the internal Wi-Fi radio is a separate
 * switch from the car hotspot and is often off when a session is asked to start; the app cannot
 * turn it on itself (it does not target API 28), so it must send the driver to Settings and then
 * check on return.
 *
 * The machine is deliberately narrow:
 *  - it prompts automatically at most once, and only while the bring-up is [Stage.WAITING] and the
 *    host is in the foreground;
 *  - every launch is tagged with a generation, so a late result from an older attempt is ignored;
 *  - if the settings screen returned with the radio still off ([Stage.FAILED]) it restarts the
 *    wireless stack exactly once.
 *
 * All Android/session work is injected: [launch] opens the settings entry (true when one started),
 * [wifiEnabled] reads the live radio state, and [restart] begins one fresh attempt.
 */
internal class CarWifiEnableRecovery(
    private val launch: () -> Boolean,
    private val wifiEnabled: () -> Boolean,
    private val restart: () -> Unit,
    private val diagnostic: (String) -> Unit = {},
) {

    enum class Stage { OTHER, WAITING, FAILED }

    private var generation = -1
    private var stage = Stage.OTHER
    private var foreground = false
    private var closed = false
    private var automaticPrompted = false
    private var recoveryUsed = false
    private var pendingGeneration: Int? = null
    private var returnedGeneration: Int? = null

    fun onStatus(generation: Int, stage: Stage) {
        selectGeneration(generation)
        this.stage = stage
        advance()
    }

    fun onResume(generation: Int) {
        selectGeneration(generation)
        foreground = true
        advance()
    }

    fun onPause() {
        foreground = false
    }

    /** The settings activity returned; [cancelled] is true when the driver backed out. */
    fun onResult(cancelled: Boolean, currentGeneration: Int = generation) {
        selectGeneration(currentGeneration)
        val pending = pendingGeneration ?: return
        pendingGeneration = null
        if (closed || pending != generation || cancelled) return
        returnedGeneration = pending
        advance()
    }

    /** The driver tapped the manual "open car Wi-Fi" action. */
    fun requestByUser(generation: Int) {
        if (!closed && foreground && pendingGeneration == null) {
            selectGeneration(generation)
            automaticPrompted = true
            recoveryUsed = false
            returnedGeneration = null
            request()
        }
    }

    fun close() {
        closed = true
        foreground = false
        returnedGeneration = null
    }

    private fun selectGeneration(value: Int) {
        if (generation == value) return
        generation = value
        stage = Stage.OTHER
        returnedGeneration = null
    }

    private fun advance() {
        if (closed || !foreground || pendingGeneration != null) return
        if (returnedGeneration == generation) {
            if (stage == Stage.OTHER) {
                returnedGeneration = null
            } else {
                val enabled = runCatching { wifiEnabled() }.getOrNull()
                diagnostic(
                    "Car Wi-Fi settings returned radio=" + when (enabled) {
                        true -> "enabled"
                        false -> "not-enabled"
                        null -> "unknown"
                    },
                )
                if (enabled != true) {
                    returnedGeneration = null
                } else if (stage == Stage.FAILED && !recoveryUsed) {
                    recoveryUsed = true
                    returnedGeneration = null
                    diagnostic("Car Wi-Fi settings recovery=restart-once")
                    runCatching { restart() }
                    return
                }
            }
        }
        if (stage != Stage.WAITING || automaticPrompted) return
        automaticPrompted = true
        request()
    }

    private fun request() {
        pendingGeneration = generation
        val started = runCatching { launch() }.getOrDefault(false)
        if (!started) pendingGeneration = null
    }
}
