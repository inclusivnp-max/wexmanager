package com.tim.c2d.translate

import com.tim.c2d.bytecode.JvmInsn
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Использование слотов локалов метода — для раскладки регистров и лишних записей.
 *
 * - [uses]: обращения к слоту (load/store/iinc) — частые слоты получают младшие регистры;
 * - [wideAt]: в слоте начинается живое long/double (пара соседних регистров);
 * - [neverRead]: слот не параметр, его никто не читает и у него нет имени в отладочной информации
 *   (kotlinc: маркеры inline-функций `$i$f$…` = 0). Записи в него отбрасываются;
 * - [dead]: такой слот ещё и не входит в пару живого long/double — регистра у него нет;
 * - [aliasOf]: слот-копия — его единственная запись `xload A; xstore B` в одном блоке, а `A` (параметр
 *   или такая же копия) не меняется нигде в методе (kotlinc: `$this$…`). `B` живёт в регистре `A`
 *   (у long/double — пара `B, B+1` в паре регистров `A, A+1`).
 *
 * [named] — слоты с записями LocalVariableTable (если отладочная информация переносится): их значения
 * видны отладчику, поэтому такие слоты не выбрасываются и не сливаются.
 */
class LocalSlots private constructor(
    val uses: IntArray,
    val wideAt: BooleanArray,
    val neverRead: BooleanArray,
    val dead: BooleanArray,
    val aliasOf: IntArray
) {
    companion object {
        fun analyze(
            insns: Array<JvmInsn>,
            blockOfInsn: IntArray,
            maxLocals: Int,
            ins: Int,
            wideParams: IntArray,
            named: BooleanArray
        ): LocalSlots {
            val n = maxLocals
            val uses = IntArray(n)
            val reads = IntArray(n)
            val writes = IntArray(n)
            val lastWrite = IntArray(n) { -1 }
            fun touch(counter: IntArray, slot: Int, width: Int) {
                for (k in slot until minOf(slot + width, n)) counter[k]++
            }
            for ((i, insn) in insns.withIndex()) {
                val a = insn.a
                if (a !in 0 until n) continue
                when (insn.opcode) {
                    Op.ILOAD, Op.FLOAD, Op.ALOAD -> { uses[a]++; touch(reads, a, 1) }
                    Op.LLOAD, Op.DLOAD -> { uses[a]++; touch(reads, a, 2) }
                    Op.ISTORE, Op.FSTORE, Op.ASTORE -> { uses[a]++; touch(writes, a, 1); lastWrite[a] = i }
                    Op.LSTORE, Op.DSTORE -> { uses[a]++; touch(writes, a, 2); lastWrite[a] = i }
                    Op.IINC -> { uses[a]++; touch(reads, a, 1); touch(writes, a, 1); lastWrite[a] = i }
                    Op.RET -> { uses[a]++; touch(reads, a, 1) }
                }
            }
            val candidateDead = BooleanArray(n) { it >= ins && reads[it] == 0 && !named[it] }
            val wideAt = BooleanArray(n)
            for (insn in insns) {
                val a = insn.a
                if (a !in 0 until n - 1) continue
                when (insn.opcode) {
                    Op.LLOAD, Op.DLOAD -> wideAt[a] = true
                    Op.LSTORE, Op.DSTORE -> if (!candidateDead[a]) wideAt[a] = true
                }
            }
            for (p in wideParams) if (p + 1 < n) wideAt[p] = true
            // Слот внутри живой пары остаётся с регистром (пара — соседние регистры); записи в него
            // всё равно отбрасываются — и long/double, начатые в нём, пары не требуют.
            val dead = BooleanArray(n) { candidateDead[it] && !wideAt[it] && !(it > 0 && wideAt[it - 1]) }

            val aliasOf = IntArray(n) { -1 }
            fun root(slot: Int): Int = if (aliasOf[slot] >= 0) aliasOf[slot] else slot
            // Копии копий: слот-источник должен быть уже разобран — проходим по записям в порядке кода.
            val order = (ins until n).filter { writes[it] == 1 && lastWrite[it] > 0 }.sortedBy { lastWrite[it] }
            for (b in order) {
                if (named[b] || candidateDead[b] || reads[b] == 0 || (b > 0 && wideAt[b - 1])) continue
                val s = lastWrite[b]
                val store = insns[s]
                val load = insns[s - 1]
                val kindOk = when (store.opcode) {
                    Op.ISTORE -> load.opcode == Op.ILOAD
                    Op.FSTORE -> load.opcode == Op.FLOAD
                    Op.ASTORE -> load.opcode == Op.ALOAD
                    Op.LSTORE -> load.opcode == Op.LLOAD
                    Op.DSTORE -> load.opcode == Op.DLOAD
                    else -> false
                }
                if (!kindOk || blockOfInsn[s] != blockOfInsn[s - 1]) continue
                val wide = store.opcode == Op.LSTORE || store.opcode == Op.DSTORE
                // Пара копии — ровно (b, b+1): вторую половину пишет только эта запись, других пар рядом нет.
                if (wide && (b + 1 >= n || named[b + 1] || writes[b + 1] != 1 || (b + 2 < n && wideAt[b + 1]))) continue
                if (!wide && wideAt[b]) continue
                val a = load.a
                if (a == b || a !in 0 until n || (wide && (a + 1 >= n || a + 1 == b || b + 1 == a))) continue
                // Источник не меняется: параметр без записей или уже найденная копия.
                val stable = if (wide) (writes[a] == 0 && writes[a + 1] == 0 && a + 1 < ins) else (writes[a] == 0 && a < ins)
                if (!stable && aliasOf[a] < 0) continue
                aliasOf[b] = root(a)
                if (wide) aliasOf[b + 1] = aliasOf[b] + 1
            }
            // Обращения к копии — это обращения к регистру источника.
            for (b in 0 until n) if (aliasOf[b] >= 0) { uses[aliasOf[b]] += uses[b]; uses[b] = 0 }
            return LocalSlots(uses, wideAt, candidateDead, dead, aliasOf)
        }
    }
}
