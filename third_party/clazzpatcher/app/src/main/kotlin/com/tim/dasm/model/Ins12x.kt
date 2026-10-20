package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins12x(override val opcode: Opcode, val regA: Int, val regB: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 4)
        checkRegister(opcode, regB, 4)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_12x
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA, regB)
    override fun getWideRegisters(): List<Int> = when (opcode) {
        // Оба регистра — wide (2 code units каждый)
        Opcode.MOVE_WIDE,
        Opcode.NEG_LONG, Opcode.NOT_LONG, Opcode.NEG_DOUBLE,
        Opcode.LONG_TO_DOUBLE, Opcode.DOUBLE_TO_LONG,
        Opcode.ADD_LONG_2ADDR, Opcode.SUB_LONG_2ADDR, Opcode.MUL_LONG_2ADDR,
        Opcode.DIV_LONG_2ADDR, Opcode.REM_LONG_2ADDR, Opcode.AND_LONG_2ADDR,
        Opcode.OR_LONG_2ADDR, Opcode.XOR_LONG_2ADDR,
        Opcode.ADD_DOUBLE_2ADDR, Opcode.SUB_DOUBLE_2ADDR, Opcode.MUL_DOUBLE_2ADDR,
        Opcode.DIV_DOUBLE_2ADDR, Opcode.REM_DOUBLE_2ADDR ->
            listOf(regA, regA + 1, regB, regB + 1)

        // dest — wide, src — узкий (1 code unit); у сдвигов vB — величина сдвига (int)
        Opcode.SHL_LONG_2ADDR, Opcode.SHR_LONG_2ADDR, Opcode.USHR_LONG_2ADDR,
        Opcode.INT_TO_LONG, Opcode.INT_TO_DOUBLE,
        Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE ->
            listOf(regA, regA + 1, regB)

        // dest — узкий, src — wide
        Opcode.LONG_TO_INT, Opcode.LONG_TO_FLOAT,
        Opcode.DOUBLE_TO_INT, Opcode.DOUBLE_TO_FLOAT ->
            listOf(regA, regB, regB + 1)

        else -> listOf(regA, regB)
    }
    override fun toString(): String = "$name v$regA, v$regB"
}