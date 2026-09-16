# tricoredb-kotlin

Official Kotlin client for [TriCoreDB](https://hub.docker.com/r/trinesh14/tricoredb):
SQL, documents, vectors, graphs and cache over one native connection.

[![Maven Central](https://img.shields.io/maven-central/v/com.tricoredb/tricoredb-kotlin?logo=apachemaven&label=maven%20central&color=blue&cacheSeconds=1800)](https://central.sonatype.com/artifact/com.tricoredb/tricoredb-kotlin)
[![javadoc](https://img.shields.io/badge/docs-javadoc.io-blue?cacheSeconds=86400)](https://javadoc.io/doc/com.tricoredb/tricoredb-kotlin)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue?cacheSeconds=86400)](LICENSE)

- **Coroutines throughout.** Every call is a `suspend` function; nothing blocks a thread
  while it waits for the server.
- **One dependency:** `kotlinx-coroutines-core`.
- **Server-side parameters.** Values never become part of the SQL text.
- **Transactions, connection pooling, TLS and mutual TLS.**

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Running a server](#running-a-server)
- [Quick start](#quick-start)
- [Connecting](#connecting)
- [SQL](#sql)
- [Transactions](#transactions)
- [Connection pool](#connection-pool)
- [Cache](#cache)
- [Documents](#documents)
- [Vectors](#vectors)
- [Graphs](#graphs)
- [LLM context](#llm-context)
- [Admin](#admin)
- [Errors](#errors)
- [TLS](#tls)
- [Building and testing](#building-and-testing)

## Requirements

- Java **17** or later, Kotlin **2.0** or later
- A TriCoreDB server speaking protocol 1.0 (`tricore-server` 0.1.0-rc.1 or later).
  See [Running a server](#running-a-server).

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("com.tricoredb:tricoredb-kotlin:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.tricoredb</groupId>
  <artifactId>tricoredb-kotlin</artifactId>
  <version>0.1.0</version>
</dependency>
```

`kotlinx-coroutines-core` comes with it as an `api` dependency, so calling a `suspend`
function needs nothing else declared.

## Running a server

The quickest way is the official Docker image,
[`trinesh14/tricoredb`](https://hub.docker.com/r/trinesh14/tricoredb).

**Local development** (no TLS and no encryption, for this machine only). Set
`TRICORE_ADMIN_PASSWORD` in your shell first. Then create the admin and start the
server:

```bash
docker run --rm -v tricoredb-dev:/var/lib/tricoredb -e TRICORE_ADMIN_PASSWORD --entrypoint /usr/local/bin/tricore trinesh14/tricoredb:0.1.0-rc.1-r2 auth init-admin --user admin --password-env TRICORE_ADMIN_PASSWORD --data-dir /var/lib/tricoredb/data
docker run -d --name tricoredb-dev -p 127.0.0.1:8427:8427 -e TRICORE_TLS=off -e TRICORE_ENCRYPTION=off -e TRICORE_MODULES=all -v tricoredb-dev:/var/lib/tricoredb trinesh14/tricoredb:0.1.0-rc.1-r2
```

**Anything else:** by default the image runs with **TLS on** and an **encrypted data
volume**. Follow the quick start on the
[Docker Hub page](https://hub.docker.com/r/trinesh14/tricoredb) to create the
certificate and key, then connect with [TLS](#tls).

`TRICORE_MODULES=all` enables every data model. The image's default is `sql`,
`document` and `cache`; a call to a disabled model throws a `ServerException` whose
`code` is `engine.disabled`.

## Quick start

```kotlin
import com.tricoredb.kt.TriCore
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    TriCore.connect("127.0.0.1", 8427, "admin", "your-password").use { db ->
        db.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name TEXT)")
        db.execute("INSERT INTO users VALUES (?, ?)", 1, "O'Hara")

        val rows = db.query("SELECT name FROM users WHERE id = ?", 1)
        println(rows[0][0])            // O'Hara

        db.cache.setText("sessions", "u1", "token")
        println(db.cache.getText("sessions", "u1"))
    }
}
```

## Connecting

`TriCore.connect` opens one authenticated connection. It is `Closeable`, so `use { }`
closes it; a closed connection refuses further calls rather than reconnecting behind
your back.

```kotlin
val db = TriCore.connect(
    TriCoreConfig(
        host = "db.internal",
        port = 8427,
        user = "admin",
        secret = "your-password",
        database = "reporting",
        connectTimeout = 5.seconds,
    )
)
```

| `TriCoreConfig` field | Default | Meaning |
| --- | --- | --- |
| `host` | `"127.0.0.1"` | Server host |
| `port` | `8427` (`Protocol.DEFAULT_PORT`) | Server port |
| `user` | `null` | Principal to authenticate as; `null` skips authentication |
| `secret` | `""` | Password or token |
| `database` | `"main"` | Database named in every request |
| `clientName` | `"tricoredb-kotlin/<version>"` | Name reported in the handshake |
| `connectTimeout` | `10.seconds` | Bound on the connect, TLS and handshake |
| `readTimeout` | `null` | Bound on each wait for a reply |
| `requestTimeout` | `null` | Server-side deadline stamped on each request |
| `tls` | `null` (plain TCP) | See [TLS](#tls) |
| `features` | every capability | Bitmap announced in the handshake |

`toString()` on the config prints `secret=***`, so a logged config never leaks the
password.

**One connection runs one request at a time.** For concurrent work use a
[pool](#connection-pool) rather than sharing a connection between coroutines.

## SQL

`query` runs only `SELECT`. `execute` runs everything else. The server enforces the
split: a write sent through `query` is refused.

```kotlin
db.execute("INSERT INTO users VALUES (?, ?)", 2, "ada")
val rows = db.query("SELECT id, name FROM users")
rows.forEach { row -> println(row) }
println(rows[0][1])
```

Placeholders are bound **on the server**: the values travel next to the statement, so a
value can never be read as SQL syntax, however it is spelled. How a Kotlin value goes
out:

| Kotlin value | Sent as |
| --- | --- |
| `null` | SQL `NULL` |
| `Boolean` | a boolean |
| `Int`, `Long`, `BigInteger`, unsigned types | an exact number |
| `Float`, `Double` | a number; `NaN` and infinities are refused |
| `BigDecimal` | plain digits, no exponent — for `DECIMAL` |
| `ByteArray` | `0x`-prefixed hex, for `BLOB` |
| `String`, `Char`, `UUID` | text |
| `Instant`, `LocalDateTime`, `OffsetDateTime`, `LocalDate` | the timestamp text the server stores |

Binding needs the `SERVER_PARAMS` capability, agreed in the handshake
(`db.hasFeature(Feature.SERVER_PARAMS)`). Against a server that did not grant it, a call
with parameters throws `FeatureNotGrantedException` **before anything is sent** — it
never falls back to pasting values into the statement text.

## Transactions

`db.transaction { }` sends a whole `BEGIN … COMMIT` script in **one request**. It works
on every node and is right when every statement is known up front:

```kotlin
db.transaction {
    execute("UPDATE accounts SET balance = balance - ? WHERE id = ?", 10, 1)
    execute("UPDATE accounts SET balance = balance + ? WHERE id = ?", 10, 2)
}
```

`begin`, `commit` and `rollback` keep a transaction open **across requests on this
connection**, so a later statement can depend on what an earlier one read:

```kotlin
db.begin()
try {
    db.execute("INSERT INTO t VALUES (?, ?)", 1, "ada")
    db.commit()
} catch (e: Throwable) {
    db.rollback()
    throw e
}
```

These need the `SESSION_TXN` capability; without it `begin` throws by name rather than
running each statement on its own. The transaction belongs to this connection: another
connection cannot commit it, and a dropped socket rolls it back.

## Connection pool

```kotlin
val pool = TriCorePool(config, size = 8)
try {
    coroutineScope {
        (1..8).map { id ->
            async { pool.use { db -> db.execute("INSERT INTO users VALUES (?, ?)", id, "grace") } }
        }.awaitAll()
    }
} finally {
    pool.close()
}
```

`pool.use { }` lends a connection for the duration of the block. A connection is never
returned to the pool with a transaction still open: it is rolled back first, and the
connection is retired if that rollback fails. When every connection is busy, a borrower
waits up to `acquireTimeout` and then gets `PoolTimeoutException` — the pool never grows
past `size`, because an unbounded pool does not fix overload, it moves it to the server.

## Cache

Values are bytes; the `*Text` helpers do UTF-8 for the common case. `null` is a miss,
which is how a miss is told apart from a stored empty value.

```kotlin
db.cache.set("sessions", "u1", byteArrayOf(1, 2, 3))
db.cache.setText("sessions", "u2", "token", ttlMs = 30_000)
val value: ByteArray? = db.cache.get("sessions", "u1")

db.cache.incr("counters", "hits")
db.cache.rPush("queue", "jobs", listOf("a".toByteArray(), "b".toByteArray()))
db.cache.sAdd("tags", "post:1", listOf("kotlin".toByteArray()))
db.cache.hSet("user:1", "profile", listOf(CacheField.of("name", "ada")))
val id = db.cache.xAdd("events", "log", listOf(CacheField.of("msg", "hi")))
```

Families: keys and TTLs (`set`, `setNx`, `get`, `delete`, `exists`, `ttl`, `expire`,
`persist`, `incr`, `keys`, `clearNamespace`), lists (`lPush` … `lIndex`), sets (`sAdd` …
`sMembers`), hashes (`hSet` … `hLen`) and streams (`xAdd` … `xTrim`).

## Documents

```kotlin
db.document.createCollection("products")
val id = db.document.insert("products", mapOf("name" to "widget", "price" to 9))
val cheap = db.document.find("products", DocumentFilter.Lt("price", 10))
db.document.updateOne("products", id, update { inc("price", 1) })

val totals = db.document.aggregate("orders") {
    match(DocumentFilter.Eq("status", "paid"))
    group(GroupKey.Field("customer")) { sum("total", "amount") }
    sort(SortKey("total", descending = true))
    limit(10)
}
```

Filters and pipeline stages are built with these constructors, so the request JSON is
never written by hand. Also available: `get`, `findLimit` via `find(limit = …)`,
`update`, `updateMany`, `upsert` through `updateOne(upsert = true)`, `delete`,
`listCollections`, `dropCollection`, `createIndex`, `dropIndex`, `listIndexes` and
`analyze`.

## Vectors

```kotlin
db.vector.createCollection("embeddings", dimension = 3, metric = VectorMetric.COSINE)
db.vector.upsert("embeddings", "a", floatArrayOf(0.1f, 0.2f, 0.3f), mapOf("kind" to "doc"))
val hits = db.vector.search("embeddings", floatArrayOf(0.1f, 0.2f, 0.3f), topK = 5)
val onlyDocs = db.vector.search("embeddings", floatArrayOf(0.1f, 0.2f, 0.3f), topK = 5, filter = mapOf("kind" to "doc"))
```

The score is a **similarity**: higher is closer under every metric, and results come
back best first. L2 is the case worth knowing — the server negates the squared distance,
so an L2 score is `<= 0` and `-0.02` is nearer than `-196.0`.

## Graphs

```kotlin
db.graph.create("social")
db.graph.addNode("social", "u1", listOf("User"), mapOf("name" to "ada"))
db.graph.addNode("social", "u2", listOf("User"))
db.graph.addEdge("social", "e1", "u1", "u2", "FOLLOWS")

val neighbours = db.graph.neighbors("social", "u1")
val path = db.graph.shortestPath("social", "u1", "u2")
println("${path.hops} hops: ${path.nodePath}")
```

"No path" comes back as `found == false`, not as an error. Also available: `getNode`,
`getEdge`, `deleteNode`, `deleteEdge`, `listGraphs`, `drop`, `traverse`,
`weightedShortestPath`, `degree`, `listNodes`, `listEdges` and `query` for the read-only
Cypher subset.

## LLM context

```kotlin
val bundle = db.llm.context(
    listOf(
        LlmSource.Sql("SELECT id, name FROM users"),
        LlmSource.DocumentFind("products", DocumentFilter.All),
    ),
    OutputFormat.TOON,
)
val schema = db.llm.schema(OutputFormat.MARKDOWN)
```

Sensitive fields are redacted by default.

## Admin

```kotlin
db.admin.ping()
val status = db.admin.status()
```

Admin calls need the cluster module enabled on the server, even on a single node.
`db.ping()` checks the connection itself and reaches no module.

## Errors

Everything this client throws is a `TriCoreException`. Branch on the type and on `code`,
never on the message text:

| Type | Means |
| --- | --- |
| `ServerException` | The request arrived and the operation failed. The connection stays usable. |
| `AuthException` | The credentials were refused. |
| `ProtocolException` | An ERROR frame, or a peer that broke the protocol. |
| `ConnectionException` | The transport failed, or the connection was already closed. |
| `TriCoreTimeoutException` | A deadline passed; the connection is closed, because the late reply must not be read as the next answer. |
| `FeatureNotGrantedException` | The server lacks a capability this call needs, so nothing was sent. |
| `PoolTimeoutException` / `PoolMisuseException` | No pooled connection in time; or a block that broke a pool rule. |

**Leader redirects.** In a cluster, a write that reaches a follower fails with
`code == "not_leader"`, which `isRedirect` tests. When the cluster knows the leader,
`leaderHint` holds its `host:port`; a `null` hint means the destination is unknown yet,
so wait and retry — it does not mean the failure was something else. This client does
not follow the redirect for you: where to resend a write is your application's decision.

```kotlin
try {
    db.execute("INSERT INTO t VALUES (1)")
} catch (e: ServerException) {
    if (e.isRedirect) retryAgainst(e.leaderHint)
    else throw e
}
```

## TLS

TLS is off until `TriCoreConfig.tls` is set. Without it the secret crosses the wire in
the clear.

```kotlin
val db = TriCore.connect(
    TriCoreConfig(
        host = "db.internal", port = 8427, user = "admin", secret = "your-password",
        tls = TlsOptions(caFile = File("/etc/tricore/ca.pem"), serverName = "db.internal"),
    )
)
```

With TLS on, the certificate chain and the host name are always checked. Note one
difference from the other TriCoreDB SDKs: with **no** `caFile` this client verifies
against the **JVM's default trust store** rather than an empty one, because that is what
JSSE does. Point `caFile` at your own CA for a private certificate.

| `TlsOptions` field | Default | Meaning |
| --- | --- | --- |
| `caFile` | `null` (the JVM trust store) | PEM CA bundle that verifies the server |
| `serverName` | the connection host | Expected name (SNI and certificate check) |
| `clientCertFile` / `clientKeyFile` | `null` | PEM certificate and PKCS#8 key, for mutual TLS |
| `sslContext` | `null` | A fully configured context, instead of the files above |
| `dangerAcceptInvalidCerts` | `false` | **Development only.** Skips all verification. |

## Building and testing

```bash
./gradlew build             # compile, unit tests and the scripted-peer tests
./gradlew integrationTest   # the live tests, against a private tricore-server
```

The unit and scripted-peer tests need no server: a scripted peer plays the answers a
real cluster would send, including a `not_leader` refusal and a frame that declares more
bytes than it sends. The live tests start their own `tricore-server` on an ephemeral
port — point `TRICORE_SERVER_BIN` at the binary, and without one they are **skipped**
rather than failed.

## License

[Apache License 2.0](LICENSE)
