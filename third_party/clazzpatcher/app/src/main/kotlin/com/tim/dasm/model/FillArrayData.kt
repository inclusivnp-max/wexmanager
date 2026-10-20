package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

/**
 * fill-array-data-payload.
 *
 * [data] — ровно `size * elementSize` байт; при нечётной длине payload дополняется
 * одним нулевым байтом, поэтому [byteSize] всегда чётный:
 * `(size * element_width + 1) / 2 + 4` code units.
 */
data class FillArrayData(
    val elementSize: Int,
    val size: Int,
    val data: ByteArray
) : Instruction() {
    init {
        require(elementSize == 1 || elementSize == 2 || elementSize == 4 || elementSize == 8) {
            "fill-array-data-payload: недопустимая ширина элемента $elementSize"
        }
        require(size >= 0 && data.size.toLong() == size.toLong() * elementSize) {
            "fill-array-data-payload: размер данных ${data.size} != $size * $elementSize"
        }
    }
    override val opcode: Opcode = Opcode.NOP
    override val name: String = "fill-array-data-payload"
    override val format = InsnFormat.FORMAT_FILL_ARRAY_DATA_PAYLOAD
    override val byteSize: Int = 8 + ((data.size + 1) and -2)
    override fun isPayload(): Boolean = true

    companion object {
        const val IDENT: Int = 0x0300
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FillArrayData) return false
        return elementSize == other.elementSize && size == other.size &&
        data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = elementSize
        result = 31 * result + size
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String = "fill-array: elementSize=$elementSize, size=$size"
}
