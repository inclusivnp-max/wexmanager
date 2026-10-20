package com.tim.dex.util

/** Растущий список int без боксинга. */
class IntList(capacity: Int = 16) {
    private var data = IntArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int {
        if (i >= size) throw IndexOutOfBoundsException("IntList: индекс $i, размер $size")
        return data[i]
    }

    fun isEmpty(): Boolean = size == 0

    fun clear() { size = 0 }

    fun toArray(): IntArray = data.copyOf(size)
}
