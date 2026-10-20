package com.tim.d2c

import com.tim.dex.parser.DexReader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Семантика DEX → class: программа на Java → javac → d8 → DexToClass → запуск на JVM с
 * `-Xverify:all`. Вывод должен совпасть с запуском исходных классов javac, а каждый
 * сконвертированный класс — пройти верификатор HotSpot.
 */
class D2cProgramsTest {

    private fun sources(program: String): Map<String, String> {
        val dir = File(javaClass.getResource("/d2c/programs/$program")!!.toURI())
        return dir.walkTopDown().filter { it.name.endsWith(".java") }.associate { it.relativeTo(dir).path to it.readText() }
    }

    private fun check(program: String, noDesugaring: Boolean = false, release: Int = 8) {
        assumeTrue(D2cTestSupport.d8Jar != null, "нет d8.jar в Android SDK")
        val classes = D2cTestSupport.javac(sources(program), release)
        val expected = D2cTestSupport.runMain(classes, "Main")
        assertEquals(0, expected.exit, "исходная программа упала:\n${expected.out}")

        val dexFile = D2cTestSupport.d8(classes, noDesugaring = noDesugaring)
        val dex = DexReader().read(dexFile)
        val jar = File(D2cTestSupport.tempDir("prog"), "$program.jar")
        val t0 = System.nanoTime()
        val converted = D2cTestSupport.writeJar(dex, jar)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("$program: ${converted.size} классов из DEX за $ms мс")

        val actual = D2cTestSupport.runMain(jar, "Main")
        assertEquals(expected.out, actual.out, "вывод после DEX → class отличается")
        assertEquals(0, actual.exit)

        val v = D2cTestSupport.verify(jar)
        assertTrue(v.verifyErrors.isEmpty() && v.linkErrors.isEmpty(), "верификация: $v\n${(v.verifyErrors + v.linkErrors).joinToString("\n")}")
        assertEquals(converted.size, v.ok)
    }

    @TestFactory
    fun programs(): List<DynamicTest> = listOf("arith", "control", "exceptions", "objects", "arrays", "arrayinit", "lambdas", "misc", "reflect").map { p ->
        DynamicTest.dynamicTest(p) { check(p) }
    }

    /** Без десугаринга d8 лямбды остаются invoke-custom → проверка invokedynamic и method handles. */
    @TestFactory
    fun invokeCustom(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("indy") { check("indy", noDesugaring = true) }
    )
}
