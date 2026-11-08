package org.wexclient.app.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Показ строки: табуляция развёрнута до позиций табуляции, пробелы при желании помечены.
 *
 * Шрифт моноширинный, поэтому экранная колонка — это просто число знаков слева. С табами это
 * уже не так (один таб занимает от одного до [tabSize] мест), и без пересчёта каретка и щелчок
 * мышью попадали бы мимо. [columns] хранит экранную колонку каждого знака строки.
 */
internal class ShownLine(
    /** Текст, как он рисуется. */
    val text: String,
    /** columns[i] — колонка знака i исходной строки; null — колонка равна номеру знака. */
    private val columns: IntArray?,
    /** Ширина строки в колонках. */
    val width: Int
) {
    /** Экранная колонка знака [offsetInLine]. */
    fun columnOf(offsetInLine: Int): Int {
        val c = columns ?: return offsetInLine.coerceAtLeast(0)
        return c[offsetInLine.coerceIn(0, c.size - 1)]
    }

    /** Знак строки, стоящий в экранной колонке [column] (ближайший слева). */
    fun offsetOf(column: Int): Int {
        val c = columns ?: return max(0, column)
        if (column <= 0) return 0
        if (column >= c[c.size - 1]) return c.size - 1
        var lo = 0
        var hi = c.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (c[mid] < column) lo = mid + 1 else hi = mid
        }
        // Попали внутрь таба — прижимаемся к его началу
        return if (lo > 0 && c[lo] > column) lo - 1 else lo
    }

    companion object {
        const val TAB_MARK = '→'
        const val SPACE_MARK = '·'

        fun of(raw: String, tabSize: Int, whitespace: Boolean): ShownLine {
            val tab = raw.indexOf('\t')
            if (tab < 0) {
                val shown = if (whitespace && raw.indexOf(' ') >= 0) raw.replace(' ', SPACE_MARK) else raw
                return ShownLine(shown, null, raw.length)
            }
            val sb = StringBuilder(raw.length + tabSize)
            val columns = IntArray(raw.length + 1)
            var column = 0
            for (i in raw.indices) {
                columns[i] = column
                val c = raw[i]
                if (c == '\t') {
                    val width = tabSize - (column % tabSize)
                    sb.append(if (whitespace) TAB_MARK else ' ')
                    for (k in 1 until width) sb.append(' ')
                    column += width
                } else {
                    sb.append(if (whitespace && c == ' ') SPACE_MARK else c)
                    column++
                }
            }
            columns[raw.length] = column
            return ShownLine(sb.toString(), columns, column)
        }
    }
}

/**
 * Раскладка документа по экранным строкам. Без переноса каждая строка занимает одну экранную,
 * и всё считается умножением. При переносе храним границы слов в длинных строках
 * и накопленные номера экранных строк; рисование и ввод используют одни границы.
 */
internal class FastLayout(
    val snapshot: HighlightSnapshot,
    val tabSize: Int,
    val whitespace: Boolean,
    /** Ширина окна в знаках; 0 или меньше — перенос выключен. */
    val columns: Int,
    /** Свёрнутые участки текста: их строки не показываются (кроме первой — с меткой). */
    val collapsed: List<IntRange> = emptyList(),
    private val measuredWidths: IntArray? = null
) {
    val wrap: Boolean = columns > 0

    // Порядок доступа, давно не показанные вытесняются: без предела прокрутка огромного лога
    // без правок копила копию каждой когда-либо показанной строки. Вытесненная посчитается заново
    private val shown = object : LinkedHashMap<Int, ShownLine>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, ShownLine>) = size > MAX_SHOWN_LINES
    }

    /** Сколько строк сейчас в кэше показа — для тестов. */
    internal val shownCount: Int get() = shown.size

    /** Спрятанные свёрткой строки. */
    private val hidden: BooleanArray? = if (collapsed.isEmpty()) null else BooleanArray(snapshot.lineCount).also { arr ->
        val length = snapshot.text.length
        for (range in collapsed) {
            val from = snapshot.lineOf(range.first.coerceIn(0, length)) + 1
            val to = snapshot.lineOf(range.last.coerceIn(0, length))
            for (line in from..to) {
                if (line in arr.indices) arr[line] = true
            }
        }
    }

    /** Строка спрятана свёрткой. */
    fun isHidden(line: Int): Boolean = hidden?.getOrNull(line) == true

    private val wrapped = HashMap<Int, IntArray>()

    private fun breaks(line: Int): IntArray = wrapped.getOrPut(line) {
        val value = shownLine(line).text
        val ends = ArrayList<Int>()
        ends.add(0)
        var start = 0
        while (start < value.length) {
            var end = min(value.length, start + columns)
            if (end < value.length) {
                var boundary = end
                while (boundary > start && !value[boundary - 1].isWhitespace() && value[boundary - 1] !in "-/") boundary--
                if (boundary > start && (start until boundary).any { !value[it].isWhitespace() }) end = boundary
                // Не разрезаем UTF-16 пару или хвост комбинируемого знака.
                if (end < value.length && end > start && Character.isLowSurrogate(value[end]) && Character.isHighSurrogate(value[end - 1])) end--
                if (end == start) end = min(value.length, start + 2)
                while (end < value.length && (Character.getType(value[end]) in MARK_TYPES || value[end] == '\u200D' || value[end - 1] == '\u200D')) {
                    end += if (Character.isHighSurrogate(value[end]) && end + 1 < value.length && Character.isLowSurrogate(value[end + 1])) 2 else 1
                }
            }
            ends.add(end)
            start = end
        }
        ends.toIntArray()
    }

    /** rowStart[i] — номер первой экранной строки для строки i (нужен при переносе и свёртках). */
    private val rowStart: IntArray? = if (!wrap && hidden == null) null else IntArray(snapshot.lineCount + 1).also { arr ->
        var row = 0
        for (line in 0 until snapshot.lineCount) {
            arr[line] = row
            row += rowsOfLine(line)
        }
        arr[snapshot.lineCount] = row
    }

    val totalRows: Int get() = rowStart?.get(snapshot.lineCount) ?: snapshot.lineCount

    private fun rowsOfLine(line: Int): Int = when {
        isHidden(line) -> 0
        !wrap -> 1
        widthOf(line) <= columns -> 1
        else -> breaks(line).size - 1
    }

    /** Ширина строки в колонках — без разворачивания, если табов нет. */
    private fun widthOf(line: Int): Int {
        measuredWidths?.let { return it[line] }
        return visualWidth(snapshot, line, tabSize)
    }

    internal fun visualWidth(snapshot: HighlightSnapshot, line: Int, tabSize: Int): Int {
        val start = snapshot.lineStart(line)
        val end = snapshot.lineEnd(line)
        if (end <= start) return 0
        val text = snapshot.text
        var width = 0
        var i = start
        var hasTab = false
        while (i < end) {
            if (text[i] == '\t') {
                hasTab = true
                break
            }
            i++
        }
        if (!hasTab) return end - start
        width = 0
        i = start
        while (i < end) {
            width += if (text[i] == '\t') tabSize - (width % tabSize) else 1
            i++
        }
        return width
    }

    fun shownLine(line: Int): ShownLine = shown.getOrPut(line) {
        val start = snapshot.lineStart(line)
        val end = snapshot.lineEnd(line).coerceAtMost(snapshot.text.length)
        ShownLine.of(if (end > start) snapshot.text.substring(start, end) else "", tabSize, whitespace)
    }

    fun rowsOf(line: Int): Int = rowsOfLine(line)

    fun firstRowOf(line: Int): Int = rowStart?.get(line.coerceIn(0, snapshot.lineCount)) ?: line

    fun lineOfRow(row: Int): Int {
        val arr = rowStart ?: return row.coerceIn(0, max(0, snapshot.lineCount - 1))
        if (row <= 0) return 0
        if (row >= arr[snapshot.lineCount]) return max(0, snapshot.lineCount - 1)
        var lo = 0
        var hi = snapshot.lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (arr[mid] <= row) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Экранная строка и колонка; одинаковое смещение на стыке допускает каретку в конце предыдущей строки. */
    fun rowAndColumn(offset: Int, previousRow: Boolean = false): Pair<Int, Int> {
        val safe = offset.coerceIn(0, snapshot.text.length)
        val line = snapshot.lineOf(safe)
        val column = shownLine(line).columnOf(safe - snapshot.lineStart(line))
        if (!wrap || rowsOf(line) == 1) return firstRowOf(line) to column
        val points = breaks(line)
        var low = 0
        var high = points.lastIndex - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (points[middle] <= column) low = middle else high = middle - 1
        }
        if (previousRow && low > 0 && points[low] == column) low--
        return firstRowOf(line) + low to column - points[low]
    }

    /** Колонки только данной экранной строки: длинная желаемая колонка не перепрыгивает перенос. */
    fun offsetAt(row: Int, column: Int): Int {
        if (snapshot.lineCount == 0) return 0
        val safeRow = row.coerceIn(0, max(0, totalRows - 1))
        val line = lineOfRow(safeRow)
        val shown = shownLine(line)
        val range = sliceOf(line, safeRow)
        val wanted = (range.first.toLong() + column.coerceAtLeast(0)).coerceAtMost(range.last.toLong()).toInt()
        val start = snapshot.lineStart(line)
        var offset = start + shown.offsetOf(wanted).coerceIn(0, snapshot.lineEnd(line) - start)
        if (offset > start && offset < snapshot.text.length && Character.isLowSurrogate(snapshot.text[offset]) && Character.isHighSurrogate(snapshot.text[offset - 1])) offset--
        return offset
    }

    /** Конец диапазона исключающий (тот же контракт, что у substring). */
    fun sliceOf(line: Int, row: Int): IntRange {
        val shown = shownLine(line)
        if (!wrap || rowsOf(line) == 1) return 0..shown.text.length
        val points = breaks(line)
        val part = (row - firstRowOf(line)).coerceIn(0, points.lastIndex - 1)
        return points[part]..points[part + 1]
    }

    fun rowWidth(row: Int): Int {
        val range = sliceOf(lineOfRow(row), row)
        return range.last - range.first
    }

    companion object {
        private val MARK_TYPES = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    }

}

/** Строка с подсветкой: спаны только на эту строку, слишком длинную обрезаем. */
internal fun buildShownLine(
    shown: ShownLine,
    tokens: IntArray,
    styles: Array<SpanStyle?>,
    slice: IntRange
): AnnotatedString {
    val from = slice.first.coerceIn(0, shown.text.length)
    val to = min(slice.last.coerceAtLeast(from), min(shown.text.length, from + MAX_LINE_DRAW))
    val cut = shown.text.substring(from, to)
    if (tokens.isEmpty() || cut.isEmpty()) return AnnotatedString(cut)
    val b = AnnotatedString.Builder(cut)
    var k = 0
    while (k < tokens.size) {
        val style = styles[tokens[k + 2]]
        if (style != null) {
            // Токены заданы в знаках строки — переводим в экранные колонки
            val s = shown.columnOf(tokens[k]) - from
            val e = shown.columnOf(tokens[k + 1]) - from
            val a = s.coerceIn(0, cut.length)
            val z = e.coerceIn(0, cut.length)
            if (z > a) b.addStyle(style, a, z)
        }
        k += 3
    }
    return b.toAnnotatedString()
}

/** Длиннее одного экрана всё равно не видно, а раскладка длинной строки дорогая. */
private const val MAX_LINE_DRAW = 4000

/** Показанных строк в кэше раскладки: с запасом на экран с переносом, каретки и выделение. */
internal const val MAX_SHOWN_LINES = 2048
