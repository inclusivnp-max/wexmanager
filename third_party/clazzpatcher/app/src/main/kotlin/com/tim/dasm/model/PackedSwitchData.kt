package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class PackedSwitchData(
    val size: Int,
    val firstKey: Int,
    val targets: List<Int>
) : Instruction() {
    init {
        require(size in 0..0xFFFF && targets.size == size) {
            "packed-switch-payload: некорректный размер $size (targets=${targets.size})"
        }
    }
    override val opcode: Opcode = Opcode.NOP
    override val name: String = "packed-switch-payload"
    override val format = InsnFormat.FORMAT_PACKED_SWITCH_PAYLOAD
    override val byteSize: Int = 8 + size * 4
    override fun isPayload(): Boolean = true

    companion object {
        const val IDENT: Int = 0x0100
    }

    override fun toString(): String = "packed-switch: size=$size, first=$firstKey"
}
