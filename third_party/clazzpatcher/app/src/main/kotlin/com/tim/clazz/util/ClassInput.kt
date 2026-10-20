package com.tim.clazz.util

/**
 * Чтение big-endian значений из массива байт class-файла.
 * [limit] ограничивает чтение текущей структурой (например, телом атрибута),
 * выход за него — ошибка, а не чтение соседних данных.
 */
internal class ClassInput(val bytes: ByteArray) {
    var pos: Int = 0
    var limit: Int = bytes.size

    val remaining: Int get() = limit - pos

    fun u1(): Int {
        val p = pos
        if (p >= limit) throw underflow(1)
        pos = p + 1
        return bytes[p].toInt() and 0xFF
    }

    fun u2(): Int {
        val p = pos
        if (p + 2 > limit) throw underflow(2)
        pos = p + 2
        val b = bytes
        return ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    }

    fun s4(): Int {
        val p = pos
        if (p + 4 > limit) throw underflow(4)
        pos = p + 4
        val b = bytes
        return ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
    }

    fun u4(): Long = s4().toLong() and 0xFFFFFFFFL

    fun s8(): Long {
        val hi = s4().toLong()
        val lo = s4().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    /** Копия следующих [length] байт. */
    fun bytes(length: Int): ByteArray {
        val p = pos
        if (length < 0 || length > limit - p) throw underflow(length)
        pos = p + length
        return bytes.copyOfRange(p, p + length)
    }

    /** Проверяет, что u4-длина [length] помещается в оставшиеся данные, и возвращает её как Int. */
    fun checkLength(length: Long, what: String): Int {
        if (length > remaining) {
            throw ClassFormatException("$what = $length выходит за границы данных (осталось $remaining байт)")
        }
        return length.toInt()
    }

    private fun underflow(needed: Int): ClassFormatException =
        ClassFormatException("неожиданный конец данных: нужно $needed байт, осталось ${limit - pos}")
}
