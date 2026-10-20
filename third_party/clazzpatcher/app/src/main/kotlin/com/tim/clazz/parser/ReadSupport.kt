package com.tim.clazz.parser

/**
 * Список из [count] элементов (массив фиксированного размера); для 0 — общий пустой список.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <T> readList(count: Int, read: (index: Int) -> T): List<T> {
    if (count == 0) return emptyList()
    val array = arrayOfNulls<Any?>(count)
    for (i in 0 until count) array[i] = read(i)
    return array.asList() as List<T>
}
