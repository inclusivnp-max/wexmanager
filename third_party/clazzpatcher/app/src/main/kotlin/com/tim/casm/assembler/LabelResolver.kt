package com.tim.casm.assembler

import com.tim.casm.dsl.CodeDsl
import com.tim.clazz.model.opcodes.Opcodes

/**
 * Раскладка инструкций [CodeDsl] в байты: адреса меток, выравнивание switch и расширение
 * дальних переходов (итерации до неподвижной точки — размеры только растут).
 */
internal object LabelResolver {

    class Result(
        val code: ByteArray,
        private val labels: Map<String, Int>,
        private val positions: IntArray
    ) {
        /** pc метки или null, если метка не объявлена. */
        fun pc(label: String): Int? = labels[label]?.let { positions[it] }
    }

    private const val SHORT = 0
    private const val WIDE = 1

    fun resolve(dsl: CodeDsl): Result {
        val insns = dsl.instructions
        val labels = dsl.labels
        val n = insns.size
        val positions = IntArray(n + 1)
        val form = IntArray(n)

        for ((name, index) in labels) {
            if (index > n) throw IllegalStateException("Метка $name вне кода")
        }

        while (true) {
            var pc = 0
            for (i in 0 until n) {
                positions[i] = pc
                pc += sizeOf(insns[i], pc, form[i])
            }
            positions[n] = pc
            var changed = false
            for (i in 0 until n) {
                val insn = insns[i] as? CodeDsl.Branch ?: continue
                if (form[i] == WIDE || insn.opcode == Opcodes.GOTO_W || insn.opcode == Opcodes.JSR_W) continue
                val delta = positions[target(labels, insn.label)] - positions[i]
                if (delta !in Short.MIN_VALUE..Short.MAX_VALUE) {
                    form[i] = WIDE
                    changed = true
                }
            }
            if (!changed) break
        }

        val out = ByteWriter(positions[n] + 16)
        for (i in 0 until n) {
            val at = out.pos
            when (val insn = insns[i]) {
                is CodeDsl.Raw -> out.bytes(insn.bytes)
                is CodeDsl.Branch -> writeBranch(out, insn, positions[target(labels, insn.label)] - at, form[i])
                is CodeDsl.Switch -> writeSwitch(out, insn) { positions[target(labels, it)] - at }
            }
            check(out.pos - at == sizeOf(insns[i], at, form[i])) { "Размер инструкции #$i не совпал с раскладкой" }
        }
        return Result(out.toByteArray(), labels, positions)
    }

    private fun target(labels: Map<String, Int>, label: String): Int =
        labels[label] ?: throw IllegalStateException("Неизвестная метка $label")

    private fun sizeOf(insn: CodeDsl.Insn, pc: Int, form: Int): Int = when (insn) {
        is CodeDsl.Raw -> insn.bytes.size
        is CodeDsl.Branch -> when {
            insn.opcode == Opcodes.GOTO_W || insn.opcode == Opcodes.JSR_W -> 5
            form == SHORT -> 3
            CodeDsl.isConditional(insn.opcode) -> 8 // if!cond +8; goto_w цель
            else -> 5
        }
        is CodeDsl.Switch -> {
            val padding = 3 - (pc and 3)
            val body = if (insn.keys == null) 12 + 4 * insn.targetLabels.size else 8 + 8 * insn.keys.size
            1 + padding + body
        }
    }

    private fun writeBranch(out: ByteWriter, insn: CodeDsl.Branch, delta: Int, form: Int) {
        val op = insn.opcode
        when {
            op == Opcodes.GOTO_W || op == Opcodes.JSR_W -> { out.u1(op); out.s4(delta) }
            form == SHORT -> { out.u1(op); out.s2(delta) }
            CodeDsl.isConditional(op) -> {
                out.u1(CodeDsl.inverted(op)); out.s2(8)
                out.u1(Opcodes.GOTO_W); out.s4(delta - 3)
            }
            else -> { out.u1(if (op == Opcodes.GOTO) Opcodes.GOTO_W else Opcodes.JSR_W); out.s4(delta) }
        }
    }

    private inline fun writeSwitch(out: ByteWriter, sw: CodeDsl.Switch, offsetOf: (String) -> Int) {
        out.u1(if (sw.keys == null) Opcodes.TABLESWITCH else Opcodes.LOOKUPSWITCH)
        out.align4()
        out.s4(offsetOf(sw.defaultLabel))
        if (sw.keys == null) {
            out.s4(sw.low); out.s4(sw.high)
            for (label in sw.targetLabels) out.s4(offsetOf(label))
        } else {
            out.s4(sw.keys.size)
            for (i in sw.keys.indices) {
                out.s4(sw.keys[i])
                out.s4(offsetOf(sw.targetLabels[i]))
            }
        }
    }
}
