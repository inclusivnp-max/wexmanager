package com.tim.it

import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.Memo
import com.tim.testutil.Pipelines
import com.tim.testutil.RefSmali
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Контракт синтаксиса smali 3.0.9, проверяемый по отдельности для каждой половины конвейера:
 *  - наш дизассемблер -> эталонный smali: тот же DEX, что эталонный baksmali -> эталонный smali
 *    (особенности эталонного smali — outs_size только по invoke, обрезка хвостовых значений по умолчанию
 *    в static_values — одинаково действуют на обе стороны);
 *  - эталонный baksmali -> наш парсер и ассемблер -> DEX, эквивалентный исходному.
 */
class SmaliContractTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames

        /** Уровень API эталонного smali по версии DEX из заголовка (без DexReader). */
        private fun api(name: String) = RefSmali.apiForVersion(String(Fixtures.dex(name), 4, 3, Charsets.US_ASCII))

        fun ourTextByRefSmali(name: String): ByteArray = Memo.get("ours->ref:$name") {
            RefSmali.assemble(SmaliRoundTripTest.texts(name).values, api(name))
        }

        fun refTextByRefSmali(name: String): ByteArray = Memo.get("ref->ref:$name") {
            RefSmali.assemble(RefSmali.disassemble(Fixtures.dex(name)).values, api(name))
        }

        fun refTextByOurAsm(name: String): ByteArray = Memo.get("ref->ours:$name") {
            Pipelines.assembleFromText(RefSmali.disassemble(Fixtures.dex(name)).values)
        }
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `наш smali собирается эталонным smali так же, как smali эталонного baksmali`(name: String) {
        val expected = refTextByRefSmali(name)
        val rebuilt = ourTextByRefSmali(name)
        assertNoDiff(RefSmali.diff(expected, rebuilt), "$name: наш дизассемблер -> эталонный smali (baksmali-тексты)")
        // Ожидаемая сторона прошла через baksmali 3.0.9, который печатает DBG_SET_EPILOGUE_BEGIN как .prologue
        assertNoDiff(
            DexSemantics.diff(expected, rebuilt, DexSemantics.Options(mergePrologueEpilogue = true)),
            "$name: наш дизассемблер -> эталонный smali (семантика)"
        )
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `smali эталонного baksmali собирается нашим ассемблером в валидный DEX`(name: String) {
        DexDump.assertValid(refTextByOurAsm(name), "$name: эталонный baksmali -> наш ассемблер")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `smali эталонного baksmali собирается нашим ассемблером в эквивалентный DEX`(name: String) {
        val ours = refTextByOurAsm(name)
        assertNoDiff(RefSmali.diff(Fixtures.dex(name), ours), "$name: эталонный baksmali -> наш ассемблер (baksmali-тексты)")
        // baksmali 3.0.9 печатает DBG_SET_EPILOGUE_BEGIN как .prologue — эпилог теряется не по нашей вине
        assertNoDiff(
            DexSemantics.diff(Fixtures.dex(name), ours, DexSemantics.Options.SMALI.copy(mergePrologueEpilogue = true)),
            "$name: эталонный baksmali -> наш ассемблер (семантика)"
        )
    }
}
