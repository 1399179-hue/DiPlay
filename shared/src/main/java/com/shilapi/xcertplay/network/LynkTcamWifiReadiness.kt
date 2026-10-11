package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.platform.HeadUnitProfiles
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Waits until the head unit's own Wi-Fi radio is usable for the factory hotspot link.
 *
 * The radio is a system resource the driver can switch off at any moment, and the app cannot rely
 * on `WifiManager` alone: on Android 11 a normal app may not toggle Wi-Fi unless it targets API 28
 * or lower, which the Lynk build does and this build does not. Two paths therefore exist:
 *
 *  - [canInitialize] (targetSdk <= 28 on API 30, Lynk & Co 03 only): the app may call
 *    `setWifiEnabled(true)` and reports exactly why the framework refused;
 *  - otherwise: the app only waits, and says so, rather than failing with a permission error it
 *    cannot fix.
 *
 * Every radio-state transition is reported once, so a "no signal" report can be told apart from a
 * radio that is genuinely switched off.
 */
internal class LynkTcamWifiReadiness(
    context: Context,
    private val onWaiting: () -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val nowNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val onInitializing: () -> Unit = {},
) : Closeable {

    enum class Result {
        ENABLED,
        ENABLED_AFTER_WAIT,
        TIMED_OUT,
        UNAVAILABLE,
        CANCELLED,
        INITIALIZATION_REJECTED,
        INITIALIZATION_DENIED,
        INITIALIZATION_FAILED,
    }

    private val appContext = context.applicationContext
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    private val canInitialize: Boolean = supportsBuiltinInitialization(context)

    private val enableRequested = AtomicBoolean(false)
    private val lock = Object()

    @Volatile
    private var closed = false

    @Volatile
    private var initializationFailure: Result? = null

    /** Waits up to [timeoutMillis], but never longer than a single hotspot bring-up allows. */
    fun awaitEnabled(timeoutMillis: Long): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LynkTcamWifiReadiness.awaitEnabled must not run on the main thread"
        }
        val budgetNanos = TimeUnit.MILLISECONDS.toNanos(
            timeoutMillis.coerceIn(0L, MAX_WAIT_MILLIS),
        )
        val startedNanos = nowNanos()
        if (!closed) runCatching { onDiagnostic(LynkTcamWifiDiagnostics.describe(appContext)) }

        var waited = false
        var lastStateLabel: String? = null
        while (!closed) {
            if (waited && nowNanos() - startedNanos >= budgetNanos) {
                diagnostic("enable-timed-out")
                return Result.TIMED_OUT
            }

            var state: Int? = null
            var stateLabel: String? = null
            try {
                state = wifi?.wifiState
            } catch (_: SecurityException) {
                stateLabel = "permission-denied"
            } catch (_: Exception) {
                stateLabel = "read-failed"
            }
            if (closed) return Result.CANCELLED
            if (stateLabel == null) {
                stateLabel = when (state) {
                    WifiManager.WIFI_STATE_DISABLED -> "disabled"
                    WifiManager.WIFI_STATE_DISABLING -> "disabling"
                    WifiManager.WIFI_STATE_ENABLING -> "enabling"
                    WifiManager.WIFI_STATE_ENABLED -> "enabled"
                    WifiManager.WIFI_STATE_UNKNOWN -> "unknown"
                    else -> "unavailable"
                }
            }
            if (stateLabel != lastStateLabel) {
                runCatching { onDiagnostic("TCAM Wi-Fi radio state=$stateLabel") }
            }

            if (state == WifiManager.WIFI_STATE_ENABLED) {
                if (waited) diagnostic("enabled")
                return if (waited) Result.ENABLED_AFTER_WAIT else Result.ENABLED
            }
            // disabled / disabling / enabling are the only states worth waiting through.
            val transitional = state == WifiManager.WIFI_STATE_DISABLED ||
                state == WifiManager.WIFI_STATE_DISABLING ||
                state == WifiManager.WIFI_STATE_ENABLING
            if (!transitional) {
                diagnostic("unavailable")
                return Result.UNAVAILABLE
            }

            if (!waited) {
                if (canInitialize) {
                    diagnostic("initializing")
                    runCatching { onInitializing() }
                } else {
                    // No way to switch the radio on from here: hand it to the driver and keep
                    // waiting, so the failure the caller reports names the real cause.
                    diagnostic("waiting-enabled")
                    onWaiting()
                }
                waited = true
            }
            if (closed) return Result.CANCELLED
            initializationFailure?.let { return it }

            val remainingNanos = budgetNanos - (nowNanos() - startedNanos)
            if (remainingNanos <= 0) {
                if (closed) return Result.CANCELLED
                diagnostic("enable-timed-out")
                return Result.TIMED_OUT
            }

            if (canInitialize &&
                state == WifiManager.WIFI_STATE_DISABLED &&
                enableRequested.compareAndSet(false, true)
            ) {
                val failure = try {
                    val manager = wifi
                    if (manager == null || !manager.setWifiEnabled(true)) {
                        Result.INITIALIZATION_REJECTED
                    } else {
                        null
                    }
                } catch (_: SecurityException) {
                    Result.INITIALIZATION_DENIED
                } catch (_: Exception) {
                    Result.INITIALIZATION_FAILED
                }
                if (closed) return Result.CANCELLED
                initializationFailure = failure
                val label = when (failure) {
                    Result.INITIALIZATION_REJECTED -> "rejected"
                    Result.INITIALIZATION_DENIED -> "permission-denied"
                    Result.INITIALIZATION_FAILED -> "failed"
                    else -> "accepted"
                }
                runCatching { onDiagnostic("TCAM Wi-Fi initialization result=$label") }
                if (failure != null) return failure
            } else {
                synchronized(lock) {
                    if (closed) return Result.CANCELLED
                    try {
                        val waitNanos = minOf(
                            remainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS),
                        )
                        lock.wait(
                            waitNanos / NANOS_PER_MILLISECOND,
                            (waitNanos % NANOS_PER_MILLISECOND).toInt(),
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return Result.CANCELLED
                    }
                }
            }
            lastStateLabel = stateLabel
        }
        return Result.CANCELLED
    }

    private fun diagnostic(state: String) {
        runCatching { onDiagnostic("TCAM Wi-Fi readiness state=$state") }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    companion object {
        /**
         * The Lynk build only self-initialises on the exact combination it is signed for: API 30
         * with a legacy target SDK, on the Lynk & Co 03. Anything else must not touch the radio.
         */
        fun supportsBuiltinInitialization(context: Context): Boolean {
            val targetSdk = context.applicationContext.applicationInfo.targetSdkVersion
            return Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                targetSdk in 1..28 &&
                HeadUnitProfiles.current().id == HeadUnitProfiles.LYNK_03.id
        }
    }
}

/** A single hotspot bring-up budgets at most this long for the radio. */
private const val MAX_WAIT_MILLIS = 20_000L

private const val POLL_INTERVAL_MILLIS = 200L

private const val NANOS_PER_MILLISECOND = 1_000_000L
