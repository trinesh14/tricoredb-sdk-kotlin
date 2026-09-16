package com.tricoredb.kt

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Frame tags of the `tricore` wire protocol. */
public object FrameTag {
    /** Client opening handshake. */
    public const val HELLO: Int = 0
    /** Client authentication. */
    public const val AUTH: Int = 1
    /** A `TriCoreRequest`. */
    public const val REQUEST: Int = 2
    /** A `TriCoreResponse`. */
    public const val RESPONSE: Int = 3
    /** Liveness probe. */
    public const val PING: Int = 4
    /** Answer to [PING]. */
    public const val PONG: Int = 5
    /** A refusal carrying `{"code","message"}`. */
    public const val ERROR: Int = 6
    /** Client goodbye. */
    public const val CLOSE: Int = 7
    /** Answer to [HELLO]. */
    public const val HELLO_OK: Int = 8
    /** Answer to [AUTH]; carries `ok`, which may be `false`. */
    public const val AUTH_OK: Int = 9
    /** Answer to [CLOSE]. */
    public const val BYE: Int = 10
    /** Cancel a running request, sent on a second connection. */
    public const val CANCEL: Int = 11
    /** Answer to [CANCEL]. */
    public const val CANCEL_OK: Int = 12
}

/** Constants of the `tricore` wire protocol. */
public object Protocol {
    /** The protocol name sent in HELLO. */
    public const val NAME: String = "tricore"
    /** Protocol major version. */
    public const val MAJOR: Int = 1
    /** Protocol minor version. */
    public const val MINOR: Int = 0
    /** The frame header version this SDK writes. */
    public const val FRAME_VERSION: Int = 1
    /** The highest frame header version this SDK reads. */
    public const val MAX_SUPPORTED_FRAME_VERSION: Int = 1
    /** Frame header length in bytes: version, tag, u32 big-endian length. */
    public const val HEADER_SIZE: Int = 6
    /** Payload ceiling for REQUEST and RESPONSE frames (16 MiB). */
    public const val MAX_FRAME_SIZE: Int = 16 * 1024 * 1024
    /** Payload ceiling for every other frame (64 KiB). */
    public const val MAX_CONTROL_FRAME_SIZE: Int = 64 * 1024
    /** The default server port. */
    public const val DEFAULT_PORT: Int = 8427

    /** The payload ceiling for [tag]. An unknown tag takes the tighter control ceiling. */
    public fun maxPayloadFor(tag: Int): Int =
        if (tag == FrameTag.REQUEST || tag == FrameTag.RESPONSE) MAX_FRAME_SIZE else MAX_CONTROL_FRAME_SIZE
}

/** One decoded frame: its [tag] and raw [payload] bytes. */
public class Frame(
    /** The frame tag, one of [FrameTag]. */
    public val tag: Int,
    /** The raw payload (UTF-8 JSON, or empty). */
    public val payload: ByteArray,
) {
    /** The payload parsed as JSON, or `null` when it is empty. */
    public fun json(): Any? = if (payload.isEmpty()) null else Json.parse(payload.toString(Charsets.UTF_8))
}

/** Encodes and decodes `tricore` frames. Both directions enforce the per-tag payload ceilings. */
public object FrameCodec {

    /** Encode one frame into bytes. Throws [ProtocolException] (`frame_too_large`) above the tag's ceiling. */
    public fun encode(tag: Int, payload: ByteArray): ByteArray {
        require(tag in 0..255) { "frame tag $tag does not fit in a byte" }
        val limit = Protocol.maxPayloadFor(tag)
        if (payload.size > limit) {
            throw ProtocolException(
                "refusing to send a ${payload.size}-byte payload for frame tag $tag; the protocol caps it at $limit bytes",
                ErrorCodes.FRAME_TOO_LARGE,
            )
        }
        val out = ByteArray(Protocol.HEADER_SIZE + payload.size)
        out[0] = Protocol.FRAME_VERSION.toByte()
        out[1] = tag.toByte()
        val n = payload.size
        out[2] = (n ushr 24).toByte()
        out[3] = (n ushr 16).toByte()
        out[4] = (n ushr 8).toByte()
        out[5] = n.toByte()
        System.arraycopy(payload, 0, out, Protocol.HEADER_SIZE, n)
        return out
    }

    /** Write one frame to [out] and flush. */
    public fun write(out: OutputStream, tag: Int, payload: ByteArray) {
        out.write(encode(tag, payload))
        out.flush()
    }

    /**
     * Read exactly one frame from [input].
     *
     * The header is validated before a single payload byte is read: a version above
     * [Protocol.MAX_SUPPORTED_FRAME_VERSION] is refused as `frame_version`, and a declared
     * length above the tag's ceiling as `frame_too_large`. A stream that ends is a [ConnectionException].
     */
    public fun read(input: InputStream): Frame {
        val head = readExactly(input, Protocol.HEADER_SIZE)
        val version = head[0].toInt() and 0xFF
        val tag = head[1].toInt() and 0xFF
        val length = ((head[2].toLong() and 0xFF) shl 24) or ((head[3].toLong() and 0xFF) shl 16) or
            ((head[4].toLong() and 0xFF) shl 8) or (head[5].toLong() and 0xFF)
        if (version > Protocol.MAX_SUPPORTED_FRAME_VERSION) {
            throw ProtocolException(
                "frame header version $version is newer than this SDK can read (max ${Protocol.MAX_SUPPORTED_FRAME_VERSION})",
                ErrorCodes.FRAME_VERSION,
            )
        }
        val limit = Protocol.maxPayloadFor(tag)
        if (length > limit) {
            throw ProtocolException(
                "frame (tag $tag) declares a $length-byte payload, above the $limit-byte limit for that frame",
                ErrorCodes.FRAME_TOO_LARGE,
            )
        }
        val payload = if (length == 0L) ByteArray(0) else readExactly(input, length.toInt())
        return Frame(tag, payload)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try {
                input.read(buf, off, n - off)
            } catch (e: EOFException) {
                -1
            }
            if (r < 0) throw ConnectionException("the server closed the connection mid-frame ($off of $n bytes read)")
            off += r
        }
        return buf
    }
}
