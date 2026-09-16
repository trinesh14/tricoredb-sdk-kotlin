package com.tricoredb.kt.support

import com.tricoredb.kt.FrameCodec
import com.tricoredb.kt.FrameTag
import com.tricoredb.kt.TriCore
import com.tricoredb.kt.TriCoreConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A peer that speaks the handshake and then plays one scripted answer.
 *
 * A real server cannot be asked to answer `not_leader` on demand, to hang up
 * mid-frame, or to declare a payload it does not send. What is under test is this
 * client's reading of those answers, and the shape it reads is the same one a real
 * cluster sends.
 */
class ScriptedPeer(private val script: (Conversation) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    private val worker: Thread

    /** The port this peer listens on. */
    val port: Int get() = server.localPort

    init {
        worker = thread(isDaemon = true, name = "scripted-peer") {
            runCatching {
                server.accept().use { socket ->
                    socket.tcpNoDelay = true
                    script(Conversation(socket))
                }
            }
        }
    }

    /** Connect a client to this peer, authenticating as `admin` unless [user] says otherwise. */
    suspend fun connect(user: String? = "admin", features: Long? = null): TriCore {
        val base = TriCoreConfig(host = "127.0.0.1", port = port, user = user, secret = "pw")
        return TriCore.connect(if (features == null) base else base.copy(features = features))
    }

    override fun close() {
        runCatching { server.close() }
        worker.interrupt()
    }

    /** One side of the scripted conversation. */
    class Conversation(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())

        /** Read one frame the client sent. */
        fun read() = FrameCodec.read(input)

        /** Send one frame, with a JSON body. */
        fun send(tag: Int, json: String) = FrameCodec.write(output, tag, json.toByteArray())

        /** Send raw bytes, for the malformed cases a codec would refuse to produce. */
        fun sendRaw(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        /** Answer HELLO and AUTH the way a healthy server would, granting [features]. */
        fun handshake(features: Long = 7) {
            read()
            send(FrameTag.HELLO_OK, """{"ok":true,"server_version":{"major":1,"minor":0},"message":"ok","features":$features}""")
            read()
            send(FrameTag.AUTH_OK, """{"ok":true,"session_id":"s-1"}""")
        }

        /**
         * Answer the next request with [json], then wait for the client to hang up so
         * the reply is not lost to a close race.
         */
        fun answerOnce(json: String) {
            read()
            send(FrameTag.RESPONSE, json)
            runCatching { read() }
        }

        /** Stop reading and writing, leaving the client with a closed socket. */
        fun hangUp() = runCatching { socket.close() }
    }
}
