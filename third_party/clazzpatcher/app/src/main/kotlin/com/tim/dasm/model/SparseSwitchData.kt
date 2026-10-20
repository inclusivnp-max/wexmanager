package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

data class SparseSwitchData(
    val size: Int,
    val keys: List<Int>,
    val targets: List<Int>
) : Instruction() {
    init {
        require(size in 0..0xFFFF && keys.size == size && targets.size == size) {
            "sparse-switch-payload: некорректный размер $size (keys=${keys.size}, targets=${targets.size})"
        }
    }
    override val opcode: Opcode = Opcode.NOP
    override val name: String = "sparse-switch-payload"
    override val format = InsnFormat.FORMAT_SPARSE_SWITCH_PAYLOAD
    override val byteSize: Int = 4 + size * 8
    override fun isPayload(): Boolean = true

    companion object {
        const val IDENT: Int = 0x0200
    }

    override fun toString(): String = "sparse-switch: size=$size"
}
