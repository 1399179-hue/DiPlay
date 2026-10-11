package com.shilapi.xcertplay.orchestration

/**
 * Graded retry state machine for the wireless bootstrap.
 *
 * The controller stamps [onStartSessionSent] the moment it sends the CarPlay session-start over the
 * tunnel, then calls [evaluate] on a watchdog tick. The machine returns one of four actions:
 *
 *  - [Action.WAIT]         - keep waiting, nothing to do yet.
 *  - [Action.RETRY_PREFERRED_NETWORK] - the first attempt has waited [NETWORK_GRACE_MILLIS] and the
 *    preferred network is still available; re-bring it up.
 *  - [Action.RETRY_TIMEOUT] - the *first* attempt never authenticated within [SESSION_WAIT_MILLIS];
 *    retry the whole bootstrap once.
 *  - [Action.EXHAUSTED]    - a *later* attempt never authenticated within [SESSION_WAIT_MILLIS];
 *    give up.
 *
 * `recoveryAttempt` distinguishes the first attempt (0) from retries (>= 1). The first attempt is
 * the one that gets the long [SESSION_WAIT_MILLIS] patience and is then retried; once an attempt is
 * itself a retry, a timeout there is terminal. The controller caps retries with [MAX_RECOVERIES].
 *
 * Ported from the Lynk & Co build's
 * `com.shilapi.xcertplay.orchestration.WirelessBootstrapRecovery`. The two timeouts are named after
 * what they measure there: [NETWORK_GRACE_MILLIS] is the short grace before a preferred-network
 * retry is even considered, [SESSION_WAIT_MILLIS] is the long budget a session gets to
 * authenticate at all.
 */
class WirelessBootstrapRecovery(private val recoveryAttempt: Int) {
    enum class Action {
        WAIT,
        RETRY_PREFERRED_NETWORK,
        RETRY_TIMEOUT,
        EXHAUSTED,
    }

    companion object {
        const val MAX_RECOVERIES: Int = 1
        const val NETWORK_GRACE_MILLIS: Long = 15_000L
        const val SESSION_WAIT_MILLIS: Long = 90_000L
    }

    @Volatile private var established: Boolean = false

    /** Timestamp (ms) when the session-start was sent, or null if not yet sent. The first call wins
     *  so repeated ticks do not reset the clock. Returns true if this call recorded the timestamp. */
    @Volatile private var startedAtMillis: Long? = null

    fun onStartSessionSent(nowMillis: Long): Boolean {
        if (startedAtMillis != null) return false
        startedAtMillis = nowMillis
        return true
    }

    /**
     * @param nowMillis current time in ms.
     * @param establishedNow whether the CarPlay session is currently active (the source of truth).
     * @param preferredNetworkAvailable supplier reporting whether the preferred Wi-Fi network is
     *   still available (used only on the first attempt's quick retry branch).
     */
    fun evaluate(
        nowMillis: Long,
        establishedNow: Boolean,
        preferredNetworkAvailable: () -> Boolean,
    ): Action {
        if (establishedNow) established = true
        if (established) return Action.WAIT

        val started = startedAtMillis ?: return Action.WAIT
        val elapsed = maxOf(0L, nowMillis - started)
        if (elapsed >= SESSION_WAIT_MILLIS) {
            // The first attempt is retried; a retry that also times out is terminal. This is the
            // opposite of "give up on the first timeout", which is what made the first slow
            // handset association look like a dead end.
            return if (recoveryAttempt < 1) Action.RETRY_TIMEOUT else Action.EXHAUSTED
        }
        if (recoveryAttempt < 1 && elapsed >= NETWORK_GRACE_MILLIS && preferredNetworkAvailable()) {
            return Action.RETRY_PREFERRED_NETWORK
        }
        return Action.WAIT
    }
}
