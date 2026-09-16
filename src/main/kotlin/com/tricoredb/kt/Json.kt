package com.tricoredb.kt

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A small, dependency-free JSON codec used for every frame this SDK sends and receives.
 *
 * Values map to plain Kotlin types:
 *
 * | JSON    | Kotlin (decoded)                                         |
 * |---------|----------------------------------------------------------|
 * | object  | `Map<String, Any?>` (insertion-ordered)                   |
 * | array   | `List<Any?>`                                              |
 * | string  | `String`                                                  |
 * | number  | `Long` when integral and in range, else `BigInteger`; `Double` when it has a fraction or exponent |
 * | boolean | `Boolean`                                                 |
 * | null    | `null`                                                    |
 *
 * [stringify] accepts those types plus any [Number], [CharSequence], [Char], arrays, [Iterable]
 * and [Sequence]. A whole-valued `Double`/`Float` is written without a fraction (`1.0` becomes `1`),
 * as JavaScript writes it. `NaN` and infinities have no JSON form and are refused.
 */
public object Json {

    /** Parse [text] into the Kotlin types described on [Json]. Throws [IllegalArgumentException] on malformed input. */
    public fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        require(p.pos == text.length) { "trailing characters after JSON value at offset ${p.pos}" }
        return v
    }

    /** Encode [value] as compact JSON text. Throws [IllegalArgumentException] for a value with no JSON form. */
    public fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private const val MAX_DEPTH = 512

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(v.toString())
            is Double -> writeDouble(v, sb)
            is Float -> writeFloat(v, sb)
            is BigDecimal -> sb.append(v.toPlainString())
            is Number -> writeDouble(v.toDouble(), sb)
            is Char -> writeString(v.toString(), sb)
            is CharSequence -> writeString(v.toString(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> writeSeq(v.iterator(), sb)
            is Sequence<*> -> writeSeq(v.iterator(), sb)
            is Array<*> -> writeSeq(v.iterator(), sb)
            is IntArray -> writeSeq(v.iterator(), sb)
            is LongArray -> writeSeq(v.iterator(), sb)
            is FloatArray -> writeSeq(v.iterator(), sb)
            is DoubleArray -> writeSeq(v.iterator(), sb)
            is BooleanArray -> writeSeq(v.iterator(), sb)
            is ByteArray -> writeSeq(v.map { it.toInt() and 0xFF }.iterator(), sb)
            is Pair<*, *> -> writeSeq(listOf(v.first, v.second).iterator(), sb)
            is Enum<*> -> writeString(v.name, sb)
            else -> throw IllegalArgumentException("no JSON form for ${v::class.java.name}")
        }
    }

    private fun writeSeq(it: Iterator<*>, sb: StringBuilder) {
        sb.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(',')
            first = false
            write(it.next(), sb)
        }
        sb.append(']')
    }

    private fun writeDouble(d: Double, sb: StringBuilder) {
        require(d.isFinite()) { "`$d` has no JSON form" }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun writeFloat(f: Float, sb: StringBuilder) {
        require(f.isFinite()) { "`$f` has no JSON form" }
        if (f == Math.rint(f.toDouble()).toFloat() && kotlin.math.abs(f) < 1e15f) {
            sb.append(f.toLong())
        } else {
            // Float.toString is the shortest text that parses back to the same f32.
            sb.append(f.toString())
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("malformed JSON at offset $pos: $msg")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            if (pos >= s.length) fail("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected character '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected a string key")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return l
            }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("short \\u escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            var fractional = false
            if (s[pos] == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9') {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true
                    pos++
                } else {
                    break
                }
            }
            val text = s.substring(start, pos)
            if (text == "-" || text.isEmpty()) fail("bad number")
            return try {
                if (fractional) text.toDouble() else text.toLongOrNull() ?: BigInteger(text)
            } catch (e: NumberFormatException) {
                fail("bad number '$text'")
            }
        }
    }
}
