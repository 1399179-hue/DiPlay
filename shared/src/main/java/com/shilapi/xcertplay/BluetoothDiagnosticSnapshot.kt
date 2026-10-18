package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic snapshot for Bluetooth state and failure categorization using public Android APIs.
 *
 * Privacy guarantee: Never records MAC addresses, PINs, pairing secrets, or device names.
 */
object BluetoothDiagnosticSnapshot {
    private const val PREFS = "diplay_bluetooth_diagnostics"
    private const val KEY_LAST_FAILURE_CATEGORY = "last_failure_category"
    private const val KEY_LAST_FAILURE_SUMMARY = "last_failure_summary"
    private const val KEY_LAST_FAILURE_TIME = "last_failure_time"

    enum class Classification {
        ABSENT,
        TURNING_ON,
        TURNING_OFF,
        DISABLED,
        PERMISSION_ERROR,
        SOCKET_SERVICE_MISSING,
        READY,
    }

    enum class FailureCategory {
        SOCKET_MANAGER_MISSING,
        PERMISSION_REFUSED,
        RFCOMM_FAILURE,
        UNREAL_LINK,
        RADIO_OFF,
        TIMEOUT,
        OTHER,
    }

    data class Snapshot(
        val apiLevel: Int,
        val hasBluetoothManager: Boolean,
        val hasManagerAdapter: Boolean,
        val hasDefaultAdapterFallback: Boolean,
        val adapterStateName: String,
        val adapterStateCode: Int?,
        val isEnabled: Boolean,
        val connectPermissionStatus: String,
        val classification: Classification,
        val lastFailureCategory: String?,
        val lastFailureSummary: String?,
        val lastFailureTimeMs: Long,
    )

    fun recordFailure(context: Context, category: FailureCategory, summary: String) {
        runCatching {
            val safeSummary = summary.take(120)
                .replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "[REDACTED_MAC]")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_FAILURE_CATEGORY, category.name)
                .putString(KEY_LAST_FAILURE_SUMMARY, safeSummary)
                .putLong(KEY_LAST_FAILURE_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    fun capture(context: Context): Snapshot {
        val apiLevel = Build.VERSION.SDK_INT
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val hasBluetoothManager = manager != null
        val managerAdapter = runCatching { manager?.adapter }.getOrNull()
        val hasManagerAdapter = managerAdapter != null

        val defaultAdapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val hasDefaultAdapterFallback = defaultAdapter != null

        val effectiveAdapter = managerAdapter ?: defaultAdapter

        val stateCode = runCatching { effectiveAdapter?.state }.getOrNull()
        val stateName = when (stateCode) {
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            null -> if (effectiveAdapter == null) "ABSENT" else "UNAVAILABLE"
            else -> "UNKNOWN($stateCode)"
        }

        val isEnabled = runCatching { effectiveAdapter?.isEnabled == true }.getOrDefault(false)

        val connectPermissionStatus = if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "GRANTED"
            } else {
                "DENIED"
            }
        } else {
            "NOT_REQUIRED(API $apiLevel)"
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastFailureCategory = prefs.getString(KEY_LAST_FAILURE_CATEGORY, null)
        val lastFailureSummary = prefs.getString(KEY_LAST_FAILURE_SUMMARY, null)
        val lastFailureTimeMs = prefs.getLong(KEY_LAST_FAILURE_TIME, 0L)

        val classification = when {
            effectiveAdapter == null -> Classification.ABSENT
            connectPermissionStatus == "DENIED" -> Classification.PERMISSION_ERROR
            stateCode == BluetoothAdapter.STATE_TURNING_ON -> Classification.TURNING_ON
            stateCode == BluetoothAdapter.STATE_TURNING_OFF -> Classification.TURNING_OFF
            !isEnabled -> Classification.DISABLED
            lastFailureCategory == FailureCategory.SOCKET_MANAGER_MISSING.name -> Classification.SOCKET_SERVICE_MISSING
            else -> Classification.READY
        }

        return Snapshot(
            apiLevel = apiLevel,
            hasBluetoothManager = hasBluetoothManager,
            hasManagerAdapter = hasManagerAdapter,
            hasDefaultAdapterFallback = hasDefaultAdapterFallback,
            adapterStateName = stateName,
            adapterStateCode = stateCode,
            isEnabled = isEnabled,
            connectPermissionStatus = connectPermissionStatus,
            classification = classification,
            lastFailureCategory = lastFailureCategory,
            lastFailureSummary = lastFailureSummary,
            lastFailureTimeMs = lastFailureTimeMs,
        )
    }

    fun report(context: Context): String {
        val snap = capture(context)
        val timeStr = if (snap.lastFailureTimeMs > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(snap.lastFailureTimeMs))
        } else {
            "none"
        }

        val presenceDetail = if (snap.classification == Classification.ABSENT) {
            "ABSENT (Standard Bluetooth adapter is unavailable; separate OEM Bluetooth module is a possibility requiring device evidence)"
        } else {
            "PRESENT"
        }

        return buildString {
            appendLine("--- Bluetooth Diagnostic Snapshot ---")
            appendLine("Android API level: ${snap.apiLevel}")
            appendLine("BluetoothManager service: ${if (snap.hasBluetoothManager) "exists" else "null"}")
            appendLine("BluetoothManager adapter: ${if (snap.hasManagerAdapter) "exists" else "null"}")
            appendLine("BluetoothAdapter.getDefaultAdapter(): ${if (snap.hasDefaultAdapterFallback) "exists" else "null"}")
            appendLine("Adapter presence: $presenceDetail")
            appendLine("Adapter state: ${snap.adapterStateName}")
            appendLine("isEnabled: ${snap.isEnabled}")
            appendLine("BLUETOOTH_CONNECT permission: ${snap.connectPermissionStatus}")
            appendLine("Classification: ${snap.classification}")
            appendLine("Last recorded failure: category=${snap.lastFailureCategory ?: "none"} " +
                "time=$timeStr summary=${snap.lastFailureSummary ?: "none"}")
        }
    }
}
