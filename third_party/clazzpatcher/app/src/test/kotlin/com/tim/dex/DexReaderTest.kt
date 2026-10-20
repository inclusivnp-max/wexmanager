package com.tim.dex

import com.tim.dex.util.ByteOut
import com.tim.dex.util.DexBuffer
import com.tim.dex.util.DexLeb128
import com.tim.dex.util.IntIntMap
import com.tim.dex.util.IntSort
import com.tim.dex.util.StringPool
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class DexReaderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun leb128AcceptsFiveByteValues() {
        val cases = listOf(
            bytes(0x80, 0x80, 0x80, 0x80, 0x78) to Int.MIN_VALUE,
            bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07) to Int.MAX_VALUE,
            bytes(0x7F) to -1,
            bytes(0x80, 0x7F) to -128,
            bytes(0xC0, 0xBB, 0x78) to -123456
        )
        for ((enc, value) in cases) {
            val buf = DexBuffer(enc)
            assertEquals(value, buf.readSLEB128())
            assertEquals(enc.size, buf.pos)
            assertEquals(value, DexLeb128.readSLEB128(ByteBuffer.wrap(enc)))
        }
        val u = bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)
        assertEquals(-1, DexBuffer(u).readULEB128())
        assertEquals(-1, DexLeb128.readULEB128(ByteBuffer.wrap(u)))
        assertFailsWith<IllegalArgumentException> { DexBuffer(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)).readULEB128() }
    }

    @Test
    fun leb128RoundTrip() {
        val rnd = Random(1)
        val values = List(20000) { rnd.nextInt() } + listOf(0, 1, -1, 63, 64, -64, -65, 127, 128, Int.MIN_VALUE, Int.MAX_VALUE)
        val out = ByteOut()
        for (v in values) {
            out.sleb(v)
            out.uleb(v)
            out.ulebp1(v)
        }
        val buf = DexBuffer(out.toByteArray())
        for (v in values) {
            val start = buf.pos
            assertEquals(v, buf.readSLEB128())
            assertEquals(DexLeb128.getSLEB128Size(v), buf.pos - start)
            assertEquals(v, buf.readULEB128())
            assertEquals(v, buf.readULEB128p1())
        }
    }

    @Test
    fun mutf8RoundTrip() {
        val samples = listOf("", "abc", "\u0000", "a\u0000b", "\u00e9", "\u4e2d\u6587", "\uD83D\uDE00", "\uD800x", "x\uDFFF", "\u07FF\u0800\uFFFF")
        for (s in samples) {
            val enc = StringPool.encodeMUTF8(s)
            assertTrue(enc.none { it.toInt() == 0 }, "в MUTF-8 нет нулевых байтов: $s")
            assertEquals(s, StringPool.decodeMUTF8(enc))
            val out = ByteOut()
            out.uleb(s.length)
            out.mutf8(s + "0123456789abcdefgh") // быстрый путь для длинных строк
            out.u1(0)
            val data = out.toByteArray()
            val buf = DexBuffer(data)
            val len = buf.readULEB128()
            assertEquals(s.length, len)
            assertEquals(s + "0123456789abcdefgh", StringPool.decodeMUTF8(data, buf.pos, len + 18))
        }
        // одинокий surrogate и '?' в длинной строке (быстрый путь не должен превращать surrogate в '?')
        val tricky = "abcdefghijklmnop\uD800?"
        val out = ByteOut()
        out.mutf8(tricky)
        assertContentEquals(StringPool.encodeMUTF8(tricky), out.toByteArray())
        assertFailsWith<IllegalArgumentException> { StringPool.decodeMUTF8(bytes(0x61, 0x00), 0, 2) }
        assertFailsWith<IllegalArgumentException> { StringPool.decodeMUTF8(bytes(0x61, 0x62, 0x00), 0, 1) }
    }

    @Test
    fun intCollections() {
        val map = IntIntMap(4)
        for (i in 1..5000) map.put(i * 4, i)
        for (i in 1..5000) assertEquals(i, map[i * 4])
        assertEquals(-1, map[3])
        val keys = IntArray(1000) { Random(it).nextInt(50) }
        val order = IntSort.sortedIndices(keys.size) { a, b -> keys[a].compareTo(keys[b]) }
        for (k in 1 until order.size) {
            val a = order[k - 1]
            val b = order[k]
            assertTrue(keys[a] < keys[b] || (keys[a] == keys[b] && a < b), "стабильность сортировки")
        }
    }

    @Test
    fun readerKeepsEarlierResults() {
        val file = File(System.getProperty("user.dir")).resolve("../classes.dex").canonicalFile
        assumeTrue(file.isFile, "нет тестового DEX: $file")
        val reader = DexReader()
        val first = reader.read(file)
        val codeCount = first.codeItems.size
        val firstBytes = DexWriter(first).buildDex()
        reader.read(DexWriter(first.copy(classDefs = first.classDefs.take(1))).buildDex())
        assertEquals(codeCount, first.codeItems.size)
        assertContentEquals(firstBytes, DexWriter(first).buildDex())
    }
}
