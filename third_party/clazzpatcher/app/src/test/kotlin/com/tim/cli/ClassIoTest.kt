package com.tim.cli

import com.tim.ClassIo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Запись jar: параллельное сжатие, детерминизм, больше 65535 записей (концевые записи ZIP64). */
class ClassIoTest {
    @TempDir
    lateinit var tmp: File

    private fun entries(n: Int) = (0 until n).map { i ->
        "p${i % 97}/C$i" to ByteArray(20 + i % 300) { k -> ((i * 31 + k * 7) % 251).toByte() }
    }

    @Test
    fun `больше 65535 записей — ZIP64, читается ZipFile`() {
        val list = entries(70_000)
        val jar = File(tmp, "big.jar")
        ClassIo.writeZip(list.map { (n, b) -> "$n.class" to b }, jar)
        ZipFile(jar).use { z ->
            assertEquals(70_000, z.size())
            for (i in listOf(0, 1, 65_535, 65_536, 69_999)) {
                val (name, bytes) = list[i]
                assertContentEquals(bytes, z.getInputStream(z.getEntry("$name.class")).readBytes(), name)
            }
        }
    }

    @Test
    fun `одинаковый вход — побайтно одинаковый архив`() {
        val list = entries(3_000).map { (n, b) -> "$n.class" to b }
        val a = File(tmp, "a.jar").also { ClassIo.writeZip(list, it) }
        val b = File(tmp, "b.jar").also { ClassIo.writeZip(list, it) }
        assertContentEquals(a.readBytes(), b.readBytes())
        ZipFile(a).use { z -> assertEquals(3_000, z.size()) }
    }
}
