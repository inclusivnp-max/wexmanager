package com.tim.dex.util

/** Компаратор примитивных int без боксинга. */
fun interface IntComparator {
    fun compare(a: Int, b: Int): Int
}

/**
 * Стабильная сортировка слиянием для массивов int с внешним компаратором.
 * На уже отсортированных данных — O(n) (слияние пропускается).
 */
object IntSort {

    /** Перестановка `0 until n`, упорядоченная стабильно по [cmp]. */
    fun sortedIndices(n: Int, cmp: IntComparator): IntArray {
        val a = IntArray(n)
        for (i in 0 until n) a[i] = i
        sort(a, cmp)
        return a
    }

    fun sort(a: IntArray, cmp: IntComparator) {
        val n = a.size
        if (n < 2) return
        var sorted = true
        for (i in 1 until n) {
            if (cmp.compare(a[i - 1], a[i]) > 0) {
                sorted = false
                break
            }
        }
        if (sorted) return
        mergeSort(a, IntArray(n), 0, n, cmp)
    }

    private fun mergeSort(a: IntArray, tmp: IntArray, lo: Int, hi: Int, cmp: IntComparator) {
        if (hi - lo <= 16) {
            for (i in lo + 1 until hi) {
                val x = a[i]
                var j = i - 1
                while (j >= lo && cmp.compare(a[j], x) > 0) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = x
            }
            return
        }
        val mid = (lo + hi) ushr 1
        mergeSort(a, tmp, lo, mid, cmp)
        mergeSort(a, tmp, mid, hi, cmp)
        if (cmp.compare(a[mid - 1], a[mid]) <= 0) return
        System.arraycopy(a, lo, tmp, lo, hi - lo)
        var i = lo
        var j = mid
        var k = lo
        while (i < mid && j < hi) {
            a[k++] = if (cmp.compare(tmp[j], tmp[i]) < 0) tmp[j++] else tmp[i++]
        }
        while (i < mid) a[k++] = tmp[i++]
        while (j < hi) a[k++] = tmp[j++]
    }
}
