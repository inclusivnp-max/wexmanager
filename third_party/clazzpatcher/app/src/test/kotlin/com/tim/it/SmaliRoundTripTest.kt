package com.tim.it

import com.tim.dsmali.io.SmaliWriter
import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.Memo
import com.tim.testutil.Pipelines
import com.tim.testutil.RefSmali
import com.tim.testutil.TEST_NAME
import com.tim.testutil.TextDiff
import com.tim.testutil.assertNoDiff
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * DEX -> smali-текст (DexToTree + SmaliWriter) -> SmaliParser -> TreeToDex -> DexWriter:
 * результат валиден и эквивалентен исходному, включая отладочную информацию.
 */
class SmaliRoundTripTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames

        fun texts(name: String): Map<String, String> = Memo.get("smali-text:$name") { Pipelines.disassembleToText(Fixtures.dex(name)) }

        fun roundTrip(name: String): ByteArray = Memo.get("smali-rt:$name") { Pipelines.assembleFromText(texts(name).values) }
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `результат принимается верификатором`(name: String) {
        DexDump.assertValid(roundTrip(name), "smali round-trip $name")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `семантически совпадает с исходным`(name: String) {
        assertNoDiff(DexSemantics.diff(Fixtures.dex(name), roundTrip(name), DexSemantics.Options.SMALI), "smali round-trip $name")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `совпадает с исходным по эталонному baksmali`(name: String) {
        assertNoDiff(RefSmali.diff(Fixtures.dex(name), roundTrip(name)), "smali round-trip $name (эталонный baksmali)")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `текст стабилен при повторном разборе и записи (без учёта отступов)`(name: String) {
        checkTextStable(name) { text -> text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") }
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `текст стабилен при повторном разборе и записи побайтно`(name: String) {
        checkTextStable(name) { it }
    }

    /** SmaliWriter(SmaliParser(текст)) == текст для каждого класса (после нормализации [normalize]). */
    private fun checkTextStable(name: String, normalize: (String) -> String) {
        val first = texts(name)
        val second = Pipelines.parse(first.values).classes.associate { it.descriptor to SmaliWriter.write(it) }
        val differing = first.keys.filter { second[it] == null || normalize(first.getValue(it)) != normalize(second.getValue(it)) }
        if (differing.isEmpty() && first.keys == second.keys) return
        val report = StringBuilder("$name: SmaliWriter(SmaliParser(текст)) != текст для ${differing.size} из ${first.size} классов\n")
        differing.take(3).forEach { d -> report.append("класс $d:\n").append(TextDiff.snippet(first.getValue(d), second[d] ?: "")) }
        fail<Unit>(report.toString())
    }
}
