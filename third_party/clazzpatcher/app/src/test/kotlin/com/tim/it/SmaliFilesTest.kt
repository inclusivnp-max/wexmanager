package com.tim.it

import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.testutil.DexSemantics
import com.tim.testutil.Fixtures
import com.tim.testutil.TEST_NAME
import com.tim.testutil.assertNoDiff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Smali.save / Smali.load через файловую систему. Каталог должен переноситься на файловые системы
 * без учёта регистра (Android /storage/emulated/0): имена файлов и каталогов уникальны без учёта регистра
 * и не длиннее 255 байт.
 */
class SmaliFilesTest {
    companion object {
        @JvmStatic
        fun dex() = Fixtures.dexNames
    }

    @TempDir
    lateinit var tmp: File

    private fun save(name: String): File {
        val dir = File(tmp, "smali")
        Smali.save(Smali.disassemble(DexReader().read(Fixtures.dex(name))), dir)
        return dir
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `имена уникальны без учёта регистра и не длиннее 255 байт`(name: String) {
        val dir = save(name)
        val paths = dir.walkTopDown().filter { it != dir }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        val collisions = paths.groupBy { it.lowercase() }.values.filter { it.size > 1 }
        val tooLong = paths.flatMap { it.split('/') }.distinct().filter { it.toByteArray(Charsets.UTF_8).size > 255 }
        if (collisions.isNotEmpty() || tooLong.isNotEmpty()) {
            fail<Unit>(
                "$name: коллизии без учёта регистра: ${collisions.take(5)}; " +
                    "имена длиннее 255 байт: ${tooLong.map { it.take(40) + "…(" + it.length + ")" }.take(3)}"
            )
        }
    }

    @ParameterizedTest(name = TEST_NAME)
    @MethodSource("dex")
    fun `save и load сохраняют все классы, результат эквивалентен исходному`(name: String) {
        val original = Fixtures.dex(name)
        val loaded = Smali.load(save(name))
        val expected = DexSemantics.of(original).classIndex.keys.sorted()
        assertEquals(expected, loaded.classes.map { it.descriptor }.sorted(), "$name: набор классов после save/load")
        assertNoDiff(DexSemantics.diff(original, DexWriter(Smali.assemble(loaded)).buildDex(), DexSemantics.Options.SMALI), "$name: save/load/assemble")
    }
}
