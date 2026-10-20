package org.wexclient.app.util

/** «file2» < «file10»: числа сравниваются по значению, остальное — без учёта регистра. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i] == '0') i++
                while (j < b.length && b[j] == '0') j++
                val ni = i
                val nj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val lenA = i - ni
                val lenB = j - nj
                if (lenA != lenB) return lenA - lenB
                for (k in 0 until lenA) {
                    val d = a[ni + k] - b[nj + k]
                    if (d != 0) return d
                }
                // 007 и 7 равны по значению — меньше ведущих нулей идёт первым
                val zeros = (ni - si) - (nj - sj)
                if (zeros != 0) return zeros
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
