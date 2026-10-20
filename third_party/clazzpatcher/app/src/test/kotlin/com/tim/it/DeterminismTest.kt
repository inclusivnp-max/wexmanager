package com.tim.it

import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.Pipelines
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import com.tim.testutil.assertSameBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/** Одинаковый вход — одинаковый выход (в т.ч. при параллельной загрузке smali), независимость экземпляров. */
class DeterminismTest {
    @TempDir
    lateinit var tmp: File

    @ParameterizedTest(name = TEST_NAME)
    @ValueSource(strings = ["user", "gson", "gen/misc"])
    fun `запись DEX детерминирована`(name: String) {
        val dex = Fixtures.dex(name)
        assertSameBytes(Pipelines.rawRoundTrip(dex), Pipelines.rawRoundTrip(dex), "$name: две записи одного DEX")
    }

    @ParameterizedTest(name = TEST_NAME)
    @ValueSource(strings = ["user", "gson", "gen/misc"])
    fun `дизассемблирование детерминировано`(name: String) {
        val dex = Fixtures.dex(name)
        assertEquals(Pipelines.disassembleToText(dex), Pipelines.disassembleToText(dex), "$name: два дизассемблирования")
    }

    @ParameterizedTest(name = TEST_NAME)
    @ValueSource(strings = ["user", "gson", "gen/misc"])
    fun `ассемблирование детерминировано`(name: String) {
        val texts = Pipelines.disassembleToText(Fixtures.dex(name)).values
        assertSameBytes(Pipelines.assembleFromText(texts), Pipelines.assembleFromText(texts), "$name: две сборки одних и тех же текстов")
    }

    @ParameterizedTest(name = TEST_NAME)
    @ValueSource(strings = ["user", "gson", "gen/misc"])
    fun `порядок классов в дереве не влияет на смысл результата`(name: String) {
        // Порядок class_def (и раскладка) может повторять порядок дерева, но содержимое классов — нет
        val texts = Pipelines.disassembleToText(Fixtures.dex(name)).values
        assertNoDiff(
            DexSemantics.diff(Pipelines.assembleFromText(texts), Pipelines.assembleFromText(texts.reversed()), DexSemantics.Options(version = true)),
            "$name: сборки с прямым и обратным порядком классов"
        )
    }

    @ParameterizedTest(name = TEST_NAME)
    @ValueSource(strings = ["user", "gson"])
    fun `save и load дают одинаковый DEX при повторах`(name: String) {
        val dir = File(tmp, "smali")
        Smali.save(Smali.disassemble(DexReader().read(Fixtures.dex(name))), dir)
        val first = DexWriter(Smali.assemble(Smali.load(dir))).buildDex()
        val second = DexWriter(Smali.assemble(Smali.load(dir))).buildDex()
        assertSameBytes(first, second, "$name: две загрузки одного каталога smali")
    }

    @Test
    fun `повторное использование DexReader не портит прочитанную ранее модель`() {
        val reader = DexReader()
        val first = reader.read(Fixtures.dex("sw"))
        val before = DexWriter(first).buildDex()
        reader.read(Fixtures.dex("gen/misc"))
        assertSameBytes(before, DexWriter(first).buildDex(), "модель первого DEX изменилась после второго read()")
    }
}
