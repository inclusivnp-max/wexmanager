package com.tim.c2d.bytecode

import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.LineNumberTable
import com.tim.clazz.model.opcodes.Opcodes as Op
import java.util.ArrayDeque
import java.util.BitSet

/**
 * Встраивание подпрограмм `jsr`/`ret` (так javac до 1.4.2 и ecj для старых версий компилировали
 * `finally`; в DEX подпрограмм нет): каждый `jsr` получает свою копию подпрограммы — `jsr` становится
 * `aconst_null` + `goto` на копию (null занимает место адреса возврата, который подпрограмма сохраняет
 * в локал), `ret` — `goto` на точку возврата; вложенные подпрограммы копируются рекурсивно, обработчики
 * исключений — вместе с кодом, который они защищают. Алгоритм — как у JSRInlinerAdapter из ASM (им
 * пользуется и d8): инструкции подпрограммы — достижимые из её начала без захода в другие `jsr`,
 * общие для нескольких подпрограмм выдаются в копии ближайшего по цепочке вызовов владельца.
 *
 * У встроенного метода остаются номера строк; таблицы локальных переменных и StackMapTable
 * отбрасываются (адреса в них больше не соответствуют коду).
 */
internal object JsrInliner {

    fun uses(code: Code): Boolean {
        for (insn in BytecodeDecoder.decode(code.code).insns) if (insn.opcode == Op.JSR || insn.opcode == Op.RET) return true
        return false
    }

    fun inline(code: Code): Code {
        val decoded = BytecodeDecoder.decode(code.code)
        val insns = decoded.insns
        val n = insns.size
        if (insns.none { it.opcode == Op.JSR || it.opcode == Op.RET }) return code
        val bytes = code.code

        fun index(pc: Int, what: String): Int {
            if (pc == bytes.size) return n
            val i = decoded.indexOf(pc)
            if (i < 0) throw IllegalArgumentException("jsr/ret: $what указывает не на начало инструкции (pc=$pc)")
            return i
        }

        class Try(val start: Int, val end: Int, val handler: Int, val type: Int)
        val tries = code.exceptionTable.map {
            Try(index(it.startPc, "start_pc"), index(it.endPc, "end_pc"), index(it.handlerPc, "handler_pc"), it.catchType)
        }

        // ---- подпрограммы: инструкции, достижимые из начала без захода в jsr ----
        val heads = LinkedHashMap<Int, BitSet>()
        for (insn in insns) if (insn.opcode == Op.JSR) heads.getOrPut(index(insn.a, "цель jsr")) { BitSet() }
        val visitedAny = BitSet()
        val dual = BitSet()

        fun dfs(sub: BitSet, start: Int) {
            val work = ArrayDeque<Int>()
            work.push(start)
            while (!work.isEmpty()) {
                var i = work.pop()
                while (i < n && !sub.get(i)) {
                    sub.set(i)
                    if (visitedAny.get(i)) dual.set(i)
                    visitedAny.set(i)
                    val insn = insns[i]
                    when (insn.opcode) {
                        in Op.IFEQ..Op.GOTO, Op.IFNULL, Op.IFNONNULL -> work.push(index(insn.a, "переход"))
                        Op.TABLESWITCH, Op.LOOKUPSWITCH -> {
                            work.push(index(insn.a, "default"))
                            for (t in insn.targets!!) work.push(index(t, "ветка switch"))
                        }
                    }
                    if (!fallsThrough(insn.opcode)) break
                    i++
                }
            }
        }

        fun walk(sub: BitSet, start: Int) {
            dfs(sub, start)
            var again = true
            while (again) {
                again = false
                for (t in tries) {
                    if (t.handler < n && sub.get(t.handler)) continue
                    val next = sub.nextSetBit(t.start)
                    if (next in 0 until t.end) {
                        dfs(sub, t.handler)
                        again = true
                    }
                }
            }
        }

        val main = BitSet()
        walk(main, 0)
        for ((head, sub) in heads) walk(sub, head)

        // ---- копии подпрограмм ----
        class Inst(val parent: Inst?, val sub: BitSet) {
            val returnLabel: Label? = if (parent != null) Label() else null
            val labels = HashMap<Int, Label>()
            val end = Label()

            init {
                var p = parent
                while (p != null) {
                    if (p.sub === sub) throw IllegalArgumentException("jsr/ret: рекурсивный вызов подпрограммы")
                    p = p.parent
                }
            }

            fun owner(i: Int): Inst? {
                if (i >= n || !sub.get(i)) return null
                if (!dual.get(i)) return this
                var own: Inst = this
                var p = parent
                while (p != null) {
                    if (p.sub.get(i)) own = p
                    p = p.parent
                }
                return own
            }

            fun label(i: Int): Label = labels.getOrPut(i) { Label() }

            /** Место перехода к инструкции [i]: у той копии, что её выдаёт. */
            fun gotoLabel(i: Int): Label =
                (owner(i) ?: throw IllegalArgumentException("jsr/ret: переход из подпрограммы наружу (pc=${insns[i].pc})")).label(i)

            /** Граница диапазона try в этой копии: первая своя инструкция, начиная с [i]. */
            fun rangeLabel(i: Int): Label {
                var j = i
                while (j < n && owner(j) !== this) j++
                return if (j < n) label(j) else end
            }
        }

        val lineAt = IntArray(n) { -1 }
        val lineTable = code.attributes.firstOrNull { it is LineNumberTable } as LineNumberTable?
        lineTable?.entries?.forEach { e -> decoded.indexOf(e.startPc).let { if (it >= 0) lineAt[it] = e.lineNumber } }

        val asm = Assembler()
        val newTries = ArrayList<Array<Any>>()
        val lines = ArrayList<Pair<Label, Int>>()
        val work = ArrayDeque<Inst>()
        work.add(Inst(null, main))
        var emitted = 0
        while (!work.isEmpty()) {
            val inst = work.removeFirst()
            for (i in 0 until n) {
                if (inst.owner(i) !== inst) continue
                // Каждый jsr — своя копия подпрограммы: вложенные вызовы умножают код (как и у ASM, d8).
                if (++emitted > MAX_INSNS) {
                    throw IllegalArgumentException("jsr/ret: после встраивания подпрограмм больше $MAX_INSNS инструкций")
                }
                val label = inst.label(i)
                asm.mark(label)
                if (lineAt[i] >= 0) lines.add(label to lineAt[i])
                val insn = insns[i]
                when (insn.opcode) {
                    Op.RET -> {
                        var target: Label? = null
                        var p: Inst? = inst
                        while (p != null) {
                            if (p.sub.get(i)) target = p.returnLabel
                            p = p.parent
                        }
                        asm.branch(Op.GOTO, target ?: throw IllegalArgumentException("jsr/ret: ret вне подпрограммы (pc=${insn.pc})"))
                    }
                    Op.JSR -> {
                        val head = index(insn.a, "цель jsr")
                        val child = Inst(inst, heads.getValue(head))
                        asm.raw(byteArrayOf(Op.ACONST_NULL.toByte()))
                        asm.branch(Op.GOTO, child.gotoLabel(head))
                        asm.mark(child.returnLabel!!)
                        work.add(child)
                    }
                    in Op.IFEQ..Op.GOTO, Op.IFNULL, Op.IFNONNULL -> asm.branch(insn.opcode, inst.gotoLabel(index(insn.a, "переход")))
                    Op.TABLESWITCH, Op.LOOKUPSWITCH ->
                        asm.switch(insn.opcode, inst.gotoLabel(index(insn.a, "default")), insn.b, insn.keys,
                            insn.targets!!.map { inst.gotoLabel(index(it, "ветка switch")) })
                    else -> {
                        val next = if (i + 1 < n) insns[i + 1].pc else bytes.size
                        asm.raw(bytes.copyOfRange(insn.pc, next))
                    }
                }
            }
            asm.mark(inst.end)
            for (t in tries) {
                val start = inst.rangeLabel(t.start)
                val end = inst.rangeLabel(t.end)
                if (start === end) continue
                newTries.add(arrayOf(start, end, inst.gotoLabel(t.handler), t.type))
            }
        }

        val newCode = asm.assemble()
        val exceptionTable = newTries.map {
            Code.ExceptionTableEntry((it[0] as Label).pos, (it[1] as Label).pos, (it[2] as Label).pos, it[3] as Int)
        }.filter { it.startPc < it.endPc }
        val attributes = ArrayList<AttributeInfo>()
        if (lineTable != null && lines.isNotEmpty()) {
            attributes.add(LineNumberTable(lineTable.attributeNameIndex, 0,
                lines.map { LineNumberTable.LineNumberTableEntry(it.first.pos, it.second) }.sortedBy { it.startPc }))
        }
        return Code(code.attributeNameIndex, 0, code.maxStack, code.maxLocals, newCode, exceptionTable, attributes)
    }

    /** Предел инструкций метода после встраивания (защита от экспоненциального роста). */
    private const val MAX_INSNS = 1_000_000

    private fun fallsThrough(op: Int): Boolean = when (op) {
        Op.GOTO, Op.RET, Op.TABLESWITCH, Op.LOOKUPSWITCH, Op.ATHROW,
        Op.IRETURN, Op.LRETURN, Op.FRETURN, Op.DRETURN, Op.ARETURN, Op.RETURN -> false
        else -> true
    }

    private class Label {
        var pos = -1
    }

    /** Сборка байткода с метками: дальние переходы расширяются (goto_w; if — обратным условием через goto_w). */
    private class Assembler {
        private sealed class Item
        private class Mark(val label: Label) : Item()
        private class Raw(val bytes: ByteArray) : Item()
        private class Branch(val op: Int, val target: Label) : Item() {
            var wide = false
            val size get() = if (!wide) 3 else if (op == Op.GOTO) 5 else 8
        }
        private class Switch(val op: Int, val default: Label, val low: Int, val keys: IntArray?, val targets: List<Label>) : Item()

        private val items = ArrayList<Item>()

        fun mark(label: Label) { items.add(Mark(label)) }
        fun raw(bytes: ByteArray) { items.add(Raw(bytes)) }
        fun branch(op: Int, target: Label) { items.add(Branch(op, target)) }
        fun switch(op: Int, default: Label, low: Int, keys: IntArray?, targets: List<Label>) { items.add(Switch(op, default, low, keys, targets)) }

        private fun switchSize(s: Switch, pos: Int): Int {
            val pad = (4 - (pos + 1) % 4) % 4
            return 1 + pad + if (s.op == Op.TABLESWITCH) 12 + 4 * s.targets.size else 8 + 8 * s.targets.size
        }

        fun assemble(): ByteArray {
            val at = IntArray(items.size)
            while (true) {
                var pos = 0
                for ((k, item) in items.withIndex()) {
                    at[k] = pos
                    pos += when (item) {
                        is Mark -> { item.label.pos = pos; 0 }
                        is Raw -> item.bytes.size
                        is Branch -> item.size
                        is Switch -> switchSize(item, pos)
                    }
                }
                var changed = false
                for ((k, item) in items.withIndex()) {
                    if (item is Branch && !item.wide && (item.target.pos - at[k]) !in Short.MIN_VALUE..Short.MAX_VALUE) {
                        item.wide = true
                        changed = true
                    }
                }
                if (!changed) break
            }
            val out = java.io.ByteArrayOutputStream()
            fun u1(v: Int) = out.write(v and 0xFF)
            fun u2(v: Int) { u1(v ushr 8); u1(v) }
            fun s4(v: Int) { u2(v ushr 16); u2(v) }
            for ((k, item) in items.withIndex()) {
                val pos = at[k]
                when (item) {
                    is Mark -> {}
                    is Raw -> out.write(item.bytes)
                    is Branch -> when {
                        !item.wide -> { u1(item.op); u2(item.target.pos - pos) }
                        item.op == Op.GOTO -> { u1(Op.GOTO_W); s4(item.target.pos - pos) }
                        else -> {
                            // Обратное условие перепрыгивает goto_w на исходную цель.
                            u1(invert(item.op)); u2(8)
                            u1(Op.GOTO_W); s4(item.target.pos - (pos + 3))
                        }
                    }
                    is Switch -> {
                        u1(item.op)
                        repeat((4 - (pos + 1) % 4) % 4) { u1(0) }
                        s4(item.default.pos - pos)
                        if (item.op == Op.TABLESWITCH) {
                            s4(item.low)
                            s4(item.low + item.targets.size - 1)
                            for (t in item.targets) s4(t.pos - pos)
                        } else {
                            s4(item.targets.size)
                            for ((j, t) in item.targets.withIndex()) { s4(item.keys!![j]); s4(t.pos - pos) }
                        }
                    }
                }
            }
            return out.toByteArray()
        }

        private fun invert(op: Int): Int = when (op) {
            Op.IFNULL -> Op.IFNONNULL
            Op.IFNONNULL -> Op.IFNULL
            else -> if ((op - Op.IFEQ) % 2 == 0) op + 1 else op - 1
        }
    }
}
