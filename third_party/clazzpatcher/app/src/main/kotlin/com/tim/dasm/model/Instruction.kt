package com.tim.dasm.model

import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

sealed class Instruction {
    abstract val opcode: Opcode
    abstract val name: String
    abstract val format: InsnFormat
    abstract val byteSize: Int

    open fun getRegisters(): List<Int> = emptyList()
    open fun getBranchOffset(): Int? = null
    open fun isPayload(): Boolean = false
    open fun getWideRegisters(): List<Int> = getRegisters()
}

/** Проверка, что регистр помещается в поле из [bits] бит. */
internal fun checkRegister(op: Opcode, reg: Int, bits: Int) {
    if (reg < 0 || reg >= (1 shl bits)) {
        throw IllegalArgumentException("${op.mnemonic}: регистр v$reg не помещается в $bits бит")
    }
}

/** Проверка, что индекс пула помещается в поле из [bits] бит (16 или 32). */
internal fun checkIndex(op: Opcode, index: Int, bits: Int) {
    if (index < 0 || (bits == 16 && index > 0xFFFF)) {
        val hint = if (op == Opcode.CONST_STRING) " (используйте const-string/jumbo)" else ""
        throw IllegalArgumentException("${op.mnemonic}: индекс $index не помещается в $bits бит$hint")
    }
}

/** Проверка знакового литерала/смещения на диапазон [min]..[max]. */
internal fun checkRange(op: Opcode, what: String, value: Int, min: Int, max: Int) {
    if (value < min || value > max) {
        throw IllegalArgumentException("${op.mnemonic}: $what $value вне диапазона $min..$max")
    }
}
