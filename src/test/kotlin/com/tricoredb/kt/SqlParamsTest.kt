package com.tricoredb.kt

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * How a Kotlin value reaches the server as a bound parameter.
 *
 * The server binds these at value positions its grammar has already fixed, so the
 * only question here is whether the value that arrives is the value that was
 * passed — which is why every case checks the encoded form rather than only that
 * no exception was thrown.
 */
class SqlParamsTest {

    @Test
    fun `scalars pass through unchanged`() {
        assertNull(SqlParams.param(null))
        assertEquals(true, SqlParams.param(true))
        assertEquals(42, SqlParams.param(42))
        assertEquals(-7L, SqlParams.param(-7L))
        assertEquals("O'Hara", SqlParams.param("O'Hara"), "a quote is data, never syntax")
        assertEquals("x", SqlParams.param('x'))
    }

    @Test
    fun `a decimal goes out as plain digits, not as a double`() {
        // A JSON number would be read through a double and lose the digits a
        // BigDecimal exists to keep.
        assertEquals("10.50", SqlParams.param(BigDecimal("10.50")))
        assertEquals("0.123456789012345678", SqlParams.param(BigDecimal("0.123456789012345678")))
        // Plain string, so no exponent: the server reads 1.5E+3 as a DOUBLE.
        assertEquals("1500", SqlParams.param(BigDecimal("1.5E+3")))
    }

    @Test
    fun `wide integers stay exact`() {
        assertEquals(BigInteger("18446744073709551615"), SqlParams.param(ULong.MAX_VALUE))
        assertEquals(BigInteger("170141183460469231731687303715884105727"), SqlParams.param(BigInteger("170141183460469231731687303715884105727")))
        assertEquals(255L, SqlParams.param(255u.toUByte()))
    }

    @Test
    fun `bytes become lowercase hex a BLOB column parses`() {
        assertEquals("0x00abff10", SqlParams.param(byteArrayOf(0x00, 0xab.toByte(), 0xff.toByte(), 0x10)))
        assertEquals("0x", SqlParams.param(ByteArray(0)))
        // Invalid UTF-8 has to survive too, which is why bytes never go through a String.
        assertEquals("0xc328", SqlParams.param(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test
    fun `non-finite floats are refused by name rather than sent`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val error = assertFailsWith<IllegalArgumentException> { SqlParams.param(value) }
            assertEquals(true, error.message!!.contains("no SQL parameter form"))
        }
        assertEquals(0.5, SqlParams.param(0.5f))
    }

    @Test
    fun `timestamps and ids go out in the form the server stores`() {
        assertEquals("2026-09-16 12:30:00.000", SqlParams.param(Instant.parse("2026-09-16T12:30:00Z")))
        assertEquals("2026-09-16", SqlParams.param(LocalDate.parse("2026-09-16")))
        val id = UUID.fromString("0b2f6f1e-2d3a-4a5b-8c7d-9e0f1a2b3c4d")
        assertEquals("0b2f6f1e-2d3a-4a5b-8c7d-9e0f1a2b3c4d", SqlParams.param(id))
    }

    @Test
    fun `a value with no scalar form is refused, not stringified`() {
        val error = assertFailsWith<IllegalArgumentException> { SqlParams.param(listOf(1, 2, 3)) }
        assertEquals(true, error.message!!.contains("no SQL parameter form"))
    }

    @Test
    fun `a parameter list keeps its order`() {
        val encoded = SqlParams.params(listOf(1, "ada", null, byteArrayOf(1)))
        assertEquals(listOf(1, "ada", null, "0x01"), encoded)
    }
}
