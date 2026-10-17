package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the car's factory hotspot straight from the TCAM module, with a short-lived cache.
 *
 * A Geely/ECARX head unit does not expose its hotspot through the public Android API:
 * `getWifiApState` and `isWifiApEnabled` are gone, the sticky `WIFI_AP_STATE_CHANGED` broadcast is
 * never sent, and the framework never publishes the SSID. The TCAM service is the ingress the car
 * itself uses. It is a plain Binder service with no SDK jar and no platform signature, so it is
 * reached through raw transactions:
 *
 *   component  : com.autolink.tcamservice / com.autolink.tcamservice.TcamService
 *   descriptor : com.autolink.adapterbinder.ITcamService
 *   transaction 12 -> AP state (readInt; 11 = enabling, 13 = enabled)
 *   transaction 15 -> AP configuration (readInt status; when 1: state, SSID, passphrase, bssid,
 *                                       band, channel, spare)
 *
 * The passphrase comes back as plain text. Every read is guarded by `dataAvail()` so a firmware
 * that answers with a shorter parcel degrades to "unavailable" instead of reading garbage.
 *
 * Paths and constants mirror the Lynk factory build (`LynkTcamHotspotClient.kt`): the cache is
 * 2 s, the bind/transaction work runs on two dedicated daemon threads, and a busy transaction
 * executor drops rather than queues a second reader.
 */
internal object LynkTcamHotspotClient {

    private const val TAG = "xcertplay-usb"

    private const val TCAM_PACKAGE = "com.autolink.tcamservice"
    private const val TCAM_SERVICE = "com.autolink.tcamservice.TcamService"
    private const val DESCRIPTOR = "com.autolink.adapterbinder.ITcamService"

    private const val GET_AP_STATE = 12
    private const val GET_AP_CONFIGURATION = 15

    private const val AP_STATE_ENABLING = 11
    private const val AP_STATE_ENABLED = 13

    /** The status cache is only ever a cheap hint; the hotspot manager re-reads before it acts. */
    private const val CACHE_TTL_NANOS = 2_000_000_000L

    private const val DEFAULT_READ_TIMEOUT_MILLIS = 1_500L

    private val component = ComponentName(TCAM_PACKAGE, TCAM_SERVICE)

    private val cacheLock = Any()

    private var cachedState: Boolean? = null
    private var cachedAtNanos: Long? = null
    private var refreshing = false

    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-tcam-refresh").apply { isDaemon = true }
    }

    /**
     * A single permit: a second transaction while one is in flight is rejected by the abort policy
     * instead of piling up behind it, which is what keeps a slow firmware from stalling a caller
     * that already gave up.
     */
    private val transactionExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "diplay-tcam-read").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** What the TCAM module reported, or nulls when it could not be read. */
    class Snapshot(
        val state: Int,
        val ssid: String?,
        val passphrase: String?,
        val bssid: String?,
        val band: Int?,
        val channel: Int?,
    ) {
        /** 11 = enabling, 13 = enabled, anything else is off or unknown. */
        val enabled: Boolean?
            get() = when (state) {
                AP_STATE_ENABLING -> false
                AP_STATE_ENABLED -> true
                else -> null
            }

        override fun toString(): String =
            "TcamHotspotSnapshot(state=$state, band=$band, channel=$channel)"
    }

    /**
     * The cached hotspot state, refreshed in the background.
     *
     * Returns null on a cache miss while the refresh runs, so a caller that needs a definite answer
     * reads through [read] instead. The refresh never blocks the caller.
     */
    fun cachedEnabled(context: Context): Boolean? {
        synchronized(cacheLock) {
            val cachedAt = cachedAtNanos
            if (cachedAt != null && System.nanoTime() - cachedAt < CACHE_TTL_NANOS) {
                return cachedState
            }
            if (!refreshing) {
                refreshing = true
                val appContext = context.applicationContext
                refreshExecutor.execute { refresh(appContext) }
            }
            return null
        }
    }

    private fun refresh(context: Context) {
        try {
            updateCache(readBound(context, DEFAULT_READ_TIMEOUT_MILLIS, includeConfiguration = false))
        } finally {
            synchronized(cacheLock) { refreshing = false }
        }
    }

    /**
     * Reads the hotspot configuration, blocking up to [timeoutMillis].
     *
     * Returns null when the module is not present or answers with something this build does not
     * understand. Must not run on the main thread.
     */
    fun read(context: Context, timeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS): Snapshot? {
        if (Looper.myLooper() == Looper.getMainLooper() || timeoutMillis <= 0) {
            // Not a read that failed - a read that never happened. Without this line the caller sees
            // the same null it sees when the TCAM module is absent, and the two look identical in a
            // diagnostic report.
            Log.i(TAG, "TCAM hotspot read refused on the main thread or with timeoutMillis=$timeoutMillis")
            return null
        }
        val snapshot = readBound(context.applicationContext, timeoutMillis, includeConfiguration = true)
        updateCache(snapshot)
        return snapshot
    }

    private fun updateCache(result: Snapshot?) {
        synchronized(cacheLock) {
            cachedState = result?.enabled
            cachedAtNanos = System.nanoTime()
        }
    }

    private fun readBound(
        context: Context,
        timeoutMillis: Long,
        includeConfiguration: Boolean,
    ): Snapshot? {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val latch = CountDownLatch(1)
        val holder = AtomicReference<IBinder?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                holder.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                holder.set(null)
            }

            override fun onBindingDied(name: ComponentName) {
                holder.set(null)
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                latch.countDown()
            }
        }

        val bound = try {
            context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {
            false
        }
        if (!bound) {
            transactionExecutor.purge()
            return null
        }

        var pending: Future<Snapshot?>? = null
        try {
            val bindNanos = deadlineNanos - System.nanoTime()
            if (bindNanos <= 0 || !latch.await(bindNanos, TimeUnit.NANOSECONDS)) return null
            val binder = holder.get() ?: return null
            if (deadlineNanos - System.nanoTime() <= 0) return null
            pending = transactionExecutor.submit(
                Callable { readSnapshot(binder, includeConfiguration) },
            )
            val readNanos = deadlineNanos - System.nanoTime()
            if (readNanos <= 0) return null
            return pending.get(readNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (error: Exception) {
            // A TCAM module that is present and refusing - a SecurityException, a RemoteException, a
            // descriptor mismatch - returned the same null as one that is absent, so the diagnostic
            // report could not tell a car problem from a car that has no such module.
            Log.i(TAG, "TCAM hotspot read failed (${error.javaClass.simpleName}: ${error.message})")
            return null
        } finally {
            pending?.cancel(true)
            transactionExecutor.purge()
            try {
                context.unbindService(connection)
            } catch (_: Exception) {
                // The service may already be gone; the read result is what matters.
            }
        }
    }

    private fun readSnapshot(service: IBinder, includeConfiguration: Boolean): Snapshot {
        check(service.interfaceDescriptor == DESCRIPTOR) { "Unexpected TCAM interface" }
        val state = transact(service, GET_AP_STATE) { reply ->
            check(reply.dataAvail() >= 4) { "Missing TCAM state" }
            reply.readInt()
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (!includeConfiguration) return Snapshot(state, null, null, null, null, null)
        return transact(service, GET_AP_CONFIGURATION) { reply ->
            check(reply.dataAvail() >= 4) { "Missing TCAM configuration status" }
            val status = reply.readInt()
            if (status == 0) {
                // No configuration while the hotspot is off. Not an error.
                Snapshot(state, null, null, null, null, null)
            } else {
                check(status == 1 && reply.dataAvail() >= 4) { "Invalid TCAM configuration" }
                reply.readInt()
                val ssid = reply.readString()
                val passphrase = reply.readString()
                val bssid = reply.readString()
                check(reply.dataAvail() >= 12) { "Incomplete TCAM configuration" }
                val band = reply.readInt()
                val channel = reply.readInt()
                reply.readInt()
                Snapshot(state, ssid, passphrase, bssid, band, channel)
            }
        }
    }

    private fun <T> transact(service: IBinder, code: Int, decode: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!service.transact(code, data, reply, 0)) {
                throw RemoteException("TCAM read unavailable")
            }
            reply.readException()
            decode(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
