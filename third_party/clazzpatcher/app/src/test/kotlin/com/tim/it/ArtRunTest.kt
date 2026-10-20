package com.tim.it

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.d2c.DexToClass
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.testutil.Adb
import com.tim.testutil.JvmVerifier
import com.tim.testutil.Programs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * DEX от c2d в настоящем ART (верификатор и исполнение) через adb:
 *   ./gradlew :app:test -Padb=<serial> --tests 'com.tim.it.ArtRunTest'
 * Вывод `dalvikvm64` сравнивается с выводом DEX эталонного d8 в том же ART. Проверяются два пути:
 * javac → c2d и javac → d8 → d2c → c2d (DEX → class → DEX). Без устройства тест пропускается.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArtRunTest {

    private var adb: Adb? = null
    private val tmp: File = Files.createTempDirectory("art-run").toFile()

    @BeforeAll
    fun setUp() {
        adb = Adb.configured()
        adb?.prepare()
    }

    @AfterAll
    fun tearDown() {
        adb?.cleanup()
        tmp.deleteRecursively()
    }

    /**
     * Эталон — та же программа, собранная эталонным d8 и выполненная в том же ART: так различия
     * платформ (Float.toString в JDK 21, тексты исключений) не влияют на сравнение.
     */
    private fun run(p: Programs.Program, viaD8: Boolean) {
        val device = adb
        assumeTrue(device != null, "устройство не задано (-Padb=<serial> или CLAZZPATCHER_ADB)")
        val name = (if (viaD8) "d8_" else "") + p.name.replace('/', '_')
        val work = File(tmp, name)
        val orig = Programs.compile(p, work)
        val reference = d8(orig, File(work, "d8"))
        val expected = if (p.art == Programs.ArtReference.HOTSPOT && !viaD8) {
            JvmVerifier.runMain(listOf(orig), p.mainClass).also { assertEquals(0, it.first, "эталон HotSpot: ${it.second}") }
        } else {
            device!!.runDex(File(work, "$name.ref.dex").also { reference.copyTo(it, overwrite = true) }, p.mainClass)
                .also { assertEquals(0, it.first, "эталон d8 в ART: ${it.second}") }
        }

        val classes = if (viaD8) {
            DexToClass.convert(DexReader().read(reference.readBytes())) // DEX d8 → наши .class
        } else {
            Programs.classes(orig).values.map { ClassReader().read(it) }
        }
        val dex = File(work, "$name.dex")
        dex.writeBytes(DexWriter(ClassToDex.convert(classes)).buildDex())
        val actual = device!!.runDex(dex, p.mainClass)
        assertEquals(expected.first to expected.second.trim(), actual.first to actual.second.trim(),
            "${p.name}: вывод ART для DEX от c2d${if (viaD8) " (путь d8 → d2c → c2d)" else ""}")
        val (code, log) = device.verifyDex(name, listOf(dex))
        assertEquals(0 to "", code to log, "${p.name}: верификатор ART (dex2oat)")
    }

    private fun d8(classes: File, out: File): File {
        out.mkdirs()
        val files = classes.walkTopDown().filter { it.name.endsWith(".class") }.map { it.path }.toList()
        val (code, text) = JvmVerifier.run(
            listOf(JvmVerifier.java, "-cp", d8Jar().path, "com.android.tools.r8.D8", "--min-api", "26", "--output", out.path) + files, out
        )
        assertEquals(0, code, "d8: $text")
        return File(out, "classes.dex")
    }

    private fun d8Jar(): File {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: (System.getProperty("user.home") + "/Android/Sdk")
        return File(sdk, "build-tools").listFiles().orEmpty().sortedByDescending { it.name }
            .map { File(it, "lib/d8.jar") }.firstOrNull { it.isFile }
            ?: throw IllegalStateException("нет d8.jar в Android SDK")
    }

    /** Корпуса: DEX от c2d целиком проходит верификатор ART (dex2oat), без жёстких ошибок. */
    @TestFactory
    fun corpusVerifiedByArt(): List<DynamicTest> {
        val corpora = listOf("kotlin-stdlib" to com.tim.testutil.ClassCorpus.kotlinStdlib(), "проект" to com.tim.testutil.ClassCorpus.projectMain())
        return corpora.map { (name, classes) ->
            dynamicTest(name) {
                val device = adb
                assumeTrue(device != null, "устройство не задано (-Padb=<serial> или CLAZZPATCHER_ADB)")
                val dexes = ClassToDex.convertMultiDex(classes.values.map { ClassReader().read(it) })
                val files = dexes.mapIndexed { i, d ->
                    File(tmp, "$name-${i + 1}.dex").also { it.writeBytes(DexWriter(d).buildDex()) }
                }
                val (code, log) = device!!.verifyDex(name, files)
                assertEquals(0 to "", code to log, "$name: верификатор ART")
            }
        }
    }

    /** Регрессии c2d на csmali: DEX проходит верификатор ART без ошибок, в том числе блокировок. */
    @TestFactory
    fun regressionsVerifiedByArt(): List<DynamicTest> {
        val root = File(javaClass.getResource("/c2d/regress")!!.toURI())
        return root.listFiles().orEmpty().filter { d -> d.isDirectory && d.listFiles().orEmpty().any { it.name.endsWith(".smali") } }
            .sortedBy { it.name }
            .map { dir ->
                dynamicTest(dir.name) {
                    val device = adb
                    assumeTrue(device != null, "устройство не задано (-Padb=<serial> или CLAZZPATCHER_ADB)")
                    val classes = com.tim.csmali.Smali.assembleAll(com.tim.csmali.Smali.loadDir(dir))
                    val dex = File(tmp, "regress-${dir.name}.dex")
                    dex.writeBytes(DexWriter(ClassToDex.convert(classes)).buildDex())
                    val (code, log) = device!!.verifyDex("regress-${dir.name}", listOf(dex))
                    assertEquals(0 to "", code to log, "${dir.name}: верификатор ART")
                }
            }
    }

    @TestFactory
    fun classToDexOnArt(): List<DynamicTest> = Programs.plain.map { p -> dynamicTest(p.name) { run(p, viaD8 = false) } }

    @TestFactory
    fun d8DexToClassToDexOnArt(): List<DynamicTest> = Programs.plain.map { p -> dynamicTest(p.name) { run(p, viaD8 = true) } }

    @TestFactory
    fun invokeDynamicOnArt(): List<DynamicTest> = Programs.indy.map { p -> dynamicTest(p.name) { run(p, viaD8 = false) } }
}
