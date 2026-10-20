package com.tim.it

import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Memo
import com.tim.testutil.Pipelines
import com.tim.testutil.RefSmali
import com.tim.testutil.assertNoDiff
import com.tim.testutil.assertSameBytes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.Locale

/**
 * Необязательный прогон по каталогу с DEX-файлами (рекурсивно):
 *   ./gradlew :app:test -Pcorpus=/путь/к/каталогу [-Pcorpus.pipelines=raw,smali] -PtestMaxHeap=8g --tests 'com.tim.it.CorpusTest'
 * (или переменные окружения CLAZZPATCHER_CORPUS / CLAZZPATCHER_CORPUS_PIPELINES). Без каталога тест пропускается.
 * Время каждого конвейера печатается в stdout.
 */
class CorpusTest {
    private fun setting(property: String, env: String): String? =
        System.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    private fun <T> timed(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        println(String.format(Locale.ROOT, "%s: %.2f с", what, (System.nanoTime() - t0) / 1e9))
        return r
    }

    @TestFactory
    fun corpus(): List<DynamicNode> {
        val path = setting("clazzpatcher.corpus", "CLAZZPATCHER_CORPUS")
        assumeTrue(path != null, "каталог корпуса не задан (-Pcorpus=... или CLAZZPATCHER_CORPUS)")
        val pipelines = (setting("clazzpatcher.corpus.pipelines", "CLAZZPATCHER_CORPUS_PIPELINES") ?: "raw,smali")
            .split(',').map { it.trim() }.toSet()
        val unknown = pipelines - setOf("raw", "smali")
        require(unknown.isEmpty()) { "неизвестные конвейеры $unknown (допустимо: raw, smali)" }
        val dir = File(path!!)
        require(dir.isDirectory) { "каталог корпуса не найден: $dir" }
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".dex") }.sortedBy { it.path }.toList()
        require(files.isNotEmpty()) { "в $dir нет .dex" }
        return files.map { f ->
            val name = f.relativeTo(dir).path
            // Результаты (и исключения) вычисляются один раз на файл и переиспользуются проверками
            val original: ByteArray by lazy { f.readBytes() }
            val raw: ByteArray by lazy { Memo.get("corpus-raw:${f.path}") { timed("$name: raw round-trip") { Pipelines.rawRoundTrip(original) } } }
            val smali: ByteArray by lazy { Memo.get("corpus-smali:${f.path}") { timed("$name: smali round-trip") { Pipelines.smaliRoundTrip(original) } } }
            val tests = ArrayList<DynamicTest>()
            if ("raw" in pipelines) {
                tests += dynamicTest("raw round-trip: верификатор") { DexDump.assertValid(raw, "$name: raw round-trip") }
                tests += dynamicTest("raw round-trip: семантика") {
                    assertNoDiff(DexSemantics.diff(original, raw, DexSemantics.Options(version = true, pools = true)), "$name: raw round-trip")
                }
                tests += dynamicTest("raw round-trip: эталонный baksmali") {
                    assertNoDiff(RefSmali.diff(original, raw), "$name: raw round-trip (эталонный baksmali)")
                }
                tests += dynamicTest("raw round-trip: идемпотентность") {
                    assertSameBytes(raw, Pipelines.rawRoundTrip(raw), "$name: write(read(write(x))) != write(x)")
                }
            }
            if ("smali" in pipelines) {
                tests += dynamicTest("smali round-trip: верификатор") { DexDump.assertValid(smali, "$name: smali round-trip") }
                tests += dynamicTest("smali round-trip: семантика") {
                    assertNoDiff(DexSemantics.diff(original, smali, DexSemantics.Options.SMALI), "$name: smali round-trip")
                }
                tests += dynamicTest("smali round-trip: эталонный baksmali") {
                    assertNoDiff(RefSmali.diff(original, smali), "$name: smali round-trip (эталонный baksmali)")
                }
            }
            dynamicContainer(name, tests)
        }
    }
}
