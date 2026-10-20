package org.wexclient.app.elf

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElfParserTest {

    /** Минимальный синтетический ELF64 LE: ehdr + null-секция + shstrtab. */
    private fun syntheticElf64(): ByteArray {
        val strings = byteArrayOf(0) + ".shstrtab".toByteArray() + byteArrayOf(0)
        val shOff = 64
        val strOff = shOff + 2 * 64
        val buf = ByteBuffer.allocate(strOff + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        // ident
        buf.put(0x7F.toByte()); buf.put('E'.code.toByte()); buf.put('L'.code.toByte()); buf.put('F'.code.toByte())
        buf.put(2); buf.put(1); buf.put(1); buf.put(0)
        repeat(8) { buf.put(0) }
        // ehdr64: type=3 DYN, machine=183 AArch64, version=1, entry=0x1000
        buf.putShort(3); buf.putShort(183); buf.putInt(1)
        buf.putLong(0x1000); buf.putLong(0); buf.putLong(shOff.toLong())
        buf.putInt(0)
        buf.putShort(64); buf.putShort(0); buf.putShort(0)
        buf.putShort(64); buf.putShort(2); buf.putShort(1)
        // section 0 (null)
        repeat(64) { buf.put(0) }
        // section 1 (shstrtab): name=1, type=3, offset=strOff, size=strings.size, align=1
        buf.putInt(1); buf.putInt(3); buf.putLong(0); buf.putLong(0)
        buf.putLong(strOff.toLong()); buf.putLong(strings.size.toLong())
        buf.putInt(0); buf.putInt(0); buf.putLong(1); buf.putLong(0)
        // strings
        buf.put(strings)
        return buf.array()
    }

    /** Минимальный синтетический ELF32 BE: только заголовок. */
    private fun syntheticElf32be(): ByteArray {
        val buf = ByteBuffer.allocate(52).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x7F.toByte()); buf.put('E'.code.toByte()); buf.put('L'.code.toByte()); buf.put('F'.code.toByte())
        buf.put(1); buf.put(2); buf.put(1); buf.put(0)
        repeat(8) { buf.put(0) }
        // type=2 EXEC, machine=40 ARM, version=1, entry=0x8000
        buf.putShort(2); buf.putShort(40); buf.putInt(1)
        buf.putInt(0x8000); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putShort(52); buf.putShort(0); buf.putShort(0)
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        return buf.array()
    }

    @Test
    fun `synthetic ELF64 parses`() {
        val doc = ElfDocument(syntheticElf64())
        assertTrue(doc.is64Bit)
        assertTrue(doc.littleEndian)
        val h = doc.parsed.header
        assertEquals(64, h.ehSize)
        assertEquals(64, h.shEntSize)
        assertEquals(2, h.shNum)
        assertEquals(1, h.shStrNdx)
        assertEquals(3, h.type)
        assertEquals(183, h.machine)
        assertEquals(0x1000L, h.entry)
        assertTrue(ElfParser.machineName(183).contains("AArch64"))
        assertTrue(ElfParser.typeName(3).contains("DYN"))
        assertEquals(2, doc.sections().size)
        assertEquals(".shstrtab", doc.sections()[1].name)
        assertEquals(3, doc.sections()[1].type)
        assertTrue(doc.programs().isEmpty())
    }

    @Test
    fun `synthetic ELF32 BE parses`() {
        val doc = ElfDocument(syntheticElf32be())
        assertFalse(doc.is64Bit)
        assertFalse(doc.littleEndian)
        assertEquals(2, doc.parsed.header.type)
        assertEquals(40, doc.parsed.header.machine)
        assertEquals(0x8000L, doc.parsed.header.entry)
    }

    @Test
    fun `header patch round trip`() {
        val doc = ElfDocument(syntheticElf64())
        assertFalse(doc.isDirty())
        doc.setHeaderField(ElfHeaderField.ENTRY, 0x2000)
        assertEquals(0x2000L, doc.getHeaderField(ElfHeaderField.ENTRY))
        assertTrue(doc.isDirty())
        doc.setByte(0, 0x7F)
        // Перепарс рабочей копии видит правку
        val reparsed = ElfDocument(doc.toByteArray())
        assertEquals(0x2000L, reparsed.parsed.header.entry)
    }

    @Test
    fun `non-ELF rejected`() {
        assertFailsWith<IllegalArgumentException> { ElfDocument(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { ElfDocument("MZ fake".toByteArray()) }
        assertFalse(ElfParser.isElf(byteArrayOf(0x7F, 0x45, 0x4C, 0x00)))
    }

    @Test
    fun `parseNumber formats`() {
        assertEquals(255L, ElfDocument.parseNumber("0xFF"))
        assertEquals(255L, ElfDocument.parseNumber("0xff"))
        assertEquals(10L, ElfDocument.parseNumber("10"))
        assertEquals(null, ElfDocument.parseNumber("zz"))
        assertEquals(null, ElfDocument.parseNumber(""))
        assertEquals("0x000000FF", ElfDocument.formatHex(255, 8))
    }

    @Test
    fun `real system so parses with symbols`() {
        val candidates = listOf(
            "/lib/x86_64-linux-gnu/libc.so.6",
            "/usr/lib/liba52.so",
            "/usr/lib/ld-linux.so.2",
            "/lib64/ld-linux-x86-64.so.2"
        )
        val path = candidates.firstOrNull { File(it).exists() }
            ?: error("Нет системного .so для теста")
        println("real so: $path")
        val doc = ElfDocument(File(path).readBytes())
        val h = doc.parsed.header
        println("machine=${ElfParser.machineName(h.machine)} sections=${doc.sections().size} programs=${doc.programs().size}")
        assertTrue(doc.programs().isNotEmpty(), "нет сегментов")
        val syms = doc.symbols()
        println("symbols=${syms.size} sample=${syms.take(3).map { it.name }}")
        assertTrue(syms.isNotEmpty(), "нет символов")
        println("needed=${doc.neededLibraries().take(5)} soname=${doc.soname()}")
        // Патч в памяти не трогает оригинал
        val before = doc.toByteArray()
        val oldEntry = doc.getHeaderField(ElfHeaderField.ENTRY)
        doc.setHeaderField(ElfHeaderField.ENTRY, oldEntry + 1)
        assertTrue(doc.isDirty())
        assertEquals(before.size, doc.toByteArray().size)
        assertEquals(oldEntry + 1, ElfDocument(doc.toByteArray()).getHeaderField(ElfHeaderField.ENTRY))
    }
}
