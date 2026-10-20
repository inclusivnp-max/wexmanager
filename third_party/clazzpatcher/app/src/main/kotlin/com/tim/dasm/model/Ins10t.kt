package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins10t(override val opcode: Opcode, val offset: Int) : Instruction() {
    init {
        checkRange(opcode, "смещение", offset, -128, 127)
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_10t
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getBranchOffset(): Int = offset
    override fun toString(): String = "$name $offset"
}