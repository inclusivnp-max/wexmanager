package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins4rcc(
    override val opcode: Opcode,
    val regStart: Int,
    val regCount: Int,
    val methodIdx: Int,
    val protoIdx: Int
) : Instruction() {
    init {
        checkRegister(opcode, regStart, 16)
        checkRange(opcode, "число регистров", regCount, 0, 0xFF)
        checkIndex(opcode, methodIdx, 16)
        checkIndex(opcode, protoIdx, 16)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_4rcc
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getRegisters(): List<Int> = (regStart until regStart + regCount).toList()
    override fun toString(): String = "$name { v$regStart.. }, method#$methodIdx"
}