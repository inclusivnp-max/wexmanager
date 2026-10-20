package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins21t(override val opcode: Opcode, val regA: Int, val offset: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
        checkRange(opcode, "смещение", offset, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_21t
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun getBranchOffset(): Int = offset
    override fun toString(): String = "$name v$regA, $offset"
}