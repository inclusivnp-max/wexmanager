package com.tim.c2d.analysis

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.JvmInsn
import com.tim.c2d.bytecode.PoolConstant
import com.tim.clazz.model.opcodes.Opcodes as Op

/**
 * Действие инструкции на типы кадра (JVMS §6.5, в объёме, нужном для выбора инструкций DEX).
 * Байткод считается прошедшим верификацию JVM: проверяются только глубина стека и границы локалов.
 */
class TypeInterpreter(private val pool: ConstantPoolView) {

    private val thisType = VType.ref(Descriptors.ofClass(pool.thisClass))

    fun execute(f: TypeFrame, insn: JvmInsn) {
        when (val op = insn.opcode) {
            Op.NOP -> {}
            Op.ACONST_NULL -> f.push(VType.Null)
            in Op.ICONST_M1..Op.ICONST_5, Op.BIPUSH, Op.SIPUSH -> f.push(VType.Int)
            Op.LCONST_0, Op.LCONST_1 -> f.pushValue(VType.Long)
            Op.FCONST_0, Op.FCONST_1, Op.FCONST_2 -> f.push(VType.Float)
            Op.DCONST_0, Op.DCONST_1 -> f.pushValue(VType.Double)
            Op.LDC -> f.push(ldcType(insn.a))
            Op.LDC2_W -> f.pushValue(ldc2Type(insn.a))

            Op.ILOAD -> { f.local(insn.a); f.push(VType.Int) }
            Op.FLOAD -> { f.local(insn.a); f.push(VType.Float) }
            Op.LLOAD -> { f.local(insn.a); f.pushValue(VType.Long) }
            Op.DLOAD -> { f.local(insn.a); f.pushValue(VType.Double) }
            Op.ALOAD -> f.push(f.local(insn.a))

            Op.IALOAD, Op.BALOAD, Op.CALOAD, Op.SALOAD -> { f.pop(2); f.push(VType.Int) }
            Op.FALOAD -> { f.pop(2); f.push(VType.Float) }
            Op.LALOAD -> { f.pop(2); f.pushValue(VType.Long) }
            Op.DALOAD -> { f.pop(2); f.pushValue(VType.Double) }
            Op.AALOAD -> { f.pop(); f.push(componentOf(f.pop())) }

            Op.ISTORE, Op.FSTORE, Op.ASTORE -> f.setLocal(insn.a, f.pop())
            Op.LSTORE, Op.DSTORE -> f.setLocal(insn.a, f.popValue())

            Op.IASTORE, Op.FASTORE, Op.AASTORE, Op.BASTORE, Op.CASTORE, Op.SASTORE -> f.pop(3)
            Op.LASTORE, Op.DASTORE -> f.pop(4)

            Op.POP -> f.pop(1)
            Op.POP2 -> f.pop(2)
            Op.DUP -> f.push(f.peek())
            Op.DUP_X1 -> { val v1 = f.pop(); val v2 = f.pop(); f.push(v1); f.push(v2); f.push(v1) }
            Op.DUP_X2 -> {
                val v1 = f.pop(); val v2 = f.pop(); val v3 = f.pop()
                f.push(v1); f.push(v3); f.push(v2); f.push(v1)
            }
            Op.DUP2 -> { val v1 = f.peek(0); val v2 = f.peek(1); f.push(v2); f.push(v1) }
            Op.DUP2_X1 -> {
                val v1 = f.pop(); val v2 = f.pop(); val v3 = f.pop()
                f.push(v2); f.push(v1); f.push(v3); f.push(v2); f.push(v1)
            }
            Op.DUP2_X2 -> {
                val v1 = f.pop(); val v2 = f.pop(); val v3 = f.pop(); val v4 = f.pop()
                f.push(v2); f.push(v1); f.push(v4); f.push(v3); f.push(v2); f.push(v1)
            }
            Op.SWAP -> { val v1 = f.pop(); val v2 = f.pop(); f.push(v1); f.push(v2) }

            Op.IADD, Op.ISUB, Op.IMUL, Op.IDIV, Op.IREM, Op.ISHL, Op.ISHR, Op.IUSHR,
            Op.IAND, Op.IOR, Op.IXOR -> { f.pop(2); f.push(VType.Int) }
            Op.LADD, Op.LSUB, Op.LMUL, Op.LDIV, Op.LREM, Op.LAND, Op.LOR, Op.LXOR -> { f.pop(4); f.pushValue(VType.Long) }
            Op.LSHL, Op.LSHR, Op.LUSHR -> { f.pop(3); f.pushValue(VType.Long) }
            Op.FADD, Op.FSUB, Op.FMUL, Op.FDIV, Op.FREM -> { f.pop(2); f.push(VType.Float) }
            Op.DADD, Op.DSUB, Op.DMUL, Op.DDIV, Op.DREM -> { f.pop(4); f.pushValue(VType.Double) }
            Op.INEG -> { f.pop(1); f.push(VType.Int) }
            Op.LNEG -> { f.pop(2); f.pushValue(VType.Long) }
            Op.FNEG -> { f.pop(1); f.push(VType.Float) }
            Op.DNEG -> { f.pop(2); f.pushValue(VType.Double) }
            Op.IINC -> f.local(insn.a)

            Op.I2L -> { f.pop(1); f.pushValue(VType.Long) }
            Op.I2F -> { f.pop(1); f.push(VType.Float) }
            Op.I2D -> { f.pop(1); f.pushValue(VType.Double) }
            Op.L2I -> { f.pop(2); f.push(VType.Int) }
            Op.L2F -> { f.pop(2); f.push(VType.Float) }
            Op.L2D -> { f.pop(2); f.pushValue(VType.Double) }
            Op.F2I -> { f.pop(1); f.push(VType.Int) }
            Op.F2L -> { f.pop(1); f.pushValue(VType.Long) }
            Op.F2D -> { f.pop(1); f.pushValue(VType.Double) }
            Op.D2I -> { f.pop(2); f.push(VType.Int) }
            Op.D2L -> { f.pop(2); f.pushValue(VType.Long) }
            Op.D2F -> { f.pop(2); f.push(VType.Float) }
            Op.I2B, Op.I2C, Op.I2S -> { f.pop(1); f.push(VType.Int) }

            Op.LCMP, Op.DCMPL, Op.DCMPG -> { f.pop(4); f.push(VType.Int) }
            Op.FCMPL, Op.FCMPG -> { f.pop(2); f.push(VType.Int) }

            in Op.IFEQ..Op.IFLE, Op.IFNULL, Op.IFNONNULL -> f.pop(1)
            in Op.IF_ICMPEQ..Op.IF_ACMPNE -> f.pop(2)
            Op.GOTO -> {}
            Op.TABLESWITCH, Op.LOOKUPSWITCH -> f.pop(1)
            Op.IRETURN, Op.FRETURN, Op.ARETURN -> f.pop(1)
            Op.LRETURN, Op.DRETURN -> f.pop(2)
            Op.RETURN -> {}

            Op.GETSTATIC -> f.pushValue(VType.ofDescriptor(pool.member(insn.a).descriptor))
            Op.PUTSTATIC -> f.pop(Descriptors.width(pool.member(insn.a).descriptor))
            Op.GETFIELD -> { f.pop(1); f.pushValue(VType.ofDescriptor(pool.member(insn.a).descriptor)) }
            Op.PUTFIELD -> f.pop(Descriptors.width(pool.member(insn.a).descriptor) + 1)

            Op.INVOKEVIRTUAL, Op.INVOKEINTERFACE, Op.INVOKESTATIC, Op.INVOKESPECIAL -> invoke(f, insn)
            Op.INVOKEDYNAMIC -> {
                val type = Descriptors.method(pool.invokeDynamic(insn.a).descriptor)
                f.pop(type.paramWords)
                pushResult(f, type.returnType)
            }

            Op.NEW -> f.push(VType.uninit(insn.pc))
            Op.NEWARRAY -> { f.pop(1); f.push(VType.ref("[" + primitiveOfAtype(insn.a))) }
            Op.ANEWARRAY -> { f.pop(1); f.push(VType.ref("[" + pool.classDescriptor(insn.a))) }
            Op.ARRAYLENGTH -> { f.pop(1); f.push(VType.Int) }
            Op.ATHROW -> f.pop(1)
            Op.CHECKCAST -> { f.pop(1); f.push(VType.ref(pool.classDescriptor(insn.a))) }
            Op.INSTANCEOF -> { f.pop(1); f.push(VType.Int) }
            Op.MONITORENTER, Op.MONITOREXIT -> f.pop(1)
            Op.MULTIANEWARRAY -> { f.pop(insn.b); f.push(VType.ref(pool.classDescriptor(insn.a))) }

            Op.JSR, Op.RET -> throw IllegalArgumentException(
                "jsr/ret (подпрограммы байткода Java 1.5 и более ранних версий) не поддерживаются: перекомпилируйте класс под Java 6+"
            )
            else -> throw IllegalArgumentException("неподдерживаемый опкод 0x${Integer.toHexString(op)}")
        }
    }

    private fun invoke(f: TypeFrame, insn: JvmInsn) {
        val ref = pool.member(insn.a)
        val type = ref.methodType ?: throw IllegalArgumentException("#${insn.a}: вызов не метода, а поля ${ref.name}")
        f.pop(type.paramWords)
        if (insn.opcode != Op.INVOKESTATIC) {
            val receiver = f.pop()
            if (insn.opcode == Op.INVOKESPECIAL && ref.name == "<init>") {
                when (receiver.kind) {
                    VType.UNINIT -> f.replaceAll(receiver, VType.ref(Descriptors.ofClass(ref.owner)))
                    VType.UNINIT_THIS -> f.replaceAll(receiver, thisType)
                }
            }
        }
        pushResult(f, type.returnType)
    }

    private fun pushResult(f: TypeFrame, returnType: String) {
        if (returnType != "V") f.pushValue(VType.ofDescriptor(returnType))
    }

    private fun ldcType(index: Int): VType = when (pool.constant(index)) {
        is PoolConstant.IntValue -> VType.Int
        is PoolConstant.FloatValue -> VType.Float
        is PoolConstant.StringValue -> VType.StringType
        is PoolConstant.ClassValue -> VType.ClassType
        is PoolConstant.MethodTypeValue -> VType.ref("Ljava/lang/invoke/MethodType;")
        is PoolConstant.MethodHandleValue -> VType.ref("Ljava/lang/invoke/MethodHandle;")
        is PoolConstant.LongValue, is PoolConstant.DoubleValue ->
            throw IllegalArgumentException("ldc #$index: long/double загружаются только через ldc2_w")
    }

    private fun ldc2Type(index: Int): VType = when (pool.constant(index)) {
        is PoolConstant.LongValue -> VType.Long
        is PoolConstant.DoubleValue -> VType.Double
        else -> throw IllegalArgumentException("ldc2_w #$index: ожидалась константа long/double")
    }

    companion object {
        /** Компонент массива по его типу (для `aaload`); null остаётся null. */
        fun componentOf(array: VType): VType {
            if (array.kind == VType.NULL) return VType.Null
            val desc = array.desc
            if (array.kind != VType.REF || desc == null || !desc.startsWith("[")) return VType.Object
            val component = desc.substring(1)
            return if (Descriptors.isReference(component)) VType.ref(component) else VType.Object
        }

        /** Примитивный тип элемента по `atype` инструкции `newarray`. */
        fun primitiveOfAtype(atype: Int): String = when (atype) {
            4 -> "Z"
            5 -> "C"
            6 -> "F"
            7 -> "D"
            8 -> "B"
            9 -> "S"
            10 -> "I"
            11 -> "J"
            else -> throw IllegalArgumentException("newarray: неизвестный atype $atype")
        }
    }
}
