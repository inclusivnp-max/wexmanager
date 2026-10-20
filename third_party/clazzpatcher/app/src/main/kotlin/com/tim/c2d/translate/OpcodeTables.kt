package com.tim.c2d.translate

import com.tim.c2d.bytecode.MemberRef
import com.tim.clazz.model.opcodes.Opcodes as Op
import com.tim.dex.data.Opcode

/** Соответствие арифметики, сравнений и условных переходов JVM и DEX. */
object Arithmetic {

    fun binary(op: Int): Opcode = when (op) {
        Op.IADD -> Opcode.ADD_INT
        Op.LADD -> Opcode.ADD_LONG
        Op.FADD -> Opcode.ADD_FLOAT
        Op.DADD -> Opcode.ADD_DOUBLE
        Op.ISUB -> Opcode.SUB_INT
        Op.LSUB -> Opcode.SUB_LONG
        Op.FSUB -> Opcode.SUB_FLOAT
        Op.DSUB -> Opcode.SUB_DOUBLE
        Op.IMUL -> Opcode.MUL_INT
        Op.LMUL -> Opcode.MUL_LONG
        Op.FMUL -> Opcode.MUL_FLOAT
        Op.DMUL -> Opcode.MUL_DOUBLE
        Op.IDIV -> Opcode.DIV_INT
        Op.LDIV -> Opcode.DIV_LONG
        Op.FDIV -> Opcode.DIV_FLOAT
        Op.DDIV -> Opcode.DIV_DOUBLE
        Op.IREM -> Opcode.REM_INT
        Op.LREM -> Opcode.REM_LONG
        Op.FREM -> Opcode.REM_FLOAT
        Op.DREM -> Opcode.REM_DOUBLE
        Op.ISHL -> Opcode.SHL_INT
        Op.LSHL -> Opcode.SHL_LONG
        Op.ISHR -> Opcode.SHR_INT
        Op.LSHR -> Opcode.SHR_LONG
        Op.IUSHR -> Opcode.USHR_INT
        Op.LUSHR -> Opcode.USHR_LONG
        Op.IAND -> Opcode.AND_INT
        Op.LAND -> Opcode.AND_LONG
        Op.IOR -> Opcode.OR_INT
        Op.LOR -> Opcode.OR_LONG
        Op.IXOR -> Opcode.XOR_INT
        Op.LXOR -> Opcode.XOR_LONG
        else -> throw IllegalArgumentException("не бинарная операция: 0x${Integer.toHexString(op)}")
    }

    fun unary(op: Int): Opcode = when (op) {
        Op.INEG -> Opcode.NEG_INT
        Op.LNEG -> Opcode.NEG_LONG
        Op.FNEG -> Opcode.NEG_FLOAT
        Op.DNEG -> Opcode.NEG_DOUBLE
        Op.I2L -> Opcode.INT_TO_LONG
        Op.I2F -> Opcode.INT_TO_FLOAT
        Op.I2D -> Opcode.INT_TO_DOUBLE
        Op.L2I -> Opcode.LONG_TO_INT
        Op.L2F -> Opcode.LONG_TO_FLOAT
        Op.L2D -> Opcode.LONG_TO_DOUBLE
        Op.F2I -> Opcode.FLOAT_TO_INT
        Op.F2L -> Opcode.FLOAT_TO_LONG
        Op.F2D -> Opcode.FLOAT_TO_DOUBLE
        Op.D2I -> Opcode.DOUBLE_TO_INT
        Op.D2L -> Opcode.DOUBLE_TO_LONG
        Op.D2F -> Opcode.DOUBLE_TO_FLOAT
        Op.I2B -> Opcode.INT_TO_BYTE
        Op.I2C -> Opcode.INT_TO_CHAR
        Op.I2S -> Opcode.INT_TO_SHORT
        else -> throw IllegalArgumentException("не унарная операция: 0x${Integer.toHexString(op)}")
    }

    fun compare(op: Int): Opcode = when (op) {
        Op.LCMP -> Opcode.CMP_LONG
        Op.FCMPL -> Opcode.CMPL_FLOAT
        Op.FCMPG -> Opcode.CMPG_FLOAT
        Op.DCMPL -> Opcode.CMPL_DOUBLE
        Op.DCMPG -> Opcode.CMPG_DOUBLE
        else -> throw IllegalArgumentException("не сравнение: 0x${Integer.toHexString(op)}")
    }

    fun ifZero(op: Int): Opcode = when (op) {
        Op.IFEQ, Op.IFNULL -> Opcode.IF_EQZ
        Op.IFNE, Op.IFNONNULL -> Opcode.IF_NEZ
        Op.IFLT -> Opcode.IF_LTZ
        Op.IFGE -> Opcode.IF_GEZ
        Op.IFGT -> Opcode.IF_GTZ
        Op.IFLE -> Opcode.IF_LEZ
        else -> throw IllegalArgumentException("не сравнение с нулём: 0x${Integer.toHexString(op)}")
    }

    fun ifCompare(op: Int): Opcode = when (op) {
        Op.IF_ICMPEQ, Op.IF_ACMPEQ -> Opcode.IF_EQ
        Op.IF_ICMPNE, Op.IF_ACMPNE -> Opcode.IF_NE
        Op.IF_ICMPLT -> Opcode.IF_LT
        Op.IF_ICMPGE -> Opcode.IF_GE
        Op.IF_ICMPGT -> Opcode.IF_GT
        Op.IF_ICMPLE -> Opcode.IF_LE
        else -> throw IllegalArgumentException("не сравнение двух значений: 0x${Integer.toHexString(op)}")
    }

    private val WIDE_RESULT = setOf(
        Opcode.ADD_LONG, Opcode.SUB_LONG, Opcode.MUL_LONG, Opcode.DIV_LONG, Opcode.REM_LONG,
        Opcode.AND_LONG, Opcode.OR_LONG, Opcode.XOR_LONG, Opcode.SHL_LONG, Opcode.SHR_LONG, Opcode.USHR_LONG,
        Opcode.ADD_DOUBLE, Opcode.SUB_DOUBLE, Opcode.MUL_DOUBLE, Opcode.DIV_DOUBLE, Opcode.REM_DOUBLE,
        Opcode.NEG_LONG, Opcode.NEG_DOUBLE,
        Opcode.INT_TO_LONG, Opcode.INT_TO_DOUBLE, Opcode.LONG_TO_DOUBLE,
        Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE, Opcode.DOUBLE_TO_LONG
    )

    /** Категория результата арифметической инструкции DEX. */
    fun resultKind(op: Opcode): RegKind = if (op in WIDE_RESULT) RegKind.WIDE else RegKind.NARROW
}

/** Варианты iget/iput/sget/sput по дескриптору поля. */
object FieldOps {
    fun iget(desc: String): Opcode = pick(desc, Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT)

    fun iput(desc: String): Opcode = pick(desc, Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT,
        Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT)

    fun sget(desc: String): Opcode = pick(desc, Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT,
        Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT)

    fun sput(desc: String): Opcode = pick(desc, Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT,
        Opcode.SPUT_BOOLEAN, Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT)

    private fun pick(
        desc: String, plain: Opcode, wide: Opcode, obj: Opcode,
        bool: Opcode, byte: Opcode, char: Opcode, short: Opcode
    ): Opcode = when (desc[0]) {
        'I', 'F' -> plain
        'J', 'D' -> wide
        'L', '[' -> obj
        'Z' -> bool
        'B' -> byte
        'C' -> char
        'S' -> short
        else -> throw IllegalArgumentException("некорректный тип поля: $desc")
    }
}

/**
 * Сигнатурно-полиморфные методы (JLS §15.12.3): `MethodHandle.invoke*` и методы доступа `VarHandle`.
 * В DEX их вызывает `invoke-polymorphic`: ссылка — на объявленный метод, прототип — фактический.
 */
object Polymorphic {
    private const val OBJECT_ARRAY_TO_OBJECT = "([Ljava/lang/Object;)Ljava/lang/Object;"
    private const val OBJECT_ARRAY_TO_VOID = "([Ljava/lang/Object;)V"
    private const val OBJECT_ARRAY_TO_BOOLEAN = "([Ljava/lang/Object;)Z"

    private val METHOD_HANDLE = setOf("invoke", "invokeExact")

    private val VAR_HANDLE: Map<String, String> = HashMap<String, String>().apply {
        for (m in listOf(
            "get", "getVolatile", "getOpaque", "getAcquire",
            "getAndSet", "getAndSetAcquire", "getAndSetRelease",
            "getAndAdd", "getAndAddAcquire", "getAndAddRelease",
            "getAndBitwiseOr", "getAndBitwiseOrAcquire", "getAndBitwiseOrRelease",
            "getAndBitwiseAnd", "getAndBitwiseAndAcquire", "getAndBitwiseAndRelease",
            "getAndBitwiseXor", "getAndBitwiseXorAcquire", "getAndBitwiseXorRelease",
            "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease"
        )) put(m, OBJECT_ARRAY_TO_OBJECT)
        for (m in listOf("set", "setVolatile", "setOpaque", "setRelease")) put(m, OBJECT_ARRAY_TO_VOID)
        for (m in listOf(
            "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain",
            "weakCompareAndSetAcquire", "weakCompareAndSetRelease"
        )) put(m, OBJECT_ARRAY_TO_BOOLEAN)
    }

    /** Объявленный дескриптор, если [ref] — вызов сигнатурно-полиморфного метода, иначе `null`. */
    fun declaredDescriptor(ref: MemberRef): String? = when (ref.owner) {
        "java/lang/invoke/MethodHandle" -> if (ref.name in METHOD_HANDLE) OBJECT_ARRAY_TO_OBJECT else null
        "java/lang/invoke/VarHandle" -> VAR_HANDLE[ref.name]
        else -> null
    }

    /** Класс-обёртка примитива (для `Integer.TYPE` и т. п.). */
    fun boxOf(primitive: Char): String = when (primitive) {
        'Z' -> "Ljava/lang/Boolean;"
        'B' -> "Ljava/lang/Byte;"
        'C' -> "Ljava/lang/Character;"
        'S' -> "Ljava/lang/Short;"
        'I' -> "Ljava/lang/Integer;"
        'J' -> "Ljava/lang/Long;"
        'F' -> "Ljava/lang/Float;"
        'D' -> "Ljava/lang/Double;"
        else -> throw IllegalArgumentException("не примитивный тип: $primitive")
    }
}
