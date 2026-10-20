package com.tim.dsmali

/**
 * Форматирование элементов smali-текста в синтаксисе baksmali 3.0.9:
 * в строках и символах управляющие и невидимые символы (в том числе непарные суррогаты —
 * иначе они теряются при записи текста в UTF-8) записываются как `\uXXXX`,
 * кириллица и прочие видимые символы — как есть;
 * литералы `0x..`/`-0x..` с суффиксами, типы и имена (простые имена с пробельными
 * символами берутся в обратные кавычки). Без состояния, потокобезопасно.
 */
object SmaliFormatter {
    private val HEX = "0123456789abcdef".toCharArray()

    fun visibility(v: Byte): String = when (v.toInt()) {
        0 -> "build"
        1 -> "runtime"
        2 -> "system"
        else -> throw IllegalArgumentException("Неизвестная видимость аннотации: $v")
    }

    fun escape(str: String): String {
        val sb = StringBuilder(str.length + 8)
        appendEscaped(sb, str)
        return sb.toString()
    }

    fun appendEscaped(sb: StringBuilder, str: String) {
        val n = str.length
        var i = 0
        while (i < n) {
            val c = str[i]
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> {
                    if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(str[i + 1])) {
                        // Суррогатная пара — один символ вне BMP: как есть, если он видимый.
                        val low = str[i + 1]
                        if (isVisible(Character.toCodePoint(c, low))) sb.append(c).append(low)
                        else appendUnicodeEscape(appendUnicodeEscape(sb, c), low)
                        i += 2
                        continue
                    }
                    appendPlain(sb, c)
                }
            }
            i++
        }
    }

    /** Символ без именованного escape: видимый — как есть, иначе `\uXXXX` (одиночный суррогат — всегда `\uXXXX`). */
    private fun appendPlain(sb: StringBuilder, c: Char) {
        if (c.code in 0x20..0x7e || isVisible(c.code)) sb.append(c) else appendUnicodeEscape(sb, c)
    }

    /**
     * Видимый символ: не управляющий, не форматирующий, не разделитель строк/абзацев, не пробельный
     * (кроме обычного пробела, он обрабатывается раньше), не суррогат, не неназначенный и не private use.
     */
    private fun isVisible(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.SPACE_SEPARATOR.toInt(), Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt() -> false
        else -> true
    }

    fun appendQuoted(sb: StringBuilder, str: String): StringBuilder {
        sb.append('"')
        appendEscaped(sb, str)
        return sb.append('"')
    }

    fun quote(str: String): String = appendQuoted(StringBuilder(str.length + 10), str).toString()

    fun appendChar(sb: StringBuilder, c: Char) {
        sb.append('\'')
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\'' -> sb.append("\\'")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> appendPlain(sb, c)
        }
        sb.append('\'')
    }

    private fun appendUnicodeEscape(sb: StringBuilder, c: Char): StringBuilder {
        val v = c.code
        return sb.append("\\u").append(HEX[(v shr 12) and 0xF]).append(HEX[(v shr 8) and 0xF])
            .append(HEX[(v shr 4) and 0xF]).append(HEX[v and 0xF])
    }

    fun appendUnsignedHex(sb: StringBuilder, value: Long): StringBuilder {
        if (value == 0L) {
            sb.append('0')
            return sb
        }
        var shift = (63 - java.lang.Long.numberOfLeadingZeros(value)) and 3.inv()
        while (shift >= 0) {
            sb.append(HEX[((value ushr shift) and 0xF).toInt()])
            shift -= 4
        }
        return sb
    }

    fun appendIntegral(sb: StringBuilder, value: Long, suffix: Char?): StringBuilder {
        if (value < 0) {
            sb.append("-0x")
            appendUnsignedHex(sb, -value)
        } else {
            sb.append("0x")
            appendUnsignedHex(sb, value)
        }
        if (suffix != null) sb.append(suffix)
        return sb
    }

    /**
     * Литерал инструкции: суффикс `L` только если значение не помещается в int.
     * Возвращает тот же `StringBuilder`, чтобы функцию можно было использовать
     * в цепочках и строковой интерполяции.
     */
    fun appendLiteral(sb: StringBuilder, value: Long): StringBuilder {
        if (value < 0) {
            sb.append("-0x")
            appendUnsignedHex(sb, -value)
            if (value < Int.MIN_VALUE) sb.append('L')
        } else {
            sb.append("0x")
            appendUnsignedHex(sb, value)
            if (value > Int.MAX_VALUE) sb.append('L')
        }
        return sb
    }

    fun toHex(value: Int): String =
        StringBuilder(12).also { appendIntegral(it, value.toLong(), null) }.toString()

    fun toHex(value: Long): String =
        StringBuilder(20).also { appendIntegral(it, value, null) }.toString()

    fun needsQuoting(name: CharSequence, start: Int = 0, end: Int = name.length): Boolean {
        for (i in start until end) {
            val c = name[i]
            if (c == ' ' || (c.code >= 0xa0 && Character.getType(c) == Character.SPACE_SEPARATOR.toInt())) return true
        }
        return false
    }

    fun appendSimpleName(sb: StringBuilder, name: String): StringBuilder {
        if (needsQuoting(name)) sb.append('`').append(name).append('`') else sb.append(name)
        return sb
    }

    fun appendType(sb: StringBuilder, type: String): StringBuilder {
        val l = type.indexOf('L')
        if (l < 0 || !needsQuoting(type)) return sb.append(type)
        sb.append(type, 0, l + 1)
        var start = l + 1
        for (i in start until type.length) {
            val c = type[i]
            if (c == '/' || c == ';') {
                if (needsQuoting(type, start, i)) sb.append('`').append(type, start, i).append('`')
                else sb.append(type, start, i)
                sb.append(c)
                start = i + 1
            }
        }
        if (start < type.length) sb.append(type, start, type.length)
        return sb
    }

    fun type(type: String): String =
        if (needsQuoting(type)) appendType(StringBuilder(type.length + 4), type).toString() else type

    fun simpleName(name: String): String = if (needsQuoting(name)) "`$name`" else name

    fun isWide(type: String): Boolean = type == "J" || type == "D"
}