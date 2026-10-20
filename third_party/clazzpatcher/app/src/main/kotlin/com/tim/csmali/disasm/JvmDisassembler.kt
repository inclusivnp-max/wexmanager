package com.tim.csmali.disasm

import com.tim.clazz.model.opcodes.Opcodes
import com.tim.csmali.SmaliFormatter

/**
 * Дизассемблер байткода метода в инструкции csmali.
 *
 * Регистры — локалы JVM: `pN` — слот N из области параметров (у нестатического метода p0 — this),
 * `vN` — слот (слоты параметров + N). Короткие формы (`iload_1`) печатаются как есть,
 * `wide` с индексом, помещающимся в u1, — с префиксом `wide`, чтобы сборка дала те же байты.
 */
class JvmDisassembler(
    private val code: ByteArray,
    private val names: ClassNames,
    paramTypes: List<String>,
    isStatic: Boolean
) {
    /** Число слотов параметров (включая this). */
    val paramSlots: Int = (if (isStatic) 0 else 1) + paramTypes.size + paramTypes.count { SmaliFormatter.isWide(it) }

    class Result(
        val insns: List<DecodedInsn>,
        /** pc → имена меток, которые нужно поставить перед инструкцией с этим pc. */
        val labels: Map<Int, List<String>>
    )

    class DecodedInsn(val pc: Int, val opcode: Int, val text: String)

    private val labelsByPc = HashMap<Int, MutableList<String>>()
    private var nextPc = 0

    fun decode(): Result {
        val insns = ArrayList<DecodedInsn>(code.size / 2 + 1)
        var pc = 0
        while (pc < code.size) {
            val op = u1(pc)
            val text = try {
                decodeInsn(op, pc)
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalStateException("Инструкция 0x${Integer.toHexString(op)} по адресу $pc выходит за конец кода")
            }
            insns.add(DecodedInsn(pc, op, text))
            pc = nextPc
        }
        return Result(insns, labelsByPc)
    }

    fun regName(slot: Int): String = if (slot < paramSlots) "p$slot" else "v${slot - paramSlots}"

    private fun labelAt(pc: Int, prefix: String): String {
        if (pc < 0 || pc > code.size) throw IllegalStateException("Переход на адрес $pc вне кода (${code.size} байт)")
        val name = ":$prefix${Integer.toHexString(pc)}"
        val list = labelsByPc.getOrPut(pc) { ArrayList(2) }
        if (name !in list) list.add(name)
        return name
    }

    private fun name(op: Int): String = MNEMONICS[op]

    private fun simple(pc: Int, op: Int): String { nextPc = pc + 1; return name(op) }

    private fun decodeInsn(op: Int, pc: Int): String = when (op) {
        Opcodes.BIPUSH -> { nextPc = pc + 2; "bipush ${literal(code[pc + 1].toLong())}" }
        Opcodes.SIPUSH -> { nextPc = pc + 3; "sipush ${literal(s2(pc + 1).toLong())}" }
        Opcodes.LDC -> { nextPc = pc + 2; "ldc ${names.constantText(u1(pc + 1))}" }
        Opcodes.LDC_W -> { nextPc = pc + 3; "ldc_w ${names.constantText(u2(pc + 1))}" }
        Opcodes.LDC2_W -> { nextPc = pc + 3; "ldc2_w ${names.constantText(u2(pc + 1))}" }

        in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> {
            nextPc = pc + 2
            "${name(op)} ${regName(u1(pc + 1))}"
        }
        Opcodes.IINC -> { nextPc = pc + 3; "iinc ${regName(u1(pc + 1))}, ${code[pc + 2].toInt()}" }

        in Opcodes.IFEQ..Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL ->
            { nextPc = pc + 3; "${name(op)} ${labelAt(pc + s2(pc + 1), "cond_")}" }
        Opcodes.GOTO, Opcodes.JSR -> { nextPc = pc + 3; "${name(op)} ${labelAt(pc + s2(pc + 1), "goto_")}" }
        Opcodes.GOTO_W, Opcodes.JSR_W -> { nextPc = pc + 5; "${name(op)} ${labelAt(pc + s4(pc + 1), "goto_")}" }
        Opcodes.TABLESWITCH -> tableSwitch(pc)
        Opcodes.LOOKUPSWITCH -> lookupSwitch(pc)

        Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD ->
            { nextPc = pc + 3; "${name(op)} ${names.fieldRef(u2(pc + 1))}" }
        Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC ->
            { nextPc = pc + 3; "${name(op)} ${names.methodRef(u2(pc + 1))}" }
        Opcodes.INVOKEINTERFACE -> {
            nextPc = pc + 5
            "invokeinterface ${names.methodRef(u2(pc + 1), implicitInterface = true)}, ${u1(pc + 3)}"
        }
        Opcodes.INVOKEDYNAMIC -> { nextPc = pc + 5; "invokedynamic ${names.invokeDynamicRef(u2(pc + 1))}" }

        Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF ->
            { nextPc = pc + 3; "${name(op)} ${names.typeText(u2(pc + 1))}" }
        Opcodes.NEWARRAY -> { nextPc = pc + 2; "newarray ${arrayTypeName(u1(pc + 1))}" }
        Opcodes.MULTIANEWARRAY -> { nextPc = pc + 4; "multianewarray ${names.typeText(u2(pc + 1))}, ${u1(pc + 3)}" }
        Opcodes.WIDE -> wide(pc)

        else -> {
            if (op > Opcodes.JSR_W && op != Opcodes.BREAKPOINT && op != Opcodes.IMPDEP1 && op != Opcodes.IMPDEP2) {
                throw IllegalStateException("Неизвестный опкод 0x${Integer.toHexString(op)} по адресу $pc")
            }
            simple(pc, op)
        }
    }

    private fun wide(pc: Int): String {
        val op = u1(pc + 1)
        val slot = u2(pc + 2)
        return when (op) {
            Opcodes.IINC -> {
                nextPc = pc + 6
                val c = s2(pc + 4)
                val prefix = if (slot <= 0xFF && c in Byte.MIN_VALUE..Byte.MAX_VALUE) "wide " else ""
                "${prefix}iinc ${regName(slot)}, $c"
            }
            in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> {
                nextPc = pc + 4
                "${if (slot <= 0xFF) "wide " else ""}${name(op)} ${regName(slot)}"
            }
            else -> throw IllegalStateException("wide с недопустимым опкодом 0x${Integer.toHexString(op)} по адресу $pc")
        }
    }

    /** `tableswitch low default :d` + цели построчно + `.end tableswitch`. */
    private fun tableSwitch(pc: Int): String {
        val base = (pc + 4) and 3.inv()
        val low = s4(base + 4)
        val high = s4(base + 8)
        if (high < low) throw IllegalStateException("tableswitch: high ($high) < low ($low) по адресу $pc")
        if (base + 12 + (high.toLong() - low + 1) * 4 > code.size) throw IllegalStateException("tableswitch по адресу $pc выходит за конец кода")
        val count = high - low + 1
        nextPc = base + 12 + count * 4
        val sb = StringBuilder(32 + count * 16)
        sb.append("tableswitch ").append(literal(low.toLong()))
            .append(" default ").append(labelAt(pc + s4(base), "sswitch_"))
        for (i in 0 until count) sb.append('\n').append(labelAt(pc + s4(base + 12 + i * 4), "pswitch_"))
        return sb.append("\n.end tableswitch").toString()
    }

    /** `lookupswitch default :d` + строки `ключ -> :цель` + `.end lookupswitch`. */
    private fun lookupSwitch(pc: Int): String {
        val base = (pc + 4) and 3.inv()
        val pairs = s4(base + 4)
        if (pairs < 0) throw IllegalStateException("lookupswitch: npairs ($pairs) < 0 по адресу $pc")
        if (base + 8 + pairs.toLong() * 8 > code.size) throw IllegalStateException("lookupswitch по адресу $pc выходит за конец кода")
        nextPc = base + 8 + pairs * 8
        val sb = StringBuilder(32 + pairs * 24)
        sb.append("lookupswitch default ").append(labelAt(pc + s4(base), "sswitch_"))
        for (i in 0 until pairs) {
            sb.append('\n').append(literal(s4(base + 8 + i * 8).toLong()))
                .append(" -> ").append(labelAt(pc + s4(base + 12 + i * 8), "sswitch_"))
        }
        return sb.append("\n.end lookupswitch").toString()
    }

    private fun literal(v: Long): String = SmaliFormatter.appendLiteral(StringBuilder(12), v).toString()

    private fun u1(p: Int): Int = code[p].toInt() and 0xFF
    private fun u2(p: Int): Int = (u1(p) shl 8) or u1(p + 1)
    private fun s2(p: Int): Int = u2(p).toShort().toInt()
    private fun s4(p: Int): Int = (u1(p) shl 24) or (u1(p + 1) shl 16) or (u1(p + 2) shl 8) or u1(p + 3)

    private companion object {
        /** Мнемоники по опкоду (строчными), чтобы не приводить регистр на каждой инструкции. */
        val MNEMONICS = Array(256) { Opcodes.getOpcodeName(it).lowercase() }
    }

    private fun arrayTypeName(atype: Int): String = when (atype) {
        4 -> "T_BOOLEAN"
        5 -> "T_CHAR"
        6 -> "T_FLOAT"
        7 -> "T_DOUBLE"
        8 -> "T_BYTE"
        9 -> "T_SHORT"
        10 -> "T_INT"
        11 -> "T_LONG"
        else -> throw IllegalStateException("newarray: неизвестный atype $atype")
    }
}
