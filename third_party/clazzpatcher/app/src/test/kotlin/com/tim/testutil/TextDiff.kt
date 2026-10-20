package com.tim.testutil

/** Компактное описание расхождения двух текстов: общее начало/конец отбрасываются, показывается середина. */
object TextDiff {
    fun snippet(expected: String, actual: String, context: Int = 3, maxLines: Int = 25): String {
        val a = expected.lines()
        val b = actual.lines()
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val from = maxOf(0, prefix - context)
        val sb = StringBuilder()
        sb.append("    @@ строка ${prefix + 1} @@\n")
        for (i in from until prefix) sb.append("      ").append(a[i]).append('\n')
        appendRange(sb, "    - ", a, prefix, a.size - suffix, maxLines)
        appendRange(sb, "    + ", b, prefix, b.size - suffix, maxLines)
        for (i in a.size - suffix until minOf(a.size, a.size - suffix + context)) sb.append("      ").append(a[i]).append('\n')
        return sb.toString()
    }

    private fun appendRange(sb: StringBuilder, marker: String, lines: List<String>, from: Int, to: Int, maxLines: Int) {
        val end = minOf(to, from + maxLines)
        for (i in from until end) sb.append(marker).append(lines[i]).append('\n')
        if (to > end) sb.append(marker).append("... ещё ${to - end} строк\n")
    }
}
