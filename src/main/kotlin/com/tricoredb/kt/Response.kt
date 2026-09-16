package com.tricoredb.kt

/**
 * A decoded `TriCoreResponse`.
 *
 * [data] is the externally tagged `ResponseData` as a Kotlin value: the string `"Empty"`, or a one-entry map such as
 * `{"Json": ...}`, `{"Rows": {...}}`, `{"Documents": [...]}`, `{"CacheValue": [bytes] | null}`, `{"Toon": "..."}`
 * or `{"Message": "..."}`.
 */
public class Response internal constructor(
    /** The request id this response answers. */
    public val requestId: String,
    /** `ok`, `error` or `not_implemented`. */
    public val status: String,
    /** The raw `ResponseData`. */
    public val data: Any?,
    /** The raw `diagnostics` object. */
    public val diagnostics: Map<String, Any?>,
) {
    /** True when [status] is `ok`. */
    public val isOk: Boolean get() = status == "ok"

    /** The `ResponseData` variant name, such as `Json` or `Rows`. */
    public val kind: String
        get() = when (data) {
            is String -> data
            is Map<*, *> -> data.keys.firstOrNull()?.toString() ?: "?"
            else -> "?"
        }

    /** The machine-readable failure reason, or `null`. */
    public val errorCode: String? get() = diagnostics["error_code"] as? String

    /** The leader's `host:port` alongside [ErrorCodes.NOT_LEADER], or `null`. */
    public val leaderHint: String? get() = diagnostics["leader_hint"] as? String

    /** Non-fatal warnings. A broadcast DDL that missed a shard reports it here while still answering `ok`. */
    public val warnings: List<String> get() = (diagnostics["warnings"] as? List<*>)?.map { it.toString() } ?: emptyList()

    /** How the request was routed. */
    public val route: String? get() = diagnostics["route"] as? String

    /** Server-side elapsed time in milliseconds. */
    public val elapsedMs: Long? get() = (diagnostics["elapsed_ms"] as? Number)?.toLong()

    /** True when [kind] is [variant]. */
    public fun has(variant: String): Boolean = kind == variant

    /** The payload of variant [variant]; throws [UnexpectedResponseException] when the response holds another. */
    public fun payload(variant: String): Any? {
        val d = data
        if (d is Map<*, *> && d.containsKey(variant)) return d[variant]
        throw UnexpectedResponseException("expected $variant, got $kind")
    }

    override fun toString(): String = "Response(requestId=$requestId, status=$status, kind=$kind)"

    internal companion object {
        fun from(body: Any?): Response {
            val m = body as? Map<*, *> ?: throw ProtocolException("RESPONSE payload is not a JSON object", ErrorCodes.PROTOCOL)
            @Suppress("UNCHECKED_CAST")
            val diagnostics = (m["diagnostics"] as? Map<String, Any?>) ?: emptyMap()
            return Response(
                requestId = m["request_id"]?.toString() ?: "",
                status = m["status"]?.toString() ?: "error",
                data = m["data"],
                diagnostics = diagnostics,
            )
        }
    }
}
