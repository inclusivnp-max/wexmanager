package com.tim.it

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.testutil.JvmVerifier
import com.tim.testutil.Programs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Оба конвертера вместе: javac → .class → c2d → DEX (запись и повторное чтение) → d2c → .class.
 * Программа после двойного перевода обязана выдать тот же вывод, что исходные классы, а все её
 * классы — пройти верификатор HotSpot. Так семантика c2d проверяется через d2c и наоборот.
 */
class ConvertersRoundTripTest {

    @TempDir
    lateinit var tmp: File

    private fun roundTrip(p: Programs.Program) {
        val work = File(tmp, p.name.replace('/', '_'))
        val orig = Programs.compile(p, work)
        val expected = JvmVerifier.runMain(listOf(orig), p.mainClass, "-Xverify:all")
        assertEquals(0, expected.first, "исходная программа: ${expected.second}")

        val classes = Programs.classes(orig).values.map { ClassReader().read(it) }
        val dexBytes = DexWriter(ClassToDex.convert(classes)).buildDex()
        val dex = DexReader().read(dexBytes)
        val back = DexToClass.convertToBytes(dex, D2cOptions(majorVersion = maxOf(52, classes.maxOf { it.majorVersion })))
        // Лишние — только синтетические классы лямбд (рассахаривание invokedynamic в c2d).
        assertEquals(classes.map { it.thisClassName }.toSet(), back.keys.filterNot { com.tim.c2d.C2dTestKit.SYNTHETIC_LAMBDA in it }.toSet(), "классы после двойного перевода")

        val dir = File(work, "back").also { JvmVerifier.writeClasses(it, back) }
        val actual = JvmVerifier.runMain(listOf(dir), p.mainClass, "-Xverify:all")
        assertEquals(expected, actual, "${p.name}: вывод после .class → DEX → .class")
        val report = JvmVerifier.verify(listOf(dir), back.keys)
        assertTrue(report.failed.isEmpty() && report.skipped.isEmpty()) { "${p.name}: $report" }
    }

    @TestFactory
    fun plainPrograms(): List<DynamicTest> = Programs.plain.map { p -> dynamicTest(p.name) { roundTrip(p) } }

    @TestFactory
    fun invokeDynamicPrograms(): List<DynamicTest> = Programs.indy.map { p -> dynamicTest(p.name) { roundTrip(p) } }
}
