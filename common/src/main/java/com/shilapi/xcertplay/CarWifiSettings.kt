package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.LynkTcamWifiReadiness

/**
 * Opens the head unit's Wi-Fi settings through whichever entry the firmware actually provides.
 *
 * A Lynk & Co 03 image exposes up to three entries and none is guaranteed: the direct
 * enable request (`REQUEST_ENABLE`), the settings panel (API 29+), and the full Wi-Fi settings
 * screen. They are tried in order - the enable request first when the caller wants the radio on -
 * and each attempt is logged as `started`/`missing`/`denied`/`failed` so a head unit that lacks one
 * can be told from one that refused it. This mirrors the factory build's own launcher.
 */
internal object CarWifiSettings {

    const val REQUEST_ENABLE = "android.net.wifi.action.REQUEST_ENABLE"

    enum class Destination(val action: String, val label: String) {
        ENABLE(REQUEST_ENABLE, "enable"),
        PANEL("android.settings.panel.action.WIFI", "panel"),
        SETTINGS("android.settings.WIFI_SETTINGS", "settings"),
    }

    fun helpText(context: Context): String = context.getString(
        if (LynkTcamWifiReadiness.supportsBuiltinInitialization(context)) {
            R.string.lynk_client_wifi_compat_help
        } else {
            R.string.lynk_client_wifi_help
        },
    )

    /** Opens Wi-Fi settings for the driver; [onUnavailable] runs when no entry started. */
    fun open(activity: Activity, onUnavailable: () -> Unit = {}) {
        val started = launch(activity, enableFirst = false, { activity.startActivity(it) }, {})
        if (started == null) onUnavailable()
    }

    /** Tries the enable request first; returns the destination that started, or null. */
    fun requestEnable(
        activity: Activity,
        launchForResult: (Intent) -> Unit,
        onDiagnostic: (String) -> Unit,
    ): Destination? = launch(activity, enableFirst = true, launchForResult, onDiagnostic)

    private fun launch(
        activity: Activity,
        enableFirst: Boolean,
        start: (Intent) -> Unit,
        diagnostic: (String) -> Unit,
    ): Destination? {
        val order = buildList {
            if (enableFirst) add(Destination.ENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Destination.PANEL)
            add(Destination.SETTINGS)
            if (!enableFirst) add(Destination.ENABLE)
        }
        for (destination in order) {
            val intent = Intent(destination.action)
            if (destination == Destination.ENABLE) {
                intent.putExtra("android.intent.extra.PACKAGE_NAME", activity.packageName)
            }
            val result = try {
                start(intent)
                "started"
            } catch (_: ActivityNotFoundException) {
                "missing"
            } catch (_: SecurityException) {
                "denied"
            } catch (_: RuntimeException) {
                "failed"
            }
            runCatching { diagnostic("Car Wi-Fi settings action=${destination.label} result=$result") }
            if (result == "started") return destination
        }
        val message = if (LynkTcamWifiReadiness.supportsBuiltinInitialization(activity)) {
            R.string.lynk_client_wifi_compat_settings_unavailable
        } else {
            R.string.lynk_client_wifi_settings_unavailable
        }
        Toast.makeText(activity, activity.getString(message), Toast.LENGTH_SHORT).show()
        return null
    }
}
