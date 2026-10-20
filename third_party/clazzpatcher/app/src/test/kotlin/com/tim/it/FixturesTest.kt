package com.tim.it

import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.RefSmali
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Проверка самих фикстур и оракулов: фикстуры валидны, эталонный baksmali -> smali воспроизводит их
 * (иначе сравнения с эталоном в других тестах не имели бы смысла), семантическое сравнение не пустое.
 */
class FixturesTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `фикстура принимается верификатором`(name: String) {
        DexDump.assertValid(Fixtures.dex(name), "фикстура $name")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `семантический дамп фикстуры строится и равен сам себе`(name: String) {
        val bytes = Fixtures.dex(name)
        assertNoDiff(DexSemantics.diff(bytes, bytes, DexSemantics.Options(version = true, pools = true)), name)
        assertTrue(RefSmali.disassemble(bytes).isNotEmpty(), "$name: эталонный baksmali не выдал классов")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `эталонные baksmali и smali воспроизводят фикстуру`(name: String) {
        val original = Fixtures.dex(name)
        val rebuilt = SmaliContractTest.refTextByRefSmali(name)
        DexDump.assertValid(rebuilt, "$name после эталонных baksmali+smali")
        // Проверка терпимости семантического сравнения к законным отличиям smali-конвейера:
        // outs_size (эталон считает только invoke), обрезанные хвостовые значения по умолчанию в static_values,
        // DBG_SET_EPILOGUE_BEGIN, который baksmali 3.0.9 печатает как .prologue
        assertNoDiff(
            DexSemantics.diff(original, rebuilt, DexSemantics.Options.SMALI.copy(mergePrologueEpilogue = true)),
            "$name: эталонный round-trip (семантика)"
        )
    }

    @Test
    fun `семантическое сравнение замечает изменённый литерал в коде`() {
        val original = Fixtures.dex("sw")
        // const/16 v0, 0x28 (13 00 28 00) -> 0x29
        val pattern = byteArrayOf(0x13, 0x00, 0x28, 0x00)
        val pos = indexOf(original, pattern)
        assertTrue(pos >= 0, "в sw не найден const/16 v0, 0x28")
        val mutated = original.copyOf()
        mutated[pos + 2] = 0x29
        val report = DexSemantics.diff(original, mutated)
        assertNotNull(report, "изменение литерала не замечено")
        assertTrue(report!!.contains("#40") && report.contains("#41"), report)
        assertNotNull(RefSmali.diff(original, mutated), "эталонное сравнение не заметило изменение литерала")
    }

    @Test
    fun `семантическое сравнение замечает изменённую строку`() {
        val original = Fixtures.dex("gen/hello")
        val pattern = "Hello".toByteArray()
        val pos = indexOf(original, pattern)
        assertTrue(pos >= 0)
        val mutated = original.copyOf()
        mutated[pos] = 'J'.code.toByte()
        val report = DexSemantics.diff(original, mutated, DexSemantics.Options(pools = true))
        assertNotNull(report)
        assertTrue(report!!.contains("Jello"), report)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
