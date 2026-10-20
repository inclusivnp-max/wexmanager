package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins23x(
    override val opcode: Opcode,
    val regA: Int,
    val regB: Int,
    val regC: Int
) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
        checkRegister(opcode, regB, 8)
        checkRegister(opcode, regC, 8)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_23x
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA, regB, regC)
    override fun getWideRegisters(): List<Int> = when (opcode) {
        Opcode.CMP_LONG -> listOf(regA, regB, regB + 1, regC, regC + 1)
        Opcode.CMPL_DOUBLE, Opcode.CMPG_DOUBLE -> listOf(regA, regB, regB + 1, regC, regC + 1)
        Opcode.AGET_WIDE -> listOf(regA, regA + 1, regB, regC)
        Opcode.APUT_WIDE -> listOf(regA, regA + 1, regB, regC)
        Opcode.ADD_LONG, Opcode.SUB_LONG, Opcode.MUL_LONG, Opcode.DIV_LONG,
        Opcode.REM_LONG, Opcode.AND_LONG, Opcode.OR_LONG, Opcode.XOR_LONG ->
        listOf(regA, regA + 1, regB, regB + 1, regC, regC + 1)
        // Величина сдвига vC — int
        Opcode.SHL_LONG, Opcode.SHR_LONG, Opcode.USHR_LONG ->
        listOf(regA, regA + 1, regB, regB + 1, regC)
        Opcode.ADD_DOUBLE, Opcode.SUB_DOUBLE, Opcode.MUL_DOUBLE,
        Opcode.DIV_DOUBLE, Opcode.REM_DOUBLE ->
        listOf(regA, regA + 1, regB, regB + 1, regC, regC + 1)
        else -> listOf(regA, regB, regC)
    }
    override fun toString(): String = "$name v$regA, v$regB, v$regC"
}
