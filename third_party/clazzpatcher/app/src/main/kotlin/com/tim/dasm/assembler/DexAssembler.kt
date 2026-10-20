package com.tim.dasm.assembler

import com.tim.dasm.model.*

/**
 * Кодирование инструкций в байт-код (little-endian, 16-битные code units).
 * Payload'ы выравниваются на 4 байта нулевым code unit (nop).
 */
object DexAssembler {

    fun assemble(instructions: List<Instruction>): ByteArray {
        var total = 0
        for (ins in instructions) {
            if (ins.isPayload()) total = (total + 3) and -4
            total += ins.byteSize
        }
        val out = Out(total)
        for (ins in instructions) {
            if (ins.isPayload()) while (out.pos and 3 != 0) out.u8(0)
            val start = out.pos
            assembleInstruction(out, ins)
            if (out.pos - start != ins.byteSize) {
                throw IllegalStateException("Размер закодированной инструкции ${ins.name} (${out.pos - start}) != ${ins.byteSize}")
            }
        }
        check(out.pos == total) { "Размер байт-кода ${out.pos} != $total" }
        return out.bytes
    }

    private class Out(size: Int) {
        val bytes = ByteArray(size)
        var pos = 0
        fun u8(v: Int) { bytes[pos++] = v.toByte() }
        fun u16(v: Int) { bytes[pos++] = v.toByte(); bytes[pos++] = (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        fun u64(v: Long) { u32(v.toInt()); u32((v ushr 32).toInt()) }
    }

    private fun assembleInstruction(o: Out, ins: Instruction) {
        val op = ins.opcode.value
        when (ins) {
            is Ins10x -> o.u16(op)
            is Ins12x -> o.u16(op or ((ins.regA and 0xF) shl 8) or ((ins.regB and 0xF) shl 12))
            is Ins11n -> o.u16(op or ((ins.regA and 0xF) shl 8) or ((ins.value and 0xF) shl 12))
            is Ins11x -> o.u16(op or ((ins.regA and 0xFF) shl 8))
            is Ins10t -> o.u16(op or ((ins.offset and 0xFF) shl 8))
            is Ins20t -> { o.u16(op); o.u16(ins.offset) }
            is Ins22x -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16(ins.regB) }
            is Ins21t -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16(ins.offset) }
            is Ins21s -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16(ins.value) }
            is Ins21h -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16(ins.value) }
            is Ins21c -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16(ins.index) }
            is Ins23x -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16((ins.regB and 0xFF) or ((ins.regC and 0xFF) shl 8)) }
            is Ins22b -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u16((ins.regB and 0xFF) or ((ins.value and 0xFF) shl 8)) }
            is Ins22t -> { o.u16(op or ((ins.regA and 0xF) shl 8) or ((ins.regB and 0xF) shl 12)); o.u16(ins.offset) }
            is Ins22s -> { o.u16(op or ((ins.regA and 0xF) shl 8) or ((ins.regB and 0xF) shl 12)); o.u16(ins.value) }
            is Ins22c -> { o.u16(op or ((ins.regA and 0xF) shl 8) or ((ins.regB and 0xF) shl 12)); o.u16(ins.index) }
            is Ins32x -> { o.u16(op); o.u16(ins.regA); o.u16(ins.regB) }
            is Ins30t -> { o.u16(op); o.u32(ins.offset) }
            is Ins31t -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u32(ins.offset) }
            is Ins31i -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u32(ins.value) }
            is Ins31c -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u32(ins.index) }
            is Ins35c -> {
                o.u16(op or ((ins.regG and 0xF) shl 8) or ((ins.regCount and 0xF) shl 12))
                o.u16(ins.index)
                o.u16(packRegs(ins.regCount, ins.regC, ins.regD, ins.regA, ins.regB))
            }
            is Ins3rc -> { o.u16(op or ((ins.regCount and 0xFF) shl 8)); o.u16(ins.index); o.u16(ins.regStart) }
            is Ins51l -> { o.u16(op or ((ins.regA and 0xFF) shl 8)); o.u64(ins.value) }
            is Ins45cc -> {
                o.u16(op or ((ins.regG and 0xF) shl 8) or ((ins.regCount and 0xF) shl 12))
                o.u16(ins.methodIdx)
                o.u16(packRegs(ins.regCount, ins.regC, ins.regD, ins.regA, ins.regB))
                o.u16(ins.protoIdx)
            }
            is Ins4rcc -> {
                o.u16(op or ((ins.regCount and 0xFF) shl 8)); o.u16(ins.methodIdx); o.u16(ins.regStart); o.u16(ins.protoIdx)
            }
            is PackedSwitchData -> {
                o.u16(PackedSwitchData.IDENT); o.u16(ins.size); o.u32(ins.firstKey)
                for (t in ins.targets) o.u32(t)
            }
            is SparseSwitchData -> {
                o.u16(SparseSwitchData.IDENT); o.u16(ins.size)
                for (k in ins.keys) o.u32(k)
                for (t in ins.targets) o.u32(t)
            }
            is FillArrayData -> {
                o.u16(FillArrayData.IDENT); o.u16(ins.elementSize); o.u32(ins.size)
                System.arraycopy(ins.data, 0, o.bytes, o.pos, ins.data.size)
                o.pos += ins.data.size
                if (ins.data.size and 1 != 0) o.u8(0)
            }
            is InsPlaceholder -> throw IllegalStateException(
                "Неразрешённое ветвление ${ins.opcode.mnemonic}"
            )
        }
    }

    private fun packRegs(count: Int, c: Int, d: Int, e: Int, f: Int): Int {
        var w = 0
        if (count > 0) w = w or (c and 0xF)
        if (count > 1) w = w or ((d and 0xF) shl 4)
        if (count > 2) w = w or ((e and 0xF) shl 8)
        if (count > 3) w = w or ((f and 0xF) shl 12)
        return w
    }
}
