package com.shilapi.xcertplay.diagnostics

import android.net.TrafficStats
import java.util.concurrent.atomic.AtomicLong

/**
 * Bytes this app moved while a CarPlay session was live.
 *
 * Every CarPlay path - wireless RTP over the hotspot and wired AirPlay through the VPN tun -
 * carries the app's own UID, so [TrafficStats] per-UID counters are the one honest source across
 * both transports: kernel-accounted, no socket-by-socket plumbing, and free where the platform
 * hides /sys counters. The counters are cumulative since boot, so a session's usage is the delta
 * between a baseline taken at start and the live reading.
 *
 * Counters are AtomicLongs, not a lock: the reader runs on a diagnostics timer while the session
 * runs, and a torn read is at most one sample late.
 */
class TrafficUsage {
    val baselineRxBytes = AtomicLong(-1L)
    val baselineTxBytes = AtomicLong(-1L)
    val sessionRxBytes = AtomicLong(0L)
    val sessionTxBytes = AtomicLong(0L)

    /** Samples once at session start; the delta baseline everything after is measured against. */
    fun start() {
        baselineRxBytes.set(TrafficStats.getTotalRxBytes())
        baselineTxBytes.set(TrafficStats.getTotalTxBytes())
        sessionRxBytes.set(0L)
        sessionTxBytes.set(0L)
    }

    /** True once [start] has taken a baseline for the current session. */
    fun hasStarted(): Boolean = baselineRxBytes.get() >= 0L

    /**
     * Folds the live counters into the session totals and returns the sample.
     *
     * `getUidRxBytes(uid)` includes the tun, and `getTotalRxBytes` covers every interface the
     * session can ride, so the max of the two readings is used rather than assuming one path.
     * A kernel that refuses one source still answers with the other; both refusing yields the
     * previous total, so the UI shows a stale figure instead of restarting at zero.
     */
    fun sample(): Pair<Long, Long> {
        val rx = maxOf(TrafficStats.getTotalRxBytes(), TrafficStats.getUidRxBytes(android.os.Process.myUid()))
        val tx = maxOf(TrafficStats.getTotalTxBytes(), TrafficStats.getUidTxBytes(android.os.Process.myUid()))
        val baseRx = baselineRxBytes.get()
        val baseTx = baselineTxBytes.get()
        if (rx >= 0 && baseRx >= 0) sessionRxBytes.set(maxOf(sessionRxBytes.get(), rx - baseRx))
        if (tx >= 0 && baseTx >= 0) sessionTxBytes.set(maxOf(sessionTxBytes.get(), tx - baseTx))
        return Pair(sessionRxBytes.get(), sessionTxBytes.get())
    }

    /** Formats bytes the way a driver reads them: one decimal below 10, none above. */
    fun format(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
        bytes >= 1 shl 20 -> "%.0f MB".format(bytes.toDouble() / (1 shl 20))
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes.toDouble() / (1 shl 10))
        else -> "$bytes B"
    }

    fun reset() {
        baselineRxBytes.set(-1L)
        baselineTxBytes.set(-1L)
        sessionRxBytes.set(0L)
        sessionTxBytes.set(0L)
    }
}
