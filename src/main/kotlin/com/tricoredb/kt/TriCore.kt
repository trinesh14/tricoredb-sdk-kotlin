package com.tricoredb.kt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * One authenticated connection to a TriCoreDB server.
 *
 * Open it with [connect] and close it with [close] (or `use { }`). Every operation is a `suspend` function that runs
 * its blocking socket I/O on [Dispatchers.IO]. A connection is one request/response stream: concurrent calls on the
 * same connection are serialized, one exchange at a time. Use [TriCorePool] for real concurrency.
 *
 * Operations are grouped by data model: SQL on this class ([query], [execute], [begin], [transaction]), and
 * [document], [cache], [vector], [graph], [llm] and [admin].
 *
 * Cancelling a coroutine that is waiting for a reply closes the connection: the late reply would otherwise be read
 * as the answer to the next request. To stop the server-side work as well, call [cancel] from a second connection.
 */
public class TriCore private constructor(
    /** The configuration this connection was opened with. */
    public val config: TriCoreConfig,
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {

    private val mutex = Mutex()
    private val prefix = nextPrefix()
    private val seq = AtomicLong()

    @Volatile
    private var closed = false

    /** The feature bitmap the server granted in HELLO_OK. */
    public var grantedFeatures: Long = 0
        private set

    /** The session id the server assigned at AUTH, if any. */
    public var sessionId: String? = null
        private set

    /** The `request_id` most recently sent on this connection; pass it to [cancel] on another connection. */
    @Volatile
    public var lastRequestId: String? = null
        private set

    /** True from a successful [begin] until [commit] or [rollback]. A closed connection has no transaction. */
    public var inTransaction: Boolean = false
        get() = field && !closed
        private set

    /** True once the connection is closed, by [close] or because the transport failed. */
    public val isClosed: Boolean get() = closed

    /** The database requests target. Defaults to [TriCoreConfig.database]. */
    @Volatile
    public var database: String = config.database

    /** Document operations. */
    public val document: DocumentApi = DocumentApi(this)

    /** Cache operations: strings, lists, sets, hashes and streams. */
    public val cache: CacheApi = CacheApi(this)

    /** Vector operations. */
    public val vector: VectorApi = VectorApi(this)

    /** Graph operations. */
    public val graph: GraphApi = GraphApi(this)

    /** LLM context export. */
    public val llm: LlmApi = LlmApi(this)

    /** Administrative operations. */
    public val admin: AdminApi = AdminApi(this)

    /** The granted capabilities, by name. */
    public val features: Set<Feature> get() = Feature.of(grantedFeatures)

    /** True when [feature] was granted. */
    public fun hasFeature(feature: Feature): Boolean = grantedFeatures and feature.bit != 0L

    /** Opening connections. */
    public companion object {
        private val connectionSeq = AtomicLong()
        private val processStamp = java.lang.Long.toHexString(ProcessHandle.current().pid()) +
            java.lang.Long.toHexString(System.nanoTime() and 0xFFFFFFFFFFL)
        private const val CLOSE_TIMEOUT_MS = 2000
        private val FATAL_ERROR_CODES = setOf(
            ErrorCodes.PROTOCOL, ErrorCodes.ORDER, ErrorCodes.FRAME_VERSION, ErrorCodes.FRAME_TAG,
            ErrorCodes.HANDSHAKE_MALFORMED, ErrorCodes.FRAME_TOO_LARGE, ErrorCodes.CONNECTION_LIMIT,
            ErrorCodes.AUTH_REVOKED, ErrorCodes.IDLE_IN_TRANSACTION_TIMEOUT,
        )

        private fun nextPrefix(): String = "kt-$processStamp${java.lang.Long.toHexString(connectionSeq.incrementAndGet())}"

        /** Connect, handshake and (when [TriCoreConfig.user] is set) authenticate. */
        public suspend fun connect(config: TriCoreConfig): TriCore = withContext(Dispatchers.IO) {
            val connectMs = config.connectTimeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            var raw: Socket? = null
            try {
                raw = Socket()
                try {
                    raw.connect(InetSocketAddress(config.host, config.port), connectMs)
                } catch (e: SocketTimeoutException) {
                    throw TriCoreTimeoutException("connect to ${config.host}:${config.port} timed out after ${connectMs}ms", e)
                } catch (e: IOException) {
                    throw ConnectionException("connect to ${config.host}:${config.port} failed: ${e.message}", e)
                }
                raw.tcpNoDelay = true
                raw.soTimeout = connectMs
                val socket: Socket = config.tls?.let { tls -> secure(raw, config, tls) } ?: raw
                val conn = TriCore(
                    config, socket,
                    BufferedInputStream(socket.getInputStream()),
                    BufferedOutputStream(socket.getOutputStream()),
                )
                try {
                    conn.hello()
                    if (config.user != null) conn.auth(config.user, config.secret)
                } catch (e: SocketTimeoutException) {
                    throw TriCoreTimeoutException("the handshake with ${config.host}:${config.port} timed out after ${connectMs}ms", e)
                } catch (e: IOException) {
                    throw ConnectionException("the handshake with ${config.host}:${config.port} failed: ${e.message}", e)
                }
                socket.soTimeout = config.readTimeout?.inWholeMilliseconds?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                conn
            } catch (e: Throwable) {
                runCatching { raw?.close() }
                throw e
            }
        }

        /** Connect to [host]:[port] and authenticate as [user]. */
        public suspend fun connect(host: String, port: Int, user: String, secret: String): TriCore =
            connect(TriCoreConfig(host = host, port = port, user = user, secret = secret))

        private fun secure(raw: Socket, config: TriCoreConfig, tls: TlsOptions): Socket {
            val serverName = tls.serverName ?: config.host
            val ssl = try {
                tls.buildContext().socketFactory.createSocket(raw, serverName, config.port, true) as SSLSocket
            } catch (e: IOException) {
                throw ConnectionException("tls setup failed: ${e.message}", e)
            }
            val params = ssl.sslParameters
            if (!tls.dangerAcceptInvalidCerts) params.endpointIdentificationAlgorithm = "HTTPS"
            if (!serverName.matches(Regex("^[0-9.]+$")) && !serverName.contains(':')) {
                params.serverNames = listOf(SNIHostName(serverName))
            }
            ssl.sslParameters = params
            try {
                ssl.startHandshake()
            } catch (e: SocketTimeoutException) {
                throw TriCoreTimeoutException("tls handshake with ${config.host}:${config.port} timed out", e)
            } catch (e: SSLException) {
                throw ConnectionException("tls verification of `$serverName` failed: ${e.message}", e)
            }
            return ssl
        }
    }

    // ---- handshake ------------------------------------------------------------------------------------------

    private fun hello() {
        send(
            FrameTag.HELLO,
            linkedMapOf(
                "protocol" to Protocol.NAME,
                "version" to linkedMapOf("major" to Protocol.MAJOR, "minor" to Protocol.MINOR),
                "client" to config.clientName,
                "features" to config.features,
            ),
        )
        val reply = FrameCodec.read(input)
        val body = reply.json()
        when (reply.tag) {
            FrameTag.HELLO_OK -> {
                val m = body as? Map<*, *> ?: throw ProtocolException("HELLO_OK payload is not an object", ErrorCodes.PROTOCOL)
                if (m["ok"] != true) {
                    throw ProtocolException(
                        (m["message"] as? String) ?: "handshake refused",
                        (m["code"] as? String) ?: ErrorCodes.PROTOCOL_VERSION,
                    )
                }
                grantedFeatures = (m["features"] as? Number)?.toLong() ?: 0L
            }
            FrameTag.ERROR -> throw errorFrame(body, auth = false)
            else -> throw ProtocolException("expected HELLO_OK, got frame tag ${reply.tag}", ErrorCodes.PROTOCOL)
        }
    }

    private fun auth(user: String, secret: String) {
        send(
            FrameTag.AUTH,
            linkedMapOf("username" to user, "secret" to Wire.byteList(secret.toByteArray(Charsets.UTF_8))),
        )
        val reply = FrameCodec.read(input)
        sessionId = parseAuthReply(reply)
    }

    // ---- transport ------------------------------------------------------------------------------------------

    private fun send(tag: Int, body: Any?) {
        val payload = if (body == null) ByteArray(0) else Json.stringify(body).toByteArray(Charsets.UTF_8)
        FrameCodec.write(output, tag, payload)
    }

    internal suspend fun exchange(tag: Int, body: Any?): Frame {
        ensureOpen()
        return mutex.withLock {
            ensureOpen()
            blocking {
                try {
                    send(tag, body)
                    FrameCodec.read(input)
                } catch (e: SocketTimeoutException) {
                    poison()
                    throw TriCoreTimeoutException(
                        "no reply within ${config.readTimeout}; the connection is closed because the reply may still be in flight",
                        e,
                    )
                } catch (e: ProtocolException) {
                    poison()
                    throw e
                } catch (e: ConnectionException) {
                    poison()
                    throw e
                } catch (e: IOException) {
                    poison()
                    throw ConnectionException("connection to ${config.host}:${config.port} failed: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun <T> blocking(block: () -> T): T = coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        try {
            work.await()
        } catch (e: CancellationException) {
            poison()
            throw e
        }
    }

    private fun ensureOpen() {
        if (closed) throw ConnectionException("the connection is closed")
    }

    private fun poison() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    /**
     * Say goodbye (CLOSE, waiting up to two seconds for BYE) and release the socket. Idempotent.
     * If a request is in flight the socket is closed immediately instead.
     */
    override fun close() {
        if (closed) return
        if (mutex.tryLock()) {
            try {
                socket.soTimeout = CLOSE_TIMEOUT_MS
                send(FrameTag.CLOSE, null)
                FrameCodec.read(input)
            } catch (_: Exception) {
            } finally {
                closed = true
                runCatching { socket.close() }
                mutex.unlock()
            }
        } else {
            poison()
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------

    /** Exchange a PING for a PONG. Does not reach any module; see [AdminApi.ping] for a full-pipeline check. */
    public suspend fun ping() {
        val reply = exchange(FrameTag.PING, null)
        if (reply.tag == FrameTag.ERROR) throw errorFrame(reply.json(), auth = false)
        if (reply.tag != FrameTag.PONG) {
            poison()
            throw ProtocolException("expected PONG, got frame tag ${reply.tag}", ErrorCodes.PROTOCOL)
        }
    }

    /**
     * Send a raw `TriCoreOp` and return the response. [op] is the externally tagged operation as Kotlin values,
     * for example `mapOf("Cache" to "Ping")`. A response whose status is not `ok` throws [ServerException].
     */
    public suspend fun request(op: Any, database: String = this.database): Response {
        val requestId = "$prefix-${seq.incrementAndGet()}"
        val body = linkedMapOf<String, Any?>(
            "request_id" to requestId,
            "database" to database,
            "region_hint" to null,
            "op" to op,
        )
        val options = linkedMapOf<String, Any?>()
        config.requestTimeout?.let { options["timeout_ms"] = it.inWholeMilliseconds }
        body["options"] = options
        currentCoroutineContext()[CorrelationId]?.let {
            requireFeature(Feature.CORRELATION_ID, "a correlation id")
            body["correlation_id"] = it.value
        }
        lastRequestId = requestId
        val reply = exchange(FrameTag.REQUEST, body)
        return when (reply.tag) {
            FrameTag.RESPONSE -> {
                val resp = Response.from(reply.json())
                if (!resp.isOk) throw refusal(resp)
                resp
            }
            FrameTag.ERROR -> throw errorFrame(reply.json(), auth = false)
            else -> {
                poison()
                throw ProtocolException("expected RESPONSE, got frame tag ${reply.tag}", ErrorCodes.PROTOCOL)
            }
        }
    }

    /**
     * Ask the server to stop one of this principal's running requests, named by its `request_id`.
     *
     * Send it on a **second** connection: the connection running the statement is waiting for that statement's
     * reply. Returns how many executions were cancelled; an unknown id returns 0.
     */
    public suspend fun cancel(requestId: String): Long {
        val reply = exchange(FrameTag.CANCEL, mapOf("request_id" to requestId))
        return when (reply.tag) {
            FrameTag.CANCEL_OK -> (Wire.obj(reply.json(), "cancel")["cancelled"] as? Number)?.toLong() ?: 0L
            FrameTag.ERROR -> throw errorFrame(reply.json(), auth = false)
            else -> {
                poison()
                throw ProtocolException("expected CANCEL_OK, got frame tag ${reply.tag}", ErrorCodes.PROTOCOL)
            }
        }
    }

    internal fun requireFeature(feature: Feature, what: String) {
        if (!hasFeature(feature)) {
            throw FeatureNotGrantedException(
                feature,
                "$what needs the ${feature.name} capability, which this server did not grant in the handshake " +
                    "(granted: ${features.joinToString().ifEmpty { "none" }}); the request was not sent",
            )
        }
    }

    // ---- SQL --------------------------------------------------------------------------------------------------

    /** Run a read and return its rows. [params] bind `?` placeholders server-side; see [SqlParams]. */
    public suspend fun query(sql: String, vararg params: Any?): Rows = query(sql, params.toList())

    /** Run a read with a list of [params]. */
    public suspend fun query(sql: String, params: List<Any?>): Rows {
        val resp = request(sqlOp("Query", sql, params))
        val r = Wire.obj(resp.payload("Rows"), "query")
        val rows = Wire.list(r["rows"] ?: emptyList<Any?>(), "query rows").map { Wire.strings(it, "query row") }
        return Rows(Wire.strings(r["columns"] ?: emptyList<Any?>(), "query columns"), rows)
    }

    /** Run a write (DDL, INSERT, UPDATE, DELETE, or a `BEGIN; ...; COMMIT` script). [params] bind server-side. */
    public suspend fun execute(sql: String, vararg params: Any?): ExecResult = execute(sql, params.toList())

    /** Run a write with a list of [params]. */
    public suspend fun execute(sql: String, params: List<Any?>): ExecResult = toExec(request(sqlOp("Exec", sql, params)))

    private fun sqlOp(kind: String, sql: String, params: List<Any?>): Map<String, Any?> {
        val inner = linkedMapOf<String, Any?>("sql" to sql)
        if (params.isNotEmpty()) {
            requireFeature(Feature.SERVER_PARAMS, "binding SQL parameters")
            inner["params"] = SqlParams.params(params)
        }
        return mapOf("Sql" to mapOf(kind to inner))
    }

    private fun toExec(resp: Response): ExecResult {
        val data = resp.data
        return when {
            data is Map<*, *> && data.containsKey("Json") -> {
                val j = data["Json"]
                ExecResult((j as? Map<*, *>)?.let { Wire.longOrNull(it, "rows_affected") }, j, resp)
            }
            data is Map<*, *> && data.containsKey("Message") -> ExecResult(null, data["Message"], resp)
            else -> ExecResult(null, null, resp)
        }
    }

    // ---- session transactions --------------------------------------------------------------------------------

    /**
     * Open a session transaction on this connection. Needs [Feature.SESSION_TXN]; without it this fails by name
     * and nothing is sent. Returns the server's outcome.
     */
    public suspend fun begin(): Map<String, Any?> {
        requireFeature(Feature.SESSION_TXN, "begin()")
        if (inTransaction) throw IllegalStateException("a session transaction is already open on this connection")
        val out = txnControl("BEGIN")
        inTransaction = true
        return out
    }

    /** Commit the open session transaction. Every reply, a refusal included, ends the block. */
    public suspend fun commit(): Map<String, Any?> {
        requireFeature(Feature.SESSION_TXN, "commit()")
        try {
            return txnControl("COMMIT")
        } finally {
            inTransaction = false
        }
    }

    /** Roll back the open session transaction. */
    public suspend fun rollback(): Map<String, Any?> {
        requireFeature(Feature.SESSION_TXN, "rollback()")
        try {
            return txnControl("ROLLBACK")
        } finally {
            inTransaction = false
        }
    }

    /**
     * Run [block] inside a session transaction: [begin], then [commit] when it returns, or [rollback] and rethrow
     * when it throws (the original exception propagates). Needs [Feature.SESSION_TXN].
     *
     * Issue statements on the receiver, this connection. A statement on another connection is outside the block.
     */
    public suspend fun <T> transaction(block: suspend TriCore.() -> T): T {
        begin()
        val result = try {
            block()
        } catch (e: Throwable) {
            if (inTransaction) {
                withContext(NonCancellable) {
                    try {
                        rollback()
                    } catch (suppressed: Exception) {
                        e.addSuppressed(suppressed)
                    }
                }
            }
            throw e
        }
        commit()
        return result
    }

    private suspend fun txnControl(keyword: String): Map<String, Any?> {
        val resp = request(mapOf("Sql" to mapOf("Exec" to mapOf("sql" to keyword))))
        val d = resp.data
        return if (d is Map<*, *> && d["Json"] is Map<*, *>) Wire.objectMap(d["Json"]) else emptyMap()
    }

    // ---- errors -------------------------------------------------------------------------------------------------

    private fun refusal(resp: Response): ServerException {
        val base = when (val d = resp.data) {
            is Map<*, *> -> (d["Message"] as? String) ?: d["Json"]?.let { Json.stringify(it) }
            else -> null
        } ?: "request failed"
        val code = resp.errorCode ?: if (resp.status == "not_implemented") ErrorCodes.NOT_IMPLEMENTED else null
        val hint = resp.leaderHint
        var message = "$base (server status: ${resp.status}${code?.let { ", code: $it" } ?: ""})"
        if (code == ErrorCodes.NOT_LEADER) {
            message += if (hint != null) {
                " [not_leader: the leader serves clients at `$hint`; this SDK does not follow the hint on its own]"
            } else {
                " [not_leader: no leader address is known yet; wait and retry]"
            }
            if (inTransaction) message += " The open session transaction is bound to this connection and cannot move nodes."
        }
        return ServerException(message, code, hint, resp.status, resp)
    }

    private fun errorFrame(body: Any?, auth: Boolean): TriCoreException {
        val m = body as? Map<*, *>
        val code = m?.get("code") as? String
        val message = (m?.get("message") as? String) ?: body?.let { Json.stringify(it) } ?: "the server sent an ERROR frame"
        if (code == null || code in FATAL_ERROR_CODES || auth) poison()
        if (code == ErrorCodes.IDLE_IN_TRANSACTION_TIMEOUT) inTransaction = false
        return if (auth || code == ErrorCodes.AUTH_REVOKED) {
            AuthException(message, code ?: ErrorCodes.AUTH_FAILED)
        } else {
            ProtocolException("$message${code?.let { " (code: $it)" } ?: ""}", code)
        }
    }
}

/**
 * Interpret the reply to AUTH. Returns the session id on success.
 *
 * A refused login arrives as `AUTH_OK` (tag 9) carrying `ok: false`, not as an ERROR frame, so `ok` is checked
 * before anything else: treating the tag alone as success would hand back an unauthenticated connection.
 */
internal fun parseAuthReply(reply: Frame): String? {
    val body = reply.json()
    return when (reply.tag) {
        FrameTag.AUTH_OK -> {
            val m = body as? Map<*, *> ?: throw ProtocolException("AUTH_OK payload is not an object", ErrorCodes.PROTOCOL)
            if (m["ok"] != true) {
                throw AuthException((m["message"] as? String) ?: "authentication refused", ErrorCodes.AUTH_FAILED)
            }
            m["session_id"] as? String
        }
        FrameTag.ERROR -> {
            val m = body as? Map<*, *>
            throw AuthException(
                (m?.get("message") as? String) ?: "authentication refused",
                (m?.get("code") as? String) ?: ErrorCodes.AUTH_FAILED,
            )
        }
        else -> throw ProtocolException("expected AUTH_OK, got frame tag ${reply.tag}", ErrorCodes.PROTOCOL)
    }
}
