package com.tricoredb.kt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The frame layout, and the ceilings that keep a wrong or hostile peer from making
 * this client allocate whatever length it declares.
 */
class FrameCodecTest {

    @Test
    fun `the header is version, tag and a big-endian length`() {
        val bytes = FrameCodec.encode(FrameTag.REQUEST, "{}".toByteArray())
        assertContentEquals(byteArrayOf(1, 2, 0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte()), bytes)
        assertContentEquals(byteArrayOf(1, 4, 0, 0, 0, 0), FrameCodec.encode(FrameTag.PING, ByteArray(0)))
    }

    @Test
    fun `a frame round-trips through the codec`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, FrameTag.AUTH_OK, """{"ok":false}""".toByteArray())
        val frame = FrameCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(FrameTag.AUTH_OK, frame.tag)
        assertEquals("""{"ok":false}""", frame.payload.toString(Charsets.UTF_8))
        @Suppress("UNCHECKED_CAST")
        val json = frame.json() as Map<String, Any?>
        assertEquals(false, json["ok"])
    }

    @Test
    fun `only request and response frames take the large ceiling`() {
        assertEquals(16 * 1024 * 1024, Protocol.maxPayloadFor(FrameTag.REQUEST))
        assertEquals(16 * 1024 * 1024, Protocol.maxPayloadFor(FrameTag.RESPONSE))
        for (tag in listOf(FrameTag.HELLO, FrameTag.AUTH, FrameTag.PING, FrameTag.AUTH_OK, FrameTag.CANCEL_OK)) {
            assertEquals(64 * 1024, Protocol.maxPayloadFor(tag))
        }
        // A tag this build cannot name is a tag whose size it cannot vouch for.
        assertEquals(64 * 1024, Protocol.maxPayloadFor(99))
    }

    @Test
    fun `an oversized control frame is refused on the way out`() {
        val tooBig = ByteArray(Protocol.MAX_CONTROL_FRAME_SIZE + 1)
        val error = assertFailsWith<ProtocolException> { FrameCodec.encode(FrameTag.AUTH, tooBig) }
        assertEquals(ErrorCodes.FRAME_TOO_LARGE, error.code)
        // The same payload is fine on a REQUEST, which has the larger ceiling.
        FrameCodec.encode(FrameTag.REQUEST, tooBig)
    }

    @Test
    fun `a declared length above the ceiling is refused before the payload is read`() {
        // Six bytes of header claiming 64 KiB + 1, and not one byte of body. A client
        // that trusted the length would allocate it and then block for ever.
        val header = byteArrayOf(1, FrameTag.AUTH_OK.toByte(), 0, 1, 0, 1)
        val error = assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(header)) }
        assertEquals(ErrorCodes.FRAME_TOO_LARGE, error.code)
    }

    @Test
    fun `a frame version this build cannot read is refused by name`() {
        val header = byteArrayOf(2, FrameTag.RESPONSE.toByte(), 0, 0, 0, 0)
        val error = assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(header)) }
        assertEquals(ErrorCodes.FRAME_VERSION, error.code)
    }

    @Test
    fun `a stream that ends mid-frame is a connection failure`() {
        val truncated = byteArrayOf(1, FrameTag.RESPONSE.toByte(), 0, 0, 0, 10, '{'.code.toByte())
        val error = assertFailsWith<ConnectionException> { FrameCodec.read(ByteArrayInputStream(truncated)) }
        assertTrue(error.message!!.isNotEmpty())
    }

    @Test
    fun `a payload spanning several reads is reassembled`() {
        // TCP delivers a byte stream, not message boundaries: a client that read once
        // would truncate any payload larger than one segment.
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val encoded = FrameCodec.encode(FrameTag.RESPONSE, payload)
        val drip = object : ByteArrayInputStream(encoded) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1024))
        }
        val frame = FrameCodec.read(drip)
        assertContentEquals(payload, frame.payload)
    }
}
