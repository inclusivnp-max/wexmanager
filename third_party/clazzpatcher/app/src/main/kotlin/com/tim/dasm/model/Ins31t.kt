package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins31t(override val opcode: Opcode, val regA: Int, val offset: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_31t
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun getBranchOffset(): Int = offset
    override fun toString(): String = "$name v$regA, $offset"
}