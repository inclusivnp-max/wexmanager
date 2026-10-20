package com.tim.c2d.bytecode

import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Декодер байткода JVM (JVMS §6.5) в список [JvmInsn].
 *
 * Проверяет только структуру: известные опкоды, границы инструкций, выравнивание switch и то,
 * что цели переходов указывают на начало инструкции. Некорректный код — [IllegalArgumentException]
 * с адресом.
 */
object BytecodeDecoder {

    fun decode(code: ByteArray): DecodedCode {
        val length = code.size
        val insns = ArrayList<JvmInsn>(length / 2 + 1)
        val pcToIndex = IntArray(length) { -1 }
        var pc = 0
        while (pc < length) {
            pcToIndex[pc] = insns.size
            val (insn, size) = decodeAt(code, pc)
            insns.add(insn)
            pc += size
        }
        val decoded = DecodedCode(insns.toTypedArray(), pcToIndex, length)
        checkTargets(decoded)
        return decoded
    }

    private fun decodeAt(c: ByteArray, pc: Int): Pair<JvmInsn, Int> {
        val op = u1(c, pc)
        return when (op) {
            in Op.ICONST_M1..Op.ICONST_5 -> JvmInsn(pc, op, b = op - Op.ICONST_0) to 1
            Op.BIPUSH -> JvmInsn(pc, op, b = s1(c, pc + 1)) to 2
            Op.SIPUSH -> JvmInsn(pc, op, b = s2(c, pc + 1)) to 3
            Op.LDC -> JvmInsn(pc, Op.LDC, a = u1(c, pc + 1)) to 2
            Op.LDC_W -> JvmInsn(pc, Op.LDC, a = u2(c, pc + 1)) to 3
            Op.LDC2_W -> JvmInsn(pc, op, a = u2(c, pc + 1)) to 3

            Op.ILOAD, Op.LLOAD, Op.FLOAD, Op.DLOAD, Op.ALOAD,
            Op.ISTORE, Op.LSTORE, Op.FSTORE, Op.DSTORE, Op.ASTORE, Op.RET ->
                JvmInsn(pc, op, a = u1(c, pc + 1)) to 2
            in Op.ILOAD_0..Op.ALOAD_3 -> {
                val n = op - Op.ILOAD_0
                JvmInsn(pc, Op.ILOAD + n / 4, a = n % 4) to 1
            }
            in Op.ISTORE_0..Op.ASTORE_3 -> {
                val n = op - Op.ISTORE_0
                JvmInsn(pc, Op.ISTORE + n / 4, a = n % 4) to 1
            }

            Op.IINC -> JvmInsn(pc, op, a = u1(c, pc + 1), b = s1(c, pc + 2)) to 3

            in Op.IFEQ..Op.JSR, Op.IFNULL, Op.IFNONNULL ->
                JvmInsn(pc, op, a = pc + s2(c, pc + 1)) to 3
            Op.GOTO_W -> JvmInsn(pc, Op.GOTO, a = pc + s4(c, pc + 1)) to 5
            Op.JSR_W -> JvmInsn(pc, Op.JSR, a = pc + s4(c, pc + 1)) to 5

            Op.TABLESWITCH -> tableSwitch(c, pc)
            Op.LOOKUPSWITCH -> lookupSwitch(c, pc)

            Op.GETSTATIC, Op.PUTSTATIC, Op.GETFIELD, Op.PUTFIELD,
            Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC,
            Op.NEW, Op.ANEWARRAY, Op.CHECKCAST, Op.INSTANCEOF ->
                JvmInsn(pc, op, a = u2(c, pc + 1)) to 3
            Op.INVOKEINTERFACE, Op.INVOKEDYNAMIC -> JvmInsn(pc, op, a = u2(c, pc + 1)) to 5
            Op.NEWARRAY -> JvmInsn(pc, op, a = u1(c, pc + 1)) to 2
            Op.MULTIANEWARRAY -> JvmInsn(pc, op, a = u2(c, pc + 1), b = u1(c, pc + 3)) to 4

            Op.WIDE -> wide(c, pc)

            Op.BREAKPOINT, Op.IMPDEP1, Op.IMPDEP2 ->
                throw error(pc, "зарезервированный опкод 0x${Integer.toHexString(op)} недопустим в class-файле")
            else -> {
                if (op > Op.IMPDEP2 || op in (Op.BREAKPOINT + 1) until Op.IMPDEP1) {
                    throw error(pc, "неизвестный опкод 0x${Integer.toHexString(op)}")
                }
                JvmInsn(pc, op) to 1
            }
        }
    }

    private fun wide(c: ByteArray, pc: Int): Pair<JvmInsn, Int> {
        val op = u1(c, pc + 1)
        return when (op) {
            Op.ILOAD, Op.LLOAD, Op.FLOAD, Op.DLOAD, Op.ALOAD,
            Op.ISTORE, Op.LSTORE, Op.FSTORE, Op.DSTORE, Op.ASTORE, Op.RET ->
                JvmInsn(pc, op, a = u2(c, pc + 2)) to 4
            Op.IINC -> JvmInsn(pc, op, a = u2(c, pc + 2), b = s2(c, pc + 4)) to 6
            else -> throw error(pc, "недопустимый опкод 0x${Integer.toHexString(op)} после wide")
        }
    }

    private fun tableSwitch(c: ByteArray, pc: Int): Pair<JvmInsn, Int> {
        val base = (pc + 4) and 3.inv()
        val default = pc + s4(c, base)
        val low = s4(c, base + 4)
        val high = s4(c, base + 8)
        if (high < low) throw error(pc, "tableswitch: high ($high) меньше low ($low)")
        val count = high.toLong() - low + 1
        if (base + 12 + count * 4 > c.size) throw error(pc, "tableswitch выходит за конец кода")
        val targets = IntArray(count.toInt()) { pc + s4(c, base + 12 + it * 4) }
        return JvmInsn(pc, Op.TABLESWITCH, a = default, b = low, targets = targets) to (base + 12 + targets.size * 4 - pc)
    }

    private fun lookupSwitch(c: ByteArray, pc: Int): Pair<JvmInsn, Int> {
        val base = (pc + 4) and 3.inv()
        val default = pc + s4(c, base)
        val npairs = s4(c, base + 4)
        if (npairs < 0) throw error(pc, "lookupswitch: отрицательное число пар ($npairs)")
        if (base + 8 + npairs.toLong() * 8 > c.size) throw error(pc, "lookupswitch выходит за конец кода")
        val keys = IntArray(npairs)
        val targets = IntArray(npairs)
        for (i in 0 until npairs) {
            keys[i] = s4(c, base + 8 + i * 8)
            targets[i] = pc + s4(c, base + 12 + i * 8)
            if (i > 0 && keys[i - 1] >= keys[i]) throw error(pc, "lookupswitch: ключи не возрастают (${keys[i - 1]}, ${keys[i]})")
        }
        return JvmInsn(pc, Op.LOOKUPSWITCH, a = default, keys = keys, targets = targets) to (base + 8 + npairs * 8 - pc)
    }

    private fun checkTargets(code: DecodedCode) {
        for (insn in code.insns) {
            when (insn.opcode) {
                in Op.IFEQ..Op.JSR, Op.IFNULL, Op.IFNONNULL -> checkTarget(code, insn, insn.a)
                Op.TABLESWITCH, Op.LOOKUPSWITCH -> {
                    checkTarget(code, insn, insn.a)
                    for (t in insn.targets!!) checkTarget(code, insn, t)
                }
            }
        }
    }

    private fun checkTarget(code: DecodedCode, insn: JvmInsn, target: Int) {
        if (code.indexOf(target) < 0) throw error(insn.pc, "переход на $target — это не начало инструкции")
    }

    private fun error(pc: Int, msg: String) = IllegalArgumentException("pc=$pc: $msg")

    private fun u1(c: ByteArray, p: Int): Int {
        if (p >= c.size) throw error(p, "инструкция выходит за конец кода")
        return c[p].toInt() and 0xFF
    }

    private fun s1(c: ByteArray, p: Int): Int = u1(c, p).toByte().toInt()
    private fun u2(c: ByteArray, p: Int): Int = (u1(c, p) shl 8) or u1(c, p + 1)
    private fun s2(c: ByteArray, p: Int): Int = u2(c, p).toShort().toInt()
    private fun s4(c: ByteArray, p: Int): Int = (u2(c, p) shl 16) or u2(c, p + 2)
}
