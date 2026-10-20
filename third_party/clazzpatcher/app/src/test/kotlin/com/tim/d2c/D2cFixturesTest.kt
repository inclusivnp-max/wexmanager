package com.tim.d2c

import com.tim.dex.parser.DexReader
import com.tim.testutil.Fixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEX-фикстуры проекта → .class: каждый класс проходит ClassWriter → ClassReader и верификатор
 * HotSpot (`-Xverify:all`, отдельный процесс).
 *
 * gen/allops — синтаксическая фикстура (регистры не определены, int пишется в Object[]): ART её не
 * пропустил бы, поэтому такие методы в нестрогом режиме становятся заглушками с предупреждением.
 * gen/invoke — аннотация со значениями method type/handle и bootstrap-аргумент null: в JVM
 * невыразимы, тоже предупреждения.
 */
class D2cFixturesTest {

    private val expectedWarnings = mapOf("gen/allops" to 6, "gen/invoke" to 2)

    @TestFactory
    fun fixtures(): List<DynamicTest> = Fixtures.dexNames.map { name -> DynamicTest.dynamicTest(name) { check(name) } }

    private fun check(name: String) {
        val dex = DexReader().read(Fixtures.dex(name))
        val jar = File(D2cTestSupport.tempDir("fx"), "out.jar")
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val t0 = System.nanoTime()
        val classes = D2cTestSupport.writeJar(dex, jar, D2cOptions(strict = false, warnings = { warnings.add(it) }))
        val ms = (System.nanoTime() - t0) / 1_000_000
        val v = D2cTestSupport.verify(jar)
        println("$name: ${classes.size} классов за $ms мс; $v; предупреждений ${warnings.size}")
        for (w in warnings) println("   предупреждение: $w")
        assertEquals(dex.classDefs.size, classes.size)
        assertTrue(v.verifyErrors.isEmpty() && v.linkErrors.isEmpty(), "$v\n${(v.verifyErrors + v.linkErrors).joinToString("\n")}")
        assertEquals(expectedWarnings[name] ?: 0, warnings.size, warnings.joinToString("\n"))
    }

    /** Корневой classes.dex проекта (крупнее фикстур). */
    @Test
    fun projectDex() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "classes.dex") }.firstOrNull { it.isFile }
            ?: return
        val dex = DexReader().read(file)
        val jar = File(D2cTestSupport.tempDir("root"), "out.jar")
        val t0 = System.nanoTime()
        val classes = D2cTestSupport.writeJar(dex, jar)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val v = D2cTestSupport.verify(jar)
        println("classes.dex (${file.length() / 1024} КБ): ${classes.size} классов за $ms мс; $v")
        assertTrue(v.verifyErrors.isEmpty(), v.verifyErrors.joinToString("\n"))
    }

    /** Нестрогий режим: классы с заглушками вместо методов — в degraded (их можно взять у другого переводчика). */
    @Test
    fun degradedClassesReported() {
        val degraded = Collections.synchronizedSet(HashSet<String>())
        DexToClass.convert(DexReader().read(Fixtures.dex("gen/allops")), D2cOptions(strict = false, degraded = { degraded.add(it) }))
        assertEquals(setOf("Lfx/ops/AllOps;"), degraded)
        degraded.clear()
        // gen/invoke: метод с bootstrap-аргументом null — заглушка (аннотация с method handle класс не портит)
        DexToClass.convert(DexReader().read(Fixtures.dex("gen/invoke")), D2cOptions(strict = false, degraded = { degraded.add(it) }))
        assertEquals(setOf("Lfx/ic/Indy;"), degraded)
        degraded.clear()
        DexToClass.convert(DexReader().read(Fixtures.dex("user")), D2cOptions(strict = false, degraded = { degraded.add(it) }))
        assertEquals(emptySet(), degraded)
    }

    /** Нестрогий режим: класс, который не переводится вовсе, пропускается, остальные переводятся. */
    @Test
    fun brokenClassSkippedInLenientMode() {
        val dex = DexReader().read(Fixtures.dex("user"))
        // class_data за пределами секции: ломается перевод самого класса, а не общий индекс
        val broken = dex.copy(classDefs = dex.classDefs.mapIndexed { i, cd -> if (i == 0) cd.copy(classDataIdx = dex.classDataItems.size + 5) else cd })
        val first = dex.strings[dex.typeIds[dex.classDefs[0].classIdx].descriptorIdx]
        assertThrows<Exception> { DexToClass.convertToBytes(broken, D2cOptions(threads = 2)) }
        val degraded = Collections.synchronizedSet(HashSet<String>())
        val out = DexToClass.convertToBytes(broken, D2cOptions(strict = false, threads = 2, degraded = { degraded.add(it) }))
        assertTrue(first in degraded, degraded.toString())
        assertEquals(dex.classDefs.size - 1, out.size)
        assertTrue(first.removePrefix("L").removeSuffix(";") !in out)
    }

    /** Строгий режим: невыразимое в JVM — исключение с классом и методом. */
    @Test
    fun strictModeFails() {
        val dex = DexReader().read(Fixtures.dex("gen/allops"))
        val e = assertThrows<IllegalStateException> { DexToClass.convert(dex, D2cOptions(threads = 1)) }
        val message = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: "" }
        assertTrue("Lfx/ops/AllOps;" in message && "адрес" in message, message)
    }
}
