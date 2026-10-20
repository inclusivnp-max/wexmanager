package com.tim.dasm.util

import com.tim.dasm.model.*
import com.tim.dex.data.Opcode

object InstructionUtils {

    fun isBranch(i: Instruction): Boolean = when (i.opcode) {
        Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32,
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
        Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> true
        else -> false
    }

    fun isGoto(i: Instruction): Boolean = when (i.opcode) {
        Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> true
        else -> false
    }

    fun isReturn(i: Instruction): Boolean = when (i.opcode) {
        Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT -> true
        else -> false
    }

    fun isInvoke(i: Instruction): Boolean = when (i.opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT_RANGE,
        Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE_RANGE,
        Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE,
        Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> true
        else -> false
    }

    fun isField(i: Instruction): Boolean = when (i.opcode) {
        Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN,
        Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
        Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN,
        Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
        Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN,
        Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT,
        Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN,
        Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT -> true
        else -> false
    }

    fun getBranchOffset(i: Instruction): Int? = i.getBranchOffset()
    fun getRegisters(i: Instruction): List<Int> = i.getRegisters()
    fun getWideRegisters(i: Instruction): List<Int> = i.getWideRegisters()

    fun getIndex(i: Instruction): Int? = when (i) {
        is Ins21c -> i.index
        is Ins22c -> i.index
        is Ins31c -> i.index
        is Ins35c -> i.index
        is Ins3rc -> i.index
        is Ins45cc -> i.methodIdx
        is Ins4rcc -> i.methodIdx
        else -> null
    }

    fun getMnemonic(i: Instruction): String = when (i) {
        is PackedSwitchData -> "packed-switch-payload"
        is SparseSwitchData -> "sparse-switch-payload"
        is FillArrayData -> "fill-array-data-payload"
        else -> i.opcode.mnemonic
    }
}
