package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins21s(override val opcode: Opcode, val regA: Int, val value: Int) : Instruction() {
    init {
        checkRegister(opcode, regA, 8)
        checkRange(opcode, "литерал", value, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_21s
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = listOf(regA)
    override fun getWideRegisters(): List<Int> = when (opcode) {
        Opcode.CONST_WIDE_16 -> listOf(regA, regA + 1)
        else -> listOf(regA)
    }
    override fun toString(): String = "$name v$regA, #$value"
}
