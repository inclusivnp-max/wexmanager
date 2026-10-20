package com.tim.dex.util

/**
 * Утилита кодирования/декодирования MUTF-8 (Modified UTF-8), как в DEX.
 * Без состояния — только чистые функции.
 *
 * MUTF-8 отличается от UTF-8:
 *  - символ U+0000 кодируется двумя байтами 0xC0 0x80;
 *  - surrogate-пары кодируются как две 3-байтовые последовательности (CESU-8);
 *  - 4-байтовых последовательностей нет.
 */
object StringPool {

    /**
     * Декодирует MUTF-8 байты (без нулевого терминатора).
     */
    fun decodeMUTF8(bytes: ByteArray): String {
        val chars = CharArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            when (a shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> {
                    if (a == 0) throw IllegalArgumentException("MUTF-8: нулевой байт внутри строки (позиция $i)")
                    chars[n++] = a.toChar()
                    i++
                }
                12, 13 -> {
                    if (i + 1 >= bytes.size) throw IllegalArgumentException("MUTF-8: обрезанная последовательность (позиция $i)")
                    chars[n++] = (((a and 0x1F) shl 6) or cont(bytes, i + 1)).toChar()
                    i += 2
                }
                14 -> {
                    if (i + 2 >= bytes.size) throw IllegalArgumentException("MUTF-8: обрезанная последовательность (позиция $i)")
                    chars[n++] = (((a and 0x0F) shl 12) or (cont(bytes, i + 1) shl 6) or cont(bytes, i + 2)).toChar()
                    i += 3
                }
                else -> throw IllegalArgumentException("MUTF-8: недопустимый байт 0x${a.toString(16)} (позиция $i)")
            }
        }
        return String(chars, 0, n)
    }

    /**
     * Декодирует string_data_item: [utf16Size] UTF-16 символов начиная с [offset],
     * за которыми обязан идти нулевой терминатор.
     */
    fun decodeMUTF8(bytes: ByteArray, offset: Int, utf16Size: Int): String {
        if (utf16Size < 0) throw IllegalArgumentException("MUTF-8: отрицательный utf16_size по смещению 0x${offset.toString(16)}")
        // Каждый UTF-16 символ занимает хотя бы байт (плюс терминатор): иначе размер ложный — не выделяем память под него.
        if (utf16Size > bytes.size - offset - 1) {
            throw IllegalArgumentException("MUTF-8: utf16_size=$utf16Size по смещению 0x${offset.toString(16)} больше остатка файла")
        }
        // Быстрый путь: чистый ASCII (байт на символ)
        val asciiEnd = offset + utf16Size
        if (asciiEnd < bytes.size && asciiEnd >= offset) {
            var p = offset
            while (p < asciiEnd) {
                val c = bytes[p].toInt()
                if (c <= 0) break // 0x00 или >= 0x80
                p++
            }
            if (p == asciiEnd && bytes[p].toInt() == 0) {
                return String(bytes, offset, utf16Size, Charsets.ISO_8859_1)
            }
        }
        val chars = CharArray(utf16Size)
        var p = offset
        for (n in 0 until utf16Size) {
            val a = bytes[p].toInt() and 0xFF
            when (a shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> {
                    if (a == 0) {
                        throw IllegalArgumentException(
                            "MUTF-8: строка по смещению 0x${offset.toString(16)} короче utf16_size=$utf16Size"
                        )
                    }
                    chars[n] = a.toChar()
                    p++
                }
                12, 13 -> {
                    chars[n] = (((a and 0x1F) shl 6) or cont(bytes, p + 1)).toChar()
                    p += 2
                }
                14 -> {
                    chars[n] = (((a and 0x0F) shl 12) or (cont(bytes, p + 1) shl 6) or cont(bytes, p + 2)).toChar()
                    p += 3
                }
                else -> throw IllegalArgumentException(
                    "MUTF-8: недопустимый байт 0x${a.toString(16)} по смещению 0x${p.toString(16)}"
                )
            }
        }
        if (bytes[p].toInt() != 0) {
            throw IllegalArgumentException(
                "MUTF-8: строка по смещению 0x${offset.toString(16)} длиннее utf16_size=$utf16Size"
            )
        }
        return String(chars)
    }

    private fun cont(bytes: ByteArray, i: Int): Int {
        val b = bytes[i].toInt() and 0xFF
        if ((b and 0xC0) != 0x80) {
            throw IllegalArgumentException("MUTF-8: неверный байт продолжения 0x${b.toString(16)} (позиция $i)")
        }
        return b and 0x3F
    }

    /** Длина строки в MUTF-8 (без терминатора). */
    fun mutf8Length(str: String): Int {
        var len = 0
        for (i in 0 until str.length) {
            val c = str[i].code
            len += when {
                c in 0x01..0x7F -> 1
                c <= 0x7FF -> 2 // включая U+0000
                else -> 3
            }
        }
        return len
    }

    /**
     * Кодирует строку в MUTF-8 (без нулевого терминатора).
     * `str.length` == количество UTF-16 code units, которое writer
     * запишет в `utf16_size`.
     */
    fun encodeMUTF8(str: String): ByteArray {
        val bytes = ByteArray(mutf8Length(str))
        var offset = 0
        for (i in 0 until str.length) {
            val c = str[i].code
            when {
                c in 0x01..0x7F -> bytes[offset++] = c.toByte()
                c <= 0x7FF -> {
                    bytes[offset++] = (0xC0 or ((c shr 6) and 0x1F)).toByte()
                    bytes[offset++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    bytes[offset++] = (0xE0 or ((c shr 12) and 0x0F)).toByte()
                    bytes[offset++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    bytes[offset++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        return bytes
    }
}
