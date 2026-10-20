package com.tim.c2d

import com.tim.clazz.parser.ClassReader
import com.tim.dex.parser.DexReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Валидность генерации .class → DEX: результат читают DexReader, эталонный baksmali, dexdump
 * (верификатор libdexfile) и d8 (строит IR с выводом типов по каждому методу).
 */
class ClassToDexTest {

    private fun check(classes: List<ByteArray>, what: String, options: C2dOptions = C2dOptions()): ByteArray {
        val dex = C2dTestKit.convert(classes, options)
        C2dTestKit.assertValidDex(dex, what, expectedClasses = classes.size)
        return dex
    }

    @Test
    fun samplesJava8() {
        val classes = C2dTestKit.javac(mapOf("c2d/Samples.java" to C2dSamples.SAMPLES), release = 8)
        check(classes, "Samples (--release 8, -g)")
    }

    @Test
    fun samplesWithoutDebugInfo() {
        val classes = C2dTestKit.javac(mapOf("c2d/Samples.java" to C2dSamples.SAMPLES), release = 8, debug = "-g:none")
        check(classes, "Samples (-g:none)")
        check(C2dTestKit.javac(mapOf("c2d/Samples.java" to C2dSamples.SAMPLES), release = 8),
            "Samples (debugInfo = false)", C2dOptions(debugInfo = false))
    }

    @Test
    fun bigMethodBeyondRegisterLimits() {
        val classes = C2dTestKit.javac(mapOf("c2d/Big.java" to C2dSamples.bigMethod(300)), release = 8)
        val dex = check(classes, "Big (300 локалов)")
        val code = DexReader().read(dex).codeItems.maxByOrNull { it.registersSize }!!
        assertTrue(code.registersSize > 256, "ожидалось > 256 регистров, получено ${code.registersSize}")
    }

    @Test
    fun modernJava17() {
        val classes = C2dTestKit.javac(mapOf("c2d/Modern.java" to C2dSamples.MODERN), release = 17)
        val dex = check(classes, "Modern (--release 17)")
        val parsed = DexReader().read(dex)
        // По умолчанию invokedynamic рассахаривается: ни одного call site, лямбды — синтетические классы.
        assertTrue(parsed.callSiteIds.isEmpty(), "invokedynamic должны быть рассахарены")
        assertTrue(parsed.classDefs.any { C2dTestKit.isSyntheticLambda(parsed, it) }, "нет синтетических классов лямбд")
        // Без рассахаривания — invoke-custom, как в исходных классах.
        val raw = DexReader().read(C2dTestKit.convert(classes, com.tim.c2d.C2dOptions(desugarIndy = false)))
        assertTrue(raw.callSiteIds.isNotEmpty(), "без рассахаривания лямбды и конкатенация должны стать invoke-custom")
    }

    @Test
    fun projectFixtures() {
        val dirs = C2dTestKit.fixturesSrc.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        assertTrue(dirs.isNotEmpty(), "нет фикстур в ${C2dTestKit.fixturesSrc}")
        for (dir in dirs) {
            val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList()
            if (files.isEmpty()) continue
            // Каталоги с подкаталогами-вариантами (debug/vars, debug/none) компилируются по отдельности.
            val groups = files.groupBy { f -> dir.toPath().relativize(f.toPath()).getName(0).toString().let { if (it.endsWith(".java")) "" else it } }
            for ((group, groupFiles) in groups) {
                // modern, features и desugar используют записи, sealed и switch-паттерны (как в build-fixtures.sh).
                val classes = if (dir.name == "modern" || dir.name == "features" || dir.name == "desugar") C2dTestKit.javacFiles(groupFiles, 21, extra = listOf("-parameters"))
                else C2dTestKit.javacFiles(groupFiles, release = 8)
                check(classes, "фикстура ${dir.name}/$group")
            }
        }
    }

    @Test
    fun committedClassFixtures() {
        val dir = File(C2dTestKit.projectDir, "app/src/test/resources/fixtures/classes")
        val classes = dir.listFiles().orEmpty().filter { it.name.endsWith(".class") }.sortedBy { it.name }.map { it.readBytes() }
        assertTrue(classes.isNotEmpty())
        check(classes, "fixtures/classes")
        val user = File(C2dTestKit.projectDir, "app/src/test/resources/fixtures/user/Main.class").readBytes()
        check(listOf(user), "fixtures/user/Main.class")
    }

    @Test
    fun deterministic() {
        val classes = C2dTestKit.javac(mapOf("c2d/Samples.java" to C2dSamples.SAMPLES), release = 8)
        val a = C2dTestKit.convert(classes)
        val b = C2dTestKit.convert(classes.reversed())
        assertTrue(a.contentEquals(C2dTestKit.convert(classes)), "повторное преобразование должно давать те же байты")
        assertEquals(DexReader().read(a).classDefs.size, DexReader().read(b).classDefs.size)
    }

    @Test
    fun moduleInfoIsSkipped() {
        val classes = C2dTestKit.javac(
            mapOf("module-info.java" to "module c2d.mod { }", "c2d/mod/A.java" to "package c2d.mod; public class A {}"),
            release = 11
        )
        val dex = C2dTestKit.convert(classes)
        assertEquals(1, DexReader().read(dex).classDefs.size)
        assertTrue(classes.any { ClassReader().read(it).thisClassName == "module-info" })
    }
}
