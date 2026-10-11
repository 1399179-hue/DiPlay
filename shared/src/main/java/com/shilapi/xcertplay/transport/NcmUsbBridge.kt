package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    private var resyncLogged = false
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val readRequests = UsbRequestQueue(connection, "ncm-read-reaper")
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val transferred = connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes",
            )
        }
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        readRequests.close()
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                when (buffered[0].toInt() and 0xff) {
                    0 -> {
                        buffered.copyInto(buffered, 0, 1, bufferedSize)
                        bufferedSize -= 1
                    }
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> throw failSession("Invalid NTB16 short-packet pad")
                }
                optionalShortPacketPad = false
            }
            if (bufferedSize < NTB16_HEADER_BYTES) return
            val blockLength = blockLengthAt(buffered, 0, bufferedSize)
            if (blockLength < 0) {
                // A USB short transfer may leave the buffer a few bytes off a block boundary.
                // Re-synchronise on the next credible header rather than latching the transport
                // dead, because failSession() is one-shot and both bridge directions share this
                // instance.
                if (resyncToBlockStart()) continue
                return
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - blockLength
            buffered.copyInto(buffered, 0, blockLength, bufferedSize)
            bufferedSize = remaining
            optionalShortPacketPad = blockLength % USB_PACKET_SIZE == 0
        }
    }

    /**
     * Returns the NTB16 block length at [offset], or -1 when those bytes are not a credible block
     * start.
     *
     * The check covers the NTH16 signature and header-length field plus the NDP16 signature, so an
     * Ethernet payload that happens to contain the 4-byte signature cannot be mistaken for a block
     * boundary while re-synchronising.
     */
    private fun blockLengthAt(source: ByteArray, offset: Int, size: Int): Int {
        if (offset + NTB16_HEADER_BYTES > size) return -1
        if (readU32(source, offset) != Ntb16Codec.NTH16_SIG) return -1
        if (readU16(source, offset + 4) != NTB16_HEADER_BYTES) return -1
        val blockLength = readU16(source, offset + 8)
        if (blockLength < NTB16_MIN_BLOCK_BYTES || blockLength > NTB16_MAX_BLOCK_BYTES) return -1
        // Mirror Ntb16Codec.parse, which reads the first NDP16 table through the header's own
        // wNdpIndex instead of assuming it sits immediately after the NTH16 header.
        val ndpOffset = readU16(source, offset + 10)
        if (ndpOffset < NTB16_HEADER_BYTES || ndpOffset > NTB16_MAX_NDP_OFFSET_BYTES) return -1
        // Check the NDP16 signature as soon as those bytes have arrived, so an Ethernet payload that
        // merely happens to contain the NTH16 signature cannot pass as a block boundary.
        if (offset + ndpOffset + 4 <= size &&
            readU32(source, offset + ndpOffset) != Ntb16Codec.NDP16_SIG
        ) {
            return -1
        }
        return blockLength
    }

    /**
     * Drops leading bytes until a credible NTB16 header starts the buffer.
     *
     * Returns true when the caller should re-run its framing loop. When no candidate is present the
     * buffer keeps only the bytes that could still begin a header split across two USB reads.
     */
    private fun resyncToBlockStart(): Boolean {
        var offset = 1
        while (offset + NTB16_HEADER_BYTES <= bufferedSize) {
            if (blockLengthAt(buffered, offset, bufferedSize) >= 0) {
                if (!resyncLogged) {
                    resyncLogged = true
                    Log.w(
                        IphoneCarPlayConfiguration.TAG,
                        "ncm resynchronised on an NTB16 header after $offset stray byte(s)",
                    )
                }
                val remaining = bufferedSize - offset
                buffered.copyInto(buffered, 0, offset, bufferedSize)
                bufferedSize = remaining
                return true
            }
            offset++
        }
        val keep = minOf(bufferedSize, NTB16_HEADER_BYTES - 1)
        val remaining = bufferedSize - keep
        buffered.copyInto(buffered, 0, remaining, bufferedSize)
        bufferedSize = keep
        return false
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { readRequests.queue(current, it) }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            val completed = try {
                readRequests.await(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val NTB16_HEADER_BYTES = 12
        private const val NTB16_MIN_BLOCK_BYTES = 28
        private const val NTB16_MAX_BLOCK_BYTES = 0xffff
        private const val NTB16_MAX_NDP_OFFSET_BYTES = 64
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = connection.setInterface(function.data)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    onDiagnostic,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
