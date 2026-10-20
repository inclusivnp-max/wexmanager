package com.tim.dex.writer

import com.tim.dex.data.IndexPool
import com.tim.dex.data.Opcode

/**
 * Перенумерация индексов пулов внутри байткода.
 *
 * Инструкции проходятся линейно, payload-псевдоинструкции распознаются по первому
 * юниту (0x0100/0x0200/0x0300). Неизвестный опкод, обрезанная инструкция или индекс,
 * который после сортировки не помещается в 16 бит, — ошибка (никакого усечения).
 *
 * Индексы call site и method handle перенумеровываются, если заданы [callSiteMap] и
 * [methodHandleMap], иначе только проверяются по [callSiteCount] и [methodHandleCount].
 * [stringOverflow] — вместо ошибки для `const-string`, чей новый индекс не помещается в 16 бит.
 */
internal class CodeRemapper(
    private val stringMap: IntArray,
    private val typeMap: IntArray,
    private val fieldMap: IntArray,
    private val methodMap: IntArray,
    private val protoMap: IntArray,
    private val callSiteCount: Int,
    private val methodHandleCount: Int,
    private val callSiteMap: IntArray? = null,
    private val methodHandleMap: IntArray? = null,
    private val stringOverflow: ((codeIdx: Int, pc: Int) -> Nothing)? = null,
    private val describe: (codeIdx: Int) -> String
) {
    /** Максимальная версия DEX, которой требуют встреченные опкоды. */
    var requiredVersion = 35
        private set

    fun remap(insns: ByteArray, codeIdx: Int): ByteArray {
        if ((insns.size and 1) != 0) fail(codeIdx, 0, "нечётная длина insns (${insns.size} байт)")
        val code = insns.copyOf()
        val units = code.size ushr 1
        var pc = 0
        while (pc < units) {
            val p = pc shl 1
            val unit = u2(code, p)
            val op = unit and 0xFF
            if (op == 0 && unit != 0) {
                pc += payloadSize(code, pc, units, unit, codeIdx)
                continue
            }
            val size = SIZE[op]
            if (size == 0) fail(codeIdx, pc, "неизвестный опкод 0x${Integer.toHexString(op)}")
            if (pc + size > units) fail(codeIdx, pc, "инструкция ${Opcode.of(op)!!.mnemonic} обрезана концом insns")
            when (POOL[op]) {
                POOL_NONE -> {}
                POOL_STRING -> {
                    if (op == OP_CONST_STRING_JUMBO) {
                        val old = u4(code, p + 2)
                        if (old < 0 || old >= stringMap.size) badRef(codeIdx, pc, "строки", old, stringMap.size)
                        if (stringMap[old] < 0) unmarked(codeIdx, pc, "строки", old)
                        set4(code, p + 2, stringMap[old])
                    } else {
                        remap16(code, p + 2, stringMap, codeIdx, pc, "строки")
                    }
                }
                POOL_TYPE -> remap16(code, p + 2, typeMap, codeIdx, pc, "типа")
                POOL_FIELD -> remap16(code, p + 2, fieldMap, codeIdx, pc, "поля")
                POOL_METHOD -> {
                    remap16(code, p + 2, methodMap, codeIdx, pc, "метода")
                    // invoke-polymorphic: второй индекс (proto) в юните 3
                    if (op == OP_INVOKE_POLYMORPHIC || op == OP_INVOKE_POLYMORPHIC_RANGE) {
                        remap16(code, p + 6, protoMap, codeIdx, pc, "прототипа")
                    }
                }
                POOL_PROTO -> remap16(code, p + 2, protoMap, codeIdx, pc, "прототипа")
                POOL_CALL_SITE -> {
                    if (callSiteMap != null) remap16(code, p + 2, callSiteMap, codeIdx, pc, "call site")
                    else {
                        val idx = u2(code, p + 2)
                        if (idx >= callSiteCount) badRef(codeIdx, pc, "call site", idx, callSiteCount)
                    }
                }
                POOL_METHOD_HANDLE -> {
                    if (methodHandleMap != null) remap16(code, p + 2, methodHandleMap, codeIdx, pc, "method handle")
                    else {
                        val idx = u2(code, p + 2)
                        if (idx >= methodHandleCount) badRef(codeIdx, pc, "method handle", idx, methodHandleCount)
                    }
                }
            }
            val v = VERSION[op]
            if (v > requiredVersion) requiredVersion = v
            pc += size
        }
        return code
    }

    /** Начала инструкций (и payload) в коде [insns]: `true` по смещению в code units. */
    fun instructionStarts(insns: ByteArray, codeIdx: Int): BooleanArray {
        val units = insns.size ushr 1
        val starts = BooleanArray(units + 1)
        var pc = 0
        while (pc < units) {
            starts[pc] = true
            val unit = u2(insns, pc shl 1)
            val op = unit and 0xFF
            pc += if (op == 0 && unit != 0) payloadSize(insns, pc, units, unit, codeIdx) else SIZE[op].also { if (it == 0) fail(codeIdx, pc, "неизвестный опкод") }
        }
        starts[units] = true
        return starts
    }

    private fun payloadSize(code: ByteArray, pc: Int, units: Int, unit: Int, codeIdx: Int): Int {
        val p = pc shl 1
        val size: Long = when (unit) {
            0x0100 -> { // packed-switch-payload: ident, size, first_key(2), targets(2*size)
                if (pc + 2 > units) fail(codeIdx, pc, "packed-switch-payload обрезан")
                4L + 2L * u2(code, p + 2)
            }
            0x0200 -> { // sparse-switch-payload: ident, size, keys(2*size), targets(2*size)
                if (pc + 2 > units) fail(codeIdx, pc, "sparse-switch-payload обрезан")
                2L + 4L * u2(code, p + 2)
            }
            0x0300 -> { // fill-array-data-payload: ident, width, size(2), data
                if (pc + 4 > units) fail(codeIdx, pc, "fill-array-data-payload обрезан")
                val width = u2(code, p + 2).toLong()
                val count = u4(code, p + 4).toLong() and 0xFFFFFFFFL
                4L + (width * count + 1L) / 2L
            }
            else -> fail(codeIdx, pc, "nop с ненулевым старшим байтом (0x${Integer.toHexString(unit)})")
        }
        if (pc + size > units) fail(codeIdx, pc, "payload (${size} юнитов) выходит за конец insns")
        return size.toInt()
    }

    private fun remap16(code: ByteArray, p: Int, map: IntArray, codeIdx: Int, pc: Int, what: String) {
        val old = u2(code, p)
        if (old >= map.size) badRef(codeIdx, pc, what, old, map.size)
        val new = map[old]
        if (new < 0) unmarked(codeIdx, pc, what, old)
        if (new > 0xFFFF) {
            if (map === stringMap) stringOverflow?.invoke(codeIdx, pc)
            val hint = if (map === stringMap) " (нужен const-string/jumbo)" else ""
            fail(codeIdx, pc, "индекс $what $new после сортировки пулов не помещается в 16 бит$hint")
        }
        code[p] = new.toByte()
        code[p + 1] = (new ushr 8).toByte()
    }

    /** Запись пула не попала в сжатую запись ([LiveIds]), а байткод на неё ссылается. */
    private fun unmarked(codeIdx: Int, pc: Int, what: String, idx: Int): Nothing =
        throw IllegalStateException("Байткод ${describe(codeIdx)}, смещение 0x${Integer.toHexString(pc)}: индекс $what $idx не отмечен разметкой используемого")

    private fun badRef(codeIdx: Int, pc: Int, what: String, idx: Int, size: Int): Nothing =
        fail(codeIdx, pc, "индекс $what $idx вне пула (размер $size)")

    private fun fail(codeIdx: Int, pc: Int, message: String): Nothing =
        throw IllegalArgumentException("Байткод ${describe(codeIdx)}, смещение 0x${Integer.toHexString(pc)}: $message")

    private companion object {
        const val POOL_NONE = 0
        const val POOL_STRING = 1
        const val POOL_TYPE = 2
        const val POOL_FIELD = 3
        const val POOL_METHOD = 4
        const val POOL_PROTO = 5
        const val POOL_CALL_SITE = 6
        const val POOL_METHOD_HANDLE = 7

        val OP_CONST_STRING_JUMBO = Opcode.CONST_STRING_JUMBO.value
        val OP_INVOKE_POLYMORPHIC = Opcode.INVOKE_POLYMORPHIC.value
        val OP_INVOKE_POLYMORPHIC_RANGE = Opcode.INVOKE_POLYMORPHIC_RANGE.value

        /** Размер инструкции в юнитах по опкоду; 0 — неиспользуемый опкод. */
        val SIZE = IntArray(256)
        val POOL = IntArray(256)
        val VERSION = IntArray(256)

        init {
            for (op in Opcode.entries) {
                SIZE[op.value] = op.sizeUnits
                VERSION[op.value] = op.version
                POOL[op.value] = when (op.indexPool) {
                    IndexPool.NONE -> POOL_NONE
                    IndexPool.STRING -> POOL_STRING
                    IndexPool.TYPE -> POOL_TYPE
                    IndexPool.FIELD -> POOL_FIELD
                    IndexPool.METHOD -> POOL_METHOD
                    IndexPool.PROTO -> POOL_PROTO
                    IndexPool.CALL_SITE -> POOL_CALL_SITE
                    IndexPool.METHOD_HANDLE -> POOL_METHOD_HANDLE
                }
                if (op.indexPool != IndexPool.NONE && op.indexOffset != 1) {
                    throw IllegalStateException("Opcode ${op.mnemonic}: индекс ожидается в юните 1")
                }
            }
        }

        fun u2(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

        fun u4(b: ByteArray, p: Int): Int = u2(b, p) or (u2(b, p + 2) shl 16)

        fun set4(b: ByteArray, p: Int, v: Int) {
            b[p] = v.toByte()
            b[p + 1] = (v ushr 8).toByte()
            b[p + 2] = (v ushr 16).toByte()
            b[p + 3] = (v ushr 24).toByte()
        }
    }
}
