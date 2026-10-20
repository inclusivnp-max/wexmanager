package com.tim.it

import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import com.tim.testutil.assertSameBytes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertTrue

/**
 * Сжатая запись (DexWriter с compact = true): в пулы id — только используемое. Смысл DEX тот же,
 * файл валиден, повторная запись идемпотентна; разметка ничего не пропускает (иначе писатель падает).
 */
class CompactWriteTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames
    }

    private fun compact(bytes: ByteArray): ByteArray = DexWriter(DexReader().read(bytes), compact = true).buildDex()

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `смысл тот же, файл валиден`(name: String) {
        val bytes = Fixtures.dex(name)
        val out = compact(bytes)
        assertNoDiff(DexSemantics.diff(bytes, out, DexSemantics.Options(version = true)), "$name: сжатая запись")
        assertTrue(out.size <= DexWriter(DexReader().read(bytes)).buildDex().size, "$name: сжатая запись не меньше обычной")
        if (DexDump.available()) DexDump.assertValid(out, "$name: сжатая запись")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `повторная сжатая запись — те же байты`(name: String) {
        val once = compact(Fixtures.dex(name))
        assertSameBytes(once, compact(once), "$name: повторная сжатая запись")
    }
}
