package com.tim.clazz.util

/**
 * Modified UTF-8 из JVMS §4.4.7: символ U+0000 кодируется двумя байтами C0 80,
 * символы вне BMP — суррогатной парой, каждая половина тремя байтами; 4-байтовых форм нет.
 */
object ModifiedUtf8 {

    /** Декодирует [length] байт с позиции [offset]; некорректная последовательность — [ClassFormatException]. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        decodeImpl(bytes, offset, length, strict = true)!!

    /** Как [decode], но для некорректной последовательности возвращает null. */
    fun decodeOrNull(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String? =
        decodeImpl(bytes, offset, length, strict = false)

    private fun decodeImpl(bytes: ByteArray, offset: Int, length: Int, strict: Boolean): String? {
        val end = offset + length
        var i = offset
        // Быстрый путь: только ASCII 1..127
        while (i < end && bytes[i] > 0) i++
        if (i == end) return String(bytes, offset, length, Charsets.ISO_8859_1)
        val chars = CharArray(length)
        var n = 0
        i = offset
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            val error: String? = when {
                b0 in 0x01..0x7F -> {
                    chars[n++] = b0.toChar()
                    i++
                    null
                }
                b0 and 0xE0 == 0xC0 -> {
                    val b1 = if (i + 1 < end) bytes[i + 1].toInt() else 0
                    if (i + 1 >= end) "обрыв 2-байтовой последовательности"
                    else if (b1 and 0xC0 != 0x80) "ожидался байт продолжения"
                    else {
                        chars[n++] = (((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar()
                        i += 2
                        null
                    }
                }
                b0 and 0xF0 == 0xE0 -> {
                    val b1 = if (i + 2 < end) bytes[i + 1].toInt() else 0
                    val b2 = if (i + 2 < end) bytes[i + 2].toInt() else 0
                    if (i + 2 >= end) "обрыв 3-байтовой последовательности"
                    else if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80) "ожидался байт продолжения"
                    else {
                        chars[n++] = (((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)).toChar()
                        i += 3
                        null
                    }
                }
                b0 == 0 -> "нулевой байт недопустим"
                else -> "недопустимый байт 0x%02X".format(b0)
            }
            if (error != null) {
                if (!strict) return null
                throw ClassFormatException("некорректная строка Modified UTF-8: $error (байт ${i - offset})")
            }
        }
        return String(chars, 0, n)
    }

    /** Длина строки в байтах Modified UTF-8. */
    fun encodedLength(value: String): Int {
        var len = 0
        for (i in 0 until value.length) {
            val c = value[i].code
            len += when {
                c in 0x01..0x7F -> 1
                c <= 0x7FF -> 2
                else -> 3
            }
        }
        return len
    }

    fun encode(value: String): ByteArray {
        // Быстрый путь: строка из ASCII без нуля (имена, дескрипторы) — один проход, байт на символ.
        val n = value.length
        val ascii = ByteArray(n)
        var k = 0
        while (k < n) {
            val c = value[k].code
            if (c == 0 || c > 0x7F) break
            ascii[k++] = c.toByte()
        }
        if (k == n) return ascii
        val out = ByteArray(encodedLength(value))
        var p = 0
        for (i in 0 until value.length) {
            val c = value[i].code
            when {
                c in 0x01..0x7F -> out[p++] = c.toByte()
                c <= 0x7FF -> {
                    out[p++] = (0xC0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    out[p++] = (0xE0 or (c shr 12)).toByte()
                    out[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        return out
    }
}
