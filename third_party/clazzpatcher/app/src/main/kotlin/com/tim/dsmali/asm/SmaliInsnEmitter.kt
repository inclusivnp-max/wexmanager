package com.tim.dsmali.asm

import com.tim.dasm.dsl.InstructionsDsl
import com.tim.dasm.model.*
import com.tim.dex.data.IndexPool
import com.tim.dex.data.InsnFormat
import com.tim.dex.data.Opcode
import com.tim.dsmali.model.MethodContext

/**
 * Инструкция smali (текст `SmaliInsn.text`) → инструкция DEX в [InstructionsDsl].
 *
 * Литералы проверяются по правилам smali: `const/4` — -8..15, `/lit8` — -128..255,
 * `const/16` и `/lit16` — -32768..65535 (беззнаковая запись допустима), `const/high16` —
 * полное 32-битное значение с нулевыми младшими 16 битами, `const-wide/high16` — полное
 * 64-битное значение с нулевыми младшими 48 битами.
 */
class SmaliInsnEmitter(
    private val refs: SmaliRefs,
    private val code: InstructionsDsl,
    method: MethodContext
) {
    private val paramBase = if (method.registersSize >= method.insSize) method.registersSize - method.insSize else -1
    private val paramRegs = method.insSize

    companion object {
        private val OPCODES: Map<String, Opcode> = HashMap<String, Opcode>().apply {
            for (op in Opcode.entries) put(op.mnemonic, op)
        }

        fun opcodeByName(mnemonic: String): Opcode? = OPCODES[mnemonic]
    }

    fun emit(text: String) {
        val t = text.trim()
        if (t.startsWith(".")) emitPayload(t) else emitInstruction(t)
    }

    private fun reg(s: SmaliScanner): Int = s.register(paramBase, paramRegs)

    /** Остаток строки до комментария (ссылка на метод/поле/тип/call site). */
    private fun rest(s: SmaliScanner): String {
        s.skipWs()
        val text = s.text
        var end = s.pos
        var quote = '\u0000'
        while (end < text.length) {
            val c = text[end]
            if (quote != '\u0000') {
                if (c == '\\') end++ else if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '#') break
            end++
        }
        val r = text.substring(s.pos, minOf(end, text.length)).trim()
        s.pos = text.length
        if (r.isEmpty()) s.error("Ожидалась ссылка")
        return r
    }

    private fun emitInstruction(t: String) {
        var sp = 0
        // Мнемоника кончается пробелом или `:` (эталонный smali принимает `goto: метка`).
        while (sp < t.length && !t[sp].isWhitespace() && t[sp] != ':') sp++
        val mnemonic = t.substring(0, sp)
        val op = OPCODES[mnemonic] ?: throw IllegalArgumentException("Неизвестная инструкция $mnemonic")
        val s = SmaliScanner(t, sp)

        when (op.format) {
            InsnFormat.FORMAT_10x -> code.emit(Ins10x(op))
            InsnFormat.FORMAT_12x -> { val a = reg(s); s.expect(','); code.emit(Ins12x(op, a, reg(s))) }
            InsnFormat.FORMAT_11n -> {
                val a = reg(s); s.expect(',')
                var v = shortIntegral(s.word()).toInt()
                if (v < -8 || v > 15) s.error("Литерал $v не помещается в 4 бита")
                if (v > 7) v -= 16
                code.emit(Ins11n(op, a, v))
            }
            InsnFormat.FORMAT_11x -> code.emit(Ins11x(op, reg(s)))
            InsnFormat.FORMAT_10t, InsnFormat.FORMAT_20t, InsnFormat.FORMAT_30t -> code.branch(op, s.label())
            InsnFormat.FORMAT_22x -> { val a = reg(s); s.expect(','); code.emit(Ins22x(op, a, reg(s))) }
            InsnFormat.FORMAT_21t -> { val a = reg(s); s.expect(','); code.branch(op, s.label(), a) }
            InsnFormat.FORMAT_21s -> { val a = reg(s); s.expect(','); code.emit(Ins21s(op, a, shortIntegral(s.word()).toInt())) }
            InsnFormat.FORMAT_21h -> {
                val a = reg(s); s.expect(',')
                val w = s.word()
                if (op == Opcode.CONST_WIDE_HIGH16) {
                    val v = fixed64(w)
                    if (v and 0xFFFFFFFFFFFFL != 0L) s.error("У const-wide/high16 младшие 48 бит значения $w не нулевые")
                    code.emit(Ins21h(op, a, (v ushr 48).toInt()))
                } else {
                    val v = fixed32(w)
                    if (v and 0xFFFF != 0) s.error("У const/high16 младшие 16 бит значения $w не нулевые")
                    code.emit(Ins21h(op, a, v ushr 16))
                }
            }
            InsnFormat.FORMAT_21c -> {
                val a = reg(s); s.expect(',')
                val idx = when (op.indexPool) {
                    IndexPool.STRING -> refs.string(s.stringLiteralRaw())
                    IndexPool.TYPE -> refs.type(rest(s))
                    IndexPool.FIELD -> refs.field(rest(s))
                    IndexPool.METHOD_HANDLE -> refs.methodHandle(rest(s))
                    IndexPool.PROTO -> refs.proto(rest(s))
                    else -> s.error("Неожиданный пул индекса ${op.indexPool}")
                }
                code.emit(Ins21c(op, a, idx))
            }
            InsnFormat.FORMAT_23x -> {
                val a = reg(s); s.expect(','); val b = reg(s); s.expect(',')
                code.emit(Ins23x(op, a, b, reg(s)))
            }
            InsnFormat.FORMAT_22b -> {
                val a = reg(s); s.expect(','); val b = reg(s); s.expect(',')
                code.emit(Ins22b(op, a, b, byteIntegral(s.word()).toInt()))
            }
            InsnFormat.FORMAT_22t -> {
                val a = reg(s); s.expect(','); val b = reg(s); s.expect(',')
                code.branch(op, s.label(), a, b)
            }
            InsnFormat.FORMAT_22s -> {
                val a = reg(s); s.expect(','); val b = reg(s); s.expect(',')
                code.emit(Ins22s(op, a, b, shortIntegral(s.word()).toInt()))
            }
            InsnFormat.FORMAT_22c -> {
                val a = reg(s); s.expect(','); val b = reg(s); s.expect(',')
                val idx = if (op.indexPool == IndexPool.TYPE) refs.type(rest(s)) else refs.field(rest(s))
                code.emit(Ins22c(op, a, b, idx))
            }
            InsnFormat.FORMAT_32x -> { val a = reg(s); s.expect(','); code.emit(Ins32x(op, a, reg(s))) }
            InsnFormat.FORMAT_31i -> { val a = reg(s); s.expect(','); code.emit(Ins31i(op, a, fixed32(s.word()))) }
            InsnFormat.FORMAT_31t -> { val a = reg(s); s.expect(','); code.branch(op, s.label(), a) }
            InsnFormat.FORMAT_31c -> { val a = reg(s); s.expect(','); code.emit(Ins31c(op, a, refs.string(s.stringLiteralRaw()))) }
            InsnFormat.FORMAT_35c -> {
                val regs = regList(s)
                s.expect(',')
                val idx = when (op.indexPool) {
                    IndexPool.TYPE -> refs.type(rest(s))
                    IndexPool.CALL_SITE -> refs.callSite(rest(s))
                    else -> refs.method(rest(s))
                }
                code.emit(make35c(op, idx, regs))
            }
            InsnFormat.FORMAT_3rc -> {
                val (start, count) = regRange(s)
                s.expect(',')
                val idx = when (op.indexPool) {
                    IndexPool.TYPE -> refs.type(rest(s))
                    IndexPool.CALL_SITE -> refs.callSite(rest(s))
                    else -> refs.method(rest(s))
                }
                code.emit(Ins3rc(op, start, count, idx))
            }
            InsnFormat.FORMAT_45cc -> {
                val regs = regList(s)
                s.expect(',')
                val methodIdx = refs.method(s)
                s.expect(',')
                val protoIdx = refs.proto(rest(s))
                if (regs.size > 5) s.error("Больше 5 регистров")
                code.emit(
                    Ins45cc(
                        op, regs.size, methodIdx, protoIdx,
                        regs.getOrElse(4) { 0 }, regs.getOrElse(2) { 0 }, regs.getOrElse(3) { 0 },
                        regs.getOrElse(0) { 0 }, regs.getOrElse(1) { 0 }
                    )
                )
            }
            InsnFormat.FORMAT_4rcc -> {
                val (start, count) = regRange(s)
                s.expect(',')
                val methodIdx = refs.method(s)
                s.expect(',')
                code.emit(Ins4rcc(op, start, count, methodIdx, refs.proto(rest(s))))
            }
            InsnFormat.FORMAT_51l -> { val a = reg(s); s.expect(','); code.emit(Ins51l(op, a, fixed64(s.word()))) }
            else -> s.error("Формат ${op.format} не поддерживается")
        }
        s.expectEnd()
    }

    private fun make35c(op: Opcode, idx: Int, regs: IntArray): Ins35c {
        if (regs.size > 5) throw IllegalArgumentException("${op.mnemonic}: больше 5 регистров")
        return Ins35c(
            op, regs.size, idx,
            regs.getOrElse(4) { 0 }, regs.getOrElse(2) { 0 }, regs.getOrElse(3) { 0 },
            regs.getOrElse(0) { 0 }, regs.getOrElse(1) { 0 }
        )
    }

    private fun regList(s: SmaliScanner): IntArray {
        s.expect('{')
        if (s.tryConsume('}')) return IntArray(0)
        val out = IntArray(5)
        var n = 0
        while (true) {
            val r = reg(s)
            if (n == out.size) s.error("Больше 5 регистров в списке")
            out[n++] = r
            if (s.tryConsume('}')) break
            s.expect(',')
        }
        return out.copyOf(n)
    }

    private fun regRange(s: SmaliScanner): Pair<Int, Int> {
        s.expect('{')
        if (s.tryConsume('}')) return 0 to 0
        val first = reg(s)
        val last = if (s.tryConsume("..")) reg(s) else first
        s.expect('}')
        if (last < first) s.error("Некорректный диапазон регистров")
        return first to (last - first + 1)
    }

    // ---- Литералы ----

    private fun lit(w: String): SmaliLiterals.Literal = SmaliLiterals.parse(w)

    /** integral литерал в позиции short: -32768..65535 → short. */
    private fun shortIntegral(w: String): Short {
        val l = lit(w)
        return when (l.kind) {
            SmaliLiterals.Kind.SHORT, SmaliLiterals.Kind.BYTE -> l.bits.toShort()
            SmaliLiterals.Kind.CHAR -> l.bits.toInt().toShort()
            SmaliLiterals.Kind.INT, SmaliLiterals.Kind.LONG -> {
                if (l.bits > 0xFFFF || l.bits < -0x8000) throw IllegalArgumentException("Литерал $w не помещается в short")
                l.bits.toShort()
            }
            else -> throw IllegalArgumentException("Ожидался целочисленный литерал: $w")
        }
    }

    /** integral литерал в позиции byte: -128..255 → byte. */
    private fun byteIntegral(w: String): Byte {
        val l = lit(w)
        return when (l.kind) {
            SmaliLiterals.Kind.BYTE -> l.bits.toByte()
            SmaliLiterals.Kind.INT, SmaliLiterals.Kind.LONG, SmaliLiterals.Kind.SHORT, SmaliLiterals.Kind.CHAR -> {
                if (l.bits > 0xFF || l.bits < -0x80) throw IllegalArgumentException("Литерал $w не помещается в byte")
                l.bits.toByte()
            }
            else -> throw IllegalArgumentException("Ожидался целочисленный литерал: $w")
        }
    }

    /** fixed_32bit_literal → int (float — raw bits). */
    private fun fixed32(w: String): Int {
        val l = lit(w)
        return when (l.kind) {
            SmaliLiterals.Kind.LONG -> {
                if (l.bits > 0xFFFFFFFFL || l.bits < -0x80000000L) throw IllegalArgumentException("Литерал $w не помещается в int")
                l.bits.toInt()
            }
            SmaliLiterals.Kind.DOUBLE -> throw IllegalArgumentException("double-литерал $w в 32-битной инструкции")
            else -> l.bits.toInt()
        }
    }

    /** fixed_64bit_literal → long (int и float расширяются со знаком, double — raw bits). */
    private fun fixed64(w: String): Long {
        val l = lit(w)
        return when (l.kind) {
            SmaliLiterals.Kind.FLOAT -> l.bits.toInt().toLong()
            else -> l.bits
        }
    }

    // ---- Payload'ы ----

    private fun emitPayload(t: String) {
        val s = SmaliScanner(t)
        when {
            s.tryConsume(".packed-switch") -> {
                val firstKey = fixed32(s.word())
                val targets = ArrayList<String>()
                while (!s.tryConsume(".end packed-switch")) {
                    if (s.atEnd()) s.error("Нет .end packed-switch")
                    targets.add(s.label())
                }
                s.expectEnd()
                code.packedSwitchPayload(firstKey, targets)
            }
            s.tryConsume(".sparse-switch") -> {
                val keys = ArrayList<Int>()
                val targets = ArrayList<String>()
                while (!s.tryConsume(".end sparse-switch")) {
                    if (s.atEnd()) s.error("Нет .end sparse-switch")
                    keys.add(fixed32(s.word()))
                    if (!s.tryConsume("->")) s.error("Ожидалось '->'")
                    targets.add(s.label())
                }
                s.expectEnd()
                code.sparseSwitchPayload(keys.toIntArray(), targets)
            }
            s.tryConsume(".array-data") -> {
                val width = SmaliLiterals.parseInt(s.word())
                if (width != 1 && width != 2 && width != 4 && width != 8) s.error("Недопустимая ширина элемента .array-data: $width")
                val out = java.io.ByteArrayOutputStream()
                var count = 0
                while (!s.tryConsume(".end array-data")) {
                    if (s.atEnd()) s.error("Нет .end array-data")
                    val w = s.word()
                    val v = arrayElement(w, width)
                    for (k in 0 until width) out.write((v ushr (8 * k)).toInt() and 0xFF)
                    count++
                }
                s.expectEnd()
                code.fillArrayDataPayload(width, count, out.toByteArray())
            }
            else -> s.error("Неизвестная директива")
        }
    }

    /** Элемент .array-data: должен помещаться в [width] байт (со знаком или без). */
    private fun arrayElement(w: String, width: Int): Long {
        val l = lit(w)
        val v = when (l.kind) {
            SmaliLiterals.Kind.FLOAT -> l.bits.toInt().toLong()
            else -> l.bits
        }
        if (width < 8) {
            val bits = width * 8
            if (v < -(1L shl (bits - 1)) || v > (1L shl bits) - 1) {
                throw IllegalArgumentException("Элемент $w не помещается в $width байт")
            }
        }
        return v
    }
}
