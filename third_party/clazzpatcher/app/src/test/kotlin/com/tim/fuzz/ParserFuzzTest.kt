package com.tim.fuzz

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.testutil.Fixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Фаззинг разборщиков и конвейеров (см. [Fuzz]): мутированные DEX, .class и тексты smali
 * обрабатываются полностью — чтение, запись, дизассемблирование, конвертация — и любой отказ
 * обязан быть «чистым» исключением с сообщением. Входы прошлых находок (resources/fuzz/<цель>)
 * прогоняются всегда.
 */
class ParserFuzzTest {

    private class Target(
        val name: String,
        val ext: String,
        val seeds: () -> List<Pair<String, ByteArray>>,
        val mutation: (ByteArray, Random) -> ByteArray,
        val work: (ByteArray) -> Unit
    )

    private val strict = D2cOptions(threads = 1)

    private val targets = listOf(
        Target("dex", "dex",
            { Fixtures.dexNames.filter { it != "user" }.map { it to Fixtures.dex(it) } },
            { b, r -> Fuzz.mutate(b, r, littleEndian = true).also { if (r.nextInt(8) != 0) Fuzz.fixDexSize(it) } }
        ) { bytes ->
            val dex = DexReader().read(bytes)
            DexWriter(dex).buildDex()
            val tree = com.tim.dsmali.Smali.disassemble(dex)
            for (c in tree.classes) com.tim.dsmali.io.SmaliWriter.write(c)
            DexToClass.convert(dex, strict)
        },
        Target("class", "class",
            { Fixtures.classFiles.map { it.name to it.readBytes() } },
            { b, r -> Fuzz.mutate(b, r, littleEndian = false) }
        ) { bytes ->
            val cf = ClassReader().read(bytes)
            ClassReader().read(ClassWriter(cf).toByteArray())
            com.tim.csmali.io.SmaliWriter.write(com.tim.csmali.Smali.disassemble(cf).classes.single())
            DexWriter(ClassToDex.convert(listOf(cf))).buildDex()
        },
        Target("dsmali", "smali", ::smaliSeeds, { b, r -> Fuzz.mutateText(String(b), r).toByteArray() }) { bytes ->
            val cls = com.tim.dsmali.io.SmaliParser.parseClass(String(bytes)) ?: return@Target
            val dex = com.tim.dsmali.Smali.assemble(com.tim.dsmali.model.SmaliTree(mutableListOf(cls)))
            DexReader().read(DexWriter(dex).buildDex())
        },
        Target("csmali", "csmali",
            {
                Fixtures.classFiles.map { f ->
                    f.name to com.tim.csmali.io.SmaliWriter.write(com.tim.csmali.Smali.disassemble(ClassReader().read(f.readBytes())).classes.single()).toByteArray()
                }
            },
            { b, r -> Fuzz.mutateText(String(b), r).toByteArray() }
        ) { bytes ->
            val cls = com.tim.csmali.io.SmaliParser.parseClass(String(bytes)) ?: return@Target
            ClassReader().read(com.tim.csmali.Smali.assembleToBytes(cls))
        }
    )

    private fun smaliSeeds(): List<Pair<String, ByteArray>> {
        val dirs = listOf(File("src/test/fixtures-src/smali"), File(javaClass.getResource("/d2c/smali")!!.toURI()))
        return dirs.flatMap { d -> d.walkTopDown().filter { it.name.endsWith(".smali") }.map { it.name to it.readBytes() }.toList() }
    }

    private fun target(name: String) = targets.single { it.name == name }

    private fun fuzz(name: String) {
        val t = target(name)
        val findings = Fuzz.run(t.name, t.seeds(), t.ext, t.mutation, t.work)
        assertTrue(findings.isEmpty(), "находок: ${findings.size}\n" + findings.joinToString("\n"))
    }

    @Test fun dex() = fuzz("dex")
    @Test fun classFile() = fuzz("class")
    @Test fun dexSmali() = fuzz("dsmali")
    @Test fun classSmali() = fuzz("csmali")

    /** Входы прошлых находок: каждый обязан обрабатываться без внутренних ошибок и зависаний. */
    @TestFactory
    fun regressions(): List<DynamicTest> = targets.flatMap { t ->
        val dir = javaClass.getResource("/fuzz/${t.name}")?.let { File(it.toURI()) }
        dir?.listFiles().orEmpty().sortedBy { it.name }.map { f ->
            dynamicTest("${t.name}/${f.name}") {
                val problem = Fuzz.check { t.work(f.readBytes()) }
                assertTrue(problem == null, "${f.name}: $problem")
            }
        }
    }
}
