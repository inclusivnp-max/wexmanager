package com.tim.csmali.asm

/**
 * Литералы синтаксиса smali 3.x (семантика `LiteralTools` из smali):
 *
 * - целые: десятичные, `0x`-шестнадцатеричные, восьмеричные (`0` + цифры), со знаком `-`;
 *   без знака допускается полный беззнаковый диапазон разрядности (`0xffffffff` = -1,
 *   `0x8000000000000000L`), со знаком — вплоть до `-0x80000000` / `-0x8000000000000000L`;
 * - суффиксы: `L`/`l` — long, `S`/`s` — short, `T`/`t` — byte, `F`/`f` — float, `D`/`d` — double;
 * - вещественные: `1.0`, `1e5`, `.5`, `0x1.8p1`, `NaN`, `Infinity`, `-Infinity` (+ суффиксы);
 * - строки/символы: экранирование `\b \t \n \f \r \' \" \\ \uXXXX`.
 */
object SmaliLiterals {

    enum class Kind { INT, LONG, SHORT, BYTE, FLOAT, DOUBLE, CHAR, BOOLEAN }

    /**
     * Числовой/символьный/логический литерал.
     * [bits] — значение: для целых — само число (со знаком), для FLOAT — raw int bits,
     * для DOUBLE — raw long bits, для CHAR — код символа, для BOOLEAN — 0/1.
     */
    class Literal(val kind: Kind, val bits: Long) {
        val intValue: Int get() = bits.toInt()
        val floatValue: Float get() = java.lang.Float.intBitsToFloat(bits.toInt())
        val doubleValue: Double get() = java.lang.Double.longBitsToDouble(bits)
    }

    // ---- Строки и символы ----

    /** Раскрыть экранирование в [s] с позиции [start] до [end] (без кавычек). */
    fun unescape(s: String, start: Int, end: Int): String {
        var i = s.indexOf('\\', start)
        if (i < 0 || i >= end) return s.substring(start, end)
        val sb = StringBuilder(end - start)
        sb.append(s, start, i)
        while (i < end) {
            val c = s[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= end) throw IllegalArgumentException("Незавершённая escape-последовательность в \"${s.substring(start, end)}\"")
            when (val e = s[i + 1]) {
                'b' -> sb.append('\b')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'f' -> sb.append('\u000c')
                'r' -> sb.append('\r')
                '\'' -> sb.append('\'')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'u' -> {
                    if (i + 6 > end) throw IllegalArgumentException("\\u должна содержать 4 шестнадцатеричные цифры: \"${s.substring(start, end)}\"")
                    var v = 0
                    for (k in i + 2 until i + 6) {
                        val d = Character.digit(s[k], 16)
                        if (d < 0) throw IllegalArgumentException("\\u должна содержать 4 шестнадцатеричные цифры: \"${s.substring(start, end)}\"")
                        v = (v shl 4) or d
                    }
                    sb.append(v.toChar())
                    i += 6
                    continue
                }
                else -> throw IllegalArgumentException("Недопустимая escape-последовательность \\$e в \"${s.substring(start, end)}\"")
            }
            i += 2
        }
        return sb.toString()
    }

    /** Строковый литерал в кавычках → строка. */
    fun parseString(text: String): String {
        val t = text.trim()
        require(t.length >= 2 && t[0] == '"' && t[t.length - 1] == '"') { "Ожидался строковый литерал: $t" }
        return unescape(t, 1, t.length - 1)
    }

    /** Символьный литерал в апострофах → символ. */
    fun parseChar(text: String): Char {
        val t = text.trim()
        require(t.length >= 3 && t[0] == '\'' && t[t.length - 1] == '\'') { "Ожидался символьный литерал: $t" }
        val s = unescape(t, 1, t.length - 1)
        require(s.length == 1) { "Символьный литерал должен содержать ровно один символ: $t" }
        return s[0]
    }

    // ---- Целые ----

    fun parseInt(text: String): Int = parseIntegral(text, 32).toInt()

    fun parseLong(text: String): Long {
        val t = if (text.endsWith('L') || text.endsWith('l')) text.substring(0, text.length - 1) else text
        return parseIntegral(t, 64)
    }

    fun parseShort(text: String): Short {
        val t = if (text.endsWith('S') || text.endsWith('s')) text.substring(0, text.length - 1) else text
        return parseIntegral(t, 16).toShort()
    }

    fun parseByte(text: String): Byte {
        val t = if (text.endsWith('T') || text.endsWith('t')) text.substring(0, text.length - 1) else text
        return parseIntegral(t, 8).toByte()
    }

    /**
     * Целое без суффикса разрядности [bits] (8/16/32/64) → значение со знаком.
     * Без минуса допускается беззнаковый диапазон, с минусом — до -2^(bits-1).
     */
    private fun parseIntegral(text: String, bits: Int): Long {
        if (text.isEmpty()) throw NumberFormatException("Пустой числовой литерал")
        var i = 0
        var negative = false
        if (text[0] == '-') { negative = true; i++ }
        else if (text[0] == '+') i++
        if (i >= text.length) throw NumberFormatException("Некорректное число: $text")
        var radix = 10
        if (text[i] == '0' && i + 1 < text.length) {
            val c = text[i + 1]
            if (c == 'x' || c == 'X') { radix = 16; i += 2 }
            else if (c in '0'..'9') { radix = 8; i += 1 }
        }
        if (i >= text.length) throw NumberFormatException("Некорректное число: $text")
        var result = 0L
        val limit = if (bits == 64) -1L else (1L shl bits) - 1
        val maxDiv = java.lang.Long.divideUnsigned(-1L, radix.toLong())
        val maxRem = java.lang.Long.remainderUnsigned(-1L, radix.toLong())
        while (i < text.length) {
            val d = Character.digit(text[i], radix)
            if (d < 0) throw NumberFormatException("Некорректная цифра '${text[i]}' в числе $text")
            val cmp = java.lang.Long.compareUnsigned(result, maxDiv)
            if (cmp > 0 || (cmp == 0 && d > maxRem)) throw NumberFormatException("$text не помещается в $bits бит")
            result = result * radix + d
            if (java.lang.Long.compareUnsigned(result, limit) > 0) throw NumberFormatException("$text не помещается в $bits бит")
            i++
        }
        val signBit = 1L shl (bits - 1)
        return if (negative) {
            if (java.lang.Long.compareUnsigned(result, signBit) > 0) throw NumberFormatException("$text не помещается в $bits бит")
            -result
        } else {
            if (bits == 64) result else if (result and signBit != 0L) result - (1L shl bits) else result
        }
    }

    // ---- Вещественные ----

    fun parseFloat(text: String): Float {
        val t = if (text.endsWith('F') || text.endsWith('f')) text.substring(0, text.length - 1) else text
        special(t)?.let { return it.toFloat() }
        return java.lang.Float.parseFloat(t)
    }

    fun parseDouble(text: String): Double {
        val t = if (text.endsWith('D') || text.endsWith('d')) text.substring(0, text.length - 1) else text
        special(t)?.let { return it }
        return java.lang.Double.parseDouble(t)
    }

    private fun special(t: String): Double? {
        val neg = t.startsWith("-")
        val body = if (neg || t.startsWith("+")) t.substring(1) else t
        return when {
            body.equals("infinity", ignoreCase = true) -> if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            body.equals("nan", ignoreCase = true) -> Double.NaN
            else -> null
        }
    }

    // ---- Классификация ----

    /**
     * Разобрать литерал фиксированного значения (число/символ/логическое).
     * Бросает исключение, если [text] не является таким литералом.
     */
    fun parse(text: String): Literal {
        val t = text.trim()
        require(t.isNotEmpty()) { "Пустой литерал" }
        val c0 = t[0]
        if (c0 == '\'') return Literal(Kind.CHAR, parseChar(t).code.toLong())
        if (t == "true") return Literal(Kind.BOOLEAN, 1)
        if (t == "false") return Literal(Kind.BOOLEAN, 0)

        val body = if (c0 == '-' || c0 == '+') t.substring(1) else t
        val lower = body.lowercase()
        if (lower.startsWith("nan") || lower.startsWith("infinity")) {
            val base = if (lower.startsWith("nan")) 3 else 8
            val suffix = lower.substring(base)
            // NaN с явной полезной нагрузкой: NaNf:0x7fc00001 / NaN:0x7ff8000000000001.
            if (base == 3 && (suffix.startsWith("f:") || suffix.startsWith(":") || suffix.startsWith("d:"))) {
                val hex = suffix.substring(suffix.indexOf(':') + 1)
                if (!hex.startsWith("0x")) throw NumberFormatException("Ожидались биты NaN вида 0x…: $t")
                return if (suffix[0] == 'f') {
                    val bits = parseIntegral(hex, 32).toInt()
                    if (!java.lang.Float.intBitsToFloat(bits).isNaN()) throw NumberFormatException("Биты $hex не NaN: $t")
                    Literal(Kind.FLOAT, bits.toLong())
                } else {
                    val bits = parseIntegral(hex, 64)
                    if (!java.lang.Double.longBitsToDouble(bits).isNaN()) throw NumberFormatException("Биты $hex не NaN: $t")
                    Literal(Kind.DOUBLE, bits)
                }
            }
            return when (suffix) {
                "f" -> Literal(Kind.FLOAT, java.lang.Float.floatToRawIntBits(parseFloat(t)).toLong())
                "", "d" -> Literal(Kind.DOUBLE, java.lang.Double.doubleToRawLongBits(parseDouble(t)))
                else -> throw NumberFormatException("Некорректный литерал: $t")
            }
        }
        val isHex = lower.startsWith("0x")
        val last = t[t.length - 1]
        if (isHex) {
            val isFloat = lower.contains('p')
            if (isFloat) {
                return if (last == 'f' || last == 'F') {
                    Literal(Kind.FLOAT, java.lang.Float.floatToRawIntBits(parseFloat(t)).toLong())
                } else {
                    Literal(Kind.DOUBLE, java.lang.Double.doubleToRawLongBits(parseDouble(t)))
                }
            }
            return when (last) {
                'L', 'l' -> Literal(Kind.LONG, parseLong(t))
                'S', 's' -> Literal(Kind.SHORT, parseShort(t).toLong())
                'T', 't' -> Literal(Kind.BYTE, parseByte(t).toLong())
                else -> Literal(Kind.INT, parseInt(t).toLong())
            }
        }
        val digitsEnd = if (last == 'L' || last == 'l' || last == 'S' || last == 's' || last == 'T' || last == 't') t.length - 1 else t.length
        var allDigits = digitsEnd > (if (c0 == '-' || c0 == '+') 1 else 0)
        for (k in (if (c0 == '-' || c0 == '+') 1 else 0) until digitsEnd) if (t[k] !in '0'..'9') { allDigits = false; break }
        if (allDigits) {
            return when (last) {
                'L', 'l' -> Literal(Kind.LONG, parseLong(t))
                'S', 's' -> Literal(Kind.SHORT, parseShort(t).toLong())
                'T', 't' -> Literal(Kind.BYTE, parseByte(t).toLong())
                else -> Literal(Kind.INT, parseInt(t).toLong())
            }
        }
        return when (last) {
            'f', 'F' -> Literal(Kind.FLOAT, java.lang.Float.floatToRawIntBits(parseFloat(t)).toLong())
            else -> {
                val v = parseDouble(t)
                Literal(Kind.DOUBLE, java.lang.Double.doubleToRawLongBits(v))
            }
        }
    }
}