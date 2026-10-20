package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins51l(override val opcode: Opcode, val regA: Int, val value: Long) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_51l
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun getWideRegisters(): List<Int> = listOf(regA, regA + 1)
    override fun toString(): String = "$name v$regA, #${value}L"
}
