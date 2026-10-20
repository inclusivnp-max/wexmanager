package com.tim.dex.writer

import com.tim.dex.model.CatchHandler
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.TryItem
import com.tim.dex.model.TypeAddrPair

/**
 * Перекладка кода метода, где `const-string` после сортировки пулов ссылается на строку с индексом
 * больше 65535 (в DEX больше 65536 строк): такие инструкции становятся `const-string/jumbo` (на юнит
 * длиннее), и все адреса пересчитываются — ветвления, switch и fill-array-data с их payload,
 * try/catch и отладочная информация. Ветвление, которому перестало хватать разрядности, расширяется
 * (goto → goto/16 → goto/32; if-* — обратным условием в обход goto/32), перед payload, оказавшимся
 * не на границе 4 байт, вставляется nop. Индексы пулов остаются прежними — их затем перенумеровывает
 * [CodeRemapper] (форма /jumbo держит 32-битный индекс).
 */
internal object JumboStrings {

    class Result(val insns: ByteArray, val tries: List<TryItem>, val handlers: List<CatchHandler>, val debug: DebugInfoItem?)

    private const val OP_CONST_STRING = 0x1a
    private const val OP_CONST_STRING_JUMBO = 0x1b
    private const val OP_FILL_ARRAY_DATA = 0x26
    private const val OP_GOTO = 0x28
    private const val OP_GOTO_16 = 0x29
    private const val OP_GOTO_32 = 0x2a
    private const val OP_PACKED_SWITCH = 0x2b
    private const val OP_SPARSE_SWITCH = 0x2c
    private const val OP_IF_EQ = 0x32
    private const val OP_IF_LE = 0x37
    private const val OP_IF_EQZ = 0x38
    private const val OP_IF_LEZ = 0x3d

    private const val NORMAL = 0
    private const val JUMBO = 1
    private const val GOTO = 2
    private const val IF = 3
    private const val REF31T = 4
    private const val PAYLOAD_PACKED = 5
    private const val PAYLOAD_SPARSE = 6
    private const val PAYLOAD_ARRAY = 7

    /** Есть ли `const-string`, чей индекс строки после ремапа больше 0xFFFF. */
    fun needed(insns: ByteArray, stringMap: IntArray): Boolean {
        val units = insns.size ushr 1
        var pc = 0
        while (pc < units) {
            val unit = u2(insns, pc)
            val op = unit and 0xFF
            if (op == 0 && unit != 0) {
                pc += payloadSize(insns, pc, units, unit) ?: return false
                continue
            }
            if (op == OP_CONST_STRING && pc + 1 < units) {
                val old = u2(insns, pc + 1)
                if (old < stringMap.size && stringMap[old] > 0xFFFF) return true
            }
            val size = SIZE[op]
            if (size == 0) return false // ошибку сообщит CodeRemapper
            pc += size
        }
        return false
    }

    fun widen(
        insns: ByteArray,
        stringMap: IntArray,
        tries: List<TryItem>,
        handlers: List<CatchHandler>,
        debug: DebugInfoItem?,
        describe: () -> String
    ): Result {
        fun fail(message: String): Nothing = throw IllegalArgumentException("Байткод ${describe()}: $message (перекладка для const-string/jumbo)")

        val oldUnits = insns.size ushr 1
        // ---- разбор ----
        val pcs = IntList2()
        run {
            var pc = 0
            while (pc < oldUnits) {
                pcs.add(pc)
                val unit = u2(insns, pc)
                val op = unit and 0xFF
                pc += if (op == 0 && unit != 0) payloadSize(insns, pc, oldUnits, unit) ?: fail("некорректный payload по адресу $pc")
                else SIZE[op].takeIf { it > 0 } ?: fail("неизвестный опкод 0x${Integer.toHexString(op)} по адресу $pc")
            }
            if (pc != oldUnits) fail("инструкция по адресу ${pcs.last()} выходит за конец кода")
        }
        val n = pcs.size
        val oldPc = pcs.toArray()
        val oldSize = IntArray(n) { (if (it + 1 < n) oldPc[it + 1] else oldUnits) - oldPc[it] }
        val index = IntArray(oldUnits + 1) { -1 }
        for (i in 0 until n) index[oldPc[i]] = i
        index[oldUnits] = n
        fun at(addr: Int, what: String): Int {
            val i = if (addr in 0..oldUnits) index[addr] else -1
            if (i < 0) fail("$what указывает не на начало инструкции (адрес $addr)")
            return i
        }

        val kind = IntArray(n)
        val target = IntArray(n) { -1 }
        for (i in 0 until n) {
            val pc = oldPc[i]
            val unit = u2(insns, pc)
            val op = unit and 0xFF
            when {
                op == 0 && unit != 0 -> kind[i] = when (unit) {
                    0x0100 -> PAYLOAD_PACKED
                    0x0200 -> PAYLOAD_SPARSE
                    else -> PAYLOAD_ARRAY
                }
                op == OP_CONST_STRING -> {
                    val old = u2(insns, pc + 1)
                    if (old < stringMap.size && stringMap[old] > 0xFFFF) kind[i] = JUMBO
                }
                op == OP_GOTO -> { kind[i] = GOTO; target[i] = at(pc + (unit shr 8).toByte(), "goto") }
                op == OP_GOTO_16 -> { kind[i] = GOTO; target[i] = at(pc + u2(insns, pc + 1).toShort(), "goto/16") }
                op == OP_GOTO_32 -> { kind[i] = GOTO; target[i] = at(pc + s4(insns, pc + 1), "goto/32") }
                op in OP_IF_EQ..OP_IF_LEZ -> { kind[i] = IF; target[i] = at(pc + u2(insns, pc + 1).toShort(), "if") }
                op == OP_PACKED_SWITCH || op == OP_SPARSE_SWITCH || op == OP_FILL_ARRAY_DATA -> {
                    kind[i] = REF31T
                    target[i] = at(pc + s4(insns, pc + 1), "ссылка на payload")
                }
            }
        }
        // switch → payload: цели payload отсчитываются от switch, который на него ссылается.
        val switchOf = IntArray(n) { -1 }
        val switchTargets = arrayOfNulls<IntArray>(n)
        for (i in 0 until n) {
            if (kind[i] != REF31T) continue
            val p = target[i]
            val op = u2(insns, oldPc[i]) and 0xFF
            val expected = when (op) {
                OP_PACKED_SWITCH -> PAYLOAD_PACKED
                OP_SPARSE_SWITCH -> PAYLOAD_SPARSE
                else -> PAYLOAD_ARRAY
            }
            if (kind[p] != expected) fail("инструкция по адресу ${oldPc[i]} ссылается не на свой payload")
            if (expected == PAYLOAD_ARRAY) continue
            val base = oldPc[i]
            val pp = oldPc[p]
            val count = u2(insns, pp + 1)
            val first = if (expected == PAYLOAD_PACKED) pp + 4 else pp + 2 + 2 * count
            val targets = IntArray(count) { k -> at(base + s4(insns, first + 2 * k), "цель switch") }
            val prev = switchTargets[p]
            if (prev == null) {
                switchTargets[p] = targets
                switchOf[p] = i
            } else if (!prev.contentEquals(targets)) {
                fail("payload по адресу $pp используют несколько switch с разными целями")
            }
        }

        // ---- раскладка: размеры только растут, поэтому итерации сходятся ----
        val newSize = IntArray(n) { if (kind[it] == JUMBO) 3 else oldSize[it] }
        val pad = BooleanArray(n)
        val newPc = IntArray(n + 1)
        while (true) {
            var pc = 0
            for (i in 0 until n) {
                pad[i] = kind[i] >= PAYLOAD_PACKED && (pc and 1) != 0
                if (pad[i]) pc++
                newPc[i] = pc
                pc += newSize[i]
            }
            newPc[n] = pc
            var changed = false
            for (i in 0 until n) {
                val delta = newPc[target[i].coerceAtLeast(0)] - newPc[i]
                when (kind[i]) {
                    GOTO -> {
                        val need = when {
                            delta != 0 && delta in -128..127 -> 1
                            delta != 0 && delta in -32768..32767 -> 2
                            else -> 3
                        }
                        if (need > newSize[i]) { newSize[i] = need; changed = true }
                    }
                    IF -> if ((delta == 0 || delta !in -32768..32767) && newSize[i] == 2) { newSize[i] = 5; changed = true }
                }
            }
            if (!changed) break
        }
        val total = newPc[n]

        // ---- запись ----
        val out = ByteArray(total * 2)
        for (i in 0 until n) {
            val o = newPc[i]
            val pc = oldPc[i]
            val unit = u2(insns, pc)
            when (kind[i]) {
                JUMBO -> {
                    set2(out, o, OP_CONST_STRING_JUMBO or (unit and 0xFF00))
                    set4(out, o + 1, u2(insns, pc + 1))
                }
                GOTO -> {
                    val delta = newPc[target[i]] - o
                    when (newSize[i]) {
                        1 -> set2(out, o, OP_GOTO or ((delta and 0xFF) shl 8))
                        2 -> { set2(out, o, OP_GOTO_16); set2(out, o + 1, delta and 0xFFFF) }
                        else -> { set2(out, o, OP_GOTO_32); set4(out, o + 1, delta) }
                    }
                }
                IF -> {
                    val delta = newPc[target[i]] - o
                    if (newSize[i] == 2) {
                        set2(out, o, unit)
                        set2(out, o + 1, delta and 0xFFFF)
                    } else {
                        // Обратное условие перепрыгивает goto/32 на исходную цель.
                        set2(out, o, (unit and 0xFF00) or invert(unit and 0xFF))
                        set2(out, o + 1, 5)
                        set2(out, o + 2, OP_GOTO_32)
                        set4(out, o + 3, newPc[target[i]] - (o + 2))
                    }
                }
                REF31T -> {
                    set2(out, o, unit)
                    set4(out, o + 1, newPc[target[i]] - o)
                }
                PAYLOAD_PACKED, PAYLOAD_SPARSE -> {
                    System.arraycopy(insns, pc * 2, out, o * 2, oldSize[i] * 2)
                    val targets = switchTargets[i]
                    if (targets != null) {
                        val count = targets.size
                        val first = if (kind[i] == PAYLOAD_PACKED) o + 4 else o + 2 + 2 * count
                        val base = newPc[switchOf[i]]
                        for (k in 0 until count) set4(out, first + 2 * k, newPc[targets[k]] - base)
                    }
                }
                else -> System.arraycopy(insns, pc * 2, out, o * 2, oldSize[i] * 2)
            }
        }

        // ---- адреса вне кода: try/catch и отладка ----
        fun map(addr: Int): Int {
            if (addr >= oldUnits) return total + (addr - oldUnits)
            if (addr < 0) return addr
            var i = index[addr]
            if (i < 0) {
                var lo = 0
                var hi = n - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) ushr 1
                    if (oldPc[mid] <= addr) lo = mid else hi = mid - 1
                }
                i = lo
                return newPc[i] + minOf(addr - oldPc[i], newSize[i] - 1)
            }
            return newPc[i]
        }

        val newTries = tries.map { t ->
            val start = map(t.startAddr)
            TryItem(start, map(t.startAddr + t.insnCount) - start, t.handlerIdx)
        }
        val newHandlers = handlers.map { h ->
            CatchHandler(h.handlers.map { TypeAddrPair(it.typeIdx, map(it.addr)) }, if (h.catchAllAddr < 0) h.catchAllAddr else map(h.catchAllAddr))
        }
        val newDebug = debug?.let { d ->
            var addr = 0
            val ops = ArrayList<DebugOpcode>(d.opcodes.size + 8)
            for (op in d.opcodes) {
                when (op) {
                    is DebugOpcode.AdvancePc -> {
                        ops.add(DebugOpcode.AdvancePc(map(addr + op.addressDelta) - map(addr)))
                        addr += op.addressDelta
                    }
                    is DebugOpcode.SpecialOpcode -> {
                        val delta = map(addr + op.addressDelta) - map(addr)
                        if (delta == op.addressDelta) {
                            ops.add(op)
                        } else {
                            // Сдвиг вырос: один специальный опкод на весь сдвиг (как у d8), а если не помещается —
                            // DBG_ADVANCE_PC сразу к началу инструкции и опкод строки без сдвига. Промежуточный
                            // адрес внутри инструкции (ADVANCE_PC на прирост перед старым опкодом) d8 не читает.
                            val combined = special(op.lineDelta, delta)
                            if (combined >= 0) {
                                ops.add(DebugOpcode.SpecialOpcode(combined, op.lineDelta, delta))
                            } else {
                                ops.add(DebugOpcode.AdvancePc(delta))
                                ops.add(DebugOpcode.SpecialOpcode(special(op.lineDelta, 0), op.lineDelta, 0))
                            }
                        }
                        addr += op.addressDelta
                    }
                    else -> ops.add(op)
                }
            }
            d.copy(opcodes = ops)
        }
        return Result(out, newTries, newHandlers, newDebug)
    }

    /** Специальный опкод отладки (DBG_FIRST_SPECIAL 0x0a, line_base −4, line_range 15) или −1, если не помещается. */
    private fun special(lineDelta: Int, addressDelta: Int): Int {
        if (lineDelta !in -4..10 || addressDelta < 0) return -1
        val opcode = 0x0a + (lineDelta + 4) + 15 * addressDelta
        return if (opcode <= 0xFF) opcode else -1
    }

    /** Обратное условие if-*: eq↔ne, lt↔ge, gt↔le (так же для *z). */
    private fun invert(op: Int): Int = if (op <= OP_IF_LE) OP_IF_EQ + ((op - OP_IF_EQ) xor 1) else OP_IF_EQZ + ((op - OP_IF_EQZ) xor 1)

    /** Размер payload в юнитах или `null`, если он некорректен. */
    private fun payloadSize(b: ByteArray, pc: Int, units: Int, unit: Int): Int? {
        val size: Long = when (unit) {
            0x0100 -> if (pc + 2 > units) return null else 4L + 2L * u2(b, pc + 1)
            0x0200 -> if (pc + 2 > units) return null else 2L + 4L * u2(b, pc + 1)
            0x0300 -> {
                if (pc + 4 > units) return null
                val width = u2(b, pc + 1).toLong()
                val count = s4(b, pc + 2).toLong() and 0xFFFFFFFFL
                4L + (width * count + 1L) / 2L
            }
            else -> return null
        }
        return if (pc + size > units) null else size.toInt()
    }

    private val SIZE = IntArray(256).also { s ->
        for (op in com.tim.dex.data.Opcode.entries) s[op.value] = op.sizeUnits
    }

    /** Юнит [unit] (не байт). */
    private fun u2(b: ByteArray, unit: Int): Int = (b[unit * 2].toInt() and 0xFF) or ((b[unit * 2 + 1].toInt() and 0xFF) shl 8)

    private fun s4(b: ByteArray, unit: Int): Int = u2(b, unit) or (u2(b, unit + 1) shl 16)

    private fun set2(b: ByteArray, unit: Int, v: Int) {
        b[unit * 2] = v.toByte()
        b[unit * 2 + 1] = (v ushr 8).toByte()
    }

    private fun set4(b: ByteArray, unit: Int, v: Int) {
        set2(b, unit, v and 0xFFFF)
        set2(b, unit + 1, v ushr 16)
    }

    private class IntList2 {
        private var a = IntArray(64)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun last(): Int = a[size - 1]
        fun toArray(): IntArray = a.copyOf(size)
    }
}
