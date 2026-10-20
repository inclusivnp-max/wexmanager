package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins22b(
    override val opcode: Opcode,
    val regA: Int,
    val regB: Int,
    val value: Int
) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
        checkRegister(opcode, regB, 8)
        checkRange(opcode, "литерал", value, -128, 127)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_22b
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA, regB)
    override fun toString(): String = "$name v$regA, v$regB, #$value"
}