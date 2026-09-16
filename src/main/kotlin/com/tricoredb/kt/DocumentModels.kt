package com.tricoredb.kt

/**
 * A document query filter, mirroring the server's `DocumentFilter`.
 *
 * Fields accept dot notation (`"a.b.c"`). There is no `Or`, `Not` or regex: that is the server's vocabulary.
 * Build filters directly, or with the [filter] DSL:
 *
 * ```kotlin
 * val f = filter { "city" eq "Pune"; "visits" gt 4 }   // several conditions combine with And
 * ```
 */
public sealed class DocumentFilter {
    internal abstract fun wire(): Any

    /** Matches every document. */
    public data object All : DocumentFilter() {
        override fun wire(): Any = "All"
    }

    /** [field] equals [value]. */
    public data class Eq(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Eq" to mapOf("field" to field, "value" to value))
    }

    /** [field] exists and does not equal [value]. */
    public data class Ne(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Ne" to mapOf("field" to field, "value" to value))
    }

    /** [field] is greater than [value]. */
    public data class Gt(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Gt" to mapOf("field" to field, "value" to value))
    }

    /** [field] is greater than or equal to [value]. */
    public data class Gte(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Gte" to mapOf("field" to field, "value" to value))
    }

    /** [field] is less than [value]. */
    public data class Lt(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Lt" to mapOf("field" to field, "value" to value))
    }

    /** [field] is less than or equal to [value]. */
    public data class Lte(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Lte" to mapOf("field" to field, "value" to value))
    }

    /** [field] equals any of [values]. */
    public data class In(val field: String, val values: List<Any?>) : DocumentFilter() {
        override fun wire(): Any = mapOf("In" to mapOf("field" to field, "values" to values))
    }

    /** String [field] contains [value] as a substring, or array [field] contains an element equal to [value]. */
    public data class Contains(val field: String, val value: Any?) : DocumentFilter() {
        override fun wire(): Any = mapOf("Contains" to mapOf("field" to field, "value" to value))
    }

    /** Every filter in [filters] matches. An empty list matches everything. */
    public data class And(val filters: List<DocumentFilter>) : DocumentFilter() {
        override fun wire(): Any = mapOf("And" to filters.map { it.wire() })
    }

    /** Combine this filter with [other] into an [And]. */
    public infix fun and(other: DocumentFilter): DocumentFilter = And(
        (if (this is And) filters else listOf(this)) + (if (other is And) other.filters else listOf(other))
    )
}

/** Builder behind the [filter] DSL. Each condition added is combined with `And`. */
public class FilterBuilder internal constructor() {
    private val parts = mutableListOf<DocumentFilter>()

    /** Add an already-built [filter]. */
    public fun add(filter: DocumentFilter) {
        parts += filter
    }

    /** `field == value`. */
    public infix fun String.eq(value: Any?): Unit = add(DocumentFilter.Eq(this, value))

    /** `field != value`. */
    public infix fun String.ne(value: Any?): Unit = add(DocumentFilter.Ne(this, value))

    /** `field > value`. */
    public infix fun String.gt(value: Any?): Unit = add(DocumentFilter.Gt(this, value))

    /** `field >= value`. */
    public infix fun String.gte(value: Any?): Unit = add(DocumentFilter.Gte(this, value))

    /** `field < value`. */
    public infix fun String.lt(value: Any?): Unit = add(DocumentFilter.Lt(this, value))

    /** `field <= value`. */
    public infix fun String.lte(value: Any?): Unit = add(DocumentFilter.Lte(this, value))

    /** `field in values`. */
    public infix fun String.isIn(values: List<Any?>): Unit = add(DocumentFilter.In(this, values))

    /** Substring or array membership. */
    public infix fun String.contains(value: Any?): Unit = add(DocumentFilter.Contains(this, value))

    internal fun build(): DocumentFilter = when (parts.size) {
        0 -> DocumentFilter.All
        1 -> parts[0]
        else -> DocumentFilter.And(parts.toList())
    }
}

/** Build a [DocumentFilter]; an empty block is [DocumentFilter.All], several conditions are combined with `And`. */
public fun filter(block: FilterBuilder.() -> Unit): DocumentFilter = FilterBuilder().apply(block).build()

/**
 * A set of field mutations for one or many documents: [set] overwrites paths, [inc] adds numbers.
 * Every `set` is applied before every `inc`; `inc` never coerces a non-number.
 */
public data class DocumentUpdate(
    val set: Map<String, Any?> = emptyMap(),
    val inc: Map<String, Number> = emptyMap(),
) {
    internal fun wire(): Map<String, Any?> = buildMap {
        if (set.isNotEmpty()) put("set", set)
        if (inc.isNotEmpty()) put("inc", inc)
    }
}

/** Builder behind the [update] DSL. */
public class UpdateBuilder internal constructor() {
    private val set = LinkedHashMap<String, Any?>()
    private val inc = LinkedHashMap<String, Number>()

    /** Overwrite [path] with [value]. */
    public fun set(path: String, value: Any?) {
        set[path] = value
    }

    /** Add [delta] to the number at [path] (a missing field starts at 0). */
    public fun inc(path: String, delta: Number) {
        inc[path] = delta
    }

    internal fun build() = DocumentUpdate(set.toMap(), inc.toMap())
}

/** Build a [DocumentUpdate]: `update { set("city", "Mumbai"); inc("visits", 1) }`. */
public fun update(block: UpdateBuilder.() -> Unit): DocumentUpdate = UpdateBuilder().apply(block).build()

/** How `$group` derives a group key. */
public sealed class GroupKey {
    internal abstract fun wire(): Any

    /** Group by the value at a dot-notation [path]. */
    public data class Field(val path: String) : GroupKey() {
        override fun wire(): Any = mapOf("Field" to path)
    }

    /** One group for the whole input, keyed by [value]. */
    public data class Constant(val value: Any?) : GroupKey() {
        override fun wire(): Any = mapOf("Constant" to value)
    }
}

/** A `$group` reduction. */
public sealed class AccumulatorOp {
    internal abstract fun wire(): Any

    /** Sum of numeric [field]. */
    public data class Sum(val field: String) : AccumulatorOp() {
        override fun wire(): Any = mapOf("Sum" to field)
    }

    /** Average of numeric [field]. */
    public data class Avg(val field: String) : AccumulatorOp() {
        override fun wire(): Any = mapOf("Avg" to field)
    }

    /** Minimum of [field]. */
    public data class Min(val field: String) : AccumulatorOp() {
        override fun wire(): Any = mapOf("Min" to field)
    }

    /** Maximum of [field]. */
    public data class Max(val field: String) : AccumulatorOp() {
        override fun wire(): Any = mapOf("Max" to field)
    }

    /** Count of documents in the group. */
    public data object Count : AccumulatorOp() {
        override fun wire(): Any = "Count"
    }
}

/** An accumulator writing into [output] of the grouped document (the server's `GroupAccumulator`). */
public data class GroupAccumulator(val output: String, val op: AccumulatorOp) {
    internal fun wire(): Map<String, Any?> = mapOf("output" to output, "op" to op.wire())
}

/** One `$sort` key. */
public data class SortKey(val field: String, val descending: Boolean = false) {
    internal fun wire(): Map<String, Any?> = mapOf("field" to field, "descending" to descending)
}

/** One aggregation stage. Stages apply strictly in order. */
public sealed class AggregateStage {
    internal abstract fun wire(): Any

    /** Filter documents (or groups, after a `Group`). */
    public data class Match(val filter: DocumentFilter) : AggregateStage() {
        override fun wire(): Any = mapOf("Match" to filter.wire())
    }

    /** Group by [by] and reduce with [accumulators]. */
    public data class Group(val by: GroupKey, val accumulators: List<GroupAccumulator> = emptyList()) : AggregateStage() {
        override fun wire(): Any = mapOf("Group" to mapOf("by" to by.wire(), "accumulators" to accumulators.map { it.wire() }))
    }

    /** Sort by [keys]. */
    public data class Sort(val keys: List<SortKey>) : AggregateStage() {
        override fun wire(): Any = mapOf("Sort" to keys.map { it.wire() })
    }

    /** Skip [count] documents. */
    public data class Skip(val count: Int) : AggregateStage() {
        override fun wire(): Any = mapOf("Skip" to count)
    }

    /** Keep at most [count] documents. */
    public data class Limit(val count: Int) : AggregateStage() {
        override fun wire(): Any = mapOf("Limit" to count)
    }

    /** Keep (`include = true`) or drop the named top-level [fields]. */
    public data class Project(val fields: List<String>, val include: Boolean = true) : AggregateStage() {
        override fun wire(): Any = mapOf("Project" to mapOf("fields" to fields, "include" to include))
    }

    /** Replace the stream with one document holding the input count in [field]. */
    public data class Count(val field: String) : AggregateStage() {
        override fun wire(): Any = mapOf("Count" to mapOf("field" to field))
    }
}

/** Builder behind the [pipeline] DSL. */
public class PipelineBuilder internal constructor() {
    private val stages = mutableListOf<AggregateStage>()

    /** Add a `$match` stage built with the [filter] DSL. */
    public fun match(block: FilterBuilder.() -> Unit) {
        stages += AggregateStage.Match(filter(block))
    }

    /** Add a `$match` stage. */
    public fun match(filter: DocumentFilter) {
        stages += AggregateStage.Match(filter)
    }

    /** Add a `$group` stage keyed by the value at [byField]. */
    public fun group(byField: String, block: GroupBuilder.() -> Unit = {}) {
        stages += AggregateStage.Group(GroupKey.Field(byField), GroupBuilder().apply(block).accumulators)
    }

    /** Add a `$group` stage with an explicit [key]. */
    public fun group(key: GroupKey, block: GroupBuilder.() -> Unit = {}) {
        stages += AggregateStage.Group(key, GroupBuilder().apply(block).accumulators)
    }

    /** Add a `$sort` stage. */
    public fun sort(vararg keys: SortKey) {
        stages += AggregateStage.Sort(keys.toList())
    }

    /** Add a `$skip` stage. */
    public fun skip(count: Int) {
        stages += AggregateStage.Skip(count)
    }

    /** Add a `$limit` stage. */
    public fun limit(count: Int) {
        stages += AggregateStage.Limit(count)
    }

    /** Add a `$project` stage. */
    public fun project(fields: List<String>, include: Boolean = true) {
        stages += AggregateStage.Project(fields, include)
    }

    /** Add a `$count` stage writing into [field]. */
    public fun count(field: String) {
        stages += AggregateStage.Count(field)
    }

    internal fun build(): List<AggregateStage> = stages.toList()
}

/** Builder for the accumulators of one `$group`. */
public class GroupBuilder internal constructor() {
    internal val accumulators = mutableListOf<GroupAccumulator>()

    /** `output = sum(field)`. */
    public fun sum(output: String, field: String) {
        accumulators += GroupAccumulator(output, AccumulatorOp.Sum(field))
    }

    /** `output = avg(field)`. */
    public fun avg(output: String, field: String) {
        accumulators += GroupAccumulator(output, AccumulatorOp.Avg(field))
    }

    /** `output = min(field)`. */
    public fun min(output: String, field: String) {
        accumulators += GroupAccumulator(output, AccumulatorOp.Min(field))
    }

    /** `output = max(field)`. */
    public fun max(output: String, field: String) {
        accumulators += GroupAccumulator(output, AccumulatorOp.Max(field))
    }

    /** `output = count of documents`. */
    public fun count(output: String) {
        accumulators += GroupAccumulator(output, AccumulatorOp.Count)
    }
}

/** Build an aggregation pipeline: `pipeline { match { "tier" eq "gold" }; group("city") { sum("total", "amount") } }`. */
public fun pipeline(block: PipelineBuilder.() -> Unit): List<AggregateStage> = PipelineBuilder().apply(block).build()

/** A document index as the server lists it. */
public data class DocumentIndex(val indexName: String, val field: String, val unique: Boolean)

/** Optimizer statistics from `analyze`. */
public data class DocumentStats(val documentCount: Long, val indexedFields: List<String>, val raw: Map<String, Any?>)

/** The outcome of `updateOne`. */
public data class UpdateOneResult(val updated: Boolean, val inserted: Boolean, val id: String?)

/** The outcome of `updateMany`. */
public data class UpdateManyResult(val matched: Long, val modified: Long)
