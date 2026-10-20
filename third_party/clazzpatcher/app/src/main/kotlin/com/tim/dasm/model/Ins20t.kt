package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class Ins20t(override val opcode: Opcode, val offset: Int) : Instruction() {
    init {
        checkRange(opcode, "смещение", offset, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
    override val name: String get() = opcode.mnemonic
    override val format = InsnFormat.FORMAT_20t
    override val byteSize: Int get() = format.sizeUnits * 2
    override fun getBranchOffset(): Int = offset
    override fun toString(): String = "$name $offset"
}