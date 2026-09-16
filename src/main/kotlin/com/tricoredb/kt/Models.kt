package com.tricoredb.kt

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A SQL result set. Every value arrives as the server's text rendering, so a `BIGINT` or `DECIMAL`
 * is exact whatever its magnitude.
 *
 * @property columns column names, in order.
 * @property rows row values, each list aligned with [columns].
 */
public data class Rows(val columns: List<String>, val rows: List<List<String>>) : Iterable<List<String>> {
    /** Number of rows. */
    public val size: Int get() = rows.size

    /** Row [index]. */
    public operator fun get(index: Int): List<String> = rows[index]

    /** Rows as maps keyed by column name. */
    public fun asMaps(): List<Map<String, String>> = rows.map { r -> columns.zip(r).toMap() }

    override fun iterator(): Iterator<List<String>> = rows.iterator()
}

/**
 * The outcome of a SQL write.
 *
 * @property rowsAffected the server's `rows_affected`, or `null` when the statement reports none (DDL, scripts).
 * @property data the `Json` payload, or the `Message` text, or `null` for any other response shape.
 * @property response the full response, for diagnostics and warnings.
 */
public data class ExecResult(val rowsAffected: Long?, val data: Any?, val response: Response)

/** Output format for LLM exports. */
public enum class OutputFormat(
    /** The wire spelling. */
    public val wire: String,
) {
    /** The engine's native shape. */
    NATIVE("native"),

    /** JSON. */
    JSON("json"),

    /** TOON, a compact text encoding for LLM prompts. */
    TOON("toon"),

    /** Markdown. */
    MARKDOWN("markdown"),
}

/**
 * Attach a correlation id to every request issued inside a coroutine:
 * `withContext(CorrelationId("checkout-42")) { db.query(...) }`.
 *
 * Needs [Feature.CORRELATION_ID]; without the grant a request fails by name instead of dropping the id.
 *
 * @property value the id the server records in its access log, audit trail and cancel registry.
 */
public class CorrelationId(public val value: String) : AbstractCoroutineContextElement(CorrelationId) {
    /** The context key. */
    public companion object Key : CoroutineContext.Key<CorrelationId>

    override fun toString(): String = "CorrelationId($value)"
}

/**
 * A field/value (or entry) pair of bytes, used by cache hashes and streams. Equality compares contents.
 *
 * @property field the field bytes.
 * @property value the value bytes.
 */
public class CacheField(public val field: ByteArray, public val value: ByteArray) {
    /** [field] decoded as UTF-8. */
    // `this.` is required: inside a getter, a bare `field` is the property's own
    // backing field, not the constructor property of the same name.
    public val fieldText: String get() = this.field.toString(Charsets.UTF_8)

    /** [value] decoded as UTF-8. */
    public val valueText: String get() = value.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is CacheField && field.contentEquals(other.field) && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * field.contentHashCode() + value.contentHashCode()

    override fun toString(): String = "CacheField($fieldText=$valueText)"

    /** Constructors. */
    public companion object {
        /** A pair from UTF-8 text. */
        public fun of(field: String, value: String): CacheField =
            CacheField(field.toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))
    }
}

/**
 * A live cache key.
 *
 * @property key the key.
 * @property ttlMs remaining TTL in milliseconds, or `null` when it has none.
 * @property raw the server's full description of the key.
 */
public data class CacheKeyInfo(val key: String, val ttlMs: Long?, val raw: Map<String, Any?>)

/**
 * One stream entry.
 *
 * @property id the `<ms>-<seq>` id.
 * @property fields the entry's field/value pairs, in order.
 */
public data class StreamEntry(val id: String, val fields: List<CacheField>)

/** Vector similarity metric. */
public enum class VectorMetric(
    /** The wire spelling. */
    public val wire: String,
) {
    /** Cosine similarity. */
    COSINE("cosine"),

    /** Dot product. */
    DOT("dot"),

    /** Negated squared Euclidean distance (higher is closer). */
    L2("l2");

    /** Lookup. */
    public companion object {
        /** The metric spelled [wire]. */
        public fun fromWire(wire: String): VectorMetric =
            entries.firstOrNull { it.wire == wire } ?: throw UnexpectedResponseException("unknown vector metric `$wire`")
    }
}

/** Index-level vector quantization. Stored vectors keep full precision either way. */
public enum class VectorQuantization(
    /** The wire spelling. */
    public val wire: String,
) {
    /** No quantization. */
    NONE("none"),

    /** 8-bit quantized index. */
    INT8("int8"),
}

/**
 * A stored vector.
 *
 * @property id the vector id.
 * @property vector the components.
 * @property metadata the metadata stored with it, or `null`.
 */
public data class VectorItem(val id: String, val vector: List<Float>, val metadata: Map<String, Any?>?)

/**
 * One search hit.
 *
 * @property id the vector id.
 * @property score similarity; higher is closer for every metric.
 * @property metadata the metadata stored with it, or `null`.
 */
public data class VectorMatch(val id: String, val score: Double, val metadata: Map<String, Any?>?)

/**
 * A vector collection's description.
 *
 * @property collection the collection name.
 * @property dimension the vector dimension.
 * @property metric the similarity metric.
 * @property count the number of stored vectors.
 * @property quantization the index quantization, when reported.
 */
public data class VectorCollectionInfo(
    val collection: String,
    val dimension: Long,
    val metric: VectorMetric,
    val count: Long,
    val quantization: String?,
)

/**
 * A page of vectors ordered by id.
 *
 * @property vectors the vectors on this page.
 * @property total the collection total.
 * @property truncated whether more remain beyond this page.
 */
public data class VectorPage(val vectors: List<VectorItem>, val total: Long, val truncated: Boolean)

/** Edge direction for graph operations. */
public enum class GraphDirection(
    /** The wire spelling. */
    public val wire: String,
) {
    /** Follow edges out of the node. */
    OUTGOING("outgoing"),

    /** Follow edges into the node. */
    INCOMING("incoming"),

    /** Follow edges both ways. */
    BOTH("both"),
}

/**
 * A graph node.
 *
 * @property id the node id.
 * @property labels its labels.
 * @property properties its properties.
 */
public data class GraphNode(val id: String, val labels: List<String>, val properties: Map<String, Any?>)

/**
 * A graph edge.
 *
 * @property id the edge id.
 * @property from source node id.
 * @property to target node id.
 * @property label the edge label.
 * @property properties its properties.
 */
public data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    val label: String,
    val properties: Map<String, Any?>,
)

/**
 * A one-hop neighbour.
 *
 * @property nodeId the neighbouring node.
 * @property edgeId the edge that reaches it.
 * @property label that edge's label, when reported.
 * @property direction the direction it was reached in, when reported.
 */
public data class GraphNeighbor(val nodeId: String, val edgeId: String, val label: String?, val direction: String?)

/**
 * A node reached by a traversal.
 *
 * @property id the node id.
 * @property depth hops from the start node.
 */
public data class TraversalNode(val id: String, val depth: Long)

/**
 * A bounded breadth-first traversal.
 *
 * @property nodes nodes reached, the start node at depth 0.
 * @property truncated whether the limit cut the traversal short.
 */
public data class GraphTraversal(val nodes: List<TraversalNode>, val truncated: Boolean)

/**
 * A path search result. "No path" is `found = false`, not an error.
 *
 * @property found whether a path exists.
 * @property hops number of edges on the path, when found.
 * @property nodePath node ids along the path.
 * @property edgePath edge ids along the path.
 * @property totalCost summed weight, for a weighted search.
 */
public data class GraphPath(
    val found: Boolean,
    val hops: Long?,
    val nodePath: List<String>,
    val edgePath: List<String>,
    val totalCost: Double?,
)

/**
 * A page of nodes.
 *
 * @property nodes nodes on this page.
 * @property total the graph's node total.
 * @property truncated whether more remain.
 */
public data class GraphNodePage(val nodes: List<GraphNode>, val total: Long, val truncated: Boolean)

/**
 * A page of edges.
 *
 * @property edges edges on this page.
 * @property total the graph's edge total.
 * @property truncated whether more remain.
 */
public data class GraphEdgePage(val edges: List<GraphEdge>, val total: Long, val truncated: Boolean)

/**
 * A Cypher-subset query result.
 *
 * @property columns column names.
 * @property rows row values as JSON-decoded Kotlin values.
 */
public data class GraphQueryResult(val columns: List<String>, val rows: List<List<Any?>>)

/**
 * Options for LLM exports.
 *
 * @property maxRows cap on rows per source, or `null` for the server default.
 * @property redactSensitive redact fields the server considers sensitive.
 * @property includeSchema include schema information alongside data.
 */
public data class LlmOptions(
    val maxRows: Int? = null,
    val redactSensitive: Boolean = true,
    val includeSchema: Boolean = false,
) {
    internal fun wire(): Map<String, Any?> =
        mapOf("max_rows" to maxRows, "redact_sensitive" to redactSensitive, "include_schema" to includeSchema)
}

/** A read-only source for an LLM context bundle. */
public sealed class LlmSource {
    internal abstract fun wire(): Any

    /** The rows of a SQL [query]. */
    public data class Sql(val query: String) : LlmSource() {
        override fun wire(): Any = mapOf("Sql" to mapOf("query" to query))
    }

    /** Documents from [collection] matching [filter], up to [limit]. */
    public data class DocumentFind(
        val collection: String,
        val filter: DocumentFilter = DocumentFilter.All,
        val limit: Int? = null,
    ) : LlmSource() {
        override fun wire(): Any =
            mapOf("DocumentFind" to mapOf("collection" to collection, "filter" to filter.wire(), "limit" to limit))
    }
}
