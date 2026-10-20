package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.model.*
import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

/**
 * Построитель кода метода.
 *
 * Ветвления на метки, switch/fill-array-data и их payload'ы записываются как placeholder'ы
 * и разрешаются в [com.tim.dasm.assembler.CodeResolver] после того, как известны все
 * инструкции и метки метода (MethodDsl использует один экземпляр на весь метод).
 */
class InstructionsDsl(private val asm: DexAsm) {

    val instructions: MutableList<Instruction> = ArrayList()

    internal val labels = HashMap<String, Int>()
    internal val branches = ArrayList<PendingBranch>()
    internal val switchPayloads = ArrayList<SwitchPayloadDef>()

    fun getPendingBranches(): List<PendingBranch> = branches
    fun getLabels(): Map<String, Int> = labels

    /** Число регистров, достаточное для всех использованных (max + 1; 0 — регистры не используются). */
    val usedRegisterCount: Int
        get() {
            var max = -1
            for (ins in instructions) {
                for (r in ins.getWideRegisters()) if (r > max) max = r
            }
            for (b in branches) {
                if (b.opcode.format != InsnFormat.FORMAT_10t && b.opcode.format != InsnFormat.FORMAT_20t &&
                    b.opcode.format != InsnFormat.FORMAT_30t) {
                    if (b.regA > max) max = b.regA
                    if (b.opcode.format == InsnFormat.FORMAT_22t && b.regB > max) max = b.regB
                }
            }
            return max + 1
        }

    /** Максимальный номер использованного регистра (0, если регистры не используются). */
    val maxRegisters: Int get() = maxOf(usedRegisterCount - 1, 0)

    fun label(name: String) {
        require(name.isNotEmpty()) { "Пустое имя метки" }
        require(labels.put(name, instructions.size) == null) { "Метка $name объявлена повторно" }
    }

    fun hasLabel(name: String): Boolean = name in labels

    /** Добавить готовую инструкцию (без ссылок на метки). */
    fun emit(ins: Instruction) {
        when (ins) {
            is InsPlaceholder -> throw IllegalArgumentException("placeholder нельзя добавить напрямую")
            is PackedSwitchData, is SparseSwitchData ->
                throw IllegalArgumentException("switch-payload добавляется через packedSwitchPayload/sparseSwitchPayload")
            else -> {}
        }
        when (ins.opcode) {
            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE,
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> asm.state.requireDexVersion(38)
            Opcode.CONST_METHOD_HANDLE, Opcode.CONST_METHOD_TYPE -> asm.state.requireDexVersion(39)
            else -> {}
        }
        instructions.add(ins)
    }

    private fun add(ins: Instruction) = emit(ins)

    /**
     * Ветвление на метку: goto/goto/16/goto/32, if-*z (vA), if-* (vA, vB),
     * packed-switch/sparse-switch/fill-array-data (vA; метка указывает на payload).
     *
     * `goto` и `goto/16` при необходимости расширяются до goto/16 и goto/32 (как в smali).
     */
    fun branch(op: Opcode, label: String, regA: Int = 0, regB: Int = 0) {
        when (op.format) {
            InsnFormat.FORMAT_10t, InsnFormat.FORMAT_20t, InsnFormat.FORMAT_30t -> {}
            InsnFormat.FORMAT_21t -> checkRegister(op, regA, 8)
            InsnFormat.FORMAT_22t -> { checkRegister(op, regA, 4); checkRegister(op, regB, 4) }
            InsnFormat.FORMAT_31t -> checkRegister(op, regA, 8)
            else -> throw IllegalArgumentException("${op.mnemonic} не является инструкцией ветвления")
        }
        require(label.isNotEmpty()) { "${op.mnemonic}: пустая метка" }
        branches.add(PendingBranch(label, instructions.size, op, regA, regB))
        instructions.add(InsPlaceholder(op, op.format))
    }

    // ============================================================
    // MOVE
    // ============================================================
    fun nop() = add(Ins10x(Opcode.NOP))
    fun move(dest: Int, src: Int) = add(Ins12x(Opcode.MOVE, dest, src))
    fun moveFrom16(dest: Int, src: Int) = add(Ins22x(Opcode.MOVE_FROM16, dest, src))
    fun move16(dest: Int, src: Int) = add(Ins32x(Opcode.MOVE_16, dest, src))
    fun moveWide(dest: Int, src: Int) = add(Ins12x(Opcode.MOVE_WIDE, dest, src))
    fun moveWideFrom16(dest: Int, src: Int) = add(Ins22x(Opcode.MOVE_WIDE_FROM16, dest, src))
    fun moveWide16(dest: Int, src: Int) = add(Ins32x(Opcode.MOVE_WIDE_16, dest, src))
    fun moveObject(dest: Int, src: Int) = add(Ins12x(Opcode.MOVE_OBJECT, dest, src))
    fun moveObjectFrom16(dest: Int, src: Int) = add(Ins22x(Opcode.MOVE_OBJECT_FROM16, dest, src))
    fun moveObject16(dest: Int, src: Int) = add(Ins32x(Opcode.MOVE_OBJECT_16, dest, src))
    fun moveResult(dest: Int) = add(Ins11x(Opcode.MOVE_RESULT, dest))
    fun moveResultWide(dest: Int) = add(Ins11x(Opcode.MOVE_RESULT_WIDE, dest))
    fun moveResultObject(dest: Int) = add(Ins11x(Opcode.MOVE_RESULT_OBJECT, dest))
    fun moveException(dest: Int) = add(Ins11x(Opcode.MOVE_EXCEPTION, dest))

    // ============================================================
    // RETURN
    // ============================================================
    fun returnVoid() = add(Ins10x(Opcode.RETURN_VOID))
    fun returnValue(reg: Int) = add(Ins11x(Opcode.RETURN, reg))
    fun returnWide(reg: Int) = add(Ins11x(Opcode.RETURN_WIDE, reg))
    fun returnObject(reg: Int) = add(Ins11x(Opcode.RETURN_OBJECT, reg))

    // ============================================================
    // CONST
    // ============================================================
    fun const4(reg: Int, value: Int) = add(Ins11n(Opcode.CONST_4, reg, value))

    fun const16(reg: Int, value: Int) = add(Ins21s(Opcode.CONST_16, reg, value))

    fun const32(reg: Int, value: Int) = add(Ins31i(Opcode.CONST, reg, value))

    /** `const/high16`: [value] — полное 32-битное значение, младшие 16 бит должны быть нулевыми. */
    fun constHigh16(reg: Int, value: Int) {
        require(value and 0xFFFF == 0) {
            "const/high16: у значения 0x${Integer.toHexString(value)} младшие 16 бит не нулевые"
        }
        add(Ins21h(Opcode.CONST_HIGH16, reg, value ushr 16))
    }

    fun constWide16(reg: Int, value: Int) = add(Ins21s(Opcode.CONST_WIDE_16, reg, value))

    fun constWide32(reg: Int, value: Int) = add(Ins31i(Opcode.CONST_WIDE_32, reg, value))

    fun constWide(reg: Int, value: Long) = add(Ins51l(Opcode.CONST_WIDE, reg, value))

    /** `const-wide/high16`: [value] — полное 64-битное значение, младшие 48 бит должны быть нулевыми. */
    fun constWideHigh16(reg: Int, value: Long) {
        require(value and 0xFFFFFFFFFFFFL == 0L) {
            "const-wide/high16: у значения 0x${java.lang.Long.toHexString(value)} младшие 48 бит не нулевые"
        }
        add(Ins21h(Opcode.CONST_WIDE_HIGH16, reg, (value ushr 48).toInt()))
    }

    fun constString(reg: Int, value: String) =
        add(Ins21c(Opcode.CONST_STRING, reg, asm.strings.add(value)))

    fun constStringJumbo(reg: Int, value: String) =
        add(Ins31c(Opcode.CONST_STRING_JUMBO, reg, asm.strings.add(value)))

    fun constClass(reg: Int, className: String) =
        add(Ins21c(Opcode.CONST_CLASS, reg, asm.types.add(className)))

    // ============================================================
    // MONITOR
    // ============================================================
    fun monitorEnter(reg: Int) = add(Ins11x(Opcode.MONITOR_ENTER, reg))
    fun monitorExit(reg: Int) = add(Ins11x(Opcode.MONITOR_EXIT, reg))

    // ============================================================
    // TYPES / NEW
    // ============================================================
    fun checkCast(reg: Int, className: String) =
        add(Ins21c(Opcode.CHECK_CAST, reg, asm.types.add(className)))

    fun instanceOf(dest: Int, src: Int, className: String) =
        add(Ins22c(Opcode.INSTANCE_OF, dest, src, asm.types.add(className)))

    fun arrayLength(dest: Int, src: Int) = add(Ins12x(Opcode.ARRAY_LENGTH, dest, src))

    fun newInstance(reg: Int, className: String) =
        add(Ins21c(Opcode.NEW_INSTANCE, reg, asm.types.add(className)))

    fun newArray(reg: Int, sizeReg: Int, elementType: String) =
        add(Ins22c(Opcode.NEW_ARRAY, reg, sizeReg, asm.types.add(elementType)))

    fun filledNewArray(className: String, vararg regs: Int) =
        add(make35c(Opcode.FILLED_NEW_ARRAY, asm.types.add(className), regs))

    fun filledNewArrayRange(className: String, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.FILLED_NEW_ARRAY_RANGE, startReg, count, asm.types.add(className)))

    fun fillArrayData(reg: Int, offset: Int) =
        add(Ins31t(Opcode.FILL_ARRAY_DATA, reg, offset))

    fun fillArrayData(reg: Int, label: String) =
        branch(Opcode.FILL_ARRAY_DATA, label, reg)

    fun throwException(reg: Int) = add(Ins11x(Opcode.THROW, reg))

    // ============================================================
    // GOTO / SWITCH
    // ============================================================
    fun goto(offset: Int) = add(Ins10t(Opcode.GOTO, offset))
    fun goto16(offset: Int) = add(Ins20t(Opcode.GOTO_16, offset))
    fun goto32(offset: Int) = add(Ins30t(Opcode.GOTO_32, offset))

    fun goto(label: String) = branch(Opcode.GOTO, label)
    fun goto16(label: String) = branch(Opcode.GOTO_16, label)
    fun goto32(label: String) = branch(Opcode.GOTO_32, label)

    fun packedSwitch(reg: Int, offset: Int) =
        add(Ins31t(Opcode.PACKED_SWITCH, reg, offset))

    fun packedSwitch(reg: Int, label: String) =
        branch(Opcode.PACKED_SWITCH, label, reg)

    fun sparseSwitch(reg: Int, offset: Int) =
        add(Ins31t(Opcode.SPARSE_SWITCH, reg, offset))

    fun sparseSwitch(reg: Int, label: String) =
        branch(Opcode.SPARSE_SWITCH, label, reg)

    // ============================================================
    // CMP / IF
    // ============================================================
    fun cmplFloat(dest: Int, a: Int, b: Int) = add(Ins23x(Opcode.CMPL_FLOAT, dest, a, b))
    fun cmpgFloat(dest: Int, a: Int, b: Int) = add(Ins23x(Opcode.CMPG_FLOAT, dest, a, b))
    fun cmplDouble(dest: Int, a: Int, b: Int) = add(Ins23x(Opcode.CMPL_DOUBLE, dest, a, b))
    fun cmpgDouble(dest: Int, a: Int, b: Int) = add(Ins23x(Opcode.CMPG_DOUBLE, dest, a, b))
    fun cmpLong(dest: Int, a: Int, b: Int) = add(Ins23x(Opcode.CMP_LONG, dest, a, b))

    fun ifEq(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_EQ, a, b, offset))
    fun ifNe(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_NE, a, b, offset))
    fun ifLt(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_LT, a, b, offset))
    fun ifGe(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_GE, a, b, offset))
    fun ifGt(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_GT, a, b, offset))
    fun ifLe(a: Int, b: Int, offset: Int) = add(Ins22t(Opcode.IF_LE, a, b, offset))

    fun ifEq(a: Int, b: Int, label: String) = branch(Opcode.IF_EQ, label, a, b)
    fun ifNe(a: Int, b: Int, label: String) = branch(Opcode.IF_NE, label, a, b)
    fun ifLt(a: Int, b: Int, label: String) = branch(Opcode.IF_LT, label, a, b)
    fun ifGe(a: Int, b: Int, label: String) = branch(Opcode.IF_GE, label, a, b)
    fun ifGt(a: Int, b: Int, label: String) = branch(Opcode.IF_GT, label, a, b)
    fun ifLe(a: Int, b: Int, label: String) = branch(Opcode.IF_LE, label, a, b)

    fun ifEqz(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_EQZ, reg, offset))
    fun ifNez(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_NEZ, reg, offset))
    fun ifLtz(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_LTZ, reg, offset))
    fun ifGez(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_GEZ, reg, offset))
    fun ifGtz(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_GTZ, reg, offset))
    fun ifLez(reg: Int, offset: Int) = add(Ins21t(Opcode.IF_LEZ, reg, offset))

    fun ifEqz(reg: Int, label: String) = branch(Opcode.IF_EQZ, label, reg)
    fun ifNez(reg: Int, label: String) = branch(Opcode.IF_NEZ, label, reg)
    fun ifLtz(reg: Int, label: String) = branch(Opcode.IF_LTZ, label, reg)
    fun ifGez(reg: Int, label: String) = branch(Opcode.IF_GEZ, label, reg)
    fun ifGtz(reg: Int, label: String) = branch(Opcode.IF_GTZ, label, reg)
    fun ifLez(reg: Int, label: String) = branch(Opcode.IF_LEZ, label, reg)

    // ============================================================
    // AGET / APUT
    // ============================================================
    fun aget(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET, dest, arr, idx))
    fun agetWide(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_WIDE, dest, arr, idx))
    fun agetObject(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_OBJECT, dest, arr, idx))
    fun agetBoolean(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_BOOLEAN, dest, arr, idx))
    fun agetByte(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_BYTE, dest, arr, idx))
    fun agetChar(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_CHAR, dest, arr, idx))
    fun agetShort(dest: Int, arr: Int, idx: Int) = add(Ins23x(Opcode.AGET_SHORT, dest, arr, idx))

    fun aput(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT, src, arr, idx))
    fun aputWide(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_WIDE, src, arr, idx))
    fun aputObject(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_OBJECT, src, arr, idx))
    fun aputBoolean(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_BOOLEAN, src, arr, idx))
    fun aputByte(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_BYTE, src, arr, idx))
    fun aputChar(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_CHAR, src, arr, idx))
    fun aputShort(arr: Int, src: Int, idx: Int) = add(Ins23x(Opcode.APUT_SHORT, src, arr, idx))

    // ============================================================
    // FIELDS — instance
    // ============================================================
    fun iget(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET, dest, obj, asm.fields.add(c, n, t)))
    fun igetWide(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_WIDE, dest, obj, asm.fields.add(c, n, t)))
    fun igetObject(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_OBJECT, dest, obj, asm.fields.add(c, n, t)))
    fun igetBoolean(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_BOOLEAN, dest, obj, asm.fields.add(c, n, t)))
    fun igetByte(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_BYTE, dest, obj, asm.fields.add(c, n, t)))
    fun igetChar(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_CHAR, dest, obj, asm.fields.add(c, n, t)))
    fun igetShort(dest: Int, obj: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IGET_SHORT, dest, obj, asm.fields.add(c, n, t)))

    fun iput(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT, src, obj, asm.fields.add(c, n, t)))
    fun iputWide(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_WIDE, src, obj, asm.fields.add(c, n, t)))
    fun iputObject(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_OBJECT, src, obj, asm.fields.add(c, n, t)))
    fun iputBoolean(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_BOOLEAN, src, obj, asm.fields.add(c, n, t)))
    fun iputByte(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_BYTE, src, obj, asm.fields.add(c, n, t)))
    fun iputChar(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_CHAR, src, obj, asm.fields.add(c, n, t)))
    fun iputShort(obj: Int, src: Int, c: String, n: String, t: String) =
        add(Ins22c(Opcode.IPUT_SHORT, src, obj, asm.fields.add(c, n, t)))

    // ============================================================
    // FIELDS — static
    // ============================================================
    fun sget(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET, dest, asm.fields.add(c, n, t)))
    fun sgetWide(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_WIDE, dest, asm.fields.add(c, n, t)))
    fun sgetObject(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_OBJECT, dest, asm.fields.add(c, n, t)))
    fun sgetBoolean(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_BOOLEAN, dest, asm.fields.add(c, n, t)))
    fun sgetByte(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_BYTE, dest, asm.fields.add(c, n, t)))
    fun sgetChar(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_CHAR, dest, asm.fields.add(c, n, t)))
    fun sgetShort(dest: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SGET_SHORT, dest, asm.fields.add(c, n, t)))

    fun sput(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT, src, asm.fields.add(c, n, t)))
    fun sputWide(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_WIDE, src, asm.fields.add(c, n, t)))
    fun sputObject(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_OBJECT, src, asm.fields.add(c, n, t)))
    fun sputBoolean(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_BOOLEAN, src, asm.fields.add(c, n, t)))
    fun sputByte(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_BYTE, src, asm.fields.add(c, n, t)))
    fun sputChar(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_CHAR, src, asm.fields.add(c, n, t)))
    fun sputShort(src: Int, c: String, n: String, t: String) =
        add(Ins21c(Opcode.SPUT_SHORT, src, asm.fields.add(c, n, t)))

    // ============================================================
    // INVOKE (35c)
    // ============================================================

    private fun make35c(op: Opcode, idx: Int, regs: IntArray): Ins35c {
        checkRange(op, "число регистров", regs.size, 0, 5)
        val c = regs.getOrElse(0) { 0 }
        val d = regs.getOrElse(1) { 0 }
        val a = regs.getOrElse(2) { 0 }
        val b = regs.getOrElse(3) { 0 }
        val g = regs.getOrElse(4) { 0 }
        return Ins35c(op, regs.size, idx, g, a, b, c, d)
    }

    private fun buildInvokeRegs(
        paramTypes: List<String>,
        isStatic: Boolean,
        start: Int = 0
    ): IntArray {
        val regs = mutableListOf<Int>()
        var next = start
        if (!isStatic) { regs.add(next); next += 1 }
        for (t in paramTypes) {
            regs.add(next)
            next += if (t == "J" || t == "D") 2 else 1
        }
        return regs.toIntArray()
    }

    private fun invokeByName(
        op: Opcode,
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) {
        val idx = asm.methods.add(className, methodName, paramTypes, returnType)
        add(make35c(op, idx, regs))
    }

    /** invoke-* (35c) по индексу метода: регистры в порядке аргументов. */
    fun invoke(op: Opcode, methodIdx: Int, vararg regs: Int) {
        require(op.format == InsnFormat.FORMAT_35c && op != Opcode.FILLED_NEW_ARRAY && op != Opcode.INVOKE_CUSTOM) {
            "${op.mnemonic} не является invoke-* формата 35c"
        }
        add(make35c(op, methodIdx, regs))
    }

    /** invoke-* /range (3rc) по индексу метода. */
    fun invokeRange(op: Opcode, methodIdx: Int, startReg: Int, count: Int) {
        require(op.format == InsnFormat.FORMAT_3rc && op != Opcode.FILLED_NEW_ARRAY_RANGE && op != Opcode.INVOKE_CUSTOM_RANGE) {
            "${op.mnemonic} не является invoke-*/range"
        }
        add(Ins3rc(op, startReg, count, methodIdx))
    }

    fun invokeVirtualByName(
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) = invokeByName(Opcode.INVOKE_VIRTUAL, className, methodName, returnType, paramTypes, regs)

    fun invokeVirtualByName(
        className: String, methodName: String, returnType: String,
        vararg paramTypes: String, startRegister: Int = 0
    ) = invokeByName(
        Opcode.INVOKE_VIRTUAL, className, methodName, returnType, paramTypes.toList(),
        buildInvokeRegs(paramTypes.toList(), false, startRegister)
    )

    fun invokeSuperByName(
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) = invokeByName(Opcode.INVOKE_SUPER, className, methodName, returnType, paramTypes, regs)

    fun invokeDirectByName(
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) = invokeByName(Opcode.INVOKE_DIRECT, className, methodName, returnType, paramTypes, regs)

    fun invokeStaticByName(
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) = invokeByName(Opcode.INVOKE_STATIC, className, methodName, returnType, paramTypes, regs)

    fun invokeInterfaceByName(
        className: String, methodName: String, returnType: String,
        paramTypes: List<String>, regs: IntArray
    ) = invokeByName(Opcode.INVOKE_INTERFACE, className, methodName, returnType, paramTypes, regs)

    // ---- range ----

    fun invokeVirtualRange(methodIdx: Int, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.INVOKE_VIRTUAL_RANGE, startReg, count, methodIdx))
    fun invokeSuperRange(methodIdx: Int, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.INVOKE_SUPER_RANGE, startReg, count, methodIdx))
    fun invokeDirectRange(methodIdx: Int, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.INVOKE_DIRECT_RANGE, startReg, count, methodIdx))
    fun invokeStaticRange(methodIdx: Int, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.INVOKE_STATIC_RANGE, startReg, count, methodIdx))
    fun invokeInterfaceRange(methodIdx: Int, startReg: Int, count: Int) =
        add(Ins3rc(Opcode.INVOKE_INTERFACE_RANGE, startReg, count, methodIdx))

    // ============================================================
    // UNARY
    // ============================================================
    fun negInt(d: Int, s: Int) = add(Ins12x(Opcode.NEG_INT, d, s))
    fun notInt(d: Int, s: Int) = add(Ins12x(Opcode.NOT_INT, d, s))
    fun negLong(d: Int, s: Int) = add(Ins12x(Opcode.NEG_LONG, d, s))
    fun notLong(d: Int, s: Int) = add(Ins12x(Opcode.NOT_LONG, d, s))
    fun negFloat(d: Int, s: Int) = add(Ins12x(Opcode.NEG_FLOAT, d, s))
    fun negDouble(d: Int, s: Int) = add(Ins12x(Opcode.NEG_DOUBLE, d, s))

    // ============================================================
    // CONVERSIONS
    // ============================================================
    fun intToLong(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_LONG, d, s))
    fun intToFloat(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_FLOAT, d, s))
    fun intToDouble(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_DOUBLE, d, s))
    fun longToInt(d: Int, s: Int) = add(Ins12x(Opcode.LONG_TO_INT, d, s))
    fun longToFloat(d: Int, s: Int) = add(Ins12x(Opcode.LONG_TO_FLOAT, d, s))
    fun longToDouble(d: Int, s: Int) = add(Ins12x(Opcode.LONG_TO_DOUBLE, d, s))
    fun floatToInt(d: Int, s: Int) = add(Ins12x(Opcode.FLOAT_TO_INT, d, s))
    fun floatToLong(d: Int, s: Int) = add(Ins12x(Opcode.FLOAT_TO_LONG, d, s))
    fun floatToDouble(d: Int, s: Int) = add(Ins12x(Opcode.FLOAT_TO_DOUBLE, d, s))
    fun doubleToInt(d: Int, s: Int) = add(Ins12x(Opcode.DOUBLE_TO_INT, d, s))
    fun doubleToLong(d: Int, s: Int) = add(Ins12x(Opcode.DOUBLE_TO_LONG, d, s))
    fun doubleToFloat(d: Int, s: Int) = add(Ins12x(Opcode.DOUBLE_TO_FLOAT, d, s))
    fun intToByte(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_BYTE, d, s))
    fun intToChar(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_CHAR, d, s))
    fun intToShort(d: Int, s: Int) = add(Ins12x(Opcode.INT_TO_SHORT, d, s))

    // ============================================================
    // BINARY (23x)
    // ============================================================
    fun addInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.ADD_INT, d, a, b))
    fun subInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SUB_INT, d, a, b))
    fun mulInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.MUL_INT, d, a, b))
    fun divInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.DIV_INT, d, a, b))
    fun remInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.REM_INT, d, a, b))
    fun andInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.AND_INT, d, a, b))
    fun orInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.OR_INT, d, a, b))
    fun xorInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.XOR_INT, d, a, b))
    fun shlInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SHL_INT, d, a, b))
    fun shrInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SHR_INT, d, a, b))
    fun ushrInt(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.USHR_INT, d, a, b))

    fun addLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.ADD_LONG, d, a, b))
    fun subLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SUB_LONG, d, a, b))
    fun mulLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.MUL_LONG, d, a, b))
    fun divLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.DIV_LONG, d, a, b))
    fun remLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.REM_LONG, d, a, b))
    fun andLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.AND_LONG, d, a, b))
    fun orLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.OR_LONG, d, a, b))
    fun xorLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.XOR_LONG, d, a, b))
    fun shlLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SHL_LONG, d, a, b))
    fun shrLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SHR_LONG, d, a, b))
    fun ushrLong(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.USHR_LONG, d, a, b))

    fun addFloat(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.ADD_FLOAT, d, a, b))
    fun subFloat(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SUB_FLOAT, d, a, b))
    fun mulFloat(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.MUL_FLOAT, d, a, b))
    fun divFloat(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.DIV_FLOAT, d, a, b))
    fun remFloat(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.REM_FLOAT, d, a, b))

    fun addDouble(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.ADD_DOUBLE, d, a, b))
    fun subDouble(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.SUB_DOUBLE, d, a, b))
    fun mulDouble(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.MUL_DOUBLE, d, a, b))
    fun divDouble(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.DIV_DOUBLE, d, a, b))
    fun remDouble(d: Int, a: Int, b: Int) = add(Ins23x(Opcode.REM_DOUBLE, d, a, b))

    // ============================================================
    // BINARY 2addr (12x)
    // ============================================================
    fun addInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.ADD_INT_2ADDR, d, s))
    fun subInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SUB_INT_2ADDR, d, s))
    fun mulInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.MUL_INT_2ADDR, d, s))
    fun divInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.DIV_INT_2ADDR, d, s))
    fun remInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.REM_INT_2ADDR, d, s))
    fun andInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.AND_INT_2ADDR, d, s))
    fun orInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.OR_INT_2ADDR, d, s))
    fun xorInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.XOR_INT_2ADDR, d, s))
    fun shlInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SHL_INT_2ADDR, d, s))
    fun shrInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SHR_INT_2ADDR, d, s))
    fun ushrInt2Addr(d: Int, s: Int) = add(Ins12x(Opcode.USHR_INT_2ADDR, d, s))

    fun addLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.ADD_LONG_2ADDR, d, s))
    fun subLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SUB_LONG_2ADDR, d, s))
    fun mulLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.MUL_LONG_2ADDR, d, s))
    fun divLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.DIV_LONG_2ADDR, d, s))
    fun remLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.REM_LONG_2ADDR, d, s))
    fun andLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.AND_LONG_2ADDR, d, s))
    fun orLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.OR_LONG_2ADDR, d, s))
    fun xorLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.XOR_LONG_2ADDR, d, s))
    fun shlLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SHL_LONG_2ADDR, d, s))
    fun shrLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SHR_LONG_2ADDR, d, s))
    fun ushrLong2Addr(d: Int, s: Int) = add(Ins12x(Opcode.USHR_LONG_2ADDR, d, s))

    fun addFloat2Addr(d: Int, s: Int) = add(Ins12x(Opcode.ADD_FLOAT_2ADDR, d, s))
    fun subFloat2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SUB_FLOAT_2ADDR, d, s))
    fun mulFloat2Addr(d: Int, s: Int) = add(Ins12x(Opcode.MUL_FLOAT_2ADDR, d, s))
    fun divFloat2Addr(d: Int, s: Int) = add(Ins12x(Opcode.DIV_FLOAT_2ADDR, d, s))
    fun remFloat2Addr(d: Int, s: Int) = add(Ins12x(Opcode.REM_FLOAT_2ADDR, d, s))

    fun addDouble2Addr(d: Int, s: Int) = add(Ins12x(Opcode.ADD_DOUBLE_2ADDR, d, s))
    fun subDouble2Addr(d: Int, s: Int) = add(Ins12x(Opcode.SUB_DOUBLE_2ADDR, d, s))
    fun mulDouble2Addr(d: Int, s: Int) = add(Ins12x(Opcode.MUL_DOUBLE_2ADDR, d, s))
    fun divDouble2Addr(d: Int, s: Int) = add(Ins12x(Opcode.DIV_DOUBLE_2ADDR, d, s))
    fun remDouble2Addr(d: Int, s: Int) = add(Ins12x(Opcode.REM_DOUBLE_2ADDR, d, s))

    // ============================================================
    // LITERALS (22s / 22b)
    // ============================================================
    fun addIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.ADD_INT_LIT16, d, s, v))
    fun rsubInt(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.RSUB_INT, d, s, v))
    fun mulIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.MUL_INT_LIT16, d, s, v))
    fun divIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.DIV_INT_LIT16, d, s, v))
    fun remIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.REM_INT_LIT16, d, s, v))
    fun andIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.AND_INT_LIT16, d, s, v))
    fun orIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.OR_INT_LIT16, d, s, v))
    fun xorIntLit16(d: Int, s: Int, v: Int) = add(Ins22s(Opcode.XOR_INT_LIT16, d, s, v))

    fun addIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.ADD_INT_LIT8, d, s, v))
    fun rsubIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.RSUB_INT_LIT8, d, s, v))
    fun mulIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.MUL_INT_LIT8, d, s, v))
    fun divIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.DIV_INT_LIT8, d, s, v))
    fun remIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.REM_INT_LIT8, d, s, v))
    fun andIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.AND_INT_LIT8, d, s, v))
    fun orIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.OR_INT_LIT8, d, s, v))
    fun xorIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.XOR_INT_LIT8, d, s, v))
    fun shlIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.SHL_INT_LIT8, d, s, v))
    fun shrIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.SHR_INT_LIT8, d, s, v))
    fun ushrIntLit8(d: Int, s: Int, v: Int) = add(Ins22b(Opcode.USHR_INT_LIT8, d, s, v))

    // ============================================================
    // DEX 038+
    // ============================================================
    fun invokePolymorphic(methodIdx: Int, protoIdx: Int, vararg regs: Int) {
        checkRange(Opcode.INVOKE_POLYMORPHIC, "число регистров", regs.size, 0, 5)
        val c = regs.getOrElse(0) { 0 }
        val d = regs.getOrElse(1) { 0 }
        val a = regs.getOrElse(2) { 0 }
        val b = regs.getOrElse(3) { 0 }
        val g = regs.getOrElse(4) { 0 }
        add(Ins45cc(Opcode.INVOKE_POLYMORPHIC, regs.size, methodIdx, protoIdx, g, a, b, c, d))
    }

    fun invokePolymorphicRange(methodIdx: Int, protoIdx: Int, startReg: Int, count: Int) =
        add(Ins4rcc(Opcode.INVOKE_POLYMORPHIC_RANGE, startReg, count, methodIdx, protoIdx))

    /** invoke-custom: [callSiteIdx] — индекс call_site_id (см. [com.tim.dasm.pool.CallSitePool]). */
    fun invokeCustom(callSiteIdx: Int, vararg regs: Int) {
        require(callSiteIdx in asm.state.callSiteIds.indices) { "invoke-custom: нет call site #$callSiteIdx" }
        add(make35c(Opcode.INVOKE_CUSTOM, callSiteIdx, regs))
    }

    fun invokeCustomRange(callSiteIdx: Int, startReg: Int, count: Int) {
        require(callSiteIdx in asm.state.callSiteIds.indices) { "invoke-custom/range: нет call site #$callSiteIdx" }
        add(Ins3rc(Opcode.INVOKE_CUSTOM_RANGE, startReg, count, callSiteIdx))
    }

    /** const-method-handle: [idx] — индекс method handle (см. [com.tim.dasm.pool.MethodHandlePool]). */
    fun constMethodHandle(reg: Int, idx: Int) {
        require(idx in asm.state.methodHandles.indices) { "const-method-handle: нет method handle #$idx" }
        add(Ins21c(Opcode.CONST_METHOD_HANDLE, reg, idx))
    }

    fun constMethodType(reg: Int, protoIdx: Int) =
        add(Ins21c(Opcode.CONST_METHOD_TYPE, reg, protoIdx))

    fun constMethodType(reg: Int, returnType: String, paramTypes: List<String>) =
        constMethodType(reg, asm.protos.add(returnType, paramTypes))

    // ============================================================
    // PAYLOADS
    // ============================================================

    private fun addSwitchPayload(def: SwitchPayloadDef, placeholder: Instruction): Int {
        instructions.add(placeholder)
        switchPayloads.add(def)
        return def.index
    }

    /**
     * packed-switch-payload. Смещения targets считаются от switch-инструкции,
     * которая ссылается на метку этого payload'а.
     *
     * @return индекс payload'а в списке инструкций.
     */
    fun packedSwitchPayload(firstKey: Int, targets: List<String>): Int =
        addSwitchPayload(
            SwitchPayloadDef(instructions.size, true, firstKey, null, ArrayList(targets), null),
            PackedSwitchData(targets.size, firstKey, List(targets.size) { 0 })
        )

    /**
     * sparse-switch-payload. Ключи должны строго возрастать; смещения считаются от
     * switch-инструкции, которая ссылается на метку этого payload'а.
     */
    fun sparseSwitchPayload(keys: IntArray, targets: List<String>): Int {
        require(keys.size == targets.size) { "sparse-switch-payload: ключей ${keys.size}, меток ${targets.size}" }
        for (i in 1 until keys.size) {
            require(keys[i - 1] < keys[i]) { "sparse-switch-payload: ключи должны строго возрастать (${keys[i - 1]}, ${keys[i]})" }
        }
        return addSwitchPayload(
            SwitchPayloadDef(instructions.size, false, 0, keys.copyOf(), ArrayList(targets), null),
            SparseSwitchData(keys.size, keys.toList(), List(keys.size) { 0 })
        )
    }

    /**
     * packed-switch-payload с явной базой: [switchLabel] — метка инструкции `packed-switch`.
     */
    fun packedSwitchPayload(firstKey: Int, switchLabel: String, vararg targetLabels: String): Int =
        addSwitchPayload(
            SwitchPayloadDef(instructions.size, true, firstKey, null, targetLabels.toList(), switchLabel),
            PackedSwitchData(targetLabels.size, firstKey, List(targetLabels.size) { 0 })
        )

    /**
     * sparse-switch-payload с явной базой: [switchLabel] — метка инструкции `sparse-switch`.
     */
    fun sparseSwitchPayload(keys: IntArray, switchLabel: String, vararg targetLabels: String): Int {
        require(keys.size == targetLabels.size) { "sparse-switch-payload: ключей ${keys.size}, меток ${targetLabels.size}" }
        for (i in 1 until keys.size) {
            require(keys[i - 1] < keys[i]) { "sparse-switch-payload: ключи должны строго возрастать (${keys[i - 1]}, ${keys[i]})" }
        }
        return addSwitchPayload(
            SwitchPayloadDef(instructions.size, false, 0, keys.copyOf(), targetLabels.toList(), switchLabel),
            SparseSwitchData(keys.size, keys.toList(), List(keys.size) { 0 })
        )
    }

    /** fill-array-data-payload (чистые данные, без меток). */
    fun fillArrayDataPayload(elementSize: Int, size: Int, data: ByteArray): Int {
        val idx = instructions.size
        instructions.add(FillArrayData(elementSize, size, data))
        return idx
    }
}
