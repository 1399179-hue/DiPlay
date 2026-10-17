package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One Android location sample converted into the fields carried by the NMEA pair. */
data class CarPlayLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
    /** GGA fix quality; null keeps the legacy GPS-fix value. */
    val fixQuality: Int? = null,
    /** Satellites used in the fix; null keeps the legacy placeholder. */
    val satellitesUsed: Int? = null,
    /** Set only when [altitudeMeters] is above mean sea level: ellipsoid height minus MSL. */
    val geoidSeparationMeters: Double? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/** Supplies location data only while the phone has subscribed to iAP2 LocationInformation. */
interface Iap2LocationProvider : AutoCloseable {
    /** The 0xFFFA parameter ids, i.e. the sentence types the iPhone asked for; called before [start]. */
    fun onRequested(components: Set<Int>) = Unit

    /** Starts location updates and returns whether at least one source was subscribed. */
    fun start(): Boolean

    fun stop()

    fun latestNmea(): String?

    override fun close() {
        stop()
    }
}

/** LIVI-compatible $GPGGA + $GPRMC encoding used by iAP2 0xFFFB. */
object NmeaLocationEncoder {
    fun encode(fix: CarPlayLocationFix): String {
        val timestamp = fix.timestampMillis
            ?.takeIf { it > 0 }
            ?.let(::ofEpochMilli)
            ?: ofEpochMilli(System.currentTimeMillis())
        val time = format(
            "%02d%02d%02d.%02d",
            timestamp.hour,
            timestamp.minute,
            timestamp.second,
            timestamp.centisecond,
        )
        val date = format(
            "%02d%02d%02d",
            timestamp.day,
            timestamp.month,
            timestamp.year % 100,
        )

        val latitude = degreesToNmea(fix.latitudeDegrees, latitude = true)
        val longitude = degreesToNmea(fix.longitudeDegrees, latitude = false)
        val hdop = fix.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { min(50.0, max(0.5, it / 5.0)) }
            ?: 1.0
        val altitude = fix.altitudeMeters
            ?.takeIf { it.isFinite() }
            ?.let { format("%.1f", it) }
            ?: "0.0"

        val quality = fix.fixQuality ?: LEGACY_FIX_QUALITY
        val satellites = fix.satellitesUsed?.let { format("%02d", it) } ?: LEGACY_SATELLITES
        val separation = fix.geoidSeparationMeters?.takeIf { it.isFinite() }?.let { format("%.1f", it) } ?: "0.0"
        val ggaBody = "GPGGA,$time,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},$quality,$satellites,${format("%.1f", hdop)}," +
            "$altitude,M,$separation,M,,"
        val speedKnots = fix.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { format("%.2f", it * KNOTS_PER_METER_PER_SECOND) }
            ?: "0.00"
        // Without a GPS direction (for example while parked) the course stays empty: 0.00 would say north.
        val course = fix.bearingDegrees
            ?.takeIf { it.isFinite() }
            ?.let { format("%.2f", it) }
            .orEmpty()
        val rmcBody = "GPRMC,$time,A,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},$speedKnots,$course,$date,,"

        return "$${ggaBody}*${checksum(ggaBody)}\r\n$${rmcBody}*${checksum(rmcBody)}\r\n"
    }

    private fun degreesToNmea(value: Double, latitude: Boolean): NmeaCoordinate {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * MINUTES_PER_DEGREE
        if (minutes >= MINUTES_PER_DEGREE - MINUTE_ROUNDING_TOLERANCE) {
            degrees += 1
            minutes = 0.0
        }
        val degreeText = if (latitude) format("%02d", degrees) else format("%03d", degrees)
        val hemisphere = if (latitude) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
        return NmeaCoordinate("$degreeText${format("%07.4f", minutes)}", hemisphere)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return format("%02X", value)
    }

    private fun ofEpochMilli(millis: Long): Timestamp = Timestamp(millis)

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private data class NmeaCoordinate(val value: String, val hemisphere: String)

    private class Timestamp(millis: Long) {
        // java.time needs API 26; a proleptic Gregorian calendar in UTC gives the same fields.
        private val fields = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            timeInMillis = millis
        }

        val hour: Int = fields.get(Calendar.HOUR_OF_DAY)
        val minute: Int = fields.get(Calendar.MINUTE)
        val second: Int = fields.get(Calendar.SECOND)
        val centisecond: Int = fields.get(Calendar.MILLISECOND) / 10
        val day: Int = fields.get(Calendar.DAY_OF_MONTH)
        val month: Int = fields.get(Calendar.MONTH) + 1
        val year: Int = fields.get(Calendar.YEAR)
    }

    /** Sent unless precise positioning supplies the real value. */
    private const val LEGACY_FIX_QUALITY = 1
    private const val LEGACY_SATELLITES = "08"

    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MINUTE_ROUNDING_TOLERANCE = 0.00005
}

/**
 * Accessory side of iAP2 LocationInformation on one link: starts on 0xFFFA, sends the latest fix
 * on every [tick] (about once a second), and stops on 0xFFFC. Subscription state belongs to this
 * iAP2 link: a request received on Bluetooth must never authorize output on the Wi-Fi tunnel.
 */
class Iap2LocationReporter(
    private val provider: Iap2LocationProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * Keep a fix flowing even when the phone never sent 0xFFFA. A phone that does not subscribe
     * has to use its own receiver inside the cabin, which is far less accurate than the head
     * unit's roof antenna; the unsolicited push gives the navigation app a usable alternative.
     * A real subscription always wins: its sentence selection and stop request stay authoritative.
     */
    private val unsolicited: Boolean = false,
) {
    private var active = false
    private var unsolicitedRunning = false
    /** Set by an explicit 0xFFFC: the phone asked us to stop, so the unsolicited path stays down. */
    private var stoppedByPhone = false
    private var sentLogged = false
    private var lastAttemptNanos = 0L
    /** Interval the phone asked for in its 0xFFFA request, clamped to what the source can honour. */
    private var pollIntervalMillis = POLL_INTERVAL_MILLIS

    /** Handles 0xFFFA/0xFFFC; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
            val components = Iap2LocationMessages.requestedComponents(frame)
            pollIntervalMillis = (Iap2LocationMessages.requestedIntervalMillis(frame) ?: POLL_INTERVAL_MILLIS)
                .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
            onProgress(
                "iap2 rx=0xfffa start-location-information components=$components " +
                    "interval=${pollIntervalMillis}ms",
            )
            stoppedByPhone = false
            provider?.onRequested(components)
            start(send)
            true
        }
        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
            onProgress("iap2 rx=0xfffc stop-location-information")
            active = false
            sentLogged = false
            provider?.stop()
            unsolicitedRunning = false
            stoppedByPhone = true
            true
        }
        else -> false
    }

    /** Sends the latest fix once a second while this link has an active request. */
    fun tick(send: (Iap2Frame) -> Unit) {
        val interval = when {
            active -> pollIntervalMillis
            pushUnsolicited() -> UNSOLICITED_INTERVAL_MILLIS
            else -> return
        }
        if (sinceAttemptMillis() >= interval) sendLatest(send)
    }

    /** Wakes the loop when the next requested fix is due. */
    fun pollTimeout(remainingMillis: Long): Long = when {
        active -> min(remainingMillis, (pollIntervalMillis - sinceAttemptMillis()).coerceAtLeast(1))
        unsolicited -> min(remainingMillis, (UNSOLICITED_INTERVAL_MILLIS - sinceAttemptMillis()).coerceAtLeast(1))
        else -> remainingMillis
    }

    /** True once the unsolicited path has a running source; a no-op while a real subscription is active. */
    private fun pushUnsolicited(): Boolean {
        if (!unsolicited || provider == null || stoppedByPhone) return false
        if (!unsolicitedRunning) {
            unsolicitedRunning = startProvider()
            if (unsolicitedRunning) onProgress("iap2 location unsolicited push started")
        }
        return unsolicitedRunning
    }

    private fun sinceAttemptMillis() = (nanoTime() - lastAttemptNanos) / 1_000_000

    private fun start(send: (Iap2Frame) -> Unit) {
        active = startProvider()
        // A real subscription replaces the unsolicited stream: one cadence, phone-chosen sentences.
        unsolicitedRunning = false
        sentLogged = false
        if (active) sendLatest(send)
    }

    private fun startProvider(): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started -> if (!started) onProgress("iap2 location provider did not start") }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    private fun sendLatest(send: (Iap2Frame) -> Unit) {
        lastAttemptNanos = nanoTime()
        val sentence = provider?.latestNmea() ?: return
        send(Iap2LocationMessages.locationInformation(sentence))
        if (!sentLogged) {
            sentLogged = true
            onProgress("iap2 tx=0xfffb location-information")
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        /** Same once-a-second cadence as a subscription; phones tolerate slower, not faster. */
        const val UNSOLICITED_INTERVAL_MILLIS = 1_000L
        /** The GNSS chip updates at 1 Hz here; asking faster only burns power. */
        const val MIN_INTERVAL_MILLIS = 250L
        /** A phone asking for minutes still gets a usable stream. */
        const val MAX_INTERVAL_MILLIS = 5_000L
    }
}

object Iap2LocationMessages {
    const val START_LOCATION_INFORMATION = 0xfffa
    const val LOCATION_INFORMATION = 0xfffb
    const val STOP_LOCATION_INFORMATION = 0xfffc

    /** 0xFFFA selector for `$PASCD`; the matching IdentificationInformation flag is 20. */
    const val VEHICLE_SPEED_DATA = 4

    /** The parameter ids of a 0xFFFA request (the sentence types asked for), or none if unreadable. */
    fun requestedComponents(frame: Iap2Frame): Set<Int> =
        runCatching { frame.body().asList().map { it.id }.toSortedSet() }.getOrDefault(emptySet())

    /**
     * The fastest interval the phone asked for: parameter 0 is the message interval, 0x8001..0x8007
     * are per-sentence intervals. Null when the request carries none.
     */
    fun requestedIntervalMillis(frame: Iap2Frame): Long? = runCatching {
        frame.body().asList()
            .filter { it.id == 0 || it.id in 0x8001..0x8007 }
            .map { field -> field.payload.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) } }
            .filter { it > 0 }
            .minOrNull()
    }.getOrNull()

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2ControlMessages.locationInformation(nmeaSentence)
    }
}
