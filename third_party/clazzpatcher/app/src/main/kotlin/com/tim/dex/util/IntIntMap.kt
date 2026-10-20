package com.tim.dex.util

/**
 * Хеш-таблица int -> int с открытой адресацией (без боксинга).
 * Ключ 0 зарезервирован (в DEX смещение 0 означает «нет»), отсутствие значения — `-1`.
 * Ключ и значение лежат рядом (одна кэш-линия на пробу).
 */
class IntIntMap(expected: Int = 16) {
    private var table: IntArray
    private var mask: Int
    private var shift: Int
    private var size = 0

    init {
        var cap = 16
        // Ожидаемый размер — лишь подсказка (из входных данных): ограничиваем, чтобы удвоение не переполнилось.
        val want = expected.coerceIn(0, 1 shl 26)
        while (cap < want * 2) cap = cap shl 1
        table = IntArray(cap * 2)
        mask = cap - 1
        shift = Integer.numberOfLeadingZeros(cap) + 1
    }

    // Фибоначчиево хеширование: старшие биты произведения
    private fun slot(key: Int): Int = (key * -0x61c88647) ushr shift

    operator fun get(key: Int): Int {
        val t = table
        var i = slot(key)
        while (true) {
            val k = t[i shl 1]
            if (k == key) return t[(i shl 1) + 1]
            if (k == 0) return -1
            i = (i + 1) and mask
        }
    }

    fun put(key: Int, value: Int) {
        if (key == 0) throw IllegalArgumentException("IntIntMap: ключ 0 зарезервирован")
        if ((size + 1) * 2 > mask + 1) grow()
        val t = table
        var i = slot(key)
        while (true) {
            val k = t[i shl 1]
            if (k == key) {
                t[(i shl 1) + 1] = value
                return
            }
            if (k == 0) {
                t[i shl 1] = key
                t[(i shl 1) + 1] = value
                size++
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val old = table
        val cap = (mask + 1) * 2
        table = IntArray(cap * 2)
        mask = cap - 1
        shift = Integer.numberOfLeadingZeros(cap) + 1
        size = 0
        var i = 0
        while (i < old.size) {
            if (old[i] != 0) put(old[i], old[i + 1])
            i += 2
        }
    }
}
