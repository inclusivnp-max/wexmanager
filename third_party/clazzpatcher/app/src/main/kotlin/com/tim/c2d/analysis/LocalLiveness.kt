package com.tim.c2d.analysis

import com.tim.c2d.bytecode.DecodedCode
import com.tim.clazz.model.opcodes.Opcodes as Op
import java.util.BitSet

/**
 * Живость локалов JVM: какие слоты ещё будут прочитаны (до перезаписи) после инструкции.
 *
 * Обратный поток данных по блокам [MethodFlow]: живое на выходе блока — объединение живого на входе
 * преемников; обработчик исключений читает локалы в момент броска, поэтому живое на его входе живо
 * и перед каждой инструкцией его диапазона. Внутри блока ответ считается по запросу — проходом от
 * конца блока до инструкции.
 */
internal class LocalLiveness private constructor(
    private val code: DecodedCode,
    private val flow: MethodFlow,
    private val handlers: ExceptionTable,
    private val liveIn: Array<BitSet>,
    private val liveOut: Array<BitSet>,
    private val handlerLive: Array<BitSet?>
) {
    /** Слоты, которые могут быть прочитаны после успешного выполнения инструкции [insn]. */
    fun liveAfter(insn: Int): BitSet {
        val block = flow.blockOfInsn[insn]
        val end = flow.blockEnd(block, code.insns.size)
        val live = liveOut[block].clone() as BitSet
        for (i in end - 1 downTo insn + 1) step(i, live)
        return live
    }

    private fun step(i: Int, live: BitSet) = transfer(code, handlers, handlerLive, i, live)

    companion object {
        fun analyze(code: DecodedCode, flow: MethodFlow, handlers: ExceptionTable): LocalLiveness {
            val insns = code.insns
            val n = insns.size
            val blocks = flow.blockCount
            val liveIn = Array(blocks) { BitSet() }
            val liveOut = Array(blocks) { BitSet() }
            // Живое на входе обработчиков по набору обработчиков (обновляется вместе с liveIn).
            val handlerLive = arrayOfNulls<BitSet>(handlers.handlerSets.size)
            fun refreshHandlers() {
                for ((id, set) in handlers.handlerSets.withIndex()) {
                    if (set.isEmpty()) continue
                    val live = BitSet()
                    for (h in set) live.or(liveIn[flow.blockOfInsn[h.handlerIndex]])
                    handlerLive[id] = live
                }
            }
            // Преемники блоков по обычным рёбрам.
            val succ = Array(blocks) { b ->
                val end = flow.blockEnd(b, n)
                val last = insns[end - 1]
                val out = ArrayList<Int>(2)
                FlowAnalysis.forEachTarget(last) { pc -> out.add(flow.blockOfInsn[code.indexOf(pc)]) }
                if (FlowAnalysis.fallsThrough(last.opcode) && end < n) out.add(flow.blockOfInsn[end])
                out.toIntArray()
            }
            var changed = true
            while (changed) {
                changed = false
                refreshHandlers()
                for (b in blocks - 1 downTo 0) {
                    val out = BitSet()
                    for (s in succ[b]) out.or(liveIn[s])
                    liveOut[b] = out
                    val live = out.clone() as BitSet
                    val start = flow.blockStart[b]
                    val end = flow.blockEnd(b, n)
                    for (i in end - 1 downTo start) transfer(code, handlers, handlerLive, i, live)
                    if (live != liveIn[b]) {
                        liveIn[b] = live
                        changed = true
                    }
                }
            }
            return LocalLiveness(code, flow, handlers, liveIn, liveOut, handlerLive)
        }

        /** Живое до инструкции [i] из живого после неё (на месте). */
        private fun transfer(code: DecodedCode, handlers: ExceptionTable, handlerLive: Array<BitSet?>, i: Int, live: BitSet) {
            val insn = code.insns[i]
            val a = insn.a
            when (insn.opcode) {
                Op.ISTORE, Op.FSTORE, Op.ASTORE -> live.clear(a)
                Op.LSTORE, Op.DSTORE -> live.clear(a, a + 2)
                Op.ILOAD, Op.FLOAD, Op.ALOAD, Op.RET -> live.set(a)
                Op.LLOAD, Op.DLOAD -> live.set(a, a + 2)
                Op.IINC -> live.set(a)
            }
            // Исключение из инструкции уходит в обработчик с локалами до неё.
            handlerLive[handlers.handlerSetOfInsn[i]]?.let { live.or(it) }
        }
    }
}
