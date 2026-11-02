package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ProtocolException

/**
 * The control channel is a plain TCP socket. Before the cap, a peer could hold a partial header open forever and
 * make the caller grow its buffer without limit; these cases pin the bound and the silence for ordinary messages.
 */
class ControlReceiveBoundsTest {
    private fun request(header: String, contentLength: Int, body: String = "") =
        header.toByteArray(Charsets.US_ASCII) +
            "Content-Length: $contentLength\r\n\r\n".toByteArray(Charsets.US_ASCII) +
            body.toByteArray(Charsets.US_ASCII)

    @Test
    fun anOrdinaryRequestStillParses() {
        val buffer = "POST /feedback RTSP/1.0\r\nContent-Length: 4\r\n\r\nbody".toByteArray(Charsets.US_ASCII)

        val parsed = RtspMessage.parseMessages(buffer)

        assertEquals(1, parsed.messages.size)
        assertEquals("POST", parsed.messages[0].method)
        assertEquals("/feedback", parsed.messages[0].path)
        assertEquals("body", String(parsed.messages[0].body, Charsets.US_ASCII))
        assertEquals(0, parsed.rest.size)
    }

    @Test
    fun aPartialHeaderUnderTheCapIsRetainedSilently() {
        // No CRLFCRLF yet, but far below the cap: this is a normal split read, not an attack.
        val buffer = "POST /feedback RTSP/1.0\r\nContent-Len".toByteArray(Charsets.US_ASCII)

        val parsed = RtspMessage.parseMessages(buffer)

        assertEquals(0, parsed.messages.size)
        assertEquals(buffer.size, parsed.rest.size)
    }

    @Test
    fun aHeaderOverTheCapIsRejected() {
        val buffer = ("POST /feedback RTSP/1.0\r\nX-Filler: " + "a".repeat(20 * 1024))
            .toByteArray(Charsets.US_ASCII)

        assertThrows(ProtocolException::class.java) { RtspMessage.parseMessages(buffer) }
    }

    @Test
    fun aBodyOverTheCapIsRejectedOutright() {
        val buffer = request("POST /feedback RTSP/1.0\r\n", contentLength = 8 * 1024 * 1024)

        val error = assertThrows(ProtocolException::class.java) { RtspMessage.parseMessages(buffer) }

        assertEquals(true, error.message!!.contains("RTSP body"))
    }

    @Test
    fun aBodyStillInFlightIsWaitedForRatherThanRejected() {
        // Content-Length is legal, the body has not all arrived: keep it buffered.
        val buffer = request("POST /feedback RTSP/1.0\r\n", contentLength = 100, body = "short")

        val parsed = RtspMessage.parseMessages(buffer)

        assertEquals(0, parsed.messages.size)
        assertEquals(buffer.size, parsed.rest.size)
    }

    @Test
    fun noHeaderEndAndNoDataIsEmpty() {
        val parsed = RtspMessage.parseMessages(ByteArray(0))

        assertNull(parsed.messages.firstOrNull())
        assertEquals(0, parsed.rest.size)
    }
}
