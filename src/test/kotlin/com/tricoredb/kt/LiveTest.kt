package com.tricoredb.kt

import com.tricoredb.kt.support.TriCoreServer
import com.tricoredb.kt.support.unique
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything this client can do, against a real `tricore-server`.
 *
 * Each case reads the value *back* rather than only checking that no exception was
 * thrown: a client that mangled a quote, a backslash or a 100 KiB payload would pass
 * a "no error" test and fail every one of these.
 *
 * Run with `./gradlew integrationTest`. Without a server binary the whole class is
 * skipped, with the reason printed.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveTest {

    private var server: TriCoreServer? = null

    @BeforeAll
    fun startServer() {
        assumeTrue(TriCoreServer.skipReason == null, TriCoreServer.skipReason ?: "")
        server = TriCoreServer.start()
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    private fun live(body: suspend (TriCore) -> Unit) = runBlocking {
        val db = server!!.connect()
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a session authenticates and negotiates its capabilities`() = live { db ->
        db.ping()
        assertTrue(db.hasFeature(Feature.SERVER_PARAMS), "the server binds parameters")
        assertTrue(db.hasFeature(Feature.SESSION_TXN), "the server holds transactions open")
        assertFalse(db.isClosed)
    }

    @Test
    fun `values round-trip through bound parameters byte for byte`() = live { db ->
        val table = unique("kt_params")
        db.execute("CREATE TABLE $table (id INT PRIMARY KEY, t TEXT, b BLOB, f DOUBLE, k BOOL)")
        val victim = unique("kt_victim")
        db.execute("CREATE TABLE $victim (id INT PRIMARY KEY)")

        val quote = "O'Hara said 'hi'"
        val backslash = """C:\Users\trine\a\'b"""
        val injection = "'; DROP TABLE $victim; --"
        for ((id, text) in listOf(1 to quote, 2 to backslash, 3 to injection)) {
            db.execute("INSERT INTO $table (id, t) VALUES (?, ?)", id, text)
            val rows = db.query("SELECT t FROM $table WHERE id = ?", id)
            assertEquals(text, rows[0][0], "value $id changed in flight")
        }
        // The proof that the injection string was data: the table it named is still there.
        db.query("SELECT id FROM $victim")

        val blob = byteArrayOf(0x00, 0x01, 0xff.toByte(), 0xfe.toByte(), '\''.code.toByte(), '\\'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 0x00)
        db.execute("INSERT INTO $table (id, b, f, k) VALUES (?, ?, ?, ?)", 4, blob, -0.125, true)
        val row = db.query("SELECT b, f, k FROM $table WHERE id = ?", 4)[0]
        assertEquals("0x0001fffe275c686900", row[0], "every byte survives, NUL and invalid UTF-8 included")
        assertEquals("-0.125", row[1])
        assertEquals("true", row[2])

        // A bound null is SQL NULL, not the four letters N-U-L-L.
        db.execute("INSERT INTO $table (id, t) VALUES (?, ?)", 5, null)
        val nulls = db.query("SELECT COUNT(*) FROM $table WHERE id = ? AND t IS NULL", 5)
        assertEquals("1", nulls[0][0])

        db.execute("CREATE TABLE ${table}_d (id INT PRIMARY KEY, amount DECIMAL)")
        db.execute("INSERT INTO ${table}_d VALUES (?, ?)", 1, java.math.BigDecimal("10.50"))
        assertEquals("10.50", db.query("SELECT amount FROM ${table}_d")[0][0], "an exact decimal keeps its digits")

        db.execute("DROP TABLE $table")
        db.execute("DROP TABLE ${table}_d")
        db.execute("DROP TABLE $victim")
    }

    @Test
    fun `the query and execute split is enforced by the server`() = live { db ->
        val table = unique("kt_split")
        db.execute("CREATE TABLE $table (id INT PRIMARY KEY)")
        assertFailsWith<ServerException> { db.query("INSERT INTO $table VALUES (1)") }
        val error = assertFailsWith<ServerException> { db.execute("THIS IS NOT SQL AT ALL") }
        assertNotNull(error.code)
        assertFalse(db.isClosed, "a refusal leaves the connection usable")
        db.ping()
        db.execute("DROP TABLE $table")
    }

    @Test
    fun `a transaction script commits as one unit`() = live { db ->
        val table = unique("kt_txn")
        db.execute("CREATE TABLE $table (id INT PRIMARY KEY, name TEXT)")
        db.transaction {
            execute("INSERT INTO $table VALUES (?, ?)", 1, "ada")
            execute("INSERT INTO $table VALUES (?, ?)", 2, "grace")
        }
        assertEquals("2", db.query("SELECT COUNT(*) FROM $table")[0][0])
        db.execute("DROP TABLE $table")
    }

    @Test
    fun `a session transaction rolls back what it does not commit`() = live { db ->
        val table = unique("kt_session_txn")
        db.execute("CREATE TABLE $table (id INT PRIMARY KEY, name TEXT)")

        db.begin()
        db.execute("INSERT INTO $table VALUES (?, ?)", 1, "ada")
        db.rollback()
        assertEquals("0", db.query("SELECT COUNT(*) FROM $table")[0][0], "a rolled-back write must not persist")

        db.begin()
        db.execute("INSERT INTO $table VALUES (?, ?)", 2, "grace")
        db.commit()
        assertEquals("1", db.query("SELECT COUNT(*) FROM $table")[0][0])
        db.execute("DROP TABLE $table")
    }

    @Test
    fun `a cache value of 100 KiB round-trips byte for byte`() = live { db ->
        val ns = unique("kt_cache")
        // Larger than one TCP segment: the case a single-read client passes locally
        // and corrupts in production.
        val big = ByteArray(100 * 1024) { ((it * 31 + 7) % 256).toByte() }
        db.cache.set(ns, "big", big)
        assertContentEquals(big, db.cache.get(ns, "big"))

        assertNull(db.cache.get(ns, "absent"), "a miss is null")
        db.cache.set(ns, "empty", ByteArray(0))
        assertContentEquals(ByteArray(0), db.cache.get(ns, "empty"), "an empty value is not a miss")

        assertTrue(db.cache.exists(ns, "big"))
        assertTrue(db.cache.delete(ns, "big"))
        assertFalse(db.cache.delete(ns, "big"), "deleting an absent key is false, not an error")
        db.cache.clearNamespace(ns)
    }

    @Test
    fun `cache collections behave`() = live { db ->
        val ns = unique("kt_coll")
        db.cache.ping()

        assertEquals(2L, db.cache.rPush(ns, "q", listOf("a".toByteArray(), "b".toByteArray())))
        assertEquals(3L, db.cache.lPush(ns, "q", listOf("z".toByteArray())))
        assertEquals(3L, db.cache.lLen(ns, "q"))
        assertContentEquals("z".toByteArray(), db.cache.lPop(ns, "q"))
        assertContentEquals("b".toByteArray(), db.cache.rPop(ns, "q"))

        assertEquals(2L, db.cache.sAdd(ns, "tags", listOf("kt".toByteArray(), "db".toByteArray())))
        assertEquals(0L, db.cache.sAdd(ns, "tags", listOf("kt".toByteArray())), "an existing member adds nothing")
        assertTrue(db.cache.sIsMember(ns, "tags", "db".toByteArray()))
        assertEquals(2L, db.cache.sCard(ns, "tags"))

        // A field and a value that are not valid UTF-8 must survive, which is why
        // this API speaks bytes rather than strings.
        val field = byteArrayOf(0xff.toByte(), 0x00, 0xfe.toByte())
        val value = byteArrayOf(0x00, 0xc3.toByte(), 0x28)
        db.cache.hSet(ns, "h", listOf(CacheField(field, value)))
        assertContentEquals(value, db.cache.hGet(ns, "h", field))
        assertEquals(1L, db.cache.hLen(ns, "h"))

        val id = db.cache.xAdd(ns, "events", listOf(CacheField.of("msg", "hi")))
        assertTrue(id.isNotEmpty())
        assertEquals(1L, db.cache.xLen(ns, "events"))
        assertEquals(1, db.cache.xRange(ns, "events").size)

        assertEquals(5L, db.cache.incr(ns, "hits", 5))
        assertTrue(db.cache.setNx(ns, "lock", "1".toByteArray()))
        assertFalse(db.cache.setNx(ns, "lock", "2".toByteArray()), "set-if-absent is how a lock is taken")
        db.cache.clearNamespace(ns)
    }

    @Test
    fun `documents can be written, queried and aggregated`() = live { db ->
        val collection = unique("kt_docs")
        db.document.createCollection(collection)

        val id = db.document.insert(collection, mapOf("name" to "widget", "price" to 9, "kind" to "tool"))
        db.document.insert(collection, mapOf("name" to "gadget", "price" to 20, "kind" to "tool"), id = "gadget")

        assertEquals("gadget", db.document.get(collection, "gadget")?.get("name"))
        assertNull(db.document.get(collection, "missing"), "an absent document is null, not an empty map")
        assertEquals(1, db.document.find(collection, DocumentFilter.Gt("price", 10)).size)
        assertEquals(2, db.document.find(collection).size)

        db.document.updateOne(collection, "gadget", update { inc("price", 5) })
        assertEquals(25.0, (db.document.get(collection, "gadget")!!["price"] as Number).toDouble())

        val counts = db.document.updateMany(collection, DocumentFilter.Eq("kind", "tool"), update { set("kind", "hardware") })
        assertEquals(2L, counts.matched)

        db.document.createIndex(collection, "by_name", "name", unique = true)
        assertTrue(db.document.listIndexes(collection).any { it.indexName == "by_name" })
        db.document.dropIndex(collection, "by_name")
        assertEquals(2L, db.document.analyze(collection).documentCount)

        val totals = db.document.aggregate(collection) {
            match(DocumentFilter.Gt("price", 1))
            group(GroupKey.Constant("all")) {
                sum("total", "price")
                count("n")
            }
        }
        assertEquals(1, totals.size)
        assertEquals(34.0, (totals[0]["total"] as Number).toDouble(), "9 + 25")

        db.document.delete(collection, id)
        db.document.dropCollection(collection)
    }

    @Test
    fun `vectors are searchable and filterable`() = live { db ->
        val collection = unique("kt_vec")
        db.vector.createCollection(collection, 3, VectorMetric.COSINE)
        db.vector.upsert(collection, "a", floatArrayOf(0.1f, 0.2f, 0.3f), mapOf("kind" to "doc"))
        db.vector.upsert(collection, "b", floatArrayOf(0.9f, 0.1f, 0.0f), mapOf("kind" to "image"))

        val stored = db.vector.get(collection, "a")
        assertNotNull(stored)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), stored.vector, "stored exactly, not quantized")
        assertNull(db.vector.get(collection, "zz"))

        val hits = db.vector.search(collection, floatArrayOf(0.1f, 0.2f, 0.3f), topK = 2)
        assertEquals("a", hits.first().id, "the nearest vector comes first")

        val filtered = db.vector.search(collection, floatArrayOf(0.1f, 0.2f, 0.3f), topK = 5, filter = mapOf("kind" to "image"))
        assertEquals(listOf("b"), filtered.map { it.id })

        val info = db.vector.describeCollection(collection)
        assertEquals(3, info.dimension)
        assertEquals(2L, info.count)

        // A wrong-length vector is refused rather than padded or truncated.
        assertFailsWith<ServerException> { db.vector.upsert(collection, "bad", floatArrayOf(1.0f, 2.0f)) }

        db.vector.delete(collection, "a")
        db.vector.dropCollection(collection)
    }

    @Test
    fun `graphs traverse and find paths`() = live { db ->
        val graph = unique("kt_graph")
        db.graph.create(graph)
        for (id in listOf("u1", "u2", "u3")) db.graph.addNode(graph, id, listOf("User"))
        db.graph.addEdge(graph, "e1", "u1", "u2", "FOLLOWS", mapOf("weight" to 1.0))
        db.graph.addEdge(graph, "e2", "u2", "u3", "FOLLOWS", mapOf("weight" to 1.0))

        val node = db.graph.getNode(graph, "u1")
        assertNotNull(node)
        assertEquals(listOf("User"), node.labels)
        assertNull(db.graph.getNode(graph, "nobody"))

        assertEquals(1, db.graph.neighbors(graph, "u1").size)
        assertEquals(2L, db.graph.degree(graph, "u2", GraphDirection.BOTH))
        assertTrue(db.graph.traverse(graph, "u1").nodes.any { it.id == "u3" })

        val path = db.graph.shortestPath(graph, "u1", "u3")
        assertTrue(path.found)
        assertEquals(listOf("u1", "u2", "u3"), path.nodePath)

        // No path is an answer, not an error.
        assertFalse(db.graph.shortestPath(graph, "u3", "u1").found)

        db.graph.deleteEdge(graph, "e1")
        db.graph.deleteNode(graph, "u1")
        db.graph.drop(graph)
    }

    @Test
    fun `context exports render in the format asked for`() = live { db ->
        val table = unique("kt_llm")
        db.execute("CREATE TABLE $table (id INT PRIMARY KEY, name TEXT)")
        db.execute("INSERT INTO $table VALUES (?, ?)", 1, "ada")

        val bundle = db.llm.context(listOf(LlmSource.Sql("SELECT id, name FROM $table")), OutputFormat.TOON)
        assertTrue(bundle.contains("ada"), "the bundle holds the row: $bundle")
        assertTrue(db.llm.schema(OutputFormat.MARKDOWN).isNotEmpty())

        db.execute("DROP TABLE $table")
    }

    @Test
    fun `a disabled module is refused by name`() = live { db ->
        // The test server runs without the cluster module, so the admin plane says so
        // rather than pretending to be healthy.
        val error = assertFailsWith<ServerException> { db.admin.ping() }
        assertNotNull(error.code)
        db.ping()
    }

    @Test
    fun `a pool serves several coroutines at once`() = runBlocking {
        val table = unique("kt_pool")
        val pool = TriCorePool(server!!.config(), size = 4)
        try {
            pool.use { db -> db.execute("CREATE TABLE $table (id INT PRIMARY KEY)") }
            coroutineScope {
                (1..8).map { id ->
                    async(Dispatchers.IO) { pool.use { db -> db.execute("INSERT INTO $table VALUES (?)", id) } }
                }.awaitAll()
            }
            pool.use { db ->
                assertEquals("8", db.query("SELECT COUNT(*) FROM $table")[0][0])
                assertEquals(0, pool.stats().inUse, "every connection came back")
                db.execute("DROP TABLE $table")
            }
        } finally {
            pool.close()
        }
    }
}
