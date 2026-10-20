package com.tim.dsmali.asm

import com.tim.dsmali.asm.SmaliLiterals.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmaliLiteralsTest {

    @Test
    fun integersAcceptUnsignedAndMinValues() {
        assertEquals(-1, SmaliLiterals.parseInt("0xffffffff"))
        assertEquals(Int.MIN_VALUE, SmaliLiterals.parseInt("-0x80000000"))
        assertEquals(Int.MAX_VALUE, SmaliLiterals.parseInt("0x7fffffff"))
        assertEquals(-1, SmaliLiterals.parseInt("4294967295"))
        assertEquals(8, SmaliLiterals.parseInt("010"))
        assertEquals(0, SmaliLiterals.parseInt("0"))
        assertEquals(Long.MIN_VALUE, SmaliLiterals.parseLong("-0x8000000000000000L"))
        assertEquals(Long.MIN_VALUE, SmaliLiterals.parseLong("0x8000000000000000L"))
        assertEquals(-1L, SmaliLiterals.parseLong("0xffffffffffffffffL"))
        assertEquals(Short.MIN_VALUE, SmaliLiterals.parseShort("-0x8000s"))
        assertEquals((-1).toShort(), SmaliLiterals.parseShort("0xffffs"))
        assertEquals((-1).toByte(), SmaliLiterals.parseByte("0xfft"))
        assertEquals(Byte.MIN_VALUE, SmaliLiterals.parseByte("-0x80t"))
    }

    @Test
    fun integerOverflowIsAnError() {
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseInt("0x100000000") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseInt("-0x80000001") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseLong("0x10000000000000000L") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseLong("-0x8000000000000001L") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseByte("0x100t") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseInt("08") }
        assertFailsWith<NumberFormatException> { SmaliLiterals.parseInt("") }
    }

    @Test
    fun classifiesLiterals() {
        assertEquals(Kind.INT, SmaliLiterals.parse("0x1f").kind) // шестнадцатеричное, а не float
        assertEquals(31L, SmaliLiterals.parse("0x1f").bits)
        assertEquals(Kind.LONG, SmaliLiterals.parse("0x1fL").kind)
        assertEquals(Kind.SHORT, SmaliLiterals.parse("-0x1s").kind)
        assertEquals(Kind.BYTE, SmaliLiterals.parse("0x7ft").kind)
        assertEquals(Kind.FLOAT, SmaliLiterals.parse("1.5f").kind)
        assertEquals(1.5f, SmaliLiterals.parse("1.5f").floatValue)
        assertEquals(Kind.FLOAT, SmaliLiterals.parse("1f").kind)
        assertEquals(Kind.DOUBLE, SmaliLiterals.parse("10.0").kind)
        assertEquals(10.0, SmaliLiterals.parse("10.0").doubleValue)
        assertEquals(Kind.DOUBLE, SmaliLiterals.parse("1e5").kind)
        assertEquals(Double.NEGATIVE_INFINITY, SmaliLiterals.parse("-Infinity").doubleValue)
        assertEquals(Float.POSITIVE_INFINITY, SmaliLiterals.parse("Infinityf").floatValue)
        assert(SmaliLiterals.parse("NaN").doubleValue.isNaN())
        assert(SmaliLiterals.parse("NaNf").floatValue.isNaN())
        assertEquals(4.9E-324, SmaliLiterals.parse("4.9E-324").doubleValue)
        assertEquals(Kind.CHAR, SmaliLiterals.parse("'\\n'").kind)
        assertEquals('\n'.code.toLong(), SmaliLiterals.parse("'\\n'").bits)
        assertEquals(Kind.BOOLEAN, SmaliLiterals.parse("true").kind)
        assertFailsWith<NumberFormatException> { SmaliLiterals.parse("1.0L") }
    }

    @Test
    fun unescapesAllSmaliEscapes() {
        assertEquals("a\\nb", SmaliLiterals.parseString("\"a\\\\nb\"")) // \\n — обратный слэш + n
        assertEquals("\b\t\n\u000c\r'\"\\", SmaliLiterals.parseString("\"\\b\\t\\n\\f\\r\\'\\\"\\\\\""))
        assertEquals("é€\u0001", SmaliLiterals.parseString("\"\\u00e9\\u20ac\\u0001\""))
        assertEquals('\'', SmaliLiterals.parseChar("'\\''"))
        assertEquals('é', SmaliLiterals.parseChar("'\\u00e9'"))
        assertEquals('é', SmaliLiterals.parseChar("'é'"))
        assertFailsWith<IllegalArgumentException> { SmaliLiterals.parseString("\"\\x\"") }
        assertFailsWith<IllegalArgumentException> { SmaliLiterals.parseString("\"\\u12\"") }
        assertFailsWith<IllegalArgumentException> { SmaliLiterals.parseChar("'ab'") }
    }
}
