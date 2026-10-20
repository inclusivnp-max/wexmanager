package com.tim.casm.frames

import com.tim.clazz.model.opcodes.Opcodes

/**
 * Разобранный байткод метода (JVMS §6.5): границы инструкций и их операнды в параллельных массивах.
 *
 * Для `wide` в [op] лежит расширяемый опкод, а [wide] = true.
 * [a] — индекс локала / пула / значение bipush-sipush / абсолютная цель перехода;
 * [b] — константа iinc / count у invokeinterface / размерность multianewarray / atype у newarray.
 * У switch все цели (первая — default) — в [switchTargets].
 */
internal class Bytecode private constructor(
    val code: ByteArray,
    val count: Int,
    val pc: IntArray,
    val op: IntArray,
    val wide: BooleanArray,
    val a: IntArray,
    val b: IntArray,
    val switchTargets: Array<IntArray?>,
    /** Индекс инструкции по pc; -1 — pc не на границе инструкции. */
    private val indexByPc: IntArray
) {
    fun indexAt(pc: Int): Int = if (pc in indexByPc.indices) indexByPc[pc] else -1

    fun end(i: Int): Int = if (i + 1 < count) pc[i + 1] else code.size

    companion object {
        fun decode(code: ByteArray): Bytecode {
            val n = code.size
            var cap = maxOf(16, n / 2)
            var pcs = IntArray(cap)
            var ops = IntArray(cap)
            var wides = BooleanArray(cap)
            var aa = IntArray(cap)
            var bb = IntArray(cap)
            var sw = arrayOfNulls<IntArray>(cap)
            val byPc = IntArray(n + 1) { -1 }
            var count = 0

            fun u1(p: Int): Int {
                if (p >= n) throw error(p, "инструкция выходит за конец кода ($n байт)")
                return code[p].toInt() and 0xFF
            }
            fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
            fun s2(p: Int) = u2(p).toShort().toInt()
            fun s4(p: Int) = (u1(p) shl 24) or (u1(p + 1) shl 16) or (u1(p + 2) shl 8) or u1(p + 3)

            var p = 0
            while (p < n) {
                if (count == cap) {
                    cap *= 2
                    pcs = pcs.copyOf(cap); ops = ops.copyOf(cap); wides = wides.copyOf(cap)
                    aa = aa.copyOf(cap); bb = bb.copyOf(cap); sw = sw.copyOf(cap)
                }
                val start = p
                var op = u1(p)
                var a = 0
                var b = 0
                var isWide = false
                var targets: IntArray? = null
                val len: Int = when (op) {
                    Opcodes.BIPUSH -> { a = code[p + 1].toInt(); 2 }
                    Opcodes.SIPUSH -> { a = s2(p + 1); 3 }
                    Opcodes.LDC -> { a = u1(p + 1); 2 }
                    Opcodes.LDC_W, Opcodes.LDC2_W -> { a = u2(p + 1); 3 }
                    in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> { a = u1(p + 1); 2 }
                    in Opcodes.ILOAD_0..Opcodes.ALOAD_3 -> {
                        val k = op - Opcodes.ILOAD_0
                        op = Opcodes.ILOAD + k / 4; a = k % 4; 1
                    }
                    in Opcodes.ISTORE_0..Opcodes.ASTORE_3 -> {
                        val k = op - Opcodes.ISTORE_0
                        op = Opcodes.ISTORE + k / 4; a = k % 4; 1
                    }
                    Opcodes.IINC -> { a = u1(p + 1); b = code[p + 2].toInt(); 3 }
                    in Opcodes.IFEQ..Opcodes.JSR, Opcodes.IFNULL, Opcodes.IFNONNULL -> { a = start + s2(p + 1); 3 }
                    Opcodes.GOTO_W, Opcodes.JSR_W -> { a = start + s4(p + 1); 5 }
                    Opcodes.TABLESWITCH -> {
                        val base = (start + 4) and 3.inv()
                        val low = s4(base + 4)
                        val high = s4(base + 8)
                        if (high < low) throw error(start, "tableswitch: high ($high) < low ($low)")
                        // Размер — из входных данных: сначала сверить с длиной кода (long — без переполнения).
                        if (base + 12 + (high.toLong() - low + 1) * 4 > code.size) throw error(start, "tableswitch выходит за конец кода")
                        val k = high - low + 1
                        targets = IntArray(k + 1)
                        targets[0] = start + s4(base)
                        for (j in 0 until k) targets[j + 1] = start + s4(base + 12 + 4 * j)
                        a = low
                        base + 12 + 4 * k - start
                    }
                    Opcodes.LOOKUPSWITCH -> {
                        val base = (start + 4) and 3.inv()
                        val pairs = s4(base + 4)
                        if (pairs < 0) throw error(start, "lookupswitch: npairs ($pairs) < 0")
                        if (base + 8 + pairs.toLong() * 8 > code.size) throw error(start, "lookupswitch выходит за конец кода")
                        targets = IntArray(pairs + 1)
                        targets[0] = start + s4(base)
                        for (j in 0 until pairs) targets[j + 1] = start + s4(base + 12 + 8 * j)
                        base + 8 + 8 * pairs - start
                    }
                    in Opcodes.GETSTATIC..Opcodes.INVOKESTATIC, Opcodes.NEW, Opcodes.ANEWARRAY,
                    Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> { a = u2(p + 1); 3 }
                    Opcodes.INVOKEINTERFACE -> { a = u2(p + 1); b = u1(p + 3); 5 }
                    Opcodes.INVOKEDYNAMIC -> { a = u2(p + 1); 5 }
                    Opcodes.NEWARRAY -> { b = u1(p + 1); 2 }
                    Opcodes.MULTIANEWARRAY -> { a = u2(p + 1); b = u1(p + 3); 4 }
                    Opcodes.WIDE -> {
                        isWide = true
                        op = u1(p + 1)
                        a = u2(p + 2)
                        when (op) {
                            Opcodes.IINC -> { b = s2(p + 4); 6 }
                            in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> 4
                            else -> throw error(start, "wide с недопустимым опкодом 0x${Integer.toHexString(op)}")
                        }
                    }
                    else -> {
                        if (op > Opcodes.JSR_W && op != Opcodes.BREAKPOINT && op != Opcodes.IMPDEP1 && op != Opcodes.IMPDEP2) {
                            throw error(start, "неизвестный опкод 0x${Integer.toHexString(op)}")
                        }
                        1
                    }
                }
                if (start + len > n) throw error(start, "инструкция выходит за конец кода ($n байт)")
                pcs[count] = start; ops[count] = op; wides[count] = isWide
                aa[count] = a; bb[count] = b; sw[count] = targets
                byPc[start] = count
                count++
                p = start + len
            }
            return Bytecode(code, count, pcs, ops, wides, aa, bb, sw, byPc)
        }

        private fun error(pc: Int, msg: String) =
            IllegalArgumentException("Байткод, pc=$pc: $msg")
    }
}
