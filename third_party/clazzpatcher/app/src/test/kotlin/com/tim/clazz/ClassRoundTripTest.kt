package com.tim.clazz

import com.tim.clazz.parser.ClassReader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassRoundTripTest {

    @Test
    fun `Main class из корня репозитория`(@TempDir tmp: Path) {
        val input = File(repoRoot(), "Main.class")
        val model = assertRoundTrip(input.readBytes(), input.path)
        assertEquals("Main", model.thisClassName)
        assertEquals("java/lang/Object", model.superClassName)

        val output = tmp.resolve("Main_gen.class").toFile()
        val result = TestClass.roundTrip(input, output)
        assertTrue(result.identical, "TestClass.roundTrip: $result")
        assertContentEquals(input.readBytes(), output.readBytes())
    }

    /** Классы java.base: module-info, записи, sealed-иерархии, nestmates, новейшая версия class-файла. */
    @Test
    fun `классы java base из jrt`() {
        val jrt = try {
            FileSystems.getFileSystem(URI.create("jrt:/"))
        } catch (e: Exception) {
            null
        }
        assumeTrue(jrt != null, "jrt:/ недоступна (JDK 8)")
        var count = 0
        Files.walk(jrt!!.getPath("/modules/java.base")).use { paths ->
            for (p in paths) {
                if (!p.toString().endsWith(".class")) continue
                assertRoundTrip(Files.readAllBytes(p), p.toString())
                count++
            }
        }
        assertTrue(count > 1000, "классов java.base: $count")
    }

    /** ~27 тыс. классов разных версий Java; тест пропускается, если kotlinc не установлен. */
    @Test
    fun `все классы kotlin-compiler jar`() {
        val jar = File("/usr/share/idea/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar")
        assumeTrue(jar.isFile, "нет $jar")
        val result = TestClass.roundTripArchive(jar)
        assertTrue(result.ok, "$result\n${result.different.take(5)}\n${result.failures.entries.take(5)}")
        assertTrue(result.classes > 10_000, result.toString())
    }

    @Test
    fun `чтение из потока и файла дают одну модель`() {
        val input = File(repoRoot(), "Main.class")
        val fromFile = ClassReader().read(input)
        val fromStream = input.inputStream().use { ClassReader().read(it) }
        assertEquals(fromFile, fromStream)
    }
}
