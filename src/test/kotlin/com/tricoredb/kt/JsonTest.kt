package com.tricoredb.kt

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON reader and writer this client speaks the protocol with.
 *
 * The escape cases are not decoration: the wire carries user data — a password, a
 * cache value, a document field — and a writer that emits an escape the server's
 * reader does not accept corrupts exactly the payload it was asked to carry.
 */
class JsonTest {

    @Test
    fun `scalars round-trip`() {
        assertEquals(null, Json.parse("null"))
        assertEquals(true, Json.parse("true"))
        assertEquals("ada", Json.parse("\"ada\""))
        assertEquals("null", Json.stringify(null))
        assertEquals("true", Json.stringify(true))
        assertEquals("\"ada\"", Json.stringify("ada"))
    }

    @Test
    fun `every control character the spec names is escaped`() {
        // \b, \f, \n, \r and \t each have a two-character escape; anything else below
        // a space goes out as \u00xx.
        val text = "a\"b\\c\nd\re\tf\u0008g\u000Ch\u0001i"
        val encoded = Json.stringify(text)
        assertEquals(
            """"a\"b\\c\nd\re\tf\bg\fh\u0001i"""",
            encoded,
        )
        assertEquals(text, Json.parse(encoded))
    }

    @Test
    fun `a form feed survives both directions`() {
        // The pair that was written as the literal characters 000C, so a form feed
        // was emitted as the letter f and read back as nothing of the sort.
        val value = "before\u000Cafter"
        assertTrue(Json.stringify(value).contains("\\f"))
        assertEquals(value, Json.parse(Json.stringify(value)))
        assertEquals("\u000C", Json.parse("\"\\f\""))
    }

    @Test
    fun `objects and arrays nest`() {
        @Suppress("UNCHECKED_CAST")
        val parsed = Json.parse("""{"a":[1,2,{"b":"c"}],"d":null}""") as Map<String, Any?>
        val list = parsed["a"] as List<*>
        assertEquals(3, list.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("c", (list[2] as Map<String, Any?>)["b"])
        assertNull(parsed["d"])
        assertTrue(parsed.containsKey("d"), "a null value is a key that is present, not an absent key")
    }

    @Test
    fun `numbers keep their type and their digits`() {
        assertEquals(42L, (Json.parse("42") as Number).toLong())
        assertEquals(-0.125, (Json.parse("-0.125") as Number).toDouble())
        // A number wider than a Long must not silently become a rounded Double.
        val wide = Json.parse("18446744073709551615")
        assertEquals("18446744073709551615", wide.toString())
        assertEquals("10.50", Json.stringify(BigDecimal("10.50")).trim('"'))
    }

    @Test
    fun `unicode escapes are read`() {
        assertEquals("aé☃", Json.parse(""""a\u00e9\u2603""""))
    }
}
