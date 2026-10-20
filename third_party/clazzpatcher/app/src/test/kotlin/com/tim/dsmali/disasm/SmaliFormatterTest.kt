package com.tim.dsmali.disasm

import com.tim.dsmali.SmaliFormatter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmaliFormatterTest {
    private fun literal(v: Long) = StringBuilder().also { SmaliFormatter.appendLiteral(it, v) }.toString()
    private fun integral(v: Long, suffix: Char?) = StringBuilder().also { SmaliFormatter.appendIntegral(it, v, suffix) }.toString()
    private fun char(c: Char) = StringBuilder().also { SmaliFormatter.appendChar(it, c) }.toString()

    @Test
    fun `strings escape control and invisible characters, visible ones stay as is`() {
        assertEquals("a\\\"b\\\\c\\'d", SmaliFormatter.escape("a\"b\\c'd"))
        assertEquals("\\n\\r\\t\\u0000\\u0001\\u001f\\u007f", SmaliFormatter.escape("\n\r\t\u0000\u0001\u001f\u007f"))
        // Видимые символы (кириллица, é, эмодзи) — как есть; разделитель строк и непарный суррогат — \uXXXX
        assertEquals("\u00e9\\u2028\uD83D\uDE00\\ud800", SmaliFormatter.escape("\u00e9\u2028\uD83D\uDE00\uD800"))
        assertEquals("\u0436\\u0080\\u00a0\\ufeff\\udc00x", SmaliFormatter.escape("\u0436\u0080\u00a0\ufeff\uDC00x"))
        assertEquals("\"x y\"", SmaliFormatter.quote("x y"))
    }

    @Test
    fun `chars escape control and invisible characters, visible ones stay as is`() {
        assertEquals("'a'", char('a'))
        assertEquals("'\\''", char('\''))
        assertEquals("'\\\"'", char('"'))
        assertEquals("'\\\\'", char('\\'))
        assertEquals("'\\n'", char('\n'))
        assertEquals("'\\u0000'", char('\u0000'))
        assertEquals("'\u00e9'", char('\u00e9'))
        assertEquals("'\\uffff'", char('\uffff'))
        assertEquals("'\\ud800'", char('\uD800'))
    }

    @Test
    fun `extreme literals`() {
        assertEquals("-0x80000000", SmaliFormatter.toHex(Int.MIN_VALUE))
        assertEquals("0x7fffffff", SmaliFormatter.toHex(Int.MAX_VALUE))
        assertEquals("-0x8000000000000000", SmaliFormatter.toHex(Long.MIN_VALUE))
        assertEquals("0x0", SmaliFormatter.toHex(0))
        assertEquals("-0x1", SmaliFormatter.toHex(-1))
        // суффикс L — только если значение не помещается в int
        assertEquals("0x7fffffff", literal(Int.MAX_VALUE.toLong()))
        assertEquals("0x80000000L", literal(0x80000000L))
        assertEquals("-0x80000000", literal(Int.MIN_VALUE.toLong()))
        assertEquals("-0x80000001L", literal(Int.MIN_VALUE - 1L))
        assertEquals("-0x8000000000000000L", literal(Long.MIN_VALUE))
        assertEquals("0x4024000000000000L", literal(0x4024L shl 48))
        // encoded values: суффикс по типу
        assertEquals("-0x80t", integral(-128, 't'))
        assertEquals("0x7fffs", integral(0x7fff, 's'))
        assertEquals("0x0L", integral(0, 'L'))
        assertEquals("-0x8000000000000000L", integral(Long.MIN_VALUE, 'L'))
    }

    @Test
    fun `names with spaces are quoted with backticks`() {
        assertEquals("Lsp/`my class`;", SmaliFormatter.type("Lsp/`my class`;".replace("`", "")))
        assertEquals("[[Lsp/`x y`;", SmaliFormatter.type("[[Lsp/x y;"))
        assertEquals("Ljava/lang/String;", SmaliFormatter.type("Ljava/lang/String;"))
        assertEquals("`a b`", SmaliFormatter.simpleName("a b"))
        assertEquals("<init>", SmaliFormatter.simpleName("<init>"))
    }

    @Test
    fun `unknown annotation visibility is an error`() {
        assertEquals("system", SmaliFormatter.visibility(2))
        assertFailsWith<IllegalArgumentException> { SmaliFormatter.visibility(3) }
    }
}
