package com.tim.it

import com.tim.c2d.ClassToDex
import com.tim.clazz.parser.ClassReader
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import com.tim.testutil.Fixtures
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertTrue

/**
 * Утечки при работе библиотекой в долгоживущем процессе: одни и те же преобразования много раз
 * в одной JVM — куча после сборки мусора и число потоков не растут (кэши ограничены, пулы
 * потоков закрываются, ThreadLocal-буферы не накапливаются).
 */
class ResourceLeakTest {

    private fun heapAfterGc(): Long {
        val mx = ManagementFactory.getMemoryMXBean()
        repeat(4) { System.gc(); Thread.sleep(20) }
        return mx.heapMemoryUsage.used
    }

    private fun liveThreads(): Int {
        Thread.sleep(50)
        return ManagementFactory.getThreadMXBean().threadCount
    }

    private fun round() {
        for (name in listOf("gson", "sw", "gen/allops", "gen/invoke")) {
            val bytes = Fixtures.dex(name)
            val dex = DexReader().read(bytes)
            DexWriter(dex).buildDex()
            val tree = com.tim.dsmali.Smali.disassemble(dex)
            DexReader().read(DexWriter(com.tim.dsmali.Smali.assemble(tree)).buildDex())
            val classes = DexToClass.convertToBytes(dex, D2cOptions(strict = false))
            val back = ClassToDex.convert(classes.values.map { ClassReader().read(it) })
            DexWriter(back).buildDex()
            com.tim.csmali.Smali.disassembleAll(classes.values.map { ClassReader().read(it) })
        }
    }

    @Test
    fun `повторные преобразования не накапливают память и потоки`() {
        repeat(2) { round() } // прогрев: кэши заполняются до предела
        val heap0 = heapAfterGc()
        val threads0 = liveThreads()
        repeat(6) { round() }
        val heap1 = heapAfterGc()
        val threads1 = liveThreads()
        val growth = (heap1 - heap0) / (1 shl 20)
        assertTrue(growth < 24, "куча выросла на $growth МБ за 6 кругов (${heap0 shr 20} → ${heap1 shr 20} МБ)")
        assertTrue(threads1 <= threads0 + 2, "потоков было $threads0, стало $threads1")
    }
}
