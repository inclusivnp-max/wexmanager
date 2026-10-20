package com.tim.fuzz

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.writer.DexWriter
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Сквозной перевод случайных программ без устройства: smali → DEX → d2c → c2d → запись DEX не должны
 * падать (выполнение и сравнение с ART — в [DiffFuzzTest]). Так нашлась пара long/double, записанная
 * в слот, который никто не читает, на последний регистр кадра.
 */
class PipelineFuzzTest {
    @Test
    fun d2cThenC2dNeverFails() {
        val programs = System.getProperty("clazzpatcher.fuzz")?.toIntOrNull()?.let { maxOf(it, 400) } ?: 1200
        val failures = ArrayList<String>()
        for (k in 0 until programs) {
            val seed = Fuzz.seed * 104_729 + k
            val text = SmaliProgramGen(seed, "g/G$k").generate()
            try {
                val dex = Smali.assemble(SmaliTree(mutableListOf(SmaliParser.parseClass(text)!!)))
                val bytes = DexToClass.convertToBytes(dex, D2cOptions())
                DexWriter(ClassToDex.convert(bytes.values.map { ClassReader().read(it) })).buildDex()
            } catch (e: Exception) {
                failures.add("seed $seed: ${generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: "" }}")
                if (failures.size >= 5) break
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
