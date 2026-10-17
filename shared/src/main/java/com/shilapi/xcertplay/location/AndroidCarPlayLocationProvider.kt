package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * A fresh GPS fix is always preferred; fused or network location is used only while GPS is
 * stale, so a coarse fix never displaces satellite positioning. With [precise], GGA also carries
 * the real fix quality, satellites used and sea-level altitude, each omitted on its own when
 * Android cannot supply it.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val precise: Boolean = false,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in locationManager.allProviders }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            if (provider in preferredProviders) {
                latestFixes[provider] = location
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    /** Satellites used in the latest GNSS fix and when it was reported (elapsed realtime). */
    @Volatile private var satellites: Pair<Int, Long>? = null
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellites = (0 until status.satelliteCount).count(status::usedInFix) to
                SystemClock.elapsedRealtime()
        }

        override fun onStopped() {
            satellites = null
        }
    }
    private var gnssRegistered = false

    /** Sea-level altitude for one fix; computed off the main thread and reused afterwards. */
    @Volatile private var mslCache: Pair<Location, Double?>? = null
    private var started = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (started && precise) registerGnssStatus()
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        gnssRegistered = try {
            locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        } catch (error: Exception) {
            Log.w(TAG, "Satellite status unavailable; satellites used fall back", error)
            false
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
        if (gnssRegistered) {
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            gnssRegistered = false
        }
        satellites = null
    }

    override fun latestNmea(): String? {
        val fix = latestFix() ?: return null
        return try {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = (if (precise) mslAltitude(fix) else null)
                        ?: fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    geoidSeparationMeters = (if (precise) mslAltitude(fix) else null)
                        ?.let { fix.altitude - it },
                    fixQuality = if (precise) fixQuality(fix) else null,
                    satellitesUsed = if (precise) satellitesUsed(fix) else null,
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    private fun latestFix(): Location? {
        val now = SystemClock.elapsedRealtime()
        val usable = preferredProviders.mapNotNull(latestFixes::get)
            .filter { now - it.elapsedRealtimeNanos / 1_000_000 in 0..preferredLocationAgeMillis }
        return usable.firstOrNull {
            it.provider == LocationManager.GPS_PROVIDER &&
                now - it.elapsedRealtimeNanos / 1_000_000 <= GPS_FRESH_MILLIS
        } ?: usable.maxByOrNull { it.elapsedRealtimeNanos }
    }

    /** GGA quality: 1 for a GNSS fix, 6 (estimated) for network; unknown sources fall back. */
    private fun fixQuality(fix: Location): Int? = when (fix.provider) {
        LocationManager.GPS_PROVIDER -> 1
        LocationManager.NETWORK_PROVIDER -> 6
        else -> null
    }

    /** Only a GPS fix is known to come from the satellites GnssStatus reports. */
    private fun satellitesUsed(fix: Location): Int? {
        if (fix.provider != LocationManager.GPS_PROVIDER) return null
        val (count, at) = satellites ?: return null
        return count.takeIf { SystemClock.elapsedRealtime() - at in 0..SATELLITE_STATUS_MAX_AGE_MILLIS }
    }

    /**
     * Android altitude is WGS84 ellipsoid height, GGA wants mean sea level. API 34 can convert;
     * any failure leaves the ellipsoid height in place. The converter reads its geoid model from
     * disk, so the main thread only reuses a value already computed for this fix.
     */
    private fun mslAltitude(fix: Location): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !fix.hasAltitude()) return null
        mslCache?.let { (cached, value) -> if (cached === fix) return value }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val value = try {
            if (fix.hasMslAltitude()) {
                fix.mslAltitudeMeters
            } else {
                val copy = Location(fix)
                android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(appContext, copy)
                copy.mslAltitudeMeters.takeIf { copy.hasMslAltitude() }
            }?.takeIf { it.isFinite() }
        } catch (error: Exception) {
            Log.w(TAG, "Sea-level altitude unavailable; ellipsoid height is sent", error)
            null
        }
        mslCache = fix to value
        return value
    }

    companion object {
        private const val TAG = "CarPlayLocation"
        /** Matches the 250 ms GGA/RMC interval iPhones request; the GNSS chip may still deliver slower. */
        private const val UPDATE_INTERVAL_MILLIS = 250L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L
        /** A GPS fix this recent wins over any other provider. */
        private const val GPS_FRESH_MILLIS = 2_000L
        private const val SATELLITE_STATUS_MAX_AGE_MILLIS = 3_000L
    }
}
