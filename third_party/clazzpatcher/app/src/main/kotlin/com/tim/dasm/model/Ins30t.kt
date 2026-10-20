package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins30t(override val opcode: Opcode, val offset: Int) : Instruction() {
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_30t
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getBranchOffset(): Int = offset
    override fun toString(): String = "$name $offset"
}