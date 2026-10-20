package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins31c(override val opcode: Opcode, val regA: Int, val index: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
        checkIndex(opcode, index, 32)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_31c
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun toString(): String = "$name v$regA, index#$index"
}