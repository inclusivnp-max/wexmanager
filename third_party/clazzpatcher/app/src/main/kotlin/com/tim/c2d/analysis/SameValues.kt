package com.tim.c2d.analysis

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.DecodedCode
import com.tim.c2d.bytecode.JvmInsn
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Какие локалы и слова стека держат тот же объект, что и операнд `monitorenter`.
 *
 * Анализ копий: `load`/`store`, `dup*`, `swap` и `checkcast` переносят значение, любая другая
 * инструкция даёт новое; на слиянии путей несовпадающие значения становятся новыми (свой номер
 * на блок и слот). Верификатор ART отслеживает блокировку по регистрам: monitor-exit должен взять
 * объект из регистра, который держит блокировку, или из копии, снятой после захвата. Поэтому после
 * monitor-enter признак блокировки передаётся копиями всем регистрам с тем же объектом.
 */
internal class SameValues private constructor(private val equal: Array<IntArray?>, private val maxLocals: Int) {

    /**
     * Для `monitorenter` с индексом [insn]: слоты с тем же значением, что и операнд, кроме самого
     * операнда: `0 until maxLocals` — локалы, дальше — `maxLocals + слово стека`.
     */
    fun at(insn: Int): IntArray = equal[insn] ?: EMPTY

    fun isLocal(slot: Int): Boolean = slot < maxLocals
    fun stackWord(slot: Int): Int = slot - maxLocals

    companion object {
        private val EMPTY = IntArray(0)

        fun analyze(code: DecodedCode, flow: MethodFlow, handlers: ExceptionTable, interpreter: TypeInterpreter,
                    pool: ConstantPoolView, maxLocals: Int, maxStack: Int): SameValues {
            val insns = code.insns
            val n = insns.size
            val width = maxLocals + maxStack
            val blocks = flow.blockCount
            fun phi(block: Int, slot: Int): Int = -(1 + block * width + slot)
            fun fresh(insn: Int, k: Int): Int = ((insn + 1) shl 2) or k

            val entry = arrayOfNulls<IntArray>(blocks)
            val queued = BooleanArray(blocks)
            val queue = ArrayDeque<Int>()
            fun merge(block: Int, incoming: IntArray) {
                val old = entry[block]
                if (old == null) {
                    entry[block] = incoming.copyOf()
                } else {
                    var changed = false
                    for (s in 0 until width) {
                        if (old[s] != incoming[s] && old[s] != phi(block, s)) {
                            old[s] = phi(block, s)
                            changed = true
                        }
                    }
                    if (!changed) return
                }
                if (!queued[block]) {
                    queued[block] = true
                    queue.addLast(block)
                }
            }

            val equal = arrayOfNulls<IntArray>(n)
            merge(0, IntArray(width) { phi(0, it) })
            while (queue.isNotEmpty()) {
                val block = queue.removeFirst()
                queued[block] = false
                val frame = flow.entry[block]?.copy() ?: continue
                val ids = entry[block]!!.copyOf()
                val end = flow.blockEnd(block, n)
                for (i in flow.blockStart[block] until end) {
                    val insn = insns[i]
                    for (h in handlers.handlerSets[handlers.handlerSetOfInsn[i]]) {
                        val hb = flow.blockOfInsn[h.handlerIndex]
                        val incoming = IntArray(width) { if (it < maxLocals) ids[it] else 0 }
                        incoming[maxLocals] = phi(hb, maxLocals)
                        merge(hb, incoming)
                    }
                    val before = frame.sp
                    if (insn.opcode == Op.MONITORENTER) {
                        val operand = ids[maxLocals + before - 1]
                        val same = ArrayList<Int>()
                        for (s in 0 until maxLocals) if (ids[s] == operand) same.add(s)
                        for (w in 0 until before - 1) if (ids[maxLocals + w] == operand) same.add(maxLocals + w)
                        equal[i] = same.toIntArray()
                    }
                    interpreter.execute(frame, insn)
                    transfer(insn, i, ids, maxLocals, before, frame, pool, ::fresh)
                }
                val last = insns[end - 1]
                FlowAnalysis.forEachTarget(last) { merge(flow.blockOfInsn[code.indexOf(it)], ids) }
                if (FlowAnalysis.fallsThrough(last.opcode) && end < n) merge(flow.blockOfInsn[end], ids)
            }
            return SameValues(equal, maxLocals)
        }

        private fun transfer(insn: JvmInsn, i: Int, ids: IntArray, locals: Int, before: Int, after: TypeFrame,
                             pool: ConstantPoolView, fresh: (Int, Int) -> Int) {
            val top = locals + before // слот над вершиной стека до инструкции
            val a = insn.a
            when (insn.opcode) {
                Op.ILOAD, Op.FLOAD, Op.ALOAD -> ids[top] = ids[a]
                Op.LLOAD, Op.DLOAD -> { ids[top] = ids[a]; ids[top + 1] = ids[a + 1] }
                Op.ISTORE, Op.FSTORE, Op.ASTORE -> ids[a] = ids[top - 1]
                Op.LSTORE, Op.DSTORE -> { ids[a] = ids[top - 2]; ids[a + 1] = ids[top - 1] }
                Op.IINC -> ids[a] = fresh(i, 0)
                Op.CHECKCAST, Op.POP, Op.POP2, Op.NOP, Op.MONITORENTER, Op.MONITOREXIT -> {}
                Op.DUP -> ids[top] = ids[top - 1]
                Op.DUP_X1 -> { val v1 = ids[top - 1]; val v2 = ids[top - 2]; ids[top - 2] = v1; ids[top - 1] = v2; ids[top] = v1 }
                Op.DUP_X2 -> {
                    val v1 = ids[top - 1]; val v2 = ids[top - 2]; val v3 = ids[top - 3]
                    ids[top - 3] = v1; ids[top - 2] = v3; ids[top - 1] = v2; ids[top] = v1
                }
                Op.DUP2 -> { ids[top] = ids[top - 2]; ids[top + 1] = ids[top - 1] }
                Op.DUP2_X1 -> {
                    val v1 = ids[top - 1]; val v2 = ids[top - 2]; val v3 = ids[top - 3]
                    ids[top - 3] = v2; ids[top - 2] = v1; ids[top - 1] = v3; ids[top] = v2; ids[top + 1] = v1
                }
                Op.DUP2_X2 -> {
                    val v1 = ids[top - 1]; val v2 = ids[top - 2]; val v3 = ids[top - 3]; val v4 = ids[top - 4]
                    ids[top - 4] = v2; ids[top - 3] = v1; ids[top - 2] = v4; ids[top - 1] = v3; ids[top] = v2; ids[top + 1] = v1
                }
                Op.SWAP -> { val v1 = ids[top - 1]; ids[top - 1] = ids[top - 2]; ids[top - 2] = v1 }
                else -> {
                    val pushed = pushedWords(insn, after, pool)
                    for (k in 0 until pushed) ids[locals + after.sp - pushed + k] = fresh(i, k)
                }
            }
        }

        /** Сколько слов кладёт на стек инструкция, которая не копирует значения. */
        private fun pushedWords(insn: JvmInsn, after: TypeFrame, pool: ConstantPoolView): Int {
            when (insn.opcode) {
                in Op.ISTORE..Op.SASTORE, Op.IINC, in Op.IFEQ..Op.RET, Op.TABLESWITCH, Op.LOOKUPSWITCH,
                in Op.IRETURN..Op.RETURN, Op.PUTSTATIC, Op.PUTFIELD, Op.ATHROW, Op.IFNULL, Op.IFNONNULL -> return 0
                Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC, Op.INVOKEINTERFACE ->
                    if (pool.member(insn.a).descriptor.endsWith(")V")) return 0
                Op.INVOKEDYNAMIC -> if (pool.invokeDynamic(insn.a).descriptor.endsWith(")V")) return 0
            }
            if (after.sp == 0) return 0
            return if (after.stack[after.sp - 1].isHigh) 2 else 1
        }
    }
}
