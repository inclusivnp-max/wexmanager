package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

internal data class InsPlaceholder(
    override val opcode: Opcode,
    override val format: InsnFormat
) : Instruction() {
    override val name: String = "placeholder:${opcode.mnemonic}"
    override val byteSize: Int = format.sizeUnits * 2
    override fun toString(): String = "placeholder(${format.name})"
}
