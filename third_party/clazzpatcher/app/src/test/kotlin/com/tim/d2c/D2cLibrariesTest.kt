package com.tim.d2c

import com.tim.dex.parser.DexReader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue

/**
 * Реальные библиотеки: jar → d8 → DexToClass → верификация HotSpot каждого класса.
 * Jar (и их зависимости для classpath) ищутся в кэше Gradle; переменная окружения `D2C_LIBS=a.jar:b.jar`
 * (или `-Dd2c.libs`) — свой список без зависимостей. Нет jar — тест пропускается.
 *
 * Ошибок верификации быть не должно. «Нет зависимости» допустима только у классов, которые не
 * линкуются и в исходном jar (синтетические классы d8 — лямбды и т. п. — в сравнение не входят).
 */
class D2cLibrariesTest {

    /** Артефакт → его зависимости, нужные для линковки. */
    private val defaults = linkedMapOf(
        "kotlin-stdlib" to listOf(),
        "gson" to listOf(),
        "commons-lang3" to listOf(),
        "guava" to listOf("failureaccess"),
        "okhttp" to listOf("okio-jvm", "kotlin-stdlib"),
        "kotlinx-coroutines-core-jvm" to listOf("kotlin-stdlib", "atomicfu-jvm")
    )

    private val cacheJars: List<File> by lazy {
        val cache = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        if (!cache.isDirectory) emptyList()
        else cache.walkTopDown().maxDepth(5)
            .filter { it.isFile && it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
            .toList()
    }

    private fun artifact(name: String): File? =
        cacheJars.filter { it.parentFile.parentFile.parentFile.name == name }.maxByOrNull { it.parentFile.parentFile.name }

    @TestFactory
    fun libraries(): List<DynamicTest> {
        (System.getProperty("d2c.libs") ?: System.getenv("D2C_LIBS"))?.let { p ->
            return p.split(File.pathSeparator).map { File(it) }.filter { it.isFile }
                .map { jar -> DynamicTest.dynamicTest(jar.name) { check(jar, emptyList()) } }
        }
        return defaults.mapNotNull { (name, deps) ->
            val jar = artifact(name) ?: return@mapNotNull null
            DynamicTest.dynamicTest(jar.name) { check(jar, deps.mapNotNull { artifact(it) }) }
        }
    }

    /** Имя класса из `NoClassDefFoundError: a/b/C` (через точки), null — другая ошибка. */
    private fun missingClass(line: String): String? {
        val m = Regex("NoClassDefFoundError: ([\\w/$.]+)").find(line) ?: return null
        return m.groupValues[1].replace('/', '.')
    }

    private fun check(jar: File, deps: List<File>) {
        assumeTrue(D2cTestSupport.d8Jar != null, "нет d8.jar в Android SDK")
        val dexDir = D2cTestSupport.d8Jar(jar)
        val original = D2cTestSupport.verify(jar, deps)
        val originalLink = original.linkErrors.map { it.substringBefore(':') }.toSet()
        val originalNames = original.lines.map { it.substringAfter(' ').substringBefore(':') }.toSet()
        var total = 0
        var totalMs = 0L
        var ok = 0
        val errors = ArrayList<String>()
        val newLink = ArrayList<String>()
        var syntheticLink = 0
        var externalLink = 0
        for (dexFile in dexDir.listFiles { f -> f.name.endsWith(".dex") }.orEmpty().sortedBy { it.name }) {
            val dex = DexReader().read(dexFile)
            val out = File(D2cTestSupport.tempDir("libjar"), "out.jar")
            val t0 = System.nanoTime()
            val converted = D2cTestSupport.writeJar(dex, out)
            totalMs += (System.nanoTime() - t0) / 1_000_000
            total += converted.size
            // Исходный jar — после сконвертированного: классы из соседних DEX (multidex) берутся из него.
            val v = D2cTestSupport.verify(out, deps + jar)
            ok += v.ok
            errors += v.verifyErrors
            for (l in v.linkErrors) {
                val name = l.substringAfter(' ').substringBefore(':')
                when {
                    name !in originalNames -> syntheticLink++
                    l.substringBefore(':') in originalLink -> {}
                    // Верификатор загружает типы для проверки присваиваемости; у фреймов после DEX они
                    // другие, чем у javac, поэтому отсутствие ВНЕШНЕЙ библиотеки может проявиться у
                    // других классов. Ошибка — только если не нашёлся класс самого jar.
                    missingClass(l)?.let { it !in originalNames } == true -> externalLink++
                    else -> newLink += l
                }
            }
        }
        println("${jar.name}: $total классов, DEX → class за $totalMs мс; верификация: OK=$ok, ошибок=${errors.size}, " +
            "нет зависимостей: как у исходного ${original.linkErrors.size}, у синтетических классов d8 $syntheticLink, " +
            "из-за внешних библиотек $externalLink, новых ${newLink.size}")
        for (e in (errors + newLink).take(10)) println("   $e")
        assertTrue(errors.isEmpty(), "ошибки верификации: ${errors.size}")
        assertTrue(newLink.isEmpty(), "классы, которые линкуются в исходном jar, но не после DEX → class: ${newLink.size}")
    }
}
