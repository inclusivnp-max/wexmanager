package com.tim.dex.util

/**
 * Растущий little-endian буфер для записи DEX.
 *
 * Быстрее [java.nio.ByteBuffer] для последовательной записи: нет проверок на
 * каждом `put`, нет необходимости заранее знать итоговый размер.
 */
class ByteOut(capacity: Int = 1 shl 16) {
    var buf = ByteArray(maxOf(capacity, 64))
        private set

    /** Текущая позиция записи (абсолютное смещение). */
    var pos = 0

    fun ensure(extra: Int) {
        val need = pos + extra
        if (need < 0) throw IllegalStateException("ByteOut: размер превышает 2 ГБ")
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size + (buf.size shr 1)))
    }

    fun u1(v: Int) {
        ensure(1)
        buf[pos++] = v.toByte()
    }

    fun u2(v: Int) {
        ensure(2)
        val b = buf
        var p = pos
        b[p++] = v.toByte()
        b[p++] = (v ushr 8).toByte()
        pos = p
    }

    fun u4(v: Int) {
        ensure(4)
        val b = buf
        var p = pos
        b[p++] = v.toByte()
        b[p++] = (v ushr 8).toByte()
        b[p++] = (v ushr 16).toByte()
        b[p++] = (v ushr 24).toByte()
        pos = p
    }

    fun bytes(src: ByteArray) = bytes(src, 0, src.size)

    fun bytes(src: ByteArray, offset: Int, length: Int) {
        ensure(length)
        System.arraycopy(src, offset, buf, pos, length)
        pos += length
    }

    fun zeros(count: Int) {
        ensure(count)
        java.util.Arrays.fill(buf, pos, pos + count, 0)
        pos += count
    }

    fun uleb(value: Int) {
        ensure(5)
        val b = buf
        var p = pos
        var v = value
        while (true) {
            val low = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                b[p++] = low.toByte()
                break
            }
            b[p++] = (low or 0x80).toByte()
        }
        pos = p
    }

    /** uleb128p1: `-1` кодируется как 0. */
    fun ulebp1(value: Int) = uleb(value + 1)

    fun sleb(value: Int) {
        ensure(5)
        val b = buf
        var p = pos
        var v = value
        while (true) {
            val low = v and 0x7F
            v = v shr 7
            if ((v == 0 && (low and 0x40) == 0) || (v == -1 && (low and 0x40) != 0)) {
                b[p++] = low.toByte()
                break
            }
            b[p++] = (low or 0x80).toByte()
        }
        pos = p
    }

    fun align4() {
        if ((pos and 3) == 0) return
        ensure(3)
        val b = buf
        var p = pos
        while ((p and 3) != 0) b[p++] = 0
        pos = p
    }

    /** Строка в MUTF-8 (без терминатора). */
    fun mutf8(s: String) {
        // Быстрый путь: для ASCII без U+0000 MUTF-8 совпадает с UTF-8 (кодирование в JDK — интринсик).
        // Одинокий surrogate UTF-8-кодировщик заменяет на '?', поэтому строки с '?' идут медленным путём.
        if (s.length >= 16) {
            val utf8 = s.toByteArray(Charsets.UTF_8)
            if (utf8.size == s.length && isPlainAscii(utf8)) {
                bytes(utf8)
                return
            }
        }
        ensure(s.length * 3)
        val b = buf
        var p = pos
        for (i in 0 until s.length) {
            val c = s[i].code
            if (c in 1..0x7F) {
                b[p++] = c.toByte()
            } else if (c <= 0x7FF) {
                b[p++] = (0xC0 or (c shr 6)).toByte()
                b[p++] = (0x80 or (c and 0x3F)).toByte()
            } else {
                b[p++] = (0xE0 or (c shr 12)).toByte()
                b[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                b[p++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
        pos = p
    }

    /** Нет байтов 0x00 и '?' (при размере, равном длине строки, остальные байты — ASCII). */
    private fun isPlainAscii(b: ByteArray): Boolean {
        for (x in b) {
            val c = x.toInt()
            if (c == 0 || c == '?'.code) return false
        }
        return true
    }

    fun setU2(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
    }

    fun setU4(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
        buf[at + 2] = (v ushr 16).toByte()
        buf[at + 3] = (v ushr 24).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
}
