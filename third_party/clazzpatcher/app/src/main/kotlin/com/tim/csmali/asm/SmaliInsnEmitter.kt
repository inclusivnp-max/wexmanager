package com.tim.csmali.asm

import com.tim.casm.dsl.CodeDsl
import com.tim.clazz.model.opcodes.Opcodes

/**
 * Одна инструкция csmali → [CodeDsl]. Регистры `pN`/`vN` — слоты JVM (см. [SmaliScanner.register]).
 * Длинные формы (`iload p1`) собираются как есть, короткие (`iload_1`) — как короткие,
 * так что дизассемблированный код собирается в те же байты.
 */
class SmaliInsnEmitter(
    private val refs: SmaliRefs,
    private val code: CodeDsl,
    /** Число слотов параметров (включая this). */
    private val paramSlots: Int
) {
    fun emit(text: String) {
        if (text.startsWith(".")) throw IllegalArgumentException("Директива вместо инструкции: $text")
        val s = SmaliScanner(text)
        var mnemonic = s.simpleName()
        val wide = mnemonic == "wide"
        if (wide) mnemonic = s.simpleName()
        val op = opcodeByName(mnemonic) ?: throw IllegalArgumentException("Неизвестная инструкция $mnemonic")
        if (wide) emitWide(op, mnemonic, s) else emitInstruction(op, mnemonic, s)
        s.expectEnd()
    }

    private fun reg(s: SmaliScanner): Int = s.register(paramSlots)

    private fun emitWide(op: Int, mnemonic: String, s: SmaliScanner) {
        when (op) {
            Opcodes.IINC -> {
                val r = reg(s); s.expect(',')
                code.iincWide(r, SmaliLiterals.parseInt(s.word()))
            }
            in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> code.localWide(op, reg(s))
            else -> throw IllegalArgumentException("wide неприменим к $mnemonic")
        }
    }

    private fun emitInstruction(op: Int, mnemonic: String, s: SmaliScanner) {
        when (op) {
            Opcodes.BIPUSH -> code.bipush(SmaliLiterals.parseInt(s.word()))
            Opcodes.SIPUSH -> code.sipush(SmaliLiterals.parseInt(s.word()))
            Opcodes.LDC -> code.ldc(refs.constant(s, allowLong = false))
            Opcodes.LDC_W -> code.ldcW(refs.constant(s, allowLong = false))
            Opcodes.LDC2_W -> code.ldc2W(refs.constant(s, allowLong = true))

            in Opcodes.ILOAD..Opcodes.ALOAD, in Opcodes.ISTORE..Opcodes.ASTORE, Opcodes.RET -> code.localLong(op, reg(s))
            Opcodes.IINC -> {
                val r = reg(s); s.expect(',')
                code.iinc(r, SmaliLiterals.parseInt(s.word()))
            }

            in Opcodes.IFEQ..Opcodes.JSR, Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.GOTO_W, Opcodes.JSR_W ->
                code.jump(op, s.label())
            Opcodes.TABLESWITCH -> tableSwitch(s)
            Opcodes.LOOKUPSWITCH -> lookupSwitch(s)

            Opcodes.GETSTATIC -> code.getstatic(refs.fieldRef(s))
            Opcodes.PUTSTATIC -> code.putstatic(refs.fieldRef(s))
            Opcodes.GETFIELD -> code.getfield(refs.fieldRef(s))
            Opcodes.PUTFIELD -> code.putfield(refs.fieldRef(s))
            Opcodes.INVOKEVIRTUAL -> code.invokevirtual(refs.methodRef(s).index)
            Opcodes.INVOKESPECIAL -> code.invokespecial(refs.methodRef(s).index)
            Opcodes.INVOKESTATIC -> code.invokestatic(refs.methodRef(s).index)
            Opcodes.INVOKEINTERFACE -> {
                val m = refs.methodRef(s, forceInterface = true)
                // Необязательный `, count`: без него считается по дескриптору.
                if (s.tryConsume(',')) code.invokeinterface(m.index, SmaliLiterals.parseInt(s.word()))
                else code.invokeinterface(m.index, m.descriptor)
            }
            Opcodes.INVOKEDYNAMIC -> code.invokedynamic(refs.invokeDynamic(s))

            Opcodes.NEW -> code.new(refs.typeRef(s))
            Opcodes.ANEWARRAY -> code.anewarray(refs.typeRef(s))
            Opcodes.CHECKCAST -> code.checkcast(refs.typeRef(s))
            Opcodes.INSTANCEOF -> code.instanceof(refs.typeRef(s))
            Opcodes.NEWARRAY -> code.newarray(arrayTypeCode(s.word()))
            Opcodes.MULTIANEWARRAY -> {
                val t = refs.typeRef(s); s.expect(',')
                code.multianewarray(t, SmaliLiterals.parseInt(s.word()))
            }
            Opcodes.WIDE -> throw IllegalArgumentException("wide без инструкции")

            else -> simple(op, mnemonic)
        }
    }

    /** Инструкции без операндов (включая короткие формы xload_N/xstore_N). */
    private fun simple(op: Int, mnemonic: String) {
        if (!CodeDsl.isNoOperand(op)) throw IllegalArgumentException("Инструкция $mnemonic не поддерживается")
        code.opcode(op)
    }

    /** `tableswitch low default :d` + цели по порядку ключей + `.end tableswitch`. */
    private fun tableSwitch(s: SmaliScanner) {
        val low = SmaliLiterals.parseInt(s.word())
        if (!s.tryConsume("default")) s.error("tableswitch: ожидалось default :метка")
        val default = s.label()
        val targets = ArrayList<String>()
        while (!s.tryConsume(".end tableswitch")) {
            if (s.atEnd()) s.error("tableswitch без .end tableswitch")
            targets.add(s.label())
        }
        if (targets.isEmpty()) s.error("tableswitch без целей")
        val high = low.toLong() + targets.size - 1
        if (high > Int.MAX_VALUE) s.error("tableswitch: ключи выходят за int")
        code.tableswitch(low, high.toInt(), default, targets)
    }

    /** `lookupswitch default :d` + строки `ключ -> :цель` + `.end lookupswitch`. */
    private fun lookupSwitch(s: SmaliScanner) {
        if (!s.tryConsume("default")) s.error("lookupswitch: ожидалось default :метка")
        val default = s.label()
        val keys = ArrayList<Int>()
        val targets = ArrayList<String>()
        while (!s.tryConsume(".end lookupswitch")) {
            if (s.atEnd()) s.error("lookupswitch без .end lookupswitch")
            keys.add(SmaliLiterals.parseInt(s.word()))
            if (!s.tryConsume("->")) s.error("lookupswitch: ожидалось ключ -> :метка")
            targets.add(s.label())
        }
        code.lookupswitch(keys.toIntArray(), default, targets)
    }

    private fun arrayTypeCode(name: String): Int = when (name) {
        "T_BOOLEAN", "boolean" -> 4
        "T_CHAR", "char" -> 5
        "T_FLOAT", "float" -> 6
        "T_DOUBLE", "double" -> 7
        "T_BYTE", "byte" -> 8
        "T_SHORT", "short" -> 9
        "T_INT", "int" -> 10
        "T_LONG", "long" -> 11
        else -> throw IllegalArgumentException("newarray: неизвестный тип $name")
    }

    companion object {
        private val BY_NAME: Map<String, Int> = HashMap<String, Int>().apply {
            for (op in 0..0xFF) {
                val name = Opcodes.getOpcodeName(op)
                if (!name.startsWith("UNKNOWN")) put(name.lowercase(), op)
            }
        }

        fun opcodeByName(mnemonic: String): Int? = BY_NAME[mnemonic]
    }
}
