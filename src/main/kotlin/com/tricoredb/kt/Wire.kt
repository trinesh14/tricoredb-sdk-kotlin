package com.tricoredb.kt

internal object Wire {
    fun obj(value: Any?, what: String): Map<*, *> =
        value as? Map<*, *> ?: throw UnexpectedResponseException("$what: expected a JSON object, got ${describe(value)}")

    fun list(value: Any?, what: String): List<*> =
        value as? List<*> ?: throw UnexpectedResponseException("$what: expected a JSON array, got ${describe(value)}")

    fun field(m: Map<*, *>, key: String, what: String): Any? {
        if (!m.containsKey(key)) throw UnexpectedResponseException("$what: the response has no `$key` field")
        return m[key]
    }

    fun long(m: Map<*, *>, key: String, what: String): Long =
        (field(m, key, what) as? Number)?.toLong()
            ?: throw UnexpectedResponseException("$what: `$key` is not a number (${describe(m[key])})")

    fun longOrNull(m: Map<*, *>, key: String): Long? = (m[key] as? Number)?.toLong()

    fun double(m: Map<*, *>, key: String, what: String): Double =
        (field(m, key, what) as? Number)?.toDouble()
            ?: throw UnexpectedResponseException("$what: `$key` is not a number (${describe(m[key])})")

    fun bool(m: Map<*, *>, key: String, what: String): Boolean =
        field(m, key, what) as? Boolean
            ?: throw UnexpectedResponseException("$what: `$key` is not a boolean (${describe(m[key])})")

    fun string(m: Map<*, *>, key: String, what: String): String =
        field(m, key, what) as? String
            ?: throw UnexpectedResponseException("$what: `$key` is not a string (${describe(m[key])})")

    fun stringOrNull(m: Map<*, *>, key: String): String? = m[key] as? String

    fun strings(value: Any?, what: String): List<String> = list(value, what).map {
        it as? String ?: throw UnexpectedResponseException("$what: expected strings, found ${describe(it)}")
    }

    @Suppress("UNCHECKED_CAST")
    fun objectMap(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun objectMapOrNull(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun bytes(value: Any?, what: String): ByteArray {
        val l = list(value, what)
        val out = ByteArray(l.size)
        for (i in l.indices) {
            val n = (l[i] as? Number)?.toInt() ?: throw UnexpectedResponseException("$what: byte $i is not a number")
            if (n !in 0..255) throw UnexpectedResponseException("$what: byte $i is $n, outside 0..255")
            out[i] = n.toByte()
        }
        return out
    }

    fun byteList(bytes: ByteArray): List<Int> = bytes.map { it.toInt() and 0xFF }

    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "object"
        is List<*> -> "array"
        is String -> "string"
        else -> value::class.java.simpleName
    }
}
