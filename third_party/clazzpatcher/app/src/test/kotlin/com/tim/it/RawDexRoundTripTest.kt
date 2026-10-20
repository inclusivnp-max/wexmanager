package com.tim.it

import com.tim.testutil.DexDump
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.Memo
import com.tim.testutil.Pipelines
import com.tim.testutil.RefSmali
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import com.tim.testutil.assertSameBytes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** DexReader -> DexWriter: результат валиден, семантически равен исходному, повторная запись идемпотентна. */
class RawDexRoundTripTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames

        fun roundTrip(name: String): ByteArray = Memo.get("raw:$name") { Pipelines.rawRoundTrip(Fixtures.dex(name)) }
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `результат принимается верификатором`(name: String) {
        DexDump.assertValid(roundTrip(name), "raw round-trip $name")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `семантически совпадает с исходным (включая версию и пулы)`(name: String) {
        assertNoDiff(
            DexSemantics.diff(Fixtures.dex(name), roundTrip(name), DexSemantics.Options(version = true, pools = true)),
            "raw round-trip $name"
        )
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `совпадает с исходным по эталонному baksmali`(name: String) {
        assertNoDiff(RefSmali.diff(Fixtures.dex(name), roundTrip(name)), "raw round-trip $name (эталонный baksmali)")
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `повторная запись идемпотентна`(name: String) {
        val once = roundTrip(name)
        val twice = Pipelines.rawRoundTrip(once)
        assertSameBytes(once, twice, "write(read(write(x))) != write(x) для $name") {
            // Для диагностики: чем именно вторая запись отличается от первой
            val semantic = runCatching { DexSemantics.diff(once, twice, DexSemantics.Options(version = true, pools = true)) }
            val reference = runCatching { RefSmali.diff(once, twice) }
            "\nсемантика: " + semantic.fold({ it ?: "совпадает" }, { "не строится: $it" }) +
                "\nэталонный baksmali: " + reference.fold({ it ?: "совпадает" }, { "не строится: $it" })
        }
    }
}
