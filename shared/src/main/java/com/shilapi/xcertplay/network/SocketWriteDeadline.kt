package com.shilapi.xcertplay.network

import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a socket write under a watchdog.
 *
 * A blocking write on a half-dead TCP peer parks the calling thread for as long as the kernel
 * keeps retransmitting - minutes on the platform defaults. On the AirPlay event channel that
 * thread also holds the event write lock, so every later command (volume, transport keys, cluster
 * UI) queues behind it, and the session cannot be torn down cleanly because the teardown waits for
 * the same lock.
 *
 * Mirrors the Lynk & Co build's `SocketWriteDeadline`: schedule a task that closes the socket when
 * the deadline passes - which is what unblocks the write - cancel it when the write finishes in
 * time, and raise the timeout as an exception so the caller can tear the session down instead of
 * carrying on as if the command had been delivered.
 */
internal object SocketWriteDeadline {
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "airplay-write-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /**
     * Runs [action]; when [timeoutMillis] elapses first the socket is closed to break the write,
     * and a [SocketTimeoutException] is thrown once [action] has returned.
     *
     * A non-positive timeout disables the watchdog and simply runs [action].
     */
    fun write(socket: Socket, timeoutMillis: Long, action: () -> Unit) {
        if (timeoutMillis <= 0) {
            action()
            return
        }
        val state = AtomicInteger(0)
        val deadline = watchdog.schedule(
            {
                if (state.compareAndSet(0, 2)) {
                    runCatching { socket.close() }
                }
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        action()
        state.compareAndSet(0, 1)
        deadline.cancel(false)
        if (state.get() == 2) {
            throw SocketTimeoutException("AirPlay write exceeded ${timeoutMillis}ms")
        }
    }
}
