package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins10x(override val opcode: Opcode) : Instruction() {
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_10x
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun toString(): String = name
}