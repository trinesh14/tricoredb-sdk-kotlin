package com.tricoredb.kt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A pool of [TriCore] connections, safe for concurrent coroutines.
 *
 * A pooled connection is owned exclusively for the duration of one [use] block, so it can never be shared by two
 * pieces of concurrent work. Connections open lazily, up to [size].
 *
 * A session transaction is bound to its connection, so a connection is never returned to the pool mid-transaction:
 * if the block returns with one open it is rolled back and [use] throws [PoolMisuseException]; if the block throws
 * with one open it is rolled back and the block's exception propagates.
 *
 * ```kotlin
 * TriCorePool(config, size = 8).use { pool ->
 *     pool.use { db -> db.execute("INSERT INTO t VALUES (?, ?)", 1, "ada") }
 * }
 * ```
 */
public class TriCorePool(
    /** Connection settings, TLS included, shared by every pooled connection. */
    public val config: TriCoreConfig,
    /** The maximum number of open connections. */
    public val size: Int = 8,
    /** How long [use] waits for a free connection before throwing [PoolTimeoutException]. */
    public val acquireTimeout: Duration = 10.seconds,
) : Closeable {
    init {
        require(size >= 1) { "pool size must be at least 1" }
    }

    private val permits = Semaphore(size)
    private val idle = ArrayDeque<TriCore>()

    @Volatile
    private var closed = false

    /**
     * A point-in-time view of the pool.
     *
     * @property size the configured maximum.
     * @property idle connections open and waiting.
     * @property inUse connections currently lent out.
     */
    public data class Stats(val size: Int, val idle: Int, val inUse: Int)

    /** Current pool counters. */
    public fun stats(): Stats = synchronized(idle) { Stats(size, idle.size, size - permits.availablePermits) }

    /** Borrow a connection for the duration of [block], then return it (or retire it if it broke). */
    public suspend fun <T> use(block: suspend (TriCore) -> T): T {
        if (closed) throw ConnectionException("the pool is closed")
        withTimeoutOrNull(acquireTimeout) { permits.acquire() }
            ?: throw PoolTimeoutException("no pooled connection became available within $acquireTimeout (size=$size)")
        try {
            val conn = take() ?: TriCore.connect(config)
            var broken = false
            try {
                val result = try {
                    block(conn)
                } catch (e: Throwable) {
                    broken = when {
                        conn.isClosed -> true
                        e is ProtocolException || e is ConnectionException || e is TriCoreTimeoutException ||
                            e is AuthException || e is CancellationException -> true
                        conn.inTransaction -> !abandon(conn)
                        else -> false
                    }
                    throw e
                }
                if (conn.inTransaction) {
                    broken = !abandon(conn)
                    throw PoolMisuseException(
                        "the block returned with a session transaction still open; it was rolled back rather than " +
                            "returned to the pool mid-transaction. Commit inside the block, or use db.transaction { }",
                    )
                }
                return result
            } finally {
                release(conn, broken)
            }
        } finally {
            permits.release()
        }
    }

    private fun take(): TriCore? = synchronized(idle) {
        while (idle.isNotEmpty()) {
            val c = idle.removeFirst()
            if (!c.isClosed) return c
        }
        null
    }

    private suspend fun abandon(conn: TriCore): Boolean = withContext(NonCancellable) {
        try {
            conn.rollback()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun release(conn: TriCore, broken: Boolean) {
        if (broken || closed || conn.isClosed || conn.inTransaction) {
            conn.close()
            return
        }
        synchronized(idle) { idle.addLast(conn) }
    }

    /** Close every idle connection and refuse further use. Connections lent out close when returned. */
    override fun close() {
        closed = true
        val drained = synchronized(idle) { idle.toList().also { idle.clear() } }
        drained.forEach { it.close() }
    }
}
