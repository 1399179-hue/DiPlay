package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe reply is a single status line. Before the cap, readLine() would keep growing its buffer while a service
 * stalled without a terminator; the line is read byte by byte now, and anything past the cap is a failure.
 */
class CarPlayBonjourProbeLineTest {
    @Test
    fun anOrdinaryCrLfStatusLineIsRead() {
        val line = CarPlayBonjourProbe.readLineForTest("HTTP/1.1 200 OK\r\nServer: AirTun\r\n".toByteArray())

        assertEquals("HTTP/1.1 200 OK", line)
    }

    @Test
    fun anLfOnlyStatusLineIsRead() {
        val line = CarPlayBonjourProbe.readLineForTest("HTTP/1.0 403 Forbidden\n".toByteArray())

        assertEquals("HTTP/1.0 403 Forbidden", line)
    }

    @Test
    fun aLineWithoutAnyTerminatorIsReturnedAtEof() {
        val line = CarPlayBonjourProbe.readLineForTest("HTTP/1.1 200 OK".toByteArray())

        assertEquals("HTTP/1.1 200 OK", line)
    }

    @Test
    fun anEmptyReplyIsNull() {
        assertEquals(null, CarPlayBonjourProbe.readLineForTest(ByteArray(0)))
    }

    @Test
    fun aLinePastTheCapIsRejected() {
        val bytes = ByteArray(9 * 1024) { 'a'.code.toByte() }

        val error = assertThrows(java.io.IOException::class.java) {
            CarPlayBonjourProbe.readLineForTest(bytes)
        }

        assertTrue(error.message!!.contains("status line"))
    }
}
