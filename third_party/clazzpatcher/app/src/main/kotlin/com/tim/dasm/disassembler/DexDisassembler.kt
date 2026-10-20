package com.tim.dasm.disassembler

import com.tim.dasm.model.*
import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode

/**
 * Дизассемблер байткода метода (массив `insns` из code_item).
 *
 * Поток читается линейно, как это делают dexdump и dexlib2. Payload'ы
 * (packed-switch / sparse-switch / fill-array-data) — псевдо-инструкции внутри потока;
 * выравнивание перед ними — настоящие `nop`, поэтому они разбираются как обычные инструкции.
 * Любой некорректный байткод (неизвестный опкод, обрезанная инструкция, невыровненный
 * payload, ссылка switch'а не на payload) — исключение с адресом.
 */
class DexDisassembler {

    /**
     * Результат дизассемблирования метода.
     *
     * @property instructions инструкции в порядке следования
     * @property offsets адрес каждой инструкции в code units
     * @property codeUnits размер кода в code units
     * @property payloadIndex для `packed-switch`/`sparse-switch`/`fill-array-data` — индекс payload'а,
     *   на который ссылается инструкция (для остальных -1)
     * @property switchBase для switch-payload'а — адрес switch-инструкции, относительно которой targets
     *   переведены в абсолютные адреса (первая по адресу ссылающаяся инструкция; для остальных -1)
     */
    class Code(
        val instructions: List<Instruction>,
        val offsets: IntArray,
        val codeUnits: Int,
        val payloadIndex: IntArray,
        val switchBase: IntArray
    ) {
        /** Индекс инструкции, начинающейся точно по адресу [address], или -1. */
        fun indexAt(address: Int): Int {
            val i = java.util.Arrays.binarySearch(offsets, address)
            return if (i >= 0) i else -1
        }
    }

    /**
     * Инструкции метода. Targets switch-payload'ов — абсолютные адреса (code units),
     * вычисленные относительно switch-инструкции, которая ссылается на payload.
     */
    fun disassemble(insns: ByteArray): List<Instruction> = decode(insns).instructions

    /** Полный разбор: инструкции, их адреса и связи switch → payload. */
    fun decode(insns: ByteArray): Code {
        if (insns.size % 2 != 0) throw error(0, "размер байткода (${insns.size} байт) не кратен 2")
        val n = insns.size / 2

        // Проход 1: границы инструкций.
        var offsets = IntArray(if (n < 16) 16 else n / 2)
        var count = 0
        var p = 0
        while (p < n) {
            val u0 = unit(insns, p)
            val size = if (u0 and 0xFF == 0 && u0 != 0) {
                payloadSize(insns, n, p, u0)
            } else {
                val op = Opcode.of(u0 and 0xFF)
                    ?: throw error(p, "неизвестный опкод 0x${Integer.toHexString(u0 and 0xFF)}")
                op.sizeUnits
            }
            if (size > n - p) throw error(p, "инструкция выходит за конец кода ($n code units)")
            if (count == offsets.size) offsets = offsets.copyOf(count * 2)
            offsets[count++] = p
            p += size
        }
        offsets = offsets.copyOf(count)

        // Проход 2: связи 31t-инструкций с payload'ами.
        val payloadIndex = IntArray(count) { -1 }
        val switchBase = IntArray(count) { -1 }
        for (i in 0 until count) {
            val addr = offsets[i]
            val ident = when (unit(insns, addr) and 0xFF) {
                OP_FILL_ARRAY_DATA -> FillArrayData.IDENT
                OP_PACKED_SWITCH -> PackedSwitchData.IDENT
                OP_SPARSE_SWITCH -> SparseSwitchData.IDENT
                else -> continue
            }
            val target = addr + int32(insns, addr + 1)
            var ti = java.util.Arrays.binarySearch(offsets, target)
            if (ti < 0) throw error(addr, "ссылка на payload по адресу 0x${Integer.toHexString(target)}, " +
                "который не является началом инструкции")
            if (unit(insns, offsets[ti]) != ident) {
                // Как и dexlib2, допускаем ссылку на nop-выравнивание прямо перед payload'ом.
                if (unit(insns, offsets[ti]) == 0 && ti + 1 < count && unit(insns, offsets[ti + 1]) == ident) {
                    ti++
                } else {
                    throw error(addr, "по адресу 0x${Integer.toHexString(target)} нет payload'а нужного типа")
                }
            }
            payloadIndex[i] = ti
            if (ident != FillArrayData.IDENT && switchBase[ti] == -1) switchBase[ti] = addr
        }

        // Проход 3: объекты инструкций.
        val out = ArrayList<Instruction>(count)
        for (i in 0 until count) {
            val addr = offsets[i]
            try {
                out.add(decodeAt(insns, addr, switchBase[i]))
            } catch (e: IllegalArgumentException) {
                throw error(addr, e.message ?: e.toString())
            }
        }
        return Code(out, offsets, n, payloadIndex, switchBase)
    }

    private fun payloadSize(insns: ByteArray, n: Int, p: Int, ident: Int): Int {
        if (p and 1 != 0) throw error(p, "payload 0x${Integer.toHexString(ident)} не выровнен на 4 байта")
        if (n - p < 2) throw error(p, "обрезанный payload")
        val size = unit(insns, p + 1)
        return when (ident) {
            PackedSwitchData.IDENT -> 4 + size * 2
            SparseSwitchData.IDENT -> 2 + size * 4
            FillArrayData.IDENT -> {
                if (n - p < 4) throw error(p, "обрезанный fill-array-data-payload")
                val count = int32(insns, p + 2).toLong() and 0xFFFFFFFFL
                val units = 4 + (size * count + 1) / 2
                if (units > n - p) throw error(p, "fill-array-data-payload выходит за конец кода")
                units.toInt()
            }
            else -> throw error(p, "неизвестный опкод 0x${Integer.toHexString(ident)}")
        }
    }

    private fun decodeAt(b: ByteArray, p: Int, base: Int): Instruction {
        val u0 = unit(b, p)
        if (u0 and 0xFF == 0 && u0 != 0) return decodePayload(b, p, u0, base)
        val op = Opcode.of(u0 and 0xFF)!!
        val a8 = (u0 ushr 8) and 0xFF
        val a4 = (u0 ushr 8) and 0xF
        val b4 = (u0 ushr 12) and 0xF
        return when (op.format) {
            InsnFormat.FORMAT_10x -> {
                if (a8 != 0) throw error(p, "ненулевой старший байт у ${op.mnemonic}")
                Ins10x(op)
            }
            InsnFormat.FORMAT_12x -> Ins12x(op, a4, b4)
            InsnFormat.FORMAT_11n -> Ins11n(op, a4, (u0 shl 16) shr 28)
            InsnFormat.FORMAT_11x -> Ins11x(op, a8)
            InsnFormat.FORMAT_10t -> Ins10t(op, (u0 shl 16) shr 24)
            InsnFormat.FORMAT_20t -> Ins20t(op, short(b, p + 1))
            InsnFormat.FORMAT_22x -> Ins22x(op, a8, unit(b, p + 1))
            InsnFormat.FORMAT_21t -> Ins21t(op, a8, short(b, p + 1))
            InsnFormat.FORMAT_21s -> Ins21s(op, a8, short(b, p + 1))
            InsnFormat.FORMAT_21h -> Ins21h(op, a8, unit(b, p + 1))
            InsnFormat.FORMAT_21c -> Ins21c(op, a8, unit(b, p + 1))
            InsnFormat.FORMAT_23x -> {
                val w1 = unit(b, p + 1)
                Ins23x(op, a8, w1 and 0xFF, w1 ushr 8)
            }
            InsnFormat.FORMAT_22b -> {
                val w1 = unit(b, p + 1)
                Ins22b(op, a8, w1 and 0xFF, (w1 shl 16) shr 24)
            }
            InsnFormat.FORMAT_22t -> Ins22t(op, a4, b4, short(b, p + 1))
            InsnFormat.FORMAT_22s -> Ins22s(op, a4, b4, short(b, p + 1))
            InsnFormat.FORMAT_22c -> Ins22c(op, a4, b4, unit(b, p + 1))
            InsnFormat.FORMAT_32x -> Ins32x(op, unit(b, p + 1), unit(b, p + 2))
            InsnFormat.FORMAT_30t -> Ins30t(op, int32(b, p + 1))
            InsnFormat.FORMAT_31t -> Ins31t(op, a8, int32(b, p + 1))
            InsnFormat.FORMAT_31i -> Ins31i(op, a8, int32(b, p + 1))
            InsnFormat.FORMAT_31c -> Ins31c(op, a8, int32(b, p + 1))
            InsnFormat.FORMAT_35c -> {
                val w2 = unit(b, p + 2)
                Ins35c(op, b4, unit(b, p + 1), a4,
                    (w2 ushr 8) and 0xF, (w2 ushr 12) and 0xF, w2 and 0xF, (w2 ushr 4) and 0xF)
            }
            InsnFormat.FORMAT_3rc -> Ins3rc(op, unit(b, p + 2), a8, unit(b, p + 1))
            InsnFormat.FORMAT_45cc -> {
                val w2 = unit(b, p + 2)
                Ins45cc(op, b4, unit(b, p + 1), unit(b, p + 3), a4,
                    (w2 ushr 8) and 0xF, (w2 ushr 12) and 0xF, w2 and 0xF, (w2 ushr 4) and 0xF)
            }
            InsnFormat.FORMAT_4rcc -> Ins4rcc(op, unit(b, p + 2), a8, unit(b, p + 1), unit(b, p + 3))
            InsnFormat.FORMAT_51l -> Ins51l(op, a8,
                (int32(b, p + 1).toLong() and 0xFFFFFFFFL) or (int32(b, p + 3).toLong() shl 32))
            else -> throw error(p, "неподдерживаемый формат ${op.format} у ${op.mnemonic}")
        }
    }

    private fun decodePayload(b: ByteArray, p: Int, ident: Int, base: Int): Instruction {
        val size = unit(b, p + 1)
        return when (ident) {
            PackedSwitchData.IDENT -> {
                if (base < 0) throw error(p, "packed-switch-payload не используется ни одной packed-switch")
                val firstKey = int32(b, p + 2)
                PackedSwitchData(size, firstKey, List(size) { k -> base + int32(b, p + 4 + 2 * k) })
            }
            SparseSwitchData.IDENT -> {
                if (base < 0) throw error(p, "sparse-switch-payload не используется ни одной sparse-switch")
                val keys = List(size) { k -> int32(b, p + 2 + 2 * k) }
                SparseSwitchData(size, keys, List(size) { k -> base + int32(b, p + 2 + 2 * size + 2 * k) })
            }
            else -> {
                if (size != 1 && size != 2 && size != 4 && size != 8) {
                    throw error(p, "недопустимая ширина элемента fill-array-data: $size")
                }
                val count = int32(b, p + 2)
                val start = (p + 4) * 2
                FillArrayData(size, count, b.copyOfRange(start, start + size * count))
            }
        }
    }

    private fun error(address: Int, message: String) =
        IllegalStateException("Некорректный байткод по адресу 0x${Integer.toHexString(address)}: $message")

    private companion object {
        const val OP_FILL_ARRAY_DATA = 0x26
        const val OP_PACKED_SWITCH = 0x2b
        const val OP_SPARSE_SWITCH = 0x2c

        fun unit(b: ByteArray, i: Int): Int = (b[2 * i].toInt() and 0xFF) or ((b[2 * i + 1].toInt() and 0xFF) shl 8)
        fun short(b: ByteArray, i: Int): Int = (unit(b, i) shl 16) shr 16
        fun int32(b: ByteArray, i: Int): Int = unit(b, i) or (unit(b, i + 1) shl 16)
    }
}
