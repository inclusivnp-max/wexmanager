package com.tim.clazz.util

/**
 * Растущий big-endian буфер для записи class-файла.
 * Значения проверяются на диапазон: переполнение u1/u2 — ошибка, а не молчаливое усечение.
 */
internal class ClassOutput(initialCapacity: Int = 4096) {
    private var buf = ByteArray(maxOf(initialCapacity, 64))

    var size: Int = 0
        private set

    val capacity: Int get() = buf.size

    /** Начать запись заново, сохранив выделенный буфер. */
    fun reset() {
        size = 0
    }

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) {
            var cap = buf.size shl 1
            if (cap < need) cap = need
            buf = buf.copyOf(cap)
        }
    }

    fun u1(value: Int) {
        if (value ushr 8 != 0) throw ClassFormatException("значение $value не помещается в u1")
        ensure(1)
        buf[size++] = value.toByte()
    }

    fun u2(value: Int) {
        if (value ushr 16 != 0) throw ClassFormatException("значение $value не помещается в u2")
        ensure(2)
        val b = buf
        val p = size
        b[p] = (value ushr 8).toByte()
        b[p + 1] = value.toByte()
        size = p + 2
    }

    fun s4(value: Int) {
        ensure(4)
        putS4(size, value)
        size += 4
    }

    fun s8(value: Long) {
        s4((value ushr 32).toInt())
        s4(value.toInt())
    }

    fun bytes(data: ByteArray) {
        ensure(data.size)
        System.arraycopy(data, 0, buf, size, data.size)
        size += data.size
    }

    /** Резервирует место под u4 (длину атрибута), возвращает позицию для [patchS4]. */
    fun reserveS4(): Int {
        ensure(4)
        val p = size
        size += 4
        return p
    }

    fun patchS4(position: Int, value: Int) = putS4(position, value)

    private fun putS4(p: Int, value: Int) {
        val b = buf
        b[p] = (value ushr 24).toByte()
        b[p + 1] = (value ushr 16).toByte()
        b[p + 2] = (value ushr 8).toByte()
        b[p + 3] = value.toByte()
    }

    /** Копия записанных байт (буфер переиспользуется, наружу не отдаётся). */
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Обход списка по индексу — без выделения итератора на горячих путях записи. */
internal inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
    for (i in indices) action(this[i])
}
