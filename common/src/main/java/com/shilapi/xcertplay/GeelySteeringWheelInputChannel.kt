package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayMediaKeys
import java.util.concurrent.ConcurrentHashMap

/**
 * One steering-wheel key as the Geely OneOS input service reports it.
 *
 * The codes are the head unit's private ones (`200085`, `200087`, …), not Android key codes. They
 * never reach this app as a `KeyEvent`: on ECARX/Geely head units the wheel buttons are CAN frames
 * that the vehicle maps to a vendor code, so the standard media-button path stays empty no matter
 * how many receivers are registered. See [GeelySteeringKeyCodes].
 */
/**
 * Passive listener for the OneOS steering input service used by Geely head units.
 *
 * The channel talks to the vendor service over raw Binder transactions instead of linking the
 * Geely SDK: only the AIDL descriptors and two transaction codes are needed, so the app builds and
 * runs without the vendor jar. The listener never asks OneOS to intercept a key, so the vehicle's
 * own key handling (volume, cluster, voice) stays exactly as it was — we only observe.
 *
 * Each observed press is handed to [onEvent]; DiPlay routes it through
 * CarPlayMediaKeys.onGeelySteeringKey, the same path the vehicle bridge uses.
 *
 * This is the only path that actually reaches the wheel buttons on an ECARX head unit; the media
 * centre rejects `ACTION_MEDIA_BUTTON` from a third-party package, which is why the buttons looked
 * dead before.
 *
 * Ported from the Lynk & Co build via xcertplay-nomfi: DiPlay's GeelySteeringKeys.kt declares the
 * key vocabulary, and the OneOS channel here is the only way those codes ever reach the app on an
 * ECARX head unit, because the vehicle never raises an Android KeyEvent for a wheel button.
 */
internal class GeelySteeringWheelInputChannel(
    context: Context,
    private val onEvent: (GeelySteeringKeyEvent) -> Unit,
) : AutoCloseable {
    companion object {
        private const val TAG = "xcertplay-usb"
        private const val ONE_OS_PACKAGE = "com.geely.service.oneosapi"
        private const val ONE_OS_SERVICE = "$ONE_OS_PACKAGE.OneOSApiService"
        private const val SERVICE_MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.IServiceManager"
        private const val INPUT_MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputManager"
        private const val INPUT_LISTENER_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputListener"
        private const val INPUT_SERVICE_TYPE = 8
        private const val TRANSACTION_GET_SERVICE = 2
        private const val TRANSACTION_REGISTER = 3
        private const val TRANSACTION_UNREGISTER = 4
        private const val CONNECT_RETRY_MS = 2_000L
        private const val RAW_GESTURE_WINDOW_MS = 1_500L

        /** True when this head unit actually exposes the OneOS input service. */
        fun isAvailable(context: Context): Boolean = runCatching {
            context.packageManager.getServiceInfo(ComponentName(ONE_OS_PACKAGE, ONE_OS_SERVICE), 0)
        }.isSuccess
    }

    private val app = context.applicationContext
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastRawUpAt = ConcurrentHashMap<Int, Long>()
    @Volatile private var registeredKeys = intArrayOf()
    private var serviceManager: IBinder? = null
    private var inputManager: IBinder? = null
    private var bound = false
    private var enabled = false
    private var closed = false
    private var lastFailure = "NONE"
    private var serviceMetadata = "NOT_CHECKED"

    private val retryInputService = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (closed || !enabled || inputManager != null) return
                inputManager = serviceManager?.let(::getInputService)
                if (inputManager != null) applyRequestedKeysLocked()
                else if (bound) mainHandler.postDelayed(this, CONNECT_RETRY_MS)
            }
        }
    }

    private val retryConnection = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (closed || !enabled || inputManager != null) return
                unbindLocked()
                bindLocked()
            }
        }
    }

    private val listener = object : Binder(), IInterface {
        init { attachInterface(this, INPUT_LISTENER_DESCRIPTOR) }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(INPUT_LISTENER_DESCRIPTOR)
                return true
            }
            if (code !in 1..6) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(INPUT_LISTENER_DESCRIPTOR)
            val rawKeyCode = data.readInt()
            val keyCode = GeelySteeringKeyCodes.canonicalize(rawKeyCode) ?: rawKeyCode
            val now = SystemClock.elapsedRealtime()
            when (code) {
                1 -> {
                    val action = data.readInt()
                    data.readInt() // OneOS soft-key function; the key code identifies the press.
                    if (rawKeyCode in registeredKeys &&
                        (action == GeelySteeringKeyEvent.ACTION_DOWN || action == GeelySteeringKeyEvent.ACTION_UP)
                    ) {
                        if (action == GeelySteeringKeyEvent.ACTION_UP) lastRawUpAt[keyCode] = now
                        onEvent(GeelySteeringKeyEvent(keyCode, rawKeyCode, action, now))
                    }
                }
                else -> {
                    data.readInt() // OneOS soft-key function.
                    val directAction = when (code) {
                        2 -> GeelySteeringKeyEvent.ACTION_SINGLE
                        5 -> GeelySteeringKeyEvent.ACTION_LONG
                        6 -> GeelySteeringKeyEvent.ACTION_DOUBLE
                        else -> null
                    }
                    // A raw down/up pair already produced the command for this press.
                    val rawSequenceRecentlyCompleted = now - (lastRawUpAt[keyCode] ?: 0L) < RAW_GESTURE_WINDOW_MS
                    if (rawKeyCode in registeredKeys && directAction != null && !rawSequenceRecentlyCompleted) {
                        onEvent(GeelySteeringKeyEvent(keyCode, rawKeyCode, directAction, now))
                    }
                }
            }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                mainHandler.removeCallbacks(retryConnection)
                serviceManager = service
                inputManager = service?.let(::getInputService)
                if (inputManager == null) {
                    mainHandler.removeCallbacks(retryInputService)
                    mainHandler.postDelayed(retryInputService, CONNECT_RETRY_MS)
                } else {
                    applyRequestedKeysLocked()
                }
            }
            Log.i(TAG, "geely wheel ${diagnostics()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) = resetConnection()
        override fun onBindingDied(name: ComponentName?) = resetConnection()
        override fun onNullBinding(name: ComponentName?) = resetConnection()
    }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (closed || enabled == value) return
            enabled = value
            if (!enabled) {
                mainHandler.removeCallbacks(retryInputService)
                mainHandler.removeCallbacks(retryConnection)
                releaseRegisteredLocked()
                unbindLocked()
            } else {
                bindLocked()
                applyRequestedKeysLocked()
            }
        }
        Log.i(TAG, "geely wheel ${if (value) "enabled" else "disabled"}: ${diagnostics()}")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            enabled = false
            mainHandler.removeCallbacks(retryInputService)
            mainHandler.removeCallbacks(retryConnection)
            releaseRegisteredLocked()
            unbindLocked()
        }
    }

    fun diagnostics(): String = synchronized(lock) {
        "oneOs enabled=$enabled bound=$bound input=${inputManager != null} registered=${registeredKeys.size} " +
            "service=$serviceMetadata failure=$lastFailure"
    }

    fun ready(): Boolean = synchronized(lock) { registeredKeys.isNotEmpty() }

    private fun bindLocked() {
        if (closed || !enabled || bound) return
        serviceMetadata = runCatching {
            @Suppress("DEPRECATION")
            val info = app.packageManager.getServiceInfo(ComponentName(ONE_OS_PACKAGE, ONE_OS_SERVICE), 0)
            "enabled=${info.enabled && info.applicationInfo.enabled},exported=${info.exported}," +
                "permission=${info.permission ?: "NONE"}"
        }.getOrElse { it.javaClass.simpleName }
        bound = runCatching {
            app.bindService(
                Intent().setClassName(ONE_OS_PACKAGE, ONE_OS_SERVICE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure {
            lastFailure = "bind ${it.javaClass.simpleName}: ${it.message?.take(160)}"
            Log.w(TAG, "geely wheel OneOS input service bind failed", it)
        }.getOrDefault(false)
        if (!bound && lastFailure == "NONE") lastFailure = "service_not_bound"
        if (!bound) mainHandler.postDelayed(retryConnection, CONNECT_RETRY_MS)
    }

    private fun resetConnection() {
        synchronized(lock) {
            serviceManager = null
            inputManager = null
            registeredKeys = intArrayOf()
            mainHandler.removeCallbacks(retryInputService)
            mainHandler.removeCallbacks(retryConnection)
            if (!closed && enabled) mainHandler.postDelayed(retryConnection, CONNECT_RETRY_MS)
        }
    }

    private fun unbindLocked() {
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        serviceManager = null
        inputManager = null
        registeredKeys = intArrayOf()
    }

    private fun getInputService(manager: IBinder): IBinder? = transact(
        manager,
        TRANSACTION_GET_SERVICE,
        SERVICE_MANAGER_DESCRIPTOR,
        write = { writeInt(INPUT_SERVICE_TYPE) },
        read = { readStrongBinder() },
    )

    private fun applyRequestedKeysLocked() {
        val manager = inputManager ?: return
        if (!enabled || registeredKeys.isNotEmpty()) return
        val keyTable = GeelySteeringKeyCodes.oneOsListenerCodes()
        val registered = transact<Unit>(
            manager,
            TRANSACTION_REGISTER,
            INPUT_MANAGER_DESCRIPTOR,
            write = {
                writeStrongBinder(listener)
                writeString(app.packageName)
                writeIntArray(keyTable)
            },
            read = { Unit },
        ) != null
        if (registered) {
            registeredKeys = keyTable
            lastFailure = "NONE"
        }
    }

    private fun releaseRegisteredLocked() {
        val manager = inputManager
        if (manager != null && registeredKeys.isNotEmpty()) {
            transact<Unit>(
                manager,
                TRANSACTION_UNREGISTER,
                INPUT_MANAGER_DESCRIPTOR,
                write = {
                    writeStrongBinder(listener)
                    writeString(app.packageName)
                },
                read = { Unit },
            )
        }
        registeredKeys = intArrayOf()
    }

    private fun <T> transact(
        binder: IBinder,
        code: Int,
        descriptor: String,
        write: Parcel.() -> Unit,
        read: Parcel.() -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.write()
            if (!binder.transact(code, data, reply, 0)) {
                lastFailure = "transaction=$code unsupported"
                return null
            }
            reply.readException()
            reply.read()
        } catch (error: Throwable) {
            lastFailure = "transaction=$code ${error.javaClass.simpleName}: ${error.message?.take(160)}"
            Log.w(TAG, "geely wheel OneOS input transaction failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
