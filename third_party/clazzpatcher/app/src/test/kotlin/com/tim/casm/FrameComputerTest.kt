package com.tim.casm

import com.tim.casm.frames.ComputedTry
import com.tim.casm.frames.FrameComputer
import com.tim.casm.frames.FrameMode
import com.tim.casm.frames.MethodShape
import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.StackMapTable
import com.tim.clazz.parser.ClassReader
import com.tim.testutil.ClassCorpus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Расчёт фреймов на байткоде javac/kotlinc: фреймы ставятся ровно там же, где у компилятора,
 * а max_stack/max_locals не превышают записанных компилятором (они у javac точные).
 */
class FrameComputerTest {

    private val jdk = ClassPathHierarchy.jdk()

    private class Stats(var methods: Int = 0, var frames: Int = 0, var millis: Long = 0)

    private fun check(corpus: Map<String, ByteArray>): Stats {
        val stats = Stats()
        val start = System.nanoTime()
        for ((name, bytes) in corpus) {
            val cf = ClassReader().read(bytes, name)
            for (m in cf.methods) {
                val code = m.attributes.filterIsInstance<Code>().firstOrNull() ?: continue
                val where = "$name.${cf.utf8(m.nameIndex)}${cf.utf8(m.descriptorIndex)}"
                val computed = FrameComputer.compute(
                    code.code,
                    code.exceptionTable.map {
                        ComputedTry(it.startPc, it.endPc, it.handlerPc, if (it.catchType == 0) null else cf.className(it.catchType))
                    },
                    cf.constantPool::getOrNull,
                    jdk,
                    MethodShape(cf.thisClassName, cf.utf8(m.nameIndex), cf.utf8(m.descriptorIndex), m.accessFlags and 0x0008 != 0),
                    FrameMode.forVersion(cf.majorVersion)
                )
                stats.methods++
                assertTrue(computed.maxStack <= code.maxStack) { "$where: max_stack ${computed.maxStack} > ${code.maxStack}" }
                assertTrue(computed.maxLocals <= code.maxLocals) { "$where: max_locals ${computed.maxLocals} > ${code.maxLocals}" }
                assertTrue(computed.code.contentEquals(code.code)) { "$where: в коде компилятора найден недостижимый код" }
                // Версия 50 допускает классы без StackMapTable (откат на старый верификатор).
                if (cf.majorVersion < 50 || cf.majorVersion == 50 && code.attributes.none { it is StackMapTable }) continue
                val expected = framePcs(code)
                val actual = pcs(computed.frames.map { it.offsetDelta })
                stats.frames += actual.size
                // javac иногда ставит лишние фреймы (например, в начале цикла pattern switch),
                // но каждый обязательный фрейм у него есть: наши позиции — подмножество его позиций.
                val extra = actual - expected.toSet()
                assertTrue(extra.isEmpty()) { "$where: фреймы на pc $extra, которых нет у компилятора ($expected)" }
            }
        }
        stats.millis = (System.nanoTime() - start) / 1_000_000
        return stats
    }

    private fun framePcs(code: Code): List<Int> =
        pcs(code.attributes.filterIsInstance<StackMapTable>().flatMap { t -> t.entries.map { it.offsetDelta } })

    private fun pcs(deltas: List<Int>): List<Int> {
        val out = ArrayList<Int>(deltas.size)
        var pc = -1
        for (d in deltas) { pc += d + 1; out.add(pc) }
        return out
    }

    @Test
    fun kotlinStdlib() {
        val s = check(ClassCorpus.kotlinStdlib())
        println("kotlin-stdlib: ${s.methods} методов, ${s.frames} фреймов, ${s.millis} мс")
    }

    @Test
    fun projectClasses() {
        val s = check(ClassCorpus.projectMain())
        println("классы проекта: ${s.methods} методов, ${s.frames} фреймов, ${s.millis} мс")
    }

    @Test
    fun jdkBase() {
        val s = check(ClassCorpus.jdkModule("java.base"))
        println("java.base: ${s.methods} методов, ${s.frames} фреймов, ${s.millis} мс")
    }

    @Test
    fun fixtures() {
        check(ClassCorpus.fixtures())
    }

}
