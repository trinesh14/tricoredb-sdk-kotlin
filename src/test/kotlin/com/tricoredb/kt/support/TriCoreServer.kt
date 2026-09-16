package com.tricoredb.kt.support

import com.tricoredb.kt.TriCore
import com.tricoredb.kt.TriCoreConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A private `tricore-server` for the tests that need a real one.
 *
 * The binary is named by `TRICORE_SERVER_BIN`, or found in a sibling
 * `tricore/tricore-db/target/{release,debug}` checkout. It is never built here:
 * building the server from a test is slow and collides with anything else
 * compiling.
 *
 * When there is no binary, [skipReason] says so and the live tests skip. Someone who
 * cloned this repository to read it has no server, and the suite must still be green
 * for them.
 */
class TriCoreServer private constructor(
    private val process: Process,
    private val directory: File,
    /** The host the server bound to. */
    val host: String,
    /** The port it is listening on. */
    val port: Int,
) : AutoCloseable {

    /** Connect to this server as `admin`. */
    suspend fun connect(features: Long? = null): TriCore {
        val base = TriCoreConfig(host = host, port = port, user = "admin", secret = "pw")
        return TriCore.connect(if (features == null) base else base.copy(features = features))
    }

    /** Options pointing at this server. */
    fun config(): TriCoreConfig = TriCoreConfig(host = host, port = port, user = "admin", secret = "pw")

    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        directory.deleteRecursively()
    }

    companion object {
        /** Every module on, dev authentication, and an ephemeral port so parallel runs do not collide. */
        private val CONFIG = """
            [server]
            host = "127.0.0.1"
            port = 0
            protocol = "tricore"
            node_id = "sdk-kotlin-tests"
            region_id = "local"

            [modules]
            sql = true
            document = true
            cache = true
            vector = true
            graph = true
            llm = true
            cluster = false

            [security]
            auth_mode = "password"
            dev_auth = true
            allow_default_admin = false

            [tls]
            enabled = false
        """.trimIndent()

        /** Why the live tests cannot run, or `null` when they can. */
        val skipReason: String? by lazy { if (findBinary() != null) null else NO_BINARY }

        private const val NO_BINARY =
            "no tricore-server binary: set TRICORE_SERVER_BIN, or run one from the Docker image (see the README)"

        private fun findBinary(): File? {
            val name = if (System.getProperty("os.name").startsWith("Windows")) "tricore-server.exe" else "tricore-server"
            System.getenv("TRICORE_SERVER_BIN")?.takeIf { it.isNotBlank() }?.let { named ->
                return File(named).takeIf { it.isFile }
            }
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                for (profile in listOf("release", "debug")) {
                    val candidate = File(dir, "target/$profile/$name")
                    if (candidate.isFile) return candidate
                }
                dir = dir.parentFile
            }
            return null
        }

        /** Start a server, or fail with [NO_BINARY] when there is none to start. */
        fun start(): TriCoreServer {
            val binary = findBinary() ?: error(NO_BINARY)
            val dir = File.createTempFile("tricoredb-kotlin-", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
            val config = File(dir, "tricore.toml").apply { writeText(CONFIG) }
            val data = File(dir, "data").apply { mkdirs() }

            val process = ProcessBuilder(
                binary.absolutePath,
                "--config", config.absolutePath,
                "--port", "0",
                "--data-dir", data.absolutePath,
            ).redirectErrorStream(true).start()

            // The address is read from the server's own line rather than assumed:
            // sibling runs bind their own servers at the same time. The reader keeps
            // draining afterwards, because a full pipe would stop the server dead.
            val addresses = ArrayBlockingQueue<String>(1)
            thread(isDaemon = true, name = "tricore-server-output") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    line.substringAfter("listening on", "").trim().takeIf { it.isNotEmpty() }?.let { addresses.offer(it) }
                }
            }

            val address = addresses.poll(60, TimeUnit.SECONDS)
                ?: run {
                    process.destroyForcibly()
                    dir.deleteRecursively()
                    error("the server never said which address it is listening on")
                }
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull()
                ?: run {
                    process.destroyForcibly()
                    dir.deleteRecursively()
                    error("cannot read a port out of `$address`")
                }
            return TriCoreServer(process, dir, host, port)
        }
    }
}

/** A name no other test in this run uses, so tests can share one server without colliding. */
fun unique(prefix: String): String = "${prefix}_${java.util.UUID.randomUUID().toString().replace("-", "").take(10)}"
