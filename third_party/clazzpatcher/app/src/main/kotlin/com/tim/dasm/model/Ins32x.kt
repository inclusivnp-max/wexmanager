package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins32x(override val opcode: Opcode, val regA: Int, val regB: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 16)
        checkRegister(opcode, regB, 16)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_32x
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA, regB)
    override fun getWideRegisters(): List<Int> = when (opcode) {
        Opcode.MOVE_WIDE_16 -> listOf(regA, regA + 1, regB, regB + 1)
        else -> listOf(regA, regB)
    }
    override fun toString(): String = "$name v$regA, v$regB"
}
