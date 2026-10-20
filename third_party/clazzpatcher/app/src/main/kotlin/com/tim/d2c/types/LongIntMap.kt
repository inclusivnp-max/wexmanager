package com.tim.d2c.types

/**
 * Хеш-таблица long → int с открытой адресацией, без упаковки ключей и значений. Значения — индексы
 * (≥ 0), -1 — «нет». Ключи уже перемешаны умножением (см. [Nodes]): слот — старшие биты.
 */
internal class LongIntMap(expected: Int = 32) {
    private var keys: LongArray
    private var vals: IntArray
    private var shift: Int
    private var size = 0

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap)
        vals = IntArray(cap) { -1 }
        shift = 64 - Integer.numberOfTrailingZeros(cap)
    }

    private fun slot(key: Long): Int = (key ushr shift).toInt()

    operator fun get(key: Long): Int {
        val mask = vals.size - 1
        var i = slot(key)
        while (true) {
            val v = vals[i]
            if (v < 0 || keys[i] == key) return v
            i = (i + 1) and mask
        }
    }

    fun put(key: Long, value: Int) {
        require(value >= 0) { "значение должно быть ≥ 0" }
        if ((size + 1) * 2 > vals.size) grow()
        insert(key, value)
    }

    private fun insert(key: Long, value: Int) {
        val mask = vals.size - 1
        var i = slot(key)
        while (vals[i] >= 0) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        keys[i] = key
        vals[i] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldVals = vals
        keys = LongArray(oldKeys.size * 2)
        vals = IntArray(oldVals.size * 2) { -1 }
        shift--
        size = 0
        for (i in oldVals.indices) if (oldVals[i] >= 0) insert(oldKeys[i], oldVals[i])
    }
}
