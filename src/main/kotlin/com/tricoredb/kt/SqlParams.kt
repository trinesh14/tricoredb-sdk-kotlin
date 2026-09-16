package com.tricoredb.kt

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Converts Kotlin values into server-side SQL parameters (JSON scalars).
 *
 * Parameters are only ever bound **server-side**, and only when the server granted [Feature.SERVER_PARAMS].
 * This SDK never splices values into SQL text.
 *
 * | Kotlin                                   | Wire                                                  |
 * |------------------------------------------|-------------------------------------------------------|
 * | `null`                                   | `null`                                                |
 * | `Boolean`                                | `true` / `false`                                      |
 * | `Byte`, `Short`, `Int`, `Long`, `BigInteger`, unsigned ints | exact JSON number                   |
 * | `Float`, `Double` (finite)               | JSON number                                           |
 * | `BigDecimal`                             | **text**, `toPlainString()`                           |
 * | `String`, `CharSequence`, `Char`         | string                                                |
 * | `ByteArray`                              | `"0x"` + lowercase hex, which a BLOB column parses    |
 * | `UUID`                                   | canonical text                                        |
 * | `Instant`, `LocalDateTime`, `OffsetDateTime` | `yyyy-MM-dd HH:mm:ss.SSS` text in UTC            |
 * | `LocalDate`                              | `yyyy-MM-dd` text                                     |
 *
 * **BigDecimal travels as text.** A JSON number is read through a double on the server, which would lose the
 * digits a `BigDecimal` exists to keep; a DECIMAL column parses text exactly. The trade-off: binding a
 * `BigDecimal` into a **DOUBLE** column fails by name. Pass a `Double` for a DOUBLE column.
 *
 * Any other type is refused with [IllegalArgumentException] rather than sent as its `toString()`.
 */
public object SqlParams {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** One value as a wire parameter. */
    public fun param(value: Any?): Any? = when (value) {
        null -> null
        is Boolean -> value
        is Byte, is Short, is Int, is Long, is BigInteger -> value
        is UByte -> value.toLong()
        is UShort -> value.toLong()
        is UInt -> value.toLong()
        is ULong -> BigInteger(value.toString())
        is Float -> {
            require(value.isFinite()) { "`$value` has no SQL parameter form" }
            value.toDouble()
        }
        is Double -> {
            require(value.isFinite()) { "`$value` has no SQL parameter form" }
            value
        }
        is BigDecimal -> value.toPlainString()
        is String -> value
        is CharSequence -> value.toString()
        is Char -> value.toString()
        is ByteArray -> hex(value)
        is UUID -> value.toString()
        is Instant -> LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(TIMESTAMP)
        is OffsetDateTime -> value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(TIMESTAMP)
        is LocalDateTime -> value.format(TIMESTAMP)
        is LocalDate -> value.toString()
        else -> throw IllegalArgumentException(
            "no SQL parameter form for ${value::class.java.name}; convert it explicitly (the server accepts JSON scalars only)"
        )
    }

    /** Every value in [values] as a wire parameter, in order. */
    public fun params(values: List<Any?>): List<Any?> = values.map { param(it) }

    /** `0x` followed by lowercase hex. */
    public fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(2 + bytes.size * 2).append("0x")
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}
