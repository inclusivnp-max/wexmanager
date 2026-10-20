package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins22c(
    override val opcode: Opcode,
    val regA: Int,
    val regB: Int,
    val index: Int
) : Instruction() {
    init {
        checkRegister(opcode, regA, 4)
        checkRegister(opcode, regB, 4)
        checkIndex(opcode, index, 16)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_22c
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA, regB)
    override fun getWideRegisters(): List<Int> = when (opcode) {
        Opcode.IGET_WIDE, Opcode.IPUT_WIDE -> listOf(regA, regA + 1, regB)
        else -> listOf(regA, regB)
    }
    override fun toString(): String = "$name v$regA, v$regB, index#$index"
}
