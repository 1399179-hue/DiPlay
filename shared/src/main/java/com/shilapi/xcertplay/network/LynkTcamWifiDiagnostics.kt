package com.shilapi.xcertplay.network

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.shilapi.xcertplay.platform.HeadUnitProfiles

/**
 * One line describing whether the platform will let this build touch the Wi-Fi radio.
 *
 * When the TCAM hotspot path fails, the first question is always whether the failure is ours or
 * the firmware's. This records the whole answer in one log line: API level, target SDK, whether
 * the head unit was recognised, both Wi-Fi permissions, the `change_wifi_state` app-op and
 * airplane mode. A missing permission and a firmware that refuses look identical in every other
 * log, and they need opposite responses.
 */
internal object LynkTcamWifiDiagnostics {

    private const val UNKNOWN = "unknown"
    private const val CHANGE_WIFI_STATE_OP = "android:change_wifi_state"

    fun describe(context: Context): String {
        val sdk = runCatching { Build.VERSION.SDK_INT }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?.toString()
            ?: UNKNOWN
        val target = runCatching { context.applicationInfo.targetSdkVersion }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?.toString()
            ?: UNKNOWN
        val profile = try {
            if (HeadUnitProfiles.current().id == HeadUnitProfiles.LYNK_03.id) "matched" else "unmatched"
        } catch (_: Exception) {
            UNKNOWN
        }
        val accessPermission = permission(context, Manifest.permission.ACCESS_WIFI_STATE)
        val changePermission = permission(context, Manifest.permission.CHANGE_WIFI_STATE)
        val changeAppOp = appOp(context)
        val airplane = runCatching {
            when (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)) {
                0 -> "off"
                1 -> "on"
                else -> UNKNOWN
            }
        }.getOrDefault(UNKNOWN)
        return "TCAM Wi-Fi eligibility sdk=$sdk target=$target profile=$profile" +
            " accessPermission=$accessPermission changePermission=$changePermission" +
            " changeAppOp=$changeAppOp airplane=$airplane"
    }

    private fun appOp(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNKNOWN
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return UNKNOWN
        return try {
            when (
                appOps.unsafeCheckOpNoThrow(
                    CHANGE_WIFI_STATE_OP,
                    Process.myUid(),
                    context.packageName,
                )
            ) {
                AppOpsManager.MODE_ALLOWED -> "allowed"
                AppOpsManager.MODE_IGNORED -> "ignored"
                AppOpsManager.MODE_ERRORED -> "denied"
                AppOpsManager.MODE_DEFAULT -> "default"
                else -> UNKNOWN
            }
        } catch (_: Exception) {
            UNKNOWN
        }
    }

    private fun permission(context: Context, permission: String): String = try {
        when (context.checkSelfPermission(permission)) {
            PackageManager.PERMISSION_DENIED -> "denied"
            PackageManager.PERMISSION_GRANTED -> "granted"
            else -> UNKNOWN
        }
    } catch (_: Exception) {
        UNKNOWN
    }
}
