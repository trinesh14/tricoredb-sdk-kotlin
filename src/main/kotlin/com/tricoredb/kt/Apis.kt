package com.tricoredb.kt

private fun Response.json(what: String): Any? = payload("Json").also { if (!has("Json")) throw UnexpectedResponseException(what) }

private fun Response.jsonObject(what: String): Map<*, *> = Wire.obj(payload("Json"), what)

private fun Response.cacheValue(): ByteArray? {
    val v = payload("CacheValue")
    return if (v == null) null else Wire.bytes(v, "cache value")
}

/** Document operations. Obtain it as [TriCore.document]. */
public class DocumentApi internal constructor(private val db: TriCore) {
    private suspend fun op(name: String, body: Any): Response = db.request(mapOf("Document" to mapOf(name to body)))

    /** Create a collection. */
    public suspend fun createCollection(collection: String) {
        op("CreateCollection", mapOf("collection" to collection))
    }

    /** Drop a collection. */
    public suspend fun dropCollection(collection: String) {
        op("DropCollection", mapOf("collection" to collection))
    }

    /** Collection names. */
    public suspend fun listCollections(): List<String> {
        val r = db.request(mapOf("Document" to "ListCollections")).jsonObject("document listCollections")
        return Wire.strings(Wire.field(r, "collections", "document listCollections"), "collections")
    }

    /**
     * Insert [document] and return the id it was stored under. With [id] `null` the server generates one or adopts
     * the document's own `_id`. A duplicate id is an error, not an overwrite.
     */
    public suspend fun insert(collection: String, document: Map<String, Any?>, id: String? = null): String {
        val r = op("Insert", linkedMapOf("collection" to collection, "id" to id, "document" to document))
            .jsonObject("document insert")
        return Wire.string(r, "id", "document insert")
    }

    /** The document with [id], or `null` when there is none. */
    public suspend fun get(collection: String, id: String): Map<String, Any?>? {
        val docs = Wire.list(op("Get", mapOf("collection" to collection, "id" to id)).payload("Documents"), "document get")
        return docs.firstOrNull()?.let { Wire.objectMap(it) }
    }

    /** Documents matching [filter], at most [limit]. */
    public suspend fun find(collection: String, filter: DocumentFilter = DocumentFilter.All, limit: Int? = null): List<Map<String, Any?>> {
        val resp = op("Find", linkedMapOf("collection" to collection, "filter" to filter.wire(), "limit" to limit))
        return Wire.list(resp.payload("Documents"), "document find").map { Wire.objectMap(it) }
    }

    /** Documents matching a filter built with the [filter] DSL: `find("users") { "city" eq "Pune" }`. */
    public suspend fun find(collection: String, limit: Int? = null, where: FilterBuilder.() -> Unit): List<Map<String, Any?>> =
        find(collection, filter(where), limit)

    /** Set fields on an existing document (dot-notation paths). Not an upsert. */
    public suspend fun update(collection: String, id: String, set: Map<String, Any?>) {
        op("Update", linkedMapOf("collection" to collection, "id" to id, "set" to set))
    }

    /** Apply [update] to one document; with [upsert] a missing id is inserted. */
    public suspend fun updateOne(collection: String, id: String, update: DocumentUpdate, upsert: Boolean = false): UpdateOneResult {
        val r = op("UpdateOne", linkedMapOf("collection" to collection, "id" to id, "update" to update.wire(), "upsert" to upsert))
        val d = r.data
        val j = if (d is Map<*, *>) d["Json"] as? Map<*, *> else null
        return UpdateOneResult(
            updated = j?.get("updated") as? Boolean ?: false,
            inserted = j?.get("inserted") as? Boolean ?: false,
            id = j?.get("id") as? String,
        )
    }

    /** Apply [update] to every document matching [filter]. Never upserts. */
    public suspend fun updateMany(collection: String, filter: DocumentFilter, update: DocumentUpdate): UpdateManyResult {
        val r = op("UpdateMany", linkedMapOf("collection" to collection, "filter" to filter.wire(), "update" to update.wire()))
            .jsonObject("document updateMany")
        return UpdateManyResult(Wire.long(r, "matched", "updateMany"), Wire.long(r, "modified", "updateMany"))
    }

    /** Delete the document with [id]. */
    public suspend fun delete(collection: String, id: String) {
        op("Delete", mapOf("collection" to collection, "id" to id))
    }

    /** Create an index on a top-level [field]. */
    public suspend fun createIndex(collection: String, indexName: String, field: String, unique: Boolean = false) {
        op("CreateIndex", linkedMapOf("collection" to collection, "index_name" to indexName, "field" to field, "unique" to unique))
    }

    /** Drop the index [indexName]. */
    public suspend fun dropIndex(collection: String, indexName: String) {
        op("DropIndex", mapOf("collection" to collection, "index_name" to indexName))
    }

    /** The collection's indexes. */
    public suspend fun listIndexes(collection: String): List<DocumentIndex> {
        val r = op("ListIndexes", mapOf("collection" to collection)).jsonObject("document listIndexes")
        return Wire.list(Wire.field(r, "indexes", "listIndexes"), "indexes").map {
            val m = Wire.obj(it, "index")
            DocumentIndex(
                Wire.string(m, "index_name", "index"),
                Wire.string(m, "field", "index"),
                m["unique"] as? Boolean ?: false,
            )
        }
    }

    /** Collect optimizer statistics. */
    public suspend fun analyze(collection: String): DocumentStats {
        val r = op("Analyze", mapOf("collection" to collection)).jsonObject("document analyze")
        val fields = (r["indexed_fields"] as? List<*>)?.map { it.toString() } ?: emptyList()
        return DocumentStats(Wire.long(r, "document_count", "analyze"), fields, Wire.objectMap(r))
    }

    /** Run an aggregation pipeline. */
    public suspend fun aggregate(collection: String, pipeline: List<AggregateStage>): List<Map<String, Any?>> {
        val resp = op("Aggregate", mapOf("collection" to collection, "pipeline" to pipeline.map { it.wire() }))
        return Wire.list(resp.payload("Documents"), "document aggregate").map { Wire.objectMap(it) }
    }

    /** Run a pipeline built with the [pipeline] DSL. */
    public suspend fun aggregate(collection: String, block: PipelineBuilder.() -> Unit): List<Map<String, Any?>> =
        aggregate(collection, pipeline(block))
}

/**
 * Cache operations. Values are bytes; the `*Text` helpers encode UTF-8. A key holds one type at a time, and using
 * it as another type is an error, never a coercion. Obtain it as [TriCore.cache].
 */
public class CacheApi internal constructor(private val db: TriCore) {
    private suspend fun op(name: String, body: Any): Response = db.request(mapOf("Cache" to mapOf(name to body)))

    private suspend fun obj(name: String, body: Any): Map<*, *> = op(name, body).jsonObject("cache $name")

    private fun nk(namespace: String, key: String) = linkedMapOf<String, Any?>("namespace" to namespace, "key" to key)

    private fun nonEmpty(values: List<ByteArray>, what: String): List<List<Int>> {
        require(values.isNotEmpty()) { "$what must not be empty" }
        return values.map { Wire.byteList(it) }
    }

    private fun pairs(entries: List<CacheField>, what: String): List<List<List<Int>>> {
        require(entries.isNotEmpty()) { "$what must not be empty" }
        return entries.map { listOf(Wire.byteList(it.field), Wire.byteList(it.value)) }
    }

    /** Liveness check routed through the cache module. */
    public suspend fun ping() {
        db.request(mapOf("Cache" to "Ping"))
    }

    /** Store [value] under [key], with an optional TTL in milliseconds. */
    public suspend fun set(namespace: String, key: String, value: ByteArray, ttlMs: Long? = null) {
        op("Set", nk(namespace, key).apply { put("value", Wire.byteList(value)); put("ttl_ms", ttlMs) })
    }

    /** Store UTF-8 [value]. */
    public suspend fun setText(namespace: String, key: String, value: String, ttlMs: Long? = null): Unit =
        set(namespace, key, value.toByteArray(Charsets.UTF_8), ttlMs)

    /** The value, or `null` on a miss. */
    public suspend fun get(namespace: String, key: String): ByteArray? = op("Get", nk(namespace, key)).cacheValue()

    /** The value decoded as UTF-8, or `null` on a miss. */
    public suspend fun getText(namespace: String, key: String): String? = get(namespace, key)?.toString(Charsets.UTF_8)

    /** Delete [key]; true when it existed. */
    public suspend fun delete(namespace: String, key: String): Boolean = Wire.bool(obj("Delete", nk(namespace, key)), "deleted", "cache delete")

    /** Whether [key] exists. */
    public suspend fun exists(namespace: String, key: String): Boolean = Wire.bool(obj("Exists", nk(namespace, key)), "exists", "cache exists")

    /** Remaining TTL in milliseconds, or `null` when the key has no expiry or does not exist. */
    public suspend fun ttl(namespace: String, key: String): Long? = Wire.longOrNull(obj("Ttl", nk(namespace, key)), "ttl_ms")

    /** Delete every key in [namespace]; returns how many were removed. */
    public suspend fun clearNamespace(namespace: String): Long =
        Wire.long(obj("ClearNamespace", mapOf("namespace" to namespace)), "cleared", "cache clearNamespace")

    /** Add [by] to a counter and return the new value. A missing key starts at 0. */
    public suspend fun incr(namespace: String, key: String, by: Long = 1): Long =
        Wire.long(obj("Incr", nk(namespace, key).apply { put("by", by) }), "value", "cache incr")

    /** Set or replace a TTL; false when the key does not exist. */
    public suspend fun expire(namespace: String, key: String, ttlMs: Long): Boolean =
        Wire.bool(obj("Expire", nk(namespace, key).apply { put("ttl_ms", ttlMs) }), "updated", "cache expire")

    /** Remove a TTL; false when there was none. */
    public suspend fun persist(namespace: String, key: String): Boolean =
        Wire.bool(obj("Persist", nk(namespace, key)), "persisted", "cache persist")

    /** Set only if absent; true when this call stored the value. */
    public suspend fun setNx(namespace: String, key: String, value: ByteArray, ttlMs: Long? = null): Boolean =
        Wire.bool(obj("SetNx", nk(namespace, key).apply { put("value", Wire.byteList(value)); put("ttl_ms", ttlMs) }), "set", "cache setNx")

    /** Live keys, optionally filtered by a `*` glob [pattern], at most [limit]. */
    public suspend fun keys(namespace: String, pattern: String? = null, limit: Int? = null): List<CacheKeyInfo> {
        val r = obj("Keys", linkedMapOf("namespace" to namespace, "pattern" to pattern, "limit" to limit))
        return Wire.list(Wire.field(r, "keys", "cache keys"), "keys").map {
            val m = Wire.obj(it, "key")
            CacheKeyInfo(Wire.string(m, "key", "cache key"), Wire.longOrNull(m, "ttl_ms"), Wire.objectMap(m))
        }
    }

    /** Prepend [values]; returns the new length. */
    public suspend fun lPush(namespace: String, key: String, values: List<ByteArray>): Long =
        Wire.long(obj("LPush", nk(namespace, key).apply { put("values", nonEmpty(values, "values")) }), "length", "lPush")

    /** Append [values]; returns the new length. */
    public suspend fun rPush(namespace: String, key: String, values: List<ByteArray>): Long =
        Wire.long(obj("RPush", nk(namespace, key).apply { put("values", nonEmpty(values, "values")) }), "length", "rPush")

    /** Remove and return the first element, or `null`. */
    public suspend fun lPop(namespace: String, key: String): ByteArray? = op("LPop", nk(namespace, key)).cacheValue()

    /** Remove and return the last element, or `null`. */
    public suspend fun rPop(namespace: String, key: String): ByteArray? = op("RPop", nk(namespace, key)).cacheValue()

    /** Elements in the inclusive range; negative indices count from the end. */
    public suspend fun lRange(namespace: String, key: String, start: Long, stop: Long): List<ByteArray> {
        val r = obj("LRange", nk(namespace, key).apply { put("start", start); put("stop", stop) })
        return Wire.list(Wire.field(r, "values", "lRange"), "values").map { Wire.bytes(it, "list element") }
    }

    /** List length; 0 when missing. */
    public suspend fun lLen(namespace: String, key: String): Long = Wire.long(obj("LLen", nk(namespace, key)), "length", "lLen")

    /** One element by index, or `null` when out of range. */
    public suspend fun lIndex(namespace: String, key: String, index: Long): ByteArray? =
        op("LIndex", nk(namespace, key).apply { put("index", index) }).cacheValue()

    /** Add [members]; returns how many were new. */
    public suspend fun sAdd(namespace: String, key: String, members: List<ByteArray>): Long =
        Wire.long(obj("SAdd", nk(namespace, key).apply { put("members", nonEmpty(members, "members")) }), "added", "sAdd")

    /** Remove [members]; returns how many were present. */
    public suspend fun sRem(namespace: String, key: String, members: List<ByteArray>): Long =
        Wire.long(obj("SRem", nk(namespace, key).apply { put("members", nonEmpty(members, "members")) }), "removed", "sRem")

    /** Whether [member] is in the set. */
    public suspend fun sIsMember(namespace: String, key: String, member: ByteArray): Boolean =
        Wire.bool(obj("SIsMember", nk(namespace, key).apply { put("member", Wire.byteList(member)) }), "is_member", "sIsMember")

    /** Set cardinality; 0 when missing. */
    public suspend fun sCard(namespace: String, key: String): Long = Wire.long(obj("SCard", nk(namespace, key)), "cardinality", "sCard")

    /** Every member, in ascending byte order. */
    public suspend fun sMembers(namespace: String, key: String): List<ByteArray> {
        val r = obj("SMembers", nk(namespace, key))
        return Wire.list(Wire.field(r, "members", "sMembers"), "members").map { Wire.bytes(it, "member") }
    }

    /** Set hash fields; returns how many were newly created. */
    public suspend fun hSet(namespace: String, key: String, entries: List<CacheField>): Long =
        Wire.long(obj("HSet", nk(namespace, key).apply { put("entries", pairs(entries, "entries")) }), "created", "hSet")

    /** One field's value, or `null`. */
    public suspend fun hGet(namespace: String, key: String, field: ByteArray): ByteArray? =
        op("HGet", nk(namespace, key).apply { put("field", Wire.byteList(field)) }).cacheValue()

    /** Delete [fields]; returns how many were present. */
    public suspend fun hDel(namespace: String, key: String, fields: List<ByteArray>): Long =
        Wire.long(obj("HDel", nk(namespace, key).apply { put("fields", nonEmpty(fields, "fields")) }), "deleted", "hDel")

    /** Every field/value pair, in ascending field order. */
    public suspend fun hGetAll(namespace: String, key: String): List<CacheField> {
        val r = obj("HGetAll", nk(namespace, key))
        return Wire.list(Wire.field(r, "entries", "hGetAll"), "entries").map { pair(it) }
    }

    /** Whether [field] exists in the hash. */
    public suspend fun hExists(namespace: String, key: String, field: ByteArray): Boolean =
        Wire.bool(obj("HExists", nk(namespace, key).apply { put("field", Wire.byteList(field)) }), "exists", "hExists")

    /** Number of fields; 0 when missing. */
    public suspend fun hLen(namespace: String, key: String): Long = Wire.long(obj("HLen", nk(namespace, key)), "length", "hLen")

    /** Append a stream entry and return its id. [id] `null` auto-generates. */
    public suspend fun xAdd(namespace: String, key: String, fields: List<CacheField>, id: String? = null): String =
        Wire.string(obj("XAdd", nk(namespace, key).apply { put("id", id); put("fields", pairs(fields, "fields")) }), "id", "xAdd")

    /** Number of stream entries. */
    public suspend fun xLen(namespace: String, key: String): Long = Wire.long(obj("XLen", nk(namespace, key)), "length", "xLen")

    /** Entries with ids in the inclusive range (`-` and `+` are the extremes). */
    public suspend fun xRange(namespace: String, key: String, start: String = "-", end: String = "+", count: Int? = null): List<StreamEntry> =
        entries(obj("XRange", nk(namespace, key).apply { put("start", start); put("end", end); put("count", count) }))

    /** Entries strictly newer than [after]. Never blocks. */
    public suspend fun xRead(namespace: String, key: String, after: String = "0-0", count: Int? = null): List<StreamEntry> =
        entries(obj("XRead", nk(namespace, key).apply { put("after", after); put("count", count) }))

    /** Delete entries by id; returns how many were present. */
    public suspend fun xDel(namespace: String, key: String, ids: List<String>): Long =
        Wire.long(obj("XDel", nk(namespace, key).apply { put("ids", ids) }), "deleted", "xDel")

    /** Evict the oldest entries beyond [maxLen]; returns how many were evicted. */
    public suspend fun xTrim(namespace: String, key: String, maxLen: Long): Long =
        Wire.long(obj("XTrim", nk(namespace, key).apply { put("max_len", maxLen) }), "trimmed", "xTrim")

    /** Consumer-group command. The protocol defines it; servers in this release refuse it by name. */
    public suspend fun xGroup(namespace: String, key: String, command: String) {
        op("XGroup", nk(namespace, key).apply { put("command", command) })
    }

    private fun pair(v: Any?): CacheField {
        val p = Wire.list(v, "pair")
        if (p.size != 2) throw UnexpectedResponseException("expected a [field, value] pair")
        return CacheField(Wire.bytes(p[0], "field"), Wire.bytes(p[1], "value"))
    }

    private fun entries(r: Map<*, *>): List<StreamEntry> =
        Wire.list(Wire.field(r, "entries", "stream"), "entries").map {
            val m = Wire.obj(it, "stream entry")
            StreamEntry(Wire.string(m, "id", "stream entry"), Wire.list(Wire.field(m, "fields", "entry"), "fields").map { f -> pair(f) })
        }
}

/** Vector operations. Obtain it as [TriCore.vector]. */
public class VectorApi internal constructor(private val db: TriCore) {
    private suspend fun op(name: String, body: Any): Response = db.request(mapOf("Vector" to mapOf(name to body)))

    /** Create a collection of [dimension]-component vectors. */
    public suspend fun createCollection(
        collection: String,
        dimension: Int,
        metric: VectorMetric = VectorMetric.COSINE,
        quantization: VectorQuantization = VectorQuantization.NONE,
    ) {
        op("CreateCollection", linkedMapOf("collection" to collection, "dimension" to dimension, "metric" to metric.wire, "quantization" to quantization.wire))
    }

    /** Drop a collection. */
    public suspend fun dropCollection(collection: String) {
        op("DropCollection", mapOf("collection" to collection))
    }

    /** Collection names. */
    public suspend fun listCollections(): List<String> {
        val r = db.request(mapOf("Vector" to "ListCollections")).jsonObject("vector listCollections")
        return Wire.strings(Wire.field(r, "collections", "vector listCollections"), "collections")
    }

    /** Insert or replace a vector. The dimension must match the collection. */
    public suspend fun upsert(collection: String, id: String, vector: FloatArray, metadata: Map<String, Any?>? = null) {
        val body = linkedMapOf<String, Any?>("collection" to collection, "id" to id, "vector" to vector)
        if (metadata != null) body["metadata"] = metadata
        op("Upsert", body)
    }

    /** The vector with [id], or `null`. */
    public suspend fun get(collection: String, id: String): VectorItem? {
        val j = op("Get", mapOf("collection" to collection, "id" to id)).payload("Json") ?: return null
        return item(Wire.obj(j, "vector get"))
    }

    /** Delete the vector with [id]. */
    public suspend fun delete(collection: String, id: String) {
        op("Delete", mapOf("collection" to collection, "id" to id))
    }

    /** The [topK] nearest vectors, best first. [filter] is a flat map of metadata field to required value. */
    public suspend fun search(collection: String, vector: FloatArray, topK: Int, filter: Map<String, Any?>? = null): List<VectorMatch> {
        val r = op("Search", linkedMapOf("collection" to collection, "vector" to vector, "top_k" to topK, "filter" to filter))
            .jsonObject("vector search")
        return Wire.list(Wire.field(r, "results", "vector search"), "results").map {
            val m = Wire.obj(it, "search hit")
            VectorMatch(Wire.string(m, "id", "hit"), Wire.double(m, "score", "hit"), Wire.objectMapOrNull(m["metadata"]))
        }
    }

    /** Describe a collection. */
    public suspend fun describeCollection(collection: String): VectorCollectionInfo {
        val r = op("DescribeCollection", mapOf("collection" to collection)).jsonObject("vector describe")
        return VectorCollectionInfo(
            collection = r["collection"] as? String ?: collection,
            dimension = Wire.long(r, "dimension", "describe"),
            metric = VectorMetric.fromWire(Wire.string(r, "metric", "describe")),
            count = Wire.long(r, "count", "describe"),
            quantization = r["quantization"] as? String,
        )
    }

    /** A page of stored vectors, ordered by id. */
    public suspend fun listVectors(collection: String, limit: Int? = null, offset: Int? = null): VectorPage {
        val r = op("ListVectors", linkedMapOf("collection" to collection, "limit" to limit, "offset" to offset)).jsonObject("listVectors")
        return VectorPage(
            Wire.list(Wire.field(r, "vectors", "listVectors"), "vectors").map { item(Wire.obj(it, "vector")) },
            Wire.long(r, "total", "listVectors"),
            r["truncated"] as? Boolean ?: false,
        )
    }

    private fun item(m: Map<*, *>): VectorItem = VectorItem(
        Wire.string(m, "id", "vector"),
        Wire.list(Wire.field(m, "vector", "vector"), "vector").map {
            (it as? Number)?.toFloat() ?: throw UnexpectedResponseException("vector component is not a number")
        },
        Wire.objectMapOrNull(m["metadata"]),
    )
}

/** Graph operations. Obtain it as [TriCore.graph]. */
public class GraphApi internal constructor(private val db: TriCore) {
    private suspend fun op(name: String, body: Any): Response = db.request(mapOf("Graph" to mapOf(name to body)))

    /** Create a graph. */
    public suspend fun create(graph: String) {
        op("CreateGraph", mapOf("graph" to graph))
    }

    /** Drop a graph. */
    public suspend fun drop(graph: String) {
        op("DropGraph", mapOf("graph" to graph))
    }

    /** Graph names. */
    public suspend fun listGraphs(): List<String> {
        val r = db.request(mapOf("Graph" to "ListGraphs")).jsonObject("graph listGraphs")
        return Wire.strings(Wire.field(r, "graphs", "listGraphs"), "graphs")
    }

    /** Add a node. */
    public suspend fun addNode(graph: String, id: String, labels: List<String> = emptyList(), properties: Map<String, Any?> = emptyMap()) {
        op("AddNode", linkedMapOf("graph" to graph, "id" to id, "labels" to labels, "properties" to properties))
    }

    /** The node with [id], or `null`. */
    public suspend fun getNode(graph: String, id: String): GraphNode? {
        val j = op("GetNode", mapOf("graph" to graph, "id" to id)).payload("Json") ?: return null
        return node(Wire.obj(j, "graph node"))
    }

    /** Delete a node. */
    public suspend fun deleteNode(graph: String, id: String) {
        op("DeleteNode", mapOf("graph" to graph, "id" to id))
    }

    /** Add an edge between two existing nodes. */
    public suspend fun addEdge(graph: String, id: String, from: String, to: String, label: String, properties: Map<String, Any?> = emptyMap()) {
        op("AddEdge", linkedMapOf("graph" to graph, "id" to id, "from" to from, "to" to to, "label" to label, "properties" to properties))
    }

    /** The edge with [id], or `null`. */
    public suspend fun getEdge(graph: String, id: String): GraphEdge? {
        val j = op("GetEdge", mapOf("graph" to graph, "id" to id)).payload("Json") ?: return null
        return edge(Wire.obj(j, "graph edge"))
    }

    /** Delete an edge. */
    public suspend fun deleteEdge(graph: String, id: String) {
        op("DeleteEdge", mapOf("graph" to graph, "id" to id))
    }

    /** One-hop neighbours of [nodeId]. */
    public suspend fun neighbors(
        graph: String,
        nodeId: String,
        direction: GraphDirection = GraphDirection.OUTGOING,
        label: String? = null,
        limit: Int? = null,
    ): List<GraphNeighbor> {
        val r = op("Neighbors", linkedMapOf("graph" to graph, "node_id" to nodeId, "direction" to direction.wire, "label" to label, "limit" to limit))
            .jsonObject("neighbors")
        return Wire.list(Wire.field(r, "neighbors", "neighbors"), "neighbors").map {
            val m = Wire.obj(it, "neighbor")
            GraphNeighbor(Wire.string(m, "node_id", "neighbor"), Wire.string(m, "edge_id", "neighbor"), m["label"] as? String, m["direction"] as? String)
        }
    }

    /** Incident-edge count of [nodeId] in [direction]. */
    public suspend fun degree(graph: String, nodeId: String, direction: GraphDirection = GraphDirection.OUTGOING): Long =
        Wire.long(op("Degree", linkedMapOf("graph" to graph, "node_id" to nodeId, "direction" to direction.wire)).jsonObject("degree"), "degree", "degree")

    /** Bounded breadth-first traversal from [start]. The server clamps [maxDepth] and [limit]. */
    public suspend fun traverse(
        graph: String,
        start: String,
        direction: GraphDirection = GraphDirection.OUTGOING,
        label: String? = null,
        maxDepth: Int? = null,
        limit: Int? = null,
    ): GraphTraversal {
        val r = op("Traverse", linkedMapOf("graph" to graph, "start" to start, "direction" to direction.wire, "label" to label, "max_depth" to maxDepth, "limit" to limit))
            .jsonObject("traverse")
        val nodes = Wire.list(Wire.field(r, "nodes", "traverse"), "nodes").map {
            val m = Wire.obj(it, "traversal node")
            TraversalNode(Wire.string(m, "id", "traversal node"), Wire.long(m, "depth", "traversal node"))
        }
        return GraphTraversal(nodes, r["truncated"] as? Boolean ?: false)
    }

    /** Fewest-hops path from [from] to [to]. */
    public suspend fun shortestPath(
        graph: String,
        from: String,
        to: String,
        direction: GraphDirection = GraphDirection.OUTGOING,
        label: String? = null,
        maxDepth: Int? = null,
    ): GraphPath = path(
        op("ShortestPath", linkedMapOf("graph" to graph, "from" to from, "to" to to, "direction" to direction.wire, "label" to label, "max_depth" to maxDepth))
            .jsonObject("shortestPath"),
    )

    /** Least summed-weight path; an edge without [weightProperty] (default `weight`) weighs 1. */
    public suspend fun weightedShortestPath(
        graph: String,
        from: String,
        to: String,
        direction: GraphDirection = GraphDirection.OUTGOING,
        label: String? = null,
        weightProperty: String? = null,
    ): GraphPath = path(
        op("WeightedShortestPath", linkedMapOf("graph" to graph, "from" to from, "to" to to, "direction" to direction.wire, "label" to label, "weight_property" to weightProperty))
            .jsonObject("weightedShortestPath"),
    )

    /** A page of nodes ordered by id. */
    public suspend fun listNodes(graph: String, limit: Int? = null, offset: Int? = null): GraphNodePage {
        val r = op("ListNodes", linkedMapOf("graph" to graph, "limit" to limit, "offset" to offset)).jsonObject("listNodes")
        return GraphNodePage(
            Wire.list(Wire.field(r, "nodes", "listNodes"), "nodes").map { node(Wire.obj(it, "node")) },
            Wire.long(r, "total", "listNodes"),
            r["truncated"] as? Boolean ?: false,
        )
    }

    /** A page of edges ordered by id. */
    public suspend fun listEdges(graph: String, limit: Int? = null, offset: Int? = null): GraphEdgePage {
        val r = op("ListEdges", linkedMapOf("graph" to graph, "limit" to limit, "offset" to offset)).jsonObject("listEdges")
        return GraphEdgePage(
            Wire.list(Wire.field(r, "edges", "listEdges"), "edges").map { edge(Wire.obj(it, "edge")) },
            Wire.long(r, "total", "listEdges"),
            r["truncated"] as? Boolean ?: false,
        )
    }

    /** Run a read-only Cypher-subset query. Write clauses are refused by name. */
    public suspend fun query(graph: String, cypher: String): GraphQueryResult {
        val r = op("Query", mapOf("graph" to graph, "cypher" to cypher)).jsonObject("graph query")
        return GraphQueryResult(
            Wire.strings(Wire.field(r, "columns", "graph query"), "columns"),
            Wire.list(Wire.field(r, "rows", "graph query"), "rows").map { Wire.list(it, "row").toList() },
        )
    }

    private fun node(m: Map<*, *>) = GraphNode(
        Wire.string(m, "id", "node"),
        Wire.strings(m["labels"] ?: emptyList<String>(), "labels"),
        Wire.objectMap(m["properties"]),
    )

    private fun edge(m: Map<*, *>) = GraphEdge(
        Wire.string(m, "id", "edge"),
        Wire.string(m, "from", "edge"),
        Wire.string(m, "to", "edge"),
        Wire.string(m, "label", "edge"),
        Wire.objectMap(m["properties"]),
    )

    private fun path(r: Map<*, *>) = GraphPath(
        found = Wire.bool(r, "found", "path"),
        hops = Wire.longOrNull(r, "hops"),
        nodePath = (r["node_path"] as? List<*>)?.map { it.toString() } ?: emptyList(),
        edgePath = (r["edge_path"] as? List<*>)?.map { it.toString() } ?: emptyList(),
        totalCost = (r["total_cost"] as? Number)?.toDouble(),
    )
}

/** Read-only LLM context export. Obtain it as [TriCore.llm]. */
public class LlmApi internal constructor(private val db: TriCore) {
    /** Export the schema catalogue (SQL tables and document collections), rendered in [format]. */
    public suspend fun schema(format: OutputFormat = OutputFormat.TOON, options: LlmOptions = LlmOptions()): String =
        rendered(db.request(mapOf("Llm" to mapOf("Schema" to mapOf("format" to format.wire, "options" to options.wire())))))

    /** Assemble a context bundle from [sources], rendered in [format]. */
    public suspend fun context(sources: List<LlmSource>, format: OutputFormat = OutputFormat.TOON, options: LlmOptions = LlmOptions()): String {
        require(sources.isNotEmpty()) { "a context bundle needs at least one source" }
        return rendered(
            db.request(mapOf("Llm" to mapOf("Context" to mapOf("sources" to sources.map { it.wire() }, "format" to format.wire, "options" to options.wire())))),
        )
    }

    /** The rendering as text: `Toon` and `Message` verbatim, `Json` as JSON text. */
    private fun rendered(resp: Response): String {
        val d = resp.data as? Map<*, *> ?: throw UnexpectedResponseException("expected a rendered export, got ${resp.kind}")
        return when {
            d.containsKey("Toon") -> d["Toon"].toString()
            d.containsKey("Message") -> d["Message"].toString()
            d.containsKey("Json") -> d["Json"].let { if (it is String) it else Json.stringify(it) }
            else -> throw UnexpectedResponseException("expected a rendered export, got ${resp.kind}")
        }
    }
}

/** Administrative operations. Both need the Admin permission. Obtain it as [TriCore.admin]. */
public class AdminApi internal constructor(private val db: TriCore) {
    /** Round-trip a request through auth, routing and dispatch. */
    public suspend fun ping() {
        db.request(mapOf("Admin" to "Ping"))
    }

    /** Server status as the cluster core reports it. A plain message is returned as `{"message": text}`. */
    public suspend fun status(): Map<String, Any?> {
        val resp = db.request(mapOf("Admin" to "Status"))
        val d = resp.data as? Map<*, *>
        if (d != null && d.containsKey("Message")) return mapOf("message" to d["Message"])
        return Wire.objectMap(Wire.obj(resp.payload("Json"), "admin status"))
    }
}
