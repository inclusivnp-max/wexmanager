package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins45cc(
    override val opcode: Opcode,
    val regCount: Int,
    val methodIdx: Int,
    val protoIdx: Int,
    val regG: Int,
    val regA: Int,
    val regB: Int,
    val regC: Int,
    val regD: Int
) : Instruction() {
    init {
        checkRange(opcode, "число регистров", regCount, 0, 5)
        checkIndex(opcode, methodIdx, 16)
        checkIndex(opcode, protoIdx, 16)
        checkRegister(opcode, regA, 4); checkRegister(opcode, regB, 4); checkRegister(opcode, regC, 4)
        checkRegister(opcode, regD, 4); checkRegister(opcode, regG, 4)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_45cc
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> {
        val regs = ArrayList<Int>(regCount)
        if (regCount > 0) regs.add(regC)
        if (regCount > 1) regs.add(regD)
        if (regCount > 2) regs.add(regA)
        if (regCount > 3) regs.add(regB)
        if (regCount > 4) regs.add(regG)
        return regs
    }
    override fun toString(): String =
        "$name { ${getRegisters().joinToString { "v$it" }} }"
}