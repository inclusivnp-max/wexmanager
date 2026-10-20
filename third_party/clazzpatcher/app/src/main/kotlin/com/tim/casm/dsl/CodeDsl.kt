package com.tim.casm.dsl

import com.tim.casm.model.JvmType
import com.tim.clazz.model.opcodes.Opcodes

/**
 * Построитель байткода метода. Переходы и switch ссылаются на метки и разрешаются в
 * [com.tim.casm.assembler.LabelResolver]: `goto`/`jsr` при необходимости расширяются до
 * `goto_w`/`jsr_w`, а дальний условный переход — в инвертированное условие + `goto_w`.
 * Индексы локалов > 255 и большие инкременты кодируются через `wide`.
 */
class CodeDsl {

    internal sealed class Insn

    internal class Raw(val bytes: ByteArray) : Insn()

    internal class Branch(val opcode: Int, val label: String) : Insn()

    internal class Switch(
        val low: Int,
        val high: Int,
        /** `null` — tableswitch, иначе ключи lookupswitch (строго по возрастанию). */
        val keys: IntArray?,
        val defaultLabel: String,
        val targetLabels: List<String>
    ) : Insn()

    private val list = ArrayList<Insn>()
    internal val instructions: List<Insn> get() = list

    /** Метка → индекс инструкции, перед которой она стоит (size — конец кода). */
    internal val labels = HashMap<String, Int>()

    val isEmpty: Boolean get() = list.isEmpty()

    /** Число добавленных инструкций — позиция для [labelAt]. */
    val size: Int get() = list.size

    private fun add(i: Insn) { list.add(i) }

    /**
     * Снимает последнюю инструкцию, если она побайтно равна [expected] и за ней нет меток
     * (иначе переход или граница диапазона указывали бы на неё). Для удаления пары store/load.
     */
    internal fun dropLastIf(expected: ByteArray): Boolean {
        val last = list.lastOrNull() as? Raw ?: return false
        if (!last.bytes.contentEquals(expected)) return false
        if (labels.values.any { it == list.size }) return false
        list.removeAt(list.size - 1)
        return true
    }

    /** Байты единственной инструкции (для [dropLastIf]). */
    internal fun singleRawBytes(): ByteArray = (list.single() as Raw).bytes
    private fun raw(vararg bytes: Int) = add(Raw(ByteArray(bytes.size) { bytes[it].toByte() }))

    fun label(name: String) {
        require(name.isNotEmpty()) { "Пустое имя метки" }
        require(labels.put(name, list.size) == null) { "Метка $name объявлена повторно" }
    }

    /** Метка перед инструкцией с номером [position] (≤ [size]) — для меток, нужда в которых выяснилась позже. */
    fun labelAt(name: String, position: Int) {
        require(name.isNotEmpty()) { "Пустое имя метки" }
        require(position in 0..list.size) { "Позиция метки $name вне кода: $position" }
        require(labels.put(name, position) == null) { "Метка $name объявлена повторно" }
    }

    private fun branch(opcode: Int, label: String) {
        require(label.isNotEmpty()) { "Пустая метка перехода" }
        add(Branch(opcode, label))
    }

    // ---- Константы ----

    fun nop() = raw(Opcodes.NOP)
    fun aconstNull() = raw(Opcodes.ACONST_NULL)

    fun iconst(v: Int) {
        require(v in -1..5) { "iconst: $v вне -1..5" }
        raw(Opcodes.ICONST_0 + v)
    }

    fun lconst(v: Long) {
        require(v == 0L || v == 1L) { "lconst: $v вне 0..1" }
        raw(Opcodes.LCONST_0 + v.toInt())
    }

    fun fconst(v: Float) {
        require(v == 0f && 1 / v > 0 || v == 1f || v == 2f) { "fconst: $v вне {0, 1, 2}" }
        raw(Opcodes.FCONST_0 + v.toInt())
    }

    fun dconst(v: Double) {
        require(v == 0.0 && 1 / v > 0 || v == 1.0) { "dconst: $v вне {0, 1}" }
        raw(Opcodes.DCONST_0 + v.toInt())
    }

    fun bipush(v: Int) {
        require(v in Byte.MIN_VALUE..Byte.MAX_VALUE) { "bipush: $v вне -128..127" }
        raw(Opcodes.BIPUSH, v and 0xFF)
    }

    fun sipush(v: Int) {
        require(v in Short.MIN_VALUE..Short.MAX_VALUE) { "sipush: $v вне -32768..32767" }
        raw(Opcodes.SIPUSH, (v ushr 8) and 0xFF, v and 0xFF)
    }

    /** Наиболее короткая загрузка int-константы. */
    fun pushInt(v: Int) = when (v) {
        in -1..5 -> iconst(v)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> bipush(v)
        in Short.MIN_VALUE..Short.MAX_VALUE -> sipush(v)
        else -> throw IllegalArgumentException("pushInt: $v не помещается в sipush — используйте ldc")
    }

    /** `ldc`, если индекс помещается в u1, иначе `ldc_w`. */
    fun ldc(idx: Int) = if (idx in 1..0xFF) raw(Opcodes.LDC, idx) else ldcW(idx)

    /** Строго `ldc` (u1-индекс). */
    fun ldcShort(idx: Int) {
        require(idx in 1..0xFF) { "ldc: индекс #$idx не помещается в u1 — используйте ldc_w" }
        raw(Opcodes.LDC, idx)
    }

    fun ldcW(idx: Int) = raw(Opcodes.LDC_W, u2hi(idx), idx and 0xFF)
    fun ldc2W(idx: Int) = raw(Opcodes.LDC2_W, u2hi(idx), idx and 0xFF)

    // ---- Локалы ----

    fun iload(v: Int) = local(Opcodes.ILOAD, Opcodes.ILOAD_0, v)
    fun lload(v: Int) = local(Opcodes.LLOAD, Opcodes.LLOAD_0, v)
    fun fload(v: Int) = local(Opcodes.FLOAD, Opcodes.FLOAD_0, v)
    fun dload(v: Int) = local(Opcodes.DLOAD, Opcodes.DLOAD_0, v)
    fun aload(v: Int) = local(Opcodes.ALOAD, Opcodes.ALOAD_0, v)

    fun istore(v: Int) = local(Opcodes.ISTORE, Opcodes.ISTORE_0, v)
    fun lstore(v: Int) = local(Opcodes.LSTORE, Opcodes.LSTORE_0, v)
    fun fstore(v: Int) = local(Opcodes.FSTORE, Opcodes.FSTORE_0, v)
    fun dstore(v: Int) = local(Opcodes.DSTORE, Opcodes.DSTORE_0, v)
    fun astore(v: Int) = local(Opcodes.ASTORE, Opcodes.ASTORE_0, v)

    /** Явная длинная форма (`iload 2`, а не `iload_2`) — для побайтового round-trip. */
    fun localLong(opGeneric: Int, v: Int) = when {
        v in 0..0xFF -> raw(opGeneric, v)
        v in 0..0xFFFF -> raw(Opcodes.WIDE, opGeneric, u2hi(v), v and 0xFF)
        else -> throw IllegalArgumentException("Номер локала $v вне 0..65535")
    }

    /** Форма `wide` даже для малых индексов — для побайтового round-trip. */
    fun localWide(opGeneric: Int, v: Int) {
        require(v in 0..0xFFFF) { "Номер локала $v вне 0..65535" }
        raw(Opcodes.WIDE, opGeneric, u2hi(v), v and 0xFF)
    }

    private fun local(opGeneric: Int, op0: Int, v: Int) {
        if (v in 0..3) raw(op0 + v) else localLong(opGeneric, v)
    }

    fun iload0() = raw(Opcodes.ILOAD_0); fun iload1() = raw(Opcodes.ILOAD_1)
    fun iload2() = raw(Opcodes.ILOAD_2); fun iload3() = raw(Opcodes.ILOAD_3)
    fun lload0() = raw(Opcodes.LLOAD_0); fun lload1() = raw(Opcodes.LLOAD_1)
    fun lload2() = raw(Opcodes.LLOAD_2); fun lload3() = raw(Opcodes.LLOAD_3)
    fun fload0() = raw(Opcodes.FLOAD_0); fun fload1() = raw(Opcodes.FLOAD_1)
    fun fload2() = raw(Opcodes.FLOAD_2); fun fload3() = raw(Opcodes.FLOAD_3)
    fun dload0() = raw(Opcodes.DLOAD_0); fun dload1() = raw(Opcodes.DLOAD_1)
    fun dload2() = raw(Opcodes.DLOAD_2); fun dload3() = raw(Opcodes.DLOAD_3)
    fun aload0() = raw(Opcodes.ALOAD_0); fun aload1() = raw(Opcodes.ALOAD_1)
    fun aload2() = raw(Opcodes.ALOAD_2); fun aload3() = raw(Opcodes.ALOAD_3)

    fun istore0() = raw(Opcodes.ISTORE_0); fun istore1() = raw(Opcodes.ISTORE_1)
    fun istore2() = raw(Opcodes.ISTORE_2); fun istore3() = raw(Opcodes.ISTORE_3)
    fun lstore0() = raw(Opcodes.LSTORE_0); fun lstore1() = raw(Opcodes.LSTORE_1)
    fun lstore2() = raw(Opcodes.LSTORE_2); fun lstore3() = raw(Opcodes.LSTORE_3)
    fun fstore0() = raw(Opcodes.FSTORE_0); fun fstore1() = raw(Opcodes.FSTORE_1)
    fun fstore2() = raw(Opcodes.FSTORE_2); fun fstore3() = raw(Opcodes.FSTORE_3)
    fun dstore0() = raw(Opcodes.DSTORE_0); fun dstore1() = raw(Opcodes.DSTORE_1)
    fun dstore2() = raw(Opcodes.DSTORE_2); fun dstore3() = raw(Opcodes.DSTORE_3)
    fun astore0() = raw(Opcodes.ASTORE_0); fun astore1() = raw(Opcodes.ASTORE_1)
    fun astore2() = raw(Opcodes.ASTORE_2); fun astore3() = raw(Opcodes.ASTORE_3)

    fun iinc(v: Int, c: Int) {
        if (v in 0..0xFF && c in Byte.MIN_VALUE..Byte.MAX_VALUE) raw(Opcodes.IINC, v, c and 0xFF)
        else iincWide(v, c)
    }

    fun iincWide(v: Int, c: Int) {
        require(v in 0..0xFFFF) { "iinc: номер локала $v вне 0..65535" }
        require(c in Short.MIN_VALUE..Short.MAX_VALUE) { "iinc: приращение $c вне -32768..32767" }
        raw(Opcodes.WIDE, Opcodes.IINC, u2hi(v), v and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    fun ret(v: Int) = localLong(Opcodes.RET, v)

    // ---- Массивы, стек, арифметика ----

    fun iaload() = raw(Opcodes.IALOAD); fun laload() = raw(Opcodes.LALOAD)
    fun faload() = raw(Opcodes.FALOAD); fun daload() = raw(Opcodes.DALOAD)
    fun aaload() = raw(Opcodes.AALOAD); fun baload() = raw(Opcodes.BALOAD)
    fun caload() = raw(Opcodes.CALOAD); fun saload() = raw(Opcodes.SALOAD)
    fun iastore() = raw(Opcodes.IASTORE); fun lastore() = raw(Opcodes.LASTORE)
    fun fastore() = raw(Opcodes.FASTORE); fun dastore() = raw(Opcodes.DASTORE)
    fun aastore() = raw(Opcodes.AASTORE); fun bastore() = raw(Opcodes.BASTORE)
    fun castore() = raw(Opcodes.CASTORE); fun sastore() = raw(Opcodes.SASTORE)

    fun pop() = raw(Opcodes.POP); fun pop2() = raw(Opcodes.POP2)
    fun dup() = raw(Opcodes.DUP); fun dupX1() = raw(Opcodes.DUP_X1); fun dupX2() = raw(Opcodes.DUP_X2)
    fun dup2() = raw(Opcodes.DUP2); fun dup2X1() = raw(Opcodes.DUP2_X1); fun dup2X2() = raw(Opcodes.DUP2_X2)
    fun swap() = raw(Opcodes.SWAP)

    fun iadd() = raw(Opcodes.IADD); fun ladd() = raw(Opcodes.LADD)
    fun fadd() = raw(Opcodes.FADD); fun dadd() = raw(Opcodes.DADD)
    fun isub() = raw(Opcodes.ISUB); fun lsub() = raw(Opcodes.LSUB)
    fun fsub() = raw(Opcodes.FSUB); fun dsub() = raw(Opcodes.DSUB)
    fun imul() = raw(Opcodes.IMUL); fun lmul() = raw(Opcodes.LMUL)
    fun fmul() = raw(Opcodes.FMUL); fun dmul() = raw(Opcodes.DMUL)
    fun idiv() = raw(Opcodes.IDIV); fun ldiv() = raw(Opcodes.LDIV)
    fun fdiv() = raw(Opcodes.FDIV); fun ddiv() = raw(Opcodes.DDIV)
    fun irem() = raw(Opcodes.IREM); fun lrem() = raw(Opcodes.LREM)
    fun frem() = raw(Opcodes.FREM); fun drem() = raw(Opcodes.DREM)
    fun ineg() = raw(Opcodes.INEG); fun lneg() = raw(Opcodes.LNEG)
    fun fneg() = raw(Opcodes.FNEG); fun dneg() = raw(Opcodes.DNEG)
    fun ishl() = raw(Opcodes.ISHL); fun lshl() = raw(Opcodes.LSHL)
    fun ishr() = raw(Opcodes.ISHR); fun lshr() = raw(Opcodes.LSHR)
    fun iushr() = raw(Opcodes.IUSHR); fun lushr() = raw(Opcodes.LUSHR)
    fun iand() = raw(Opcodes.IAND); fun land() = raw(Opcodes.LAND)
    fun ior() = raw(Opcodes.IOR); fun lor() = raw(Opcodes.LOR)
    fun ixor() = raw(Opcodes.IXOR); fun lxor() = raw(Opcodes.LXOR)

    fun i2l() = raw(Opcodes.I2L); fun i2f() = raw(Opcodes.I2F); fun i2d() = raw(Opcodes.I2D)
    fun l2i() = raw(Opcodes.L2I); fun l2f() = raw(Opcodes.L2F); fun l2d() = raw(Opcodes.L2D)
    fun f2i() = raw(Opcodes.F2I); fun f2l() = raw(Opcodes.F2L); fun f2d() = raw(Opcodes.F2D)
    fun d2i() = raw(Opcodes.D2I); fun d2l() = raw(Opcodes.D2L); fun d2f() = raw(Opcodes.D2F)
    fun i2b() = raw(Opcodes.I2B); fun i2c() = raw(Opcodes.I2C); fun i2s() = raw(Opcodes.I2S)

    fun lcmp() = raw(Opcodes.LCMP); fun fcmpl() = raw(Opcodes.FCMPL); fun fcmpg() = raw(Opcodes.FCMPG)
    fun dcmpl() = raw(Opcodes.DCMPL); fun dcmpg() = raw(Opcodes.DCMPG)

    // ---- Переходы ----

    fun ifeq(label: String) = branch(Opcodes.IFEQ, label)
    fun ifne(label: String) = branch(Opcodes.IFNE, label)
    fun iflt(label: String) = branch(Opcodes.IFLT, label)
    fun ifge(label: String) = branch(Opcodes.IFGE, label)
    fun ifgt(label: String) = branch(Opcodes.IFGT, label)
    fun ifle(label: String) = branch(Opcodes.IFLE, label)
    fun ifIcmpeq(label: String) = branch(Opcodes.IF_ICMPEQ, label)
    fun ifIcmpne(label: String) = branch(Opcodes.IF_ICMPNE, label)
    fun ifIcmplt(label: String) = branch(Opcodes.IF_ICMPLT, label)
    fun ifIcmpge(label: String) = branch(Opcodes.IF_ICMPGE, label)
    fun ifIcmpgt(label: String) = branch(Opcodes.IF_ICMPGT, label)
    fun ifIcmple(label: String) = branch(Opcodes.IF_ICMPLE, label)
    fun ifAcmpeq(label: String) = branch(Opcodes.IF_ACMPEQ, label)
    fun ifAcmpne(label: String) = branch(Opcodes.IF_ACMPNE, label)
    fun ifnull(label: String) = branch(Opcodes.IFNULL, label)
    fun ifnonnull(label: String) = branch(Opcodes.IFNONNULL, label)
    fun goto(label: String) = branch(Opcodes.GOTO, label)
    fun gotoW(label: String) = branch(Opcodes.GOTO_W, label)
    fun jsr(label: String) = branch(Opcodes.JSR, label)
    fun jsrW(label: String) = branch(Opcodes.JSR_W, label)

    /** Любой опкод перехода на метку (if*, goto*, jsr*). */
    fun jump(opcode: Int, label: String) {
        require(isBranchOpcode(opcode)) { "Опкод 0x${Integer.toHexString(opcode)} не является переходом" }
        branch(opcode, label)
    }

    fun tableswitch(low: Int, high: Int, defaultLabel: String, targets: List<String>) {
        require(high >= low) { "tableswitch: high ($high) < low ($low)" }
        require(targets.size.toLong() == high.toLong() - low + 1) { "tableswitch: ${targets.size} меток для $low..$high" }
        add(Switch(low, high, null, defaultLabel, targets.toList()))
    }

    fun lookupswitch(keys: IntArray, defaultLabel: String, targets: List<String>) {
        require(keys.size == targets.size) { "lookupswitch: ${keys.size} ключей, ${targets.size} меток" }
        for (i in 1 until keys.size) require(keys[i - 1] < keys[i]) { "lookupswitch: ключи должны строго возрастать" }
        add(Switch(0, 0, keys.copyOf(), defaultLabel, targets.toList()))
    }

    // ---- Возврат ----

    fun ireturn() = raw(Opcodes.IRETURN); fun lreturn() = raw(Opcodes.LRETURN)
    fun freturn() = raw(Opcodes.FRETURN); fun dreturn() = raw(Opcodes.DRETURN)
    fun areturn() = raw(Opcodes.ARETURN); fun returnVoid() = raw(Opcodes.RETURN)

    // ---- Поля, вызовы, объекты ----

    fun getstatic(idx: Int) = raw(Opcodes.GETSTATIC, u2hi(idx), idx and 0xFF)
    fun putstatic(idx: Int) = raw(Opcodes.PUTSTATIC, u2hi(idx), idx and 0xFF)
    fun getfield(idx: Int) = raw(Opcodes.GETFIELD, u2hi(idx), idx and 0xFF)
    fun putfield(idx: Int) = raw(Opcodes.PUTFIELD, u2hi(idx), idx and 0xFF)

    fun invokevirtual(idx: Int) = raw(Opcodes.INVOKEVIRTUAL, u2hi(idx), idx and 0xFF)
    fun invokespecial(idx: Int) = raw(Opcodes.INVOKESPECIAL, u2hi(idx), idx and 0xFF)
    fun invokestatic(idx: Int) = raw(Opcodes.INVOKESTATIC, u2hi(idx), idx and 0xFF)

    fun invokeinterface(idx: Int, count: Int) {
        require(count in 1..0xFF) { "invokeinterface: count $count вне 1..255" }
        raw(Opcodes.INVOKEINTERFACE, u2hi(idx), idx and 0xFF, count, 0)
    }

    /** count вычисляется по дескриптору: 1 (получатель) + слоты параметров. */
    fun invokeinterface(idx: Int, descriptor: String) {
        val (params, _) = JvmType.parseMethodDescriptor(descriptor)
        invokeinterface(idx, 1 + JvmType.paramsSlotSize(params))
    }

    fun invokedynamic(idx: Int) = raw(Opcodes.INVOKEDYNAMIC, u2hi(idx), idx and 0xFF, 0, 0)

    fun new(idx: Int) = raw(Opcodes.NEW, u2hi(idx), idx and 0xFF)

    fun newarray(atype: Int) {
        require(atype in 4..11) { "newarray: atype $atype вне 4..11" }
        raw(Opcodes.NEWARRAY, atype)
    }

    fun anewarray(idx: Int) = raw(Opcodes.ANEWARRAY, u2hi(idx), idx and 0xFF)
    fun arraylength() = raw(Opcodes.ARRAYLENGTH)
    fun athrow() = raw(Opcodes.ATHROW)
    fun checkcast(idx: Int) = raw(Opcodes.CHECKCAST, u2hi(idx), idx and 0xFF)
    fun instanceof(idx: Int) = raw(Opcodes.INSTANCEOF, u2hi(idx), idx and 0xFF)
    fun monitorenter() = raw(Opcodes.MONITORENTER)
    fun monitorexit() = raw(Opcodes.MONITOREXIT)

    fun multianewarray(idx: Int, dims: Int) {
        require(dims in 1..0xFF) { "multianewarray: размерность $dims вне 1..255" }
        raw(Opcodes.MULTIANEWARRAY, u2hi(idx), idx and 0xFF, dims)
    }

    /** Инструкция без операндов по опкоду (nop, iadd, iload_1, areturn, …). */
    fun opcode(op: Int) {
        require(isNoOperand(op)) { "Опкод 0x${Integer.toHexString(op)} требует операндов" }
        raw(op)
    }

    fun breakpoint() = raw(Opcodes.BREAKPOINT)
    fun impdep1() = raw(Opcodes.IMPDEP1)
    fun impdep2() = raw(Opcodes.IMPDEP2)

    private fun u2hi(v: Int): Int {
        require(v in 0..0xFFFF) { "Индекс $v не помещается в u2" }
        return (v ushr 8) and 0xFF
    }

    internal companion object {
        fun isNoOperand(op: Int): Boolean =
            op in Opcodes.NOP..Opcodes.DCONST_1 || op in Opcodes.ILOAD_0..Opcodes.SALOAD ||
                op in Opcodes.ISTORE_0..Opcodes.LXOR || op in Opcodes.I2L..Opcodes.DCMPG ||
                op in Opcodes.IRETURN..Opcodes.RETURN || op == Opcodes.ARRAYLENGTH || op == Opcodes.ATHROW ||
                op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT ||
                op == Opcodes.BREAKPOINT || op == Opcodes.IMPDEP1 || op == Opcodes.IMPDEP2

        fun isBranchOpcode(op: Int): Boolean =
            op in Opcodes.IFEQ..Opcodes.JSR || op in Opcodes.IFNULL..Opcodes.JSR_W

        fun isConditional(op: Int): Boolean =
            op in Opcodes.IFEQ..Opcodes.IF_ACMPNE || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL

        /** Условие с противоположным смыслом (для расширения дальнего перехода). */
        fun inverted(op: Int): Int =
            if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) op xor 1
            else ((op - Opcodes.IFEQ) xor 1) + Opcodes.IFEQ
    }
}
