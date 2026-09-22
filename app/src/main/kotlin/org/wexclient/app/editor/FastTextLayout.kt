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
 * и всё считается умножением. С переносом ширина строки известна заранее (моноширинный шрифт),
 * поэтому число экранных строк — деление, а начала хранятся суммами с накоплением.
 */
internal class FastLayout(
    val snapshot: HighlightSnapshot,
    val tabSize: Int,
    val whitespace: Boolean,
    /** Ширина окна в знаках; 0 или меньше — перенос выключен. */
    val columns: Int,
    /** Свёрнутые участки текста: их строки не показываются (кроме первой — с меткой). */
    val collapsed: List<IntRange> = emptyList()
) {
    val wrap: Boolean = columns > 0

    private val shown = HashMap<Int, ShownLine>(256)

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
        else -> rowsOfWidth(widthOf(line))
    }

    private fun rowsOfWidth(width: Int): Int = if (!wrap) 1 else max(1, (width + columns - 1) / columns)

    /** Ширина строки в колонках — без разворачивания, если табов нет. */
    private fun widthOf(line: Int): Int {
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

    /** Экранная строка и колонка в ней для позиции в тексте. */
    fun rowAndColumn(offset: Int): Pair<Int, Int> {
        val snap = snapshot
        val safe = offset.coerceIn(0, snap.text.length)
        val line = snap.lineOf(safe)
        val column = shownLine(line).columnOf(safe - snap.lineStart(line))
        if (!wrap) return firstRowOf(line) to column
        return (firstRowOf(line) + column / columns) to (column % columns)
    }

    /** Позиция в тексте для экранной строки и колонки. */
    fun offsetAt(row: Int, column: Int): Int {
        val snap = snapshot
        if (snap.lineCount == 0) return 0
        val line = lineOfRow(row.coerceAtLeast(0))
        val shownLine = shownLine(line)
        val inLineRow = if (wrap) (row - firstRowOf(line)).coerceAtLeast(0) else 0
        val wanted = if (wrap) inLineRow * columns + column else column
        val start = snap.lineStart(line)
        val len = max(0, snap.lineEnd(line) - start)
        return start + shownLine.offsetOf(wanted).coerceIn(0, len)
    }

    /** Кусок строки, который виден в экранной строке [row]. */
    fun sliceOf(line: Int, row: Int): IntRange {
        if (!wrap) return 0..shownLine(line).text.length
        val inLineRow = (row - firstRowOf(line)).coerceAtLeast(0)
        val from = inLineRow * columns
        val to = min(shownLine(line).text.length, from + columns)
        return from..max(from, to)
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
