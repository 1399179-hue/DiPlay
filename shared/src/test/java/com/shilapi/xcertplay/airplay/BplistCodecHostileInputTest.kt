package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A decoded body comes straight off the network, so the codec has to bound what it reads and allocates.
 *
 * Each case below is a plist a hostile or broken peer could send. They assert an exception rather than a slow
 * crash, so the failure happens while the body is still small enough to reject.
 */
class BplistCodecHostileInputTest {
    @Test
    fun aSelfReferencingArrayIsRejectedInsteadOfRecursingForever() {
        // One object: an array of 8 elements whose references all point at itself (index 0).
        val bytes = plist(
            objects = listOf(byteArrayOf(0xa8.toByte()) + ByteArray(8)),
            topObject = 0,
            offsetSize = 1,
            refSize = 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }

        assertEquals(true, error.message!!.contains("bplist:"))
    }

    @Test
    fun anArrayLargerThanTheDataIsRejected() {
        // 8 references of 1 byte each need 8 bytes after the marker, but there are none.
        val bytes = plist(
            objects = listOf(byteArrayOf(0xa8.toByte())),
            topObject = 0,
            offsetSize = 1,
            refSize = 1,
        )

        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    @Test
    fun aCountLargerThanTheDataIsRejected() {
        // A data object whose length marker claims 2^16 bytes in a body of a few bytes.
        val bytes = plist(
            objects = listOf(byteArrayOf(0x4f.toByte(), 0x12, 0x00)),
            topObject = 0,
            offsetSize = 1,
            refSize = 1,
        )

        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    @Test
    fun anObjectOffsetOutsideTheOffsetTableIsRejected() {
        val header = "bplist00".toByteArray(Charsets.US_ASCII)
        // The single offset points past the offset table.
        val body = header + byteArrayOf(0x09)
        val offsets = byteArrayOf(0x7f)
        val trailer = ByteArray(32).apply {
            this[6] = 1 // offsetSize
            this[7] = 1 // refSize
            writeLong(this, 8, 1L)
            writeLong(this, 16, 0L)
            writeLong(this, 24, (header.size + 1).toLong())
        }
        val bytes = body + offsets + trailer

        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    @Test
    fun anObjectCountImpossibleForTheBodyIsRejected() {
        val header = "bplist00".toByteArray(Charsets.US_ASCII)
        val trailer = ByteArray(32).apply {
            this[6] = 1
            this[7] = 1
            writeLong(this, 8, 0x7fff_ffffL) // absurd object count
            writeLong(this, 16, 0L)
            writeLong(this, 24, header.size.toLong())
        }

        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(header + trailer) }
    }

    @Test
    fun anOrdinaryPlistStillDecodes() {
        // A one-key dictionary {"a": true}; the caps must not reject real input.
        val bytes = BplistCodec.encode(mapOf("a" to true))

        assertEquals(mapOf("a" to true), BplistCodec.decode(bytes))
    }

    private fun plist(objects: List<ByteArray>, topObject: Int, offsetSize: Int, refSize: Int): ByteArray {
        val header = "bplist00".toByteArray(Charsets.US_ASCII)
        val body = ArrayList<Byte>(header.toList())
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, chunk ->
            offsets[index] = body.size
            body.addAll(chunk.toList())
        }
        val offsetTableOffset = body.size
        offsets.forEach { offset ->
            for (shift in offsetSize - 1 downTo 0) body.add(((offset ushr (shift * 8)) and 0xff).toByte())
        }
        val trailer = ByteArray(32).apply {
            this[6] = offsetSize.toByte()
            this[7] = refSize.toByte()
            writeLong(this, 8, objects.size.toLong())
            writeLong(this, 16, topObject.toLong())
            writeLong(this, 24, offsetTableOffset.toLong())
        }
        body.addAll(trailer.toList())
        return body.toByteArray()
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (index in 7 downTo 0) target[offset + index] = (value ushr ((7 - index) * 8)).toByte()
    }
}
