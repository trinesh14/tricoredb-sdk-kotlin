package com.tricoredb.kt

import com.tricoredb.kt.support.ScriptedPeer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How this client reads the protocol, proved without a server. */
class ScriptedPeerTest {

    @Test
    fun `a not_leader refusal is typed and carries the leader address`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.answerOnce(
                """{"request_id":"r1","status":"error","data":{"Message":"not the raft leader — send writes to `n2`"},""" +
                    """"diagnostics":{"error_code":"not_leader","leader_hint":"10.9.9.7:8427"}}""",
            )
        }.use { peer ->
            val db = peer.connect()
            val error = assertFailsWith<ServerException> { db.execute("INSERT INTO t VALUES (1)") }
            assertEquals(ErrorCodes.NOT_LEADER, error.code)
            assertTrue(error.isRedirect, "the code decides this, never the message text")
            assertEquals("10.9.9.7:8427", error.leaderHint)
            assertEquals("error", error.status)
            db.close()
        }
    }

    @Test
    fun `mid-election there is a code but no address`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.answerOnce(
                """{"request_id":"r1","status":"error","data":{"Message":"not the raft leader"},""" +
                    """"diagnostics":{"error_code":"not_leader"}}""",
            )
        }.use { peer ->
            val db = peer.connect()
            val error = assertFailsWith<ServerException> { db.execute("INSERT INTO t VALUES (1)") }
            assertTrue(error.isRedirect)
            assertNull(error.leaderHint, "an absent hint means the destination is unknown, not that there was no redirect")
            db.close()
        }
    }

    @Test
    fun `an ordinary failure is not read as a redirect`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.answerOnce(
                """{"request_id":"r1","status":"error","data":{"Message":"syntax error"},""" +
                    """"diagnostics":{"error_code":"request.invalid"}}""",
            )
        }.use { peer ->
            val db = peer.connect()
            val error = assertFailsWith<ServerException> { db.execute("NOT SQL") }
            assertFalse(error.isRedirect)
            assertEquals(ErrorCodes.REQUEST_INVALID, error.code)
            assertTrue(error.message!!.startsWith("syntax error"), "the server's own words come first: ${error.message}")
            db.close()
        }
    }

    @Test
    fun `an AUTH_OK frame carrying ok false is still a refusal`() = runBlocking {
        ScriptedPeer { peer ->
            peer.read()
            peer.send(FrameTag.HELLO_OK, """{"ok":true,"message":"ok","features":7}""")
            peer.read()
            // The tag names the answer's shape; the body is the verdict.
            peer.send(FrameTag.AUTH_OK, """{"ok":false,"message":"bad password"}""")
            runCatching { peer.read() }
        }.use { peer ->
            val error = assertFailsWith<AuthException> { peer.connect() }
            assertTrue(error.message!!.contains("bad password"), "the server's own words: ${error.message}")
        }
    }

    @Test
    fun `a handshake refusal is reported as one`() = runBlocking {
        ScriptedPeer { peer ->
            peer.read()
            peer.send(FrameTag.HELLO_OK, """{"ok":false,"message":"unsupported protocol version","code":"protocol_version"}""")
            runCatching { peer.read() }
        }.use { peer ->
            val error = assertFailsWith<ProtocolException> { peer.connect() }
            assertTrue(error.message!!.contains("unsupported protocol"), "${error.message}")
        }
    }

    @Test
    fun `a declared payload above the ceiling is refused before it is read`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.read()
            // A control frame claiming 64 KiB + 1 bytes, with none of them sent.
            peer.sendRaw(byteArrayOf(1, FrameTag.AUTH_OK.toByte(), 0, 1, 0, 1))
            runCatching { peer.read() }
        }.use { peer ->
            val db = peer.connect()
            val error = assertFailsWith<ProtocolException> { db.ping() }
            assertEquals(ErrorCodes.FRAME_TOO_LARGE, error.code)
            db.close()
        }
    }

    @Test
    fun `a peer that hangs up mid-frame does not leave the client waiting`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.read()
            peer.sendRaw(byteArrayOf(1, FrameTag.RESPONSE.toByte(), 0, 0, 0, 10, '{'.code.toByte()))
            peer.hangUp()
        }.use { peer ->
            val db = peer.connect()
            assertFailsWith<TriCoreException> { db.execute("SELECT 1") }
            assertTrue(db.isClosed, "a stream that cannot be resynchronised is closed, not reused")
        }
    }

    @Test
    fun `a status this client does not know is treated as a failure`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.answerOnce(
                """{"request_id":"r1","status":"not_implemented","data":{"Message":"Cache::XGroup is refused in V1"}}""",
            )
        }.use { peer ->
            val db = peer.connect()
            val error = assertFailsWith<ServerException> { db.cache.xGroup("ns", "k", "CREATE") }
            assertEquals("not_implemented", error.status)
            assertTrue(error.message!!.contains("XGroup"))
            db.close()
        }
    }

    @Test
    fun `a server that granted nothing makes the client refuse before sending`() = runBlocking {
        ScriptedPeer { peer ->
            // An older server omits the feature field entirely.
            peer.read()
            peer.send(FrameTag.HELLO_OK, """{"ok":true,"message":"ok"}""")
            peer.read()
            peer.send(FrameTag.AUTH_OK, """{"ok":true,"session_id":"s-1"}""")
            runCatching { peer.read() }
        }.use { peer ->
            val db = peer.connect()
            assertTrue(db.features.isEmpty())
            assertFalse(db.hasFeature(Feature.SERVER_PARAMS))

            val error = assertFailsWith<FeatureNotGrantedException> { db.query("SELECT * FROM t WHERE id = ?", 1) }
            assertEquals(Feature.SERVER_PARAMS, error.feature)
            assertFalse(db.isClosed, "nothing was sent, so the connection is untouched")

            val begin = assertFailsWith<FeatureNotGrantedException> { db.begin() }
            assertEquals(Feature.SESSION_TXN, begin.feature)
            db.close()
        }
    }

    @Test
    fun `warnings and diagnostics reach the caller on a successful response`() = runBlocking {
        ScriptedPeer { peer ->
            peer.handshake()
            peer.answerOnce(
                """{"request_id":"r1","status":"ok","data":{"Message":"done"},""" +
                    """"diagnostics":{"route":"local","elapsed_ms":4,"warnings":["shard 2 was unreachable"]}}""",
            )
        }.use { peer ->
            val db = peer.connect()
            val response = db.request(mapOf("Admin" to "Ping"))
            assertTrue(response.isOk)
            assertEquals(listOf("shard 2 was unreachable"), response.warnings)
            assertEquals("local", response.route)
            assertEquals(4L, response.elapsedMs)
            db.close()
        }
    }
}
