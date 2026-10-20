package com.tim.clazz

import com.tim.clazz.model.ConstantUtf8Info
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ModifiedUtf8
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModifiedUtf8Test {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Эталон — DataOutputStream.writeUTF (тоже Modified UTF-8). */
    private fun reference(s: String): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).writeUTF(s)
        return bos.toByteArray().copyOfRange(2, bos.size())
    }

    @Test
    fun `нулевой символ и суррогатные пары`() {
        assertContentEquals(bytes(0xC0, 0x80), ModifiedUtf8.encode("\u0000"))
        // U+1F600 = D83D DE00: каждая половина пары тремя байтами, без 4-байтовой формы
        assertContentEquals(bytes(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80), ModifiedUtf8.encode("😀"))
        assertEquals("\u0000", ModifiedUtf8.decode(bytes(0xC0, 0x80)))
        assertEquals("😀", ModifiedUtf8.decode(bytes(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80)))
    }

    @Test
    fun `совпадает с writeUTF`() {
        val samples = listOf(
            "", "abc", "java/lang/Object", "\u0000", "a\u0000b", "é", "Ж", "€", "߿", "ࠀ", "￿",
            "😀", "x\uD800", "\uDC00y", "смесь: \u0000 😀 € é abc"
        )
        for (s in samples) {
            val encoded = ModifiedUtf8.encode(s)
            assertContentEquals(reference(s), encoded, "кодирование '$s'")
            assertEquals(encoded.size, ModifiedUtf8.encodedLength(s))
            assertEquals(s, ModifiedUtf8.decode(encoded), "декодирование '$s'")
            assertEquals(s, ConstantUtf8Info.of(s).getString())
            assertEquals(s, ConstantUtf8Info(encoded).getString())
        }
    }

    @Test
    fun `некорректные последовательности`() {
        val invalid = listOf(
            bytes(0x00),                   // нулевой байт
            bytes(0x80),                   // продолжение без начала
            bytes(0xC0),                   // обрыв 2-байтовой
            bytes(0xE2, 0x82),             // обрыв 3-байтовой
            bytes(0xC3, 0x41),             // нет байта продолжения
            bytes(0xF0, 0x9F, 0x98, 0x80), // 4-байтовая форма UTF-8
            bytes(0xFF)
        )
        for (b in invalid) {
            assertFailsWith<ClassFormatException> { ModifiedUtf8.decode(b) }
            assertNull(ModifiedUtf8.decodeOrNull(b))
        }
    }

    @Test
    fun `декодирование части массива`() {
        val b = bytes(0x41, 0xC0, 0x80, 0x42, 0x43)
        assertEquals("\u0000B", ModifiedUtf8.decode(b, 1, 3))
        assertEquals("BC", ModifiedUtf8.decode(b, 3, 2))
    }
}
