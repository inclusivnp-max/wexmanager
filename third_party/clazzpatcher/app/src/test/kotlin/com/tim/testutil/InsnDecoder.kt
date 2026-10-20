package com.tim.testutil

import java.util.Locale

/**
 * Независимый от продуктового кода декодер байткода Dalvik для тестов.
 *
 * Печатает каждую инструкцию с адресом и операндами; индексы пулов разрешаются через [Refs],
 * поэтому результат не зависит от порядка пулов. Неизвестные опкоды и обрезанные инструкции —
 * ошибка (без молчаливых пропусков).
 */
class InsnDecoder(private val refs: Refs) {
    /** Разрешение индексов пулов в текст. */
    interface Refs {
        fun string(idx: Int): String
        fun type(idx: Int): String
        fun field(idx: Int): String
        fun method(idx: Int): String
        fun proto(idx: Int): String
        fun callSite(idx: Int): String
        fun methodHandle(idx: Int): String
    }

    private enum class Fmt(val units: Int) {
        F10x(1), F12x(1), F11n(1), F11x(1), F10t(1),
        F20t(2), F22x(2), F21t(2), F21s(2), F21h(2), F21c(2), F23x(2), F22b(2), F22t(2), F22s(2), F22c(2),
        F30t(3), F32x(3), F31i(3), F31t(3), F31c(3), F35c(3), F3rc(3),
        F45cc(4), F4rcc(4), F51l(5)
    }

    private enum class Kind { NONE, STRING, TYPE, FIELD, METHOD, CALL_SITE, METHOD_HANDLE, PROTO }

    private class Op(val name: String, val fmt: Fmt, val kind: Kind = Kind.NONE)

    /**
     * Разобранный код: строки инструкций (адрес: мнемоника операнды) и наибольшее число регистров-аргументов
     * среди invoke-* (нижняя граница outs_size).
     */
    class Decoded(val lines: List<String>, val invokeRegs: Int)

    fun decode(code: ByteArray): Decoded {
        require(code.size % 2 == 0) { "размер insns нечётный: ${code.size} байт" }
        val n = code.size / 2
        fun u(i: Int): Int {
            if (i >= n) throw IllegalStateException("инструкция выходит за конец кода (юнит $i из $n)")
            return (code[2 * i].toInt() and 0xFF) or ((code[2 * i + 1].toInt() and 0xFF) shl 8)
        }
        fun s16(i: Int): Int = u(i).toShort().toInt()
        fun i32(i: Int): Int = u(i) or (u(i + 1) shl 16)

        val lines = ArrayList<String>()
        var invokeRegs = 0
        var pc = 0
        while (pc < n) {
            val unit = u(pc)
            val opByte = unit and 0xFF
            val hi = unit ushr 8
            val sb = StringBuilder(fmt("%04x: ", pc))
            if (opByte == 0 && hi in 1..3) {
                pc += decodePayload(hi, pc, sb, ::u, ::i32, n)
                lines.add(sb.toString())
                continue
            }
            val op = OPS[opByte] ?: throw IllegalStateException(fmt("неизвестный опкод 0x%02x по адресу 0x%04x", opByte, pc))
            if (op.fmt == Fmt.F10x && hi != 0) {
                throw IllegalStateException(fmt("%s с ненулевым старшим байтом 0x%02x по адресу 0x%04x", op.name, hi, pc))
            }
            sb.append(op.name)
            val a4 = hi and 0xF
            val b4 = hi ushr 4
            fun target(off: Int) = fmt("-> %04x", pc + off)
            when (op.fmt) {
                Fmt.F10x -> {}
                Fmt.F12x -> sb.append(" v$a4, v$b4")
                Fmt.F11n -> sb.append(" v$a4, #${(b4 shl 28) shr 28}")
                Fmt.F11x -> sb.append(" v$hi")
                Fmt.F10t -> sb.append(" ").append(target(hi.toByte().toInt()))
                Fmt.F20t -> sb.append(" ").append(target(s16(pc + 1)))
                Fmt.F22x -> sb.append(" v$hi, v${u(pc + 1)}")
                Fmt.F21t -> sb.append(" v$hi, ").append(target(s16(pc + 1)))
                Fmt.F21s -> sb.append(" v$hi, #${s16(pc + 1)}")
                Fmt.F21h -> {
                    val v = if (opByte == 0x19) (s16(pc + 1).toLong() shl 48) else (s16(pc + 1).toLong() shl 16)
                    sb.append(" v$hi, #$v")
                }
                Fmt.F21c -> sb.append(" v$hi, ").append(ref(op.kind, u(pc + 1)))
                Fmt.F23x -> { val bc = u(pc + 1); sb.append(" v$hi, v${bc and 0xFF}, v${bc ushr 8}") }
                Fmt.F22b -> { val bc = u(pc + 1); sb.append(" v$hi, v${bc and 0xFF}, #${(bc ushr 8).toByte().toInt()}") }
                Fmt.F22t -> sb.append(" v$a4, v$b4, ").append(target(s16(pc + 1)))
                Fmt.F22s -> sb.append(" v$a4, v$b4, #${s16(pc + 1)}")
                Fmt.F22c -> sb.append(" v$a4, v$b4, ").append(ref(op.kind, u(pc + 1)))
                Fmt.F30t -> sb.append(" ").append(target(i32(pc + 1)))
                Fmt.F32x -> sb.append(" v${u(pc + 1)}, v${u(pc + 2)}")
                Fmt.F31i -> sb.append(" v$hi, #${i32(pc + 1)}")
                Fmt.F31t -> sb.append(" v$hi, payload ").append(target(i32(pc + 1)))
                Fmt.F31c -> sb.append(" v$hi, ").append(ref(op.kind, i32(pc + 1)))
                Fmt.F35c, Fmt.F45cc -> {
                    val count = b4
                    if (op.kind != Kind.TYPE) invokeRegs = maxOf(invokeRegs, count)
                    if (count > 5) throw IllegalStateException(fmt("%s: %d регистров (> 5) по адресу 0x%04x", op.name, count, pc))
                    val regs = u(pc + 2)
                    val list = listOf(regs and 0xF, (regs ushr 4) and 0xF, (regs ushr 8) and 0xF, regs ushr 12, a4).take(count)
                    sb.append(" {").append(list.joinToString(", ") { "v$it" }).append("}, ").append(ref(op.kind, u(pc + 1)))
                    if (op.fmt == Fmt.F45cc) sb.append(", ").append(ref(Kind.PROTO, u(pc + 3)))
                }
                Fmt.F3rc, Fmt.F4rcc -> {
                    val count = hi
                    if (op.kind != Kind.TYPE) invokeRegs = maxOf(invokeRegs, count)
                    val first = u(pc + 2)
                    sb.append(if (count == 0) " {}" else " {v$first .. v${first + count - 1}}").append(", ").append(ref(op.kind, u(pc + 1)))
                    if (op.fmt == Fmt.F4rcc) sb.append(", ").append(ref(Kind.PROTO, u(pc + 3)))
                }
                Fmt.F51l -> {
                    var v = 0L
                    for (k in 0 until 4) v = v or (u(pc + 1 + k).toLong() shl (16 * k))
                    sb.append(" v$hi, #$v")
                }
            }
            // Проверяем, что инструкция целиком помещается в код
            u(pc + op.fmt.units - 1)
            pc += op.fmt.units
            lines.add(sb.toString())
        }
        return Decoded(lines, invokeRegs)
    }

    private fun decodePayload(ident: Int, pc: Int, sb: StringBuilder, u: (Int) -> Int, i32: (Int) -> Int, n: Int): Int {
        return when (ident) {
            1 -> {
                val size = u(pc + 1)
                val firstKey = i32(pc + 2)
                val targets = List(size) { i32(pc + 4 + 2 * it) }
                sb.append("packed-switch-payload first_key=$firstKey targets=").append(targets.joinToString(", ", "[", "]") { "+$it" })
                4 + size * 2
            }
            2 -> {
                val size = u(pc + 1)
                val keys = List(size) { i32(pc + 2 + 2 * it) }
                val targets = List(size) { i32(pc + 2 + 2 * size + 2 * it) }
                sb.append("sparse-switch-payload ").append(keys.indices.joinToString(", ", "[", "]") { "${keys[it]} -> +${targets[it]}" })
                2 + size * 4
            }
            else -> {
                val width = u(pc + 1)
                val count = i32(pc + 2).toLong() and 0xFFFFFFFFL
                if (width !in listOf(1, 2, 4, 8)) throw IllegalStateException(fmt("fill-array-data-payload: ширина %d по адресу 0x%04x", width, pc))
                val bytesLen = count * width
                val units = 4 + (bytesLen + 1) / 2
                if (pc + units > n) throw IllegalStateException(fmt("fill-array-data-payload по адресу 0x%04x выходит за конец кода", pc))
                val data = ByteArray(bytesLen.toInt()) { k ->
                    val w = u(pc + 4 + k / 2)
                    (if (k % 2 == 0) w and 0xFF else w ushr 8).toByte()
                }
                val elems = (0 until count.toInt()).map { e ->
                    var v = 0L
                    for (k in 0 until width) v = v or ((data[e * width + k].toLong() and 0xFF) shl (8 * k))
                    val shift = 64 - 8 * width
                    (v shl shift) shr shift
                }
                sb.append("array-data width=$width ").append(elems.joinToString(", ", "[", "]"))
                units.toInt()
            }
        }
    }

    private fun fmt(format: String, vararg args: Any): String = String.format(Locale.ROOT, format, *args)

    private fun ref(kind: Kind, idx: Int): String = when (kind) {
        Kind.STRING -> "string@" + refs.string(idx)
        Kind.TYPE -> "type@" + refs.type(idx)
        Kind.FIELD -> "field@" + refs.field(idx)
        Kind.METHOD -> "method@" + refs.method(idx)
        Kind.CALL_SITE -> "call_site@" + refs.callSite(idx)
        Kind.METHOD_HANDLE -> "method_handle@" + refs.methodHandle(idx)
        Kind.PROTO -> "proto@" + refs.proto(idx)
        Kind.NONE -> throw IllegalStateException("инструкция без индекса")
    }

    private companion object {
        val OPS: Array<Op?> = arrayOfNulls<Op>(256).also { t ->
            fun put(code: Int, name: String, fmt: Fmt, kind: Kind = Kind.NONE) {
                check(t[code] == null) { "опкод 0x${code.toString(16)} задан дважды" }
                t[code] = Op(name, fmt, kind)
            }
            fun range(start: Int, fmt: Fmt, kind: Kind, vararg names: String) = names.forEachIndexed { i, nm -> put(start + i, nm, fmt, kind) }

            put(0x00, "nop", Fmt.F10x)
            range(0x01, Fmt.F12x, Kind.NONE, "move"); put(0x02, "move/from16", Fmt.F22x); put(0x03, "move/16", Fmt.F32x)
            put(0x04, "move-wide", Fmt.F12x); put(0x05, "move-wide/from16", Fmt.F22x); put(0x06, "move-wide/16", Fmt.F32x)
            put(0x07, "move-object", Fmt.F12x); put(0x08, "move-object/from16", Fmt.F22x); put(0x09, "move-object/16", Fmt.F32x)
            range(0x0a, Fmt.F11x, Kind.NONE, "move-result", "move-result-wide", "move-result-object", "move-exception")
            put(0x0e, "return-void", Fmt.F10x)
            range(0x0f, Fmt.F11x, Kind.NONE, "return", "return-wide", "return-object")
            put(0x12, "const/4", Fmt.F11n); put(0x13, "const/16", Fmt.F21s); put(0x14, "const", Fmt.F31i)
            put(0x15, "const/high16", Fmt.F21h); put(0x16, "const-wide/16", Fmt.F21s); put(0x17, "const-wide/32", Fmt.F31i)
            put(0x18, "const-wide", Fmt.F51l); put(0x19, "const-wide/high16", Fmt.F21h)
            put(0x1a, "const-string", Fmt.F21c, Kind.STRING); put(0x1b, "const-string/jumbo", Fmt.F31c, Kind.STRING)
            put(0x1c, "const-class", Fmt.F21c, Kind.TYPE)
            put(0x1d, "monitor-enter", Fmt.F11x); put(0x1e, "monitor-exit", Fmt.F11x)
            put(0x1f, "check-cast", Fmt.F21c, Kind.TYPE); put(0x20, "instance-of", Fmt.F22c, Kind.TYPE)
            put(0x21, "array-length", Fmt.F12x)
            put(0x22, "new-instance", Fmt.F21c, Kind.TYPE); put(0x23, "new-array", Fmt.F22c, Kind.TYPE)
            put(0x24, "filled-new-array", Fmt.F35c, Kind.TYPE); put(0x25, "filled-new-array/range", Fmt.F3rc, Kind.TYPE)
            put(0x26, "fill-array-data", Fmt.F31t); put(0x27, "throw", Fmt.F11x)
            put(0x28, "goto", Fmt.F10t); put(0x29, "goto/16", Fmt.F20t); put(0x2a, "goto/32", Fmt.F30t)
            put(0x2b, "packed-switch", Fmt.F31t); put(0x2c, "sparse-switch", Fmt.F31t)
            range(0x2d, Fmt.F23x, Kind.NONE, "cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long")
            range(0x32, Fmt.F22t, Kind.NONE, "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le")
            range(0x38, Fmt.F21t, Kind.NONE, "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez")
            range(
                0x44, Fmt.F23x, Kind.NONE, "aget", "aget-wide", "aget-object", "aget-boolean", "aget-byte", "aget-char", "aget-short",
                "aput", "aput-wide", "aput-object", "aput-boolean", "aput-byte", "aput-char", "aput-short"
            )
            range(
                0x52, Fmt.F22c, Kind.FIELD, "iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
                "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short"
            )
            range(
                0x60, Fmt.F21c, Kind.FIELD, "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte", "sget-char", "sget-short",
                "sput", "sput-wide", "sput-object", "sput-boolean", "sput-byte", "sput-char", "sput-short"
            )
            range(0x6e, Fmt.F35c, Kind.METHOD, "invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface")
            range(
                0x74, Fmt.F3rc, Kind.METHOD, "invoke-virtual/range", "invoke-super/range", "invoke-direct/range",
                "invoke-static/range", "invoke-interface/range"
            )
            range(
                0x7b, Fmt.F12x, Kind.NONE, "neg-int", "not-int", "neg-long", "not-long", "neg-float", "neg-double",
                "int-to-long", "int-to-float", "int-to-double", "long-to-int", "long-to-float", "long-to-double",
                "float-to-int", "float-to-long", "float-to-double", "double-to-int", "double-to-long", "double-to-float",
                "int-to-byte", "int-to-char", "int-to-short"
            )
            val binops = arrayOf(
                "add-int", "sub-int", "mul-int", "div-int", "rem-int", "and-int", "or-int", "xor-int", "shl-int", "shr-int", "ushr-int",
                "add-long", "sub-long", "mul-long", "div-long", "rem-long", "and-long", "or-long", "xor-long", "shl-long", "shr-long", "ushr-long",
                "add-float", "sub-float", "mul-float", "div-float", "rem-float",
                "add-double", "sub-double", "mul-double", "div-double", "rem-double"
            )
            range(0x90, Fmt.F23x, Kind.NONE, *binops)
            range(0xb0, Fmt.F12x, Kind.NONE, *binops.map { "$it/2addr" }.toTypedArray())
            range(
                0xd0, Fmt.F22s, Kind.NONE, "add-int/lit16", "rsub-int", "mul-int/lit16", "div-int/lit16", "rem-int/lit16",
                "and-int/lit16", "or-int/lit16", "xor-int/lit16"
            )
            range(
                0xd8, Fmt.F22b, Kind.NONE, "add-int/lit8", "rsub-int/lit8", "mul-int/lit8", "div-int/lit8", "rem-int/lit8",
                "and-int/lit8", "or-int/lit8", "xor-int/lit8", "shl-int/lit8", "shr-int/lit8", "ushr-int/lit8"
            )
            put(0xfa, "invoke-polymorphic", Fmt.F45cc, Kind.METHOD); put(0xfb, "invoke-polymorphic/range", Fmt.F4rcc, Kind.METHOD)
            put(0xfc, "invoke-custom", Fmt.F35c, Kind.CALL_SITE); put(0xfd, "invoke-custom/range", Fmt.F3rc, Kind.CALL_SITE)
            put(0xfe, "const-method-handle", Fmt.F21c, Kind.METHOD_HANDLE); put(0xff, "const-method-type", Fmt.F21c, Kind.PROTO)
        }
    }
}
