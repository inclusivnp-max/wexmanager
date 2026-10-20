package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins11n(override val opcode: Opcode, val regA: Int, val value: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 4)
        checkRange(opcode, "литерал", value, -8, 7)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_11n
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun toString(): String = "$name v$regA, #$value"
}