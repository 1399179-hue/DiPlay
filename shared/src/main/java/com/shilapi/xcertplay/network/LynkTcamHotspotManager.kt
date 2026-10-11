package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import kotlin.text.Charsets

/**
 * Runs wireless CarPlay over the car's own factory hotspot.
 *
 * This is the Lynk & Co 03 path and it exists because the alternative does not work on this head
 * unit. The car does not let the app create the access point: the AP lives in the TCAM module, the
 * head unit joins it as an ordinary Wi-Fi client, and Android never publishes the SSID,
 * passphrase, channel or BSSID through any public API. `ManualHotspotManager` therefore has
 * nothing to read and falls back to a placeholder identity, which is why the handset associates
 * and then goes quiet.
 *
 * What this manager does instead, in the same order the factory build does it:
 *
 *  1. read the live configuration out of the TCAM service (see [LynkTcamHotspotClient]);
 *  2. reuse the hotspot link if the head unit is already on it;
 *  3. otherwise wait for the internal radio, and ask the platform to join the hotspot - first with
 *     a network request, then with a network suggestion when the vendor image has no request UI;
 *  4. bind the process to that network and hold a high-performance Wi-Fi lock;
 *  5. report the SSID/passphrase/channel/BSSID and the address the iPhone must dial back on.
 *
 * Every failure carries a sentence naming what the driver has to change, because the alternative
 * on this firmware is a session that connects and then silently produces no audio.
 */
internal class LynkTcamHotspotManager(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
    private val allowWifiRequest: Boolean = true,
    private val onWifiRequest: () -> Unit = {},
    private val onWifiRequestNotAccepted: () -> Unit = {},
    private val onWifiDisabled: () -> Unit = {},
    private val savedSsid: String? = null,
    private val savedPassphrase: String? = null,
    private val onWifiInitializing: () -> Unit = {},
) : WirelessHotspotManager {

    private val appContext = context.applicationContext

    private val lifecycleLock = Any()

    @Volatile
    private var closed = false

    /** True once the platform actually connected the requested hotspot. Exposed for diagnostics. */
    @Volatile
    var requestedWifiConnected: Boolean = false
        private set

    /** True once the app asked the platform to connect at least once. */
    @Volatile
    var wifiRequestAttempted: Boolean = false
        private set

    private var credentialSource = LynkTcamCredentials.Source.TCAM

    private var wifiConnection: LynkTcamWifiConnection? = null
    private var wifiReadiness: LynkTcamWifiReadiness? = null
    private var wifiRequest: LynkTcamWifiRequest? = null
    private var wifiSuggestion: LynkTcamWifiSuggestion? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LynkTcamHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(!closed) { "LynkTcamHotspotManager is closed" }

        val startedMillis = SystemClock.elapsedRealtime()
        val readDeadline = startedMillis + minOf(timeoutMillis, INITIAL_READ_DEADLINE_MILLIS)
        var snapshot = LynkTcamHotspotClient.read(appContext, minOf(timeoutMillis, READ_STEP_MILLIS))
        // Poll briefly: the module answers before it has published a configuration on some boots,
        // and giving up on the first "enabled but nothing readable" loses a session that would
        // otherwise start two hundred milliseconds later.
        while (!closed && (
                snapshot == null ||
                    (snapshot.enabled == true &&
                        (snapshot.ssid == null || snapshot.passphrase == null))
                )
        ) {
            val remaining = readDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(POLL_INTERVAL_MILLIS, remaining))
            val afterSleep = readDeadline - SystemClock.elapsedRealtime()
            if (afterSleep <= 0) break
            snapshot = LynkTcamHotspotClient.read(appContext, minOf(READ_STEP_MILLIS, afterSleep))
        }
        check(!closed) { "LynkTcamHotspotManager is closed" }

        val current = snapshot
            ?: throw IOException("无法读取领克原厂热点，请确认车机热点已开启后重新连接。")
        when (current.enabled) {
            false -> throw IOException("领克原厂热点已关闭，请在车机设置中开启后重新连接。")
            null -> throw IOException("领克原厂热点状态暂不可用，请稍后重新连接。")
            true -> Unit
        }

        var credentials = resolveCredentials(current)
        val ssid = credentials.ssid?.takeIf { it.isNotBlank() }
            ?: throw IOException("无法读取领克热点名称，请检查原厂热点设置后重新连接。")

        val connection = synchronized(lifecycleLock) {
            check(!closed) { "LynkTcamHotspotManager is closed" }
            check(wifiConnection == null) { "LynkTcamHotspotManager is already starting or started" }
            LynkTcamWifiConnection(appContext, onDiagnostic).also { wifiConnection = it }
        }

        try {
            var backhaul = awaitExistingTcamNetwork(
                remainingBudgetMillis = remainingBudget(timeoutMillis, startedMillis),
                isClosed = { closed },
                connect = { connectExistingHotspot(connection, ssid) },
            )

            if (backhaul == null && allowWifiRequest) {
                val readinessBudget = remainingBudget(timeoutMillis, startedMillis)
                if (readinessBudget > 0) {
                    val readiness = synchronized(lifecycleLock) {
                        check(!closed) { "LynkTcamHotspotManager is closed" }
                        LynkTcamWifiReadiness(
                            context = appContext,
                            onWaiting = onWifiDisabled,
                            onDiagnostic = onDiagnostic,
                            onInitializing = onWifiInitializing,
                        ).also { wifiReadiness = it }
                    }
                    val result = try {
                        readiness.awaitEnabled(readinessBudget)
                    } finally {
                        readiness.close()
                        synchronized(lifecycleLock) {
                            if (wifiReadiness === readiness) wifiReadiness = null
                        }
                    }
                    if (closed) throw IllegalStateException("LynkTcamHotspotManager is closed")
                    when (result) {
                        LynkTcamWifiReadiness.Result.CANCELLED -> throw IllegalStateException(
                            "LynkTcamHotspotManager is closed",
                        )
                        LynkTcamWifiReadiness.Result.INITIALIZATION_REJECTED -> throw IOException(
                            "车机系统拒绝开启内部 Wi-Fi；尚未建立车载热点连接，请保留本次诊断日志。",
                        )
                        LynkTcamWifiReadiness.Result.INITIALIZATION_DENIED -> throw IOException(
                            "车机系统未允许本应用开启内部 Wi-Fi；尚未建立车载热点连接，" +
                                "请保留本次诊断日志。",
                        )
                        LynkTcamWifiReadiness.Result.INITIALIZATION_FAILED -> throw IOException(
                            "初始化车机内部 Wi-Fi 失败；尚未建立车载热点连接，请保留本次诊断日志。",
                        )
                        LynkTcamWifiReadiness.Result.TIMED_OUT -> throw IOException(
                            if (LynkTcamWifiReadiness.supportsBuiltinInitialization(appContext)) {
                                "车机内部 Wi-Fi 初始化超时；系统尚未报告开启，尚未建立车载热点连接，" +
                                    "请保留本次诊断日志。"
                            } else {
                                "等待车机内部 Wi-Fi 开启超时；尚未建立车载热点连接，" +
                                    "请开启内部 Wi-Fi 后重试。"
                            },
                        )
                        LynkTcamWifiReadiness.Result.UNAVAILABLE -> throw IOException(
                            "无法读取车机内部 Wi-Fi 状态，尚未建立车载热点连接，请检查系统 Wi-Fi 设置。",
                        )
                        LynkTcamWifiReadiness.Result.ENABLED_AFTER_WAIT -> {
                            // The radio only just came up, so the hotspot may only just have been
                            // published: re-read instead of advertising the stale snapshot.
                            onWifiRequest()
                            val reReadBudget = remainingBudget(timeoutMillis, startedMillis)
                            if (reReadBudget > 0) {
                                val reRead = LynkTcamHotspotClient.read(
                                    appContext,
                                    minOf(reReadBudget, READ_STEP_MILLIS),
                                ) ?: throw IOException(
                                    "开启车机 Wi-Fi 后无法读取当前热点，请返回应用重新连接。",
                                )
                                if (reRead.enabled != true) {
                                    throw IOException("领克原厂热点尚未就绪，请保持车载热点开启后重新连接。")
                                }
                                credentials = resolveCredentials(reRead)
                                val reReadSsid = credentials.ssid
                                    ?.takeIf { it.isNotBlank() && '\u0000' !in it }
                                    ?: throw IOException(
                                        "无法读取领克热点名称，请检查原厂热点设置后重新连接。",
                                    )
                                backhaul = awaitExistingTcamNetwork(
                                    remainingBudgetMillis = remainingBudget(
                                        timeoutMillis,
                                        startedMillis,
                                    ),
                                    isClosed = { closed },
                                    connect = { connectExistingHotspot(connection, reReadSsid) },
                                )
                            }
                        }
                        LynkTcamWifiReadiness.Result.ENABLED -> Unit
                    }
                }
            }

            return finishStart(credentials, connection, backhaul, timeoutMillis, startedMillis)
        } catch (failure: Exception) {
            connection.close()
            synchronized(lifecycleLock) {
                wifiRequest?.let { it.close() }
                wifiRequest = null
                wifiSuggestion?.let { it.close() }
                wifiSuggestion = null
                if (wifiConnection === connection) wifiConnection = null
            }
            throw failure
        }
    }

    override fun close() {
        val readiness: LynkTcamWifiReadiness?
        val connection: LynkTcamWifiConnection?
        val request: LynkTcamWifiRequest?
        val suggestion: LynkTcamWifiSuggestion?
        synchronized(lifecycleLock) {
            closed = true
            readiness = wifiReadiness
            wifiReadiness = null
            connection = wifiConnection
            request = wifiRequest
            suggestion = wifiSuggestion
            wifiConnection = null
            wifiRequest = null
            wifiSuggestion = null
        }
        runCatching { readiness?.close() }
        runCatching { connection?.close() }
        runCatching { request?.close() }
        runCatching { suggestion?.close() }
    }

    private fun remainingBudget(timeoutMillis: Long, startedMillis: Long): Long =
        timeoutMillis - (SystemClock.elapsedRealtime() - startedMillis)

    private fun connectExistingHotspot(
        connection: LynkTcamWifiConnection,
        ssid: String,
    ): LynkTcamNetwork.Backhaul? =
        connection.connectExisting(ssid)?.let { LynkTcamNetwork.resolveConnected(appContext, it) }

    /**
     * Uses the link as it stands, or asks the platform to connect it, then builds the payload.
     *
     * The order is deliberate: touching the platform first would silently move the head unit off a
     * hotspot it is already working on.
     */
    private fun finishStart(
        credentials: LynkTcamHotspotClient.Snapshot,
        connection: LynkTcamWifiConnection,
        existingBackhaul: LynkTcamNetwork.Backhaul?,
        timeoutMillis: Long,
        startedMillis: Long,
    ): WirelessHotspotInfo {
        val backhaul = existingBackhaul ?: requestCurrentHotspot(
            snapshot = credentials,
            connection = connection,
            remainingMillis = remainingBudget(timeoutMillis, startedMillis),
        ) ?: throw IOException(
            "车机内部 Wi-Fi 尚未连接当前车载热点或未取得本机地址，请完成 Wi-Fi 连接后重试。",
        )
        val info = tcamHotspotInfo(credentials, backhaul)
        synchronized(lifecycleLock) {
            if (closed || wifiConnection !== connection) {
                throw IllegalStateException("LynkTcamHotspotManager is closed")
            }
        }
        onDiagnostic(
            "TCAM hotspot enabled=true" +
                " configReadable=${credentialSource == LynkTcamCredentials.Source.TCAM}" +
                " credentialsSource=${credentialSource.diagnostic}" +
                " path=existing-wifi iface=${info.interfaceName}" +
                " host=${backhaul.address.hostAddress}" +
                " channel=${info.channel} band=${info.bandLabel}",
        )
        return info
    }

    /**
     * Merges the live configuration with the saved one, and remembers which one was used.
     *
     * When the saved pair wins, the snapshot is rebuilt so the rest of the flow (channel, band,
     * BSSID) still comes from the car rather than from the settings screen.
     */
    private fun resolveCredentials(snapshot: LynkTcamHotspotClient.Snapshot): LynkTcamHotspotClient.Snapshot {
        val selected = LynkTcamCredentials.select(
            liveSsid = snapshot.ssid,
            livePassphrase = snapshot.passphrase,
            savedSsid = savedSsid,
            savedPassphrase = savedPassphrase,
        )
        credentialSource = selected.source
        onDiagnostic("TCAM hotspot credentials source=${selected.source.diagnostic}")
        return if (selected.source == LynkTcamCredentials.Source.TCAM) {
            snapshot
        } else {
            LynkTcamHotspotClient.Snapshot(
                state = snapshot.state,
                ssid = selected.ssid,
                passphrase = selected.passphrase,
                bssid = snapshot.bssid,
                band = snapshot.band,
                channel = snapshot.channel,
            )
        }
    }

    /**
     * Asks the platform to join the hotspot.
     *
     * A successful request stays registered: the network exists only while its callback does, and
     * unregistering it here would drop the link the AirPlay session is about to use.
     */
    private fun requestCurrentHotspot(
        snapshot: LynkTcamHotspotClient.Snapshot,
        connection: LynkTcamWifiConnection,
        remainingMillis: Long,
    ): LynkTcamNetwork.Backhaul? {
        if (!allowWifiRequest || remainingMillis <= 0) return null
        val ssid = snapshot.ssid ?: return null
        val passphrase = snapshot.passphrase ?: return null

        val startedMillis = SystemClock.elapsedRealtime()
        var requestEmitted = false
        var requestUnsupportedUi = false

        val request = synchronized(lifecycleLock) {
            check(!closed) { "LynkTcamHotspotManager is closed" }
            LynkTcamWifiRequest(appContext) { detail ->
                onDiagnostic(detail)
                if (detail == REQUESTING_DIAGNOSTIC) {
                    wifiRequestAttempted = true
                    requestEmitted = true
                    onWifiRequest()
                }
                if (detail == UNSUPPORTED_UI_DIAGNOSTIC) requestUnsupportedUi = true
            }.also { wifiRequest = it }
        }

        try {
            val network = request.connect(
                ssid = ssid,
                password = passphrase,
                timeoutMillis = minOf(remainingMillis, REQUEST_TIMEOUT_MILLIS),
            )
            if (network == null) {
                val backhaul = if (requestUnsupportedUi) {
                    // The vendor image has no request confirmation screen, so the platform never
                    // answers: a network suggestion is the only route that works here.
                    requestSuggestedHotspot(
                        ssid = ssid,
                        password = passphrase,
                        connection = connection,
                        remainingMillis = remainingMillis -
                            (SystemClock.elapsedRealtime() - startedMillis),
                    )
                } else {
                    null
                }
                releaseRequest(request)
                if (requestEmitted) onWifiRequestNotAccepted()
                return backhaul
            }

            check(!closed) { "LynkTcamHotspotManager is closed" }
            val bound = connection.connectRequested(network)
            if (bound == null) {
                releaseRequest(request)
            } else {
                val resolved = LynkTcamNetwork.resolveConnected(appContext, bound)
                if (resolved != null) {
                    requestedWifiConnected = true
                    return resolved
                }
                releaseRequest(request)
                if (!requestEmitted) return null
            }
            onWifiRequestNotAccepted()
            return null
        } catch (failure: Throwable) {
            releaseRequest(request)
            if (requestEmitted) onWifiRequestNotAccepted()
            throw failure
        }
    }

    /**
     * The fallback that actually works on this firmware: submit a network suggestion and wait for
     * the head unit to join.
     */
    private fun requestSuggestedHotspot(
        ssid: String,
        password: String,
        connection: LynkTcamWifiConnection,
        remainingMillis: Long,
    ): LynkTcamNetwork.Backhaul? {
        if (remainingMillis <= 0) return null
        val startedMillis = SystemClock.elapsedRealtime()

        val suggestion = synchronized(lifecycleLock) {
            check(!closed) { "LynkTcamHotspotManager is closed" }
            LynkTcamWifiSuggestion(appContext, onDiagnostic).also { wifiSuggestion = it }
        }

        try {
            onWifiRequest()
            wifiRequestAttempted = true
            if (suggestion.submit(ssid, password)) {
                val backhaul = awaitExistingTcamNetwork(
                    remainingBudgetMillis = remainingMillis -
                        (SystemClock.elapsedRealtime() - startedMillis),
                    isClosed = { closed },
                    connect = { connectExistingHotspot(connection, ssid) },
                    maxWaitMillis = SUGGESTION_TIMEOUT_MILLIS,
                )
                if (backhaul != null) {
                    synchronized(lifecycleLock) {
                        if (closed || wifiSuggestion !== suggestion) {
                            throw IllegalStateException("LynkTcamHotspotManager is closed")
                        }
                        requestedWifiConnected = true
                        onDiagnostic("TCAM Wi-Fi suggestion state=connected")
                    }
                    releaseSuggestion(suggestion)
                    return backhaul
                }
                onDiagnostic("TCAM Wi-Fi suggestion state=connection-timed-out")
            }
            releaseSuggestion(suggestion)
            onWifiRequestNotAccepted()
            return null
        } catch (failure: Throwable) {
            releaseSuggestion(suggestion)
            onWifiRequestNotAccepted()
            throw failure
        }
    }

    private fun releaseRequest(request: LynkTcamWifiRequest) {
        runCatching { request.close() }
        synchronized(lifecycleLock) {
            if (wifiRequest === request) wifiRequest = null
        }
    }

    private fun releaseSuggestion(suggestion: LynkTcamWifiSuggestion) {
        runCatching { suggestion.close() }
        synchronized(lifecycleLock) {
            if (wifiSuggestion === suggestion) wifiSuggestion = null
        }
    }

    private companion object {
        /** The module needs a moment to publish its configuration after reporting "enabled". */
        const val INITIAL_READ_DEADLINE_MILLIS = 4_500L

        /** One TCAM read is bounded, so the poll loop stays responsive to cancellation. */
        const val READ_STEP_MILLIS = 1_500L

        /** A network request that needs a visible confirmation is given a short, fixed window. */
        const val REQUEST_TIMEOUT_MILLIS = 30_000L

        /** A suggestion waits for the platform's own scan/associate cycle. */
        const val SUGGESTION_TIMEOUT_MILLIS = 30_000L

        const val REQUESTING_DIAGNOSTIC = "TCAM Wi-Fi request state=requesting"
        const val UNSUPPORTED_UI_DIAGNOSTIC = "TCAM Wi-Fi request state=unsupported-ui"
    }
}

/**
 * Polls until [connect] produces something, or the budget runs out.
 *
 * The head unit does not always join the hotspot immediately, and the join is signalled by nothing
 * at all, so this is a poll rather than a callback. A short wait between attempts keeps the
 * overall timeout honest.
 */
internal fun <T : Any> awaitExistingTcamNetwork(
    remainingBudgetMillis: Long,
    isClosed: () -> Boolean,
    connect: () -> T?,
    nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    waitMillis: (Long) -> Unit = { Thread.sleep(it) },
    maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
): T? {
    require(maxWaitMillis >= 0) { "maxWaitMillis must not be negative" }
    val budget = remainingBudgetMillis.coerceIn(0L, maxWaitMillis)
    val startedMillis = nowMillis()
    while (!isClosed()) {
        connect()?.let { return it }
        val remaining = budget - (nowMillis() - startedMillis)
        if (remaining <= 0) return null
        waitMillis(minOf(POLL_INTERVAL_MILLIS, remaining))
        if (isClosed()) throw IllegalStateException("LynkTcamHotspotManager is closed")
        if (nowMillis() - startedMillis >= budget) return null
    }
    throw IllegalStateException("LynkTcamHotspotManager is closed")
}

private const val POLL_INTERVAL_MILLIS = 200L

private const val DEFAULT_MAX_WAIT_MILLIS = 4_000L

/**
 * Builds the iAP2 wireless payload for a TCAM hotspot.
 *
 * Everything the handset needs is validated here rather than downstream, because each failure
 * looks the same from the outside: the phone associates and then never opens the AirPlay port.
 * A channel is only reported when it is one the advertised band can actually carry - otherwise the
 * handset rejects the network silently - and the BSSID only when it is a real unicast address
 * rather than the module's "no address" placeholder.
 */
private fun tcamHotspotInfo(
    snapshot: LynkTcamHotspotClient.Snapshot,
    network: LynkTcamNetwork.Backhaul,
): WirelessHotspotInfo {
    if (!network.viaWifi || network.interfaceName.isBlank()) {
        throw IOException("车机内部 Wi-Fi 尚未建立，不能开始无线 CarPlay。")
    }
    val ssid = snapshot.ssid
        ?.takeIf { it.isNotBlank() && '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size <= 32 }
        ?: throw IOException("无法读取领克热点名称，请检查原厂热点设置后重新连接。")
    val passphrase = snapshot.passphrase
        ?.takeIf { it.length in 8..63 && '\u0000' !in it }
        ?: throw IOException("无法读取有效的领克热点密码，请检查原厂热点设置后重新连接。")

    val band = legacyHotspotBandToSoftApBand(snapshot.band)
    val reportedChannel = snapshot.channel?.takeIf { it in 1..196 }
    val channel = if (reportedChannel != null &&
        (band == 1 || band == 2 || tcamChannelToFrequencyMhz(reportedChannel, band) != null)
    ) {
        reportedChannel
    } else {
        0
    }
    val bssid = snapshot.bssid?.takeIf {
        TCAM_BSSID.matches(it) &&
            it != "00:00:00:00:00:00" &&
            it != "02:00:00:00:00:00"
    }
    return WirelessHotspotInfo(
        ssid = ssid,
        passphrase = passphrase,
        security = Iap2WirelessSecurity.WPA_WPA2,
        channel = channel,
        frequencyMHz = tcamChannelToFrequencyMhz(channel, band),
        bssid = bssid,
        interfaceName = network.interfaceName,
        hostAddress = network.address,
        bandLabel = tcamBandLabel(band) ?: "Auto",
        backend = WirelessHotspotBackend.LYNK_TCAM_WIFI,
    )
}

private val TCAM_BSSID = Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
