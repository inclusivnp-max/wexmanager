package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange

/**
 * Правка у одной каретки: [from, to) заменяется на [text], выделение после — [selStart, selEnd)
 * от начала вставки (может выходить за неё, если дальше текст не менялся).
 */
internal class CaretEdit(val from: Int, val to: Int, val text: String, val selStart: Int, val selEnd: Int) {
    companion object {
        /** Текст не трогаем, каретка остаётся. */
        fun keep(sel: TextRange) = CaretEdit(sel.min, sel.min, "", sel.start - sel.min, sel.end - sel.min)

        /** Выделение (или ничего) заменить на [s]; каретка — после вставки. */
        fun replace(sel: TextRange, s: String) = CaretEdit(sel.min, sel.max, s, s.length, s.length)

        /** Вставить [s] в [at]; каретка — через [caret] знаков от начала вставки. */
        fun insert(at: Int, s: String, caret: Int = s.length) = CaretEdit(at, at, s, caret, caret)

        /** Удалить [from, to); каретка — на месте удалённого. */
        fun delete(from: Int, to: Int) = CaretEdit(from, to, "", 0, 0)
    }
}

/**
 * Несколько кареток: упорядочить по тексту и слить пересекающиеся. Возвращает каретки и номер
 * основной — той, что содержит [primary] (к ней едет вид и от неё ищется следующее вхождение).
 */
internal fun normalizeCarets(all: List<TextRange>, primary: TextRange, length: Int): Pair<List<TextRange>, Int> {
    fun clamp(r: TextRange) = TextRange(r.start.coerceIn(0, length), r.end.coerceIn(0, length))
    val p = clamp(primary)
    val sorted = (all.map(::clamp) + p).distinct().sortedWith(compareBy({ it.min }, { it.max }))
    val out = ArrayList<TextRange>(sorted.size)
    var primaryIndex = 0
    for (r in sorted) {
        val last = out.lastOrNull()
        // Пересеклись — одна каретка (точка на краю чужого выделения остаётся отдельной)
        if (last != null && (r.min < last.max || (r.min == last.min && r.max == last.max))) {
            val merged = if (r.max > last.max) {
                if (last.start <= last.end) TextRange(last.min, r.max) else TextRange(r.max, last.min)
            } else last
            out[out.size - 1] = merged
        } else {
            out.add(r)
        }
        if (r == p) primaryIndex = out.size - 1
    }
    return out to primaryIndex
}

/** Правки у кареток: набор, удаление, перевод строки — то, что делается в каждой каретке по отдельности. */
internal object CaretOps {
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    /** Набран [typed]: вставить, обернуть выделение парой, перешагнуть закрывающую, дописать парную. */
    fun type(text: String, sel: TextRange, typed: String, cfg: EditConfig): CaretEdit {
        val a = sel.min
        val b = sel.max
        val c = typed.singleOrNull()
        if (c != null && cfg.autoClose) {
            val closer = TextOps.pairCloser(cfg, c)
            if (b > a && closer != null && (c in OPENERS || TextOps.isQuote(c))) {
                val inner = text.substring(a, b)
                return CaretEdit(a, b, "$c$inner$closer", 1, 1 + inner.length)
            }
            if (a == b) {
                val next = text.getOrNull(a)
                val prev = text.getOrNull(a - 1)
                if (next == c && (c in CLOSERS || TextOps.isQuote(c)) && TextOps.isPairCloser(cfg, c)) {
                    return CaretEdit(a, a, "", 1, 1)
                }
                val freeAfter = next == null || next.isWhitespace() || next in ")]};,."
                if (closer != null && c in OPENERS && freeAfter) return CaretEdit.insert(a, "$c$closer", 1)
                val wordBefore = prev != null && (prev.isLetterOrDigit() || prev == '_' || prev == '\\')
                if (closer != null && TextOps.isQuote(c) && freeAfter && !wordBefore) return CaretEdit.insert(a, "$c$closer", 1)
            }
        }
        return CaretEdit.replace(sel, typed)
    }

    /** Backspace: выделение, пару «(|)», отступ до позиции табуляции или знак (с [word] — слово). */
    fun backspace(text: String, sel: TextRange, cfg: EditConfig, word: Boolean): CaretEdit {
        if (!sel.collapsed) return CaretEdit.delete(sel.min, sel.max)
        val c = sel.start
        if (c == 0) return CaretEdit.keep(sel)
        if (word) return CaretEdit.delete(wordStart(text, c, cfg.language::isWordChar), c)
        val prev = text[c - 1]
        val next = text.getOrNull(c)
        if (cfg.autoClose && next != null && TextOps.pairCloser(cfg, prev) == next && (prev in OPENERS || TextOps.isQuote(prev))) {
            return CaretEdit.delete(c - 1, c + 1)
        }
        if (prev == ' ' && cfg.indentUnit != "\t") {
            val ls = TextOps.lineStartOf(text, c)
            if ((ls until c).all { text[it] == ' ' }) {
                val unit = cfg.unitWidth.coerceAtLeast(1)
                return CaretEdit.delete(ls + ((c - ls - 1) / unit) * unit, c)
            }
        }
        return CaretEdit.delete(stepLeft(text, c), c)
    }

    /** Delete: выделение, знак справа (с [word] — до конца слова). */
    fun delete(text: String, sel: TextRange, cfg: EditConfig, word: Boolean): CaretEdit {
        if (!sel.collapsed) return CaretEdit.delete(sel.min, sel.max)
        val c = sel.start
        if (c >= text.length) return CaretEdit.keep(sel)
        return CaretEdit.delete(c, if (word) wordEnd(text, c, cfg.language::isWordChar) else stepRight(text, c))
    }

    /** Enter: перевод строки с отступом текущей; после открывающей скобки — на уровень глубже. */
    fun newline(text: String, sel: TextRange, cfg: EditConfig): CaretEdit {
        val a = sel.min
        val ls = TextOps.lineStartOf(text, a)
        var i = ls
        while (i < a && (text[i] == ' ' || text[i] == '\t')) i++
        val indent = text.substring(ls, i)
        var p = a - 1
        while (p >= ls && (text[p] == ' ' || text[p] == '\t')) p--
        val opener = text.getOrNull(p)?.takeIf { p >= ls && it in OPENERS }
        if (opener != null) {
            val inner = "\n" + indent + cfg.indentUnit
            val after = text.getOrNull(sel.max)
            // Между парными скобками: «{|}» -> три строки
            return if (after == TextOps.closerOf(opener)) CaretEdit(a, sel.max, inner + "\n" + indent, inner.length, inner.length)
            else CaretEdit(a, sel.max, inner, inner.length, inner.length)
        }
        return CaretEdit.replace(sel, "\n" + indent)
    }

    /** Tab без выделения: отступ до следующей позиции табуляции. */
    fun tab(text: String, sel: TextRange, cfg: EditConfig): CaretEdit {
        val insert = if (cfg.indentUnit == "\t") "\t" else {
            val col = TextOps.columnOf(text, sel.min, cfg.tabSize)
            " ".repeat(cfg.unitWidth - col % cfg.unitWidth)
        }
        return CaretEdit.replace(sel, insert)
    }

    /** Дублировать выделение (копия справа и выделена) или строку каретки (каретка — в копии). */
    fun duplicate(text: String, sel: TextRange): CaretEdit {
        if (!sel.collapsed) {
            val piece = text.substring(sel.min, sel.max)
            return CaretEdit(sel.max, sel.max, piece, 0, piece.length)
        }
        val ls = TextOps.lineStartOf(text, sel.start)
        val le = TextOps.lineEndOf(text, sel.start)
        val line = text.substring(ls, le)
        return CaretEdit.insert(le, "\n" + line, 1 + (sel.start - ls))
    }
}

/**
 * Строки, которых касаются каретки (конец выделения в самом начале строки её не захватывает) —
 * для построчных команд: отступ, комментарий, удаление строк.
 */
internal fun caretLines(snap: HighlightSnapshot, carets: List<TextRange>): List<Int> {
    val lines = java.util.TreeSet<Int>()
    for (c in carets) {
        val first = snap.lineOf(c.min)
        var last = snap.lineOf(c.max)
        if (c.max > c.min && last > first && c.max == snap.lineStart(last)) last--
        for (l in first..last) lines.add(l)
    }
    return lines.toList()
}

/** Позиция после слоя правок (по возрастанию, в координатах до них); вставка в самой позиции её сдвигает. */
internal fun mapThroughLayer(p: Int, layer: List<TextEdit>): Int {
    var shift = 0
    for (e in layer) {
        if (e.start > p) break
        if (p < e.removedEnd) return e.start + shift + minOf(p - e.start, e.inserted.length)
        shift += e.delta
    }
    return p + shift
}

/**
 * Следующее вхождение [needle] после [from] (по кругу), не попадающее в уже выделенные [taken].
 * [wholeWord] — только отдельным словом (выделение начато со слова под кареткой, как в IntelliJ).
 */
internal fun nextOccurrence(
    text: String,
    needle: String,
    from: Int,
    taken: List<TextRange>,
    wholeWord: Boolean,
    isWordChar: (Char) -> Boolean
): Int? {
    if (needle.isEmpty()) return null
    fun ok(at: Int): Boolean {
        if (taken.any { it.min == at && it.max == at + needle.length }) return false
        if (!wholeWord) return true
        val before = at == 0 || !isWordChar(text[at - 1])
        val end = at + needle.length
        return before && (end >= text.length || !isWordChar(text[end]))
    }
    var i = text.indexOf(needle, from)
    while (i >= 0) {
        if (ok(i)) return i
        i = text.indexOf(needle, i + 1)
    }
    i = text.indexOf(needle)
    while (i in 0 until from) {
        if (ok(i)) return i
        i = text.indexOf(needle, i + 1)
    }
    return null
}

/** Все вхождения — для «Выделить все вхождения» (не больше [limit]). */
internal fun allOccurrences(text: String, needle: String, wholeWord: Boolean, isWordChar: (Char) -> Boolean, limit: Int): List<TextRange> {
    val out = ArrayList<TextRange>()
    if (needle.isEmpty()) return out
    var i = text.indexOf(needle)
    while (i >= 0 && out.size < limit) {
        val end = i + needle.length
        val whole = !wholeWord || ((i == 0 || !isWordChar(text[i - 1])) && (end >= text.length || !isWordChar(text[end])))
        if (whole) {
            out.add(TextRange(i, end))
            i = text.indexOf(needle, end)
        } else {
            i = text.indexOf(needle, i + 1)
        }
    }
    return out
}

/** Сколько кареток не больше: дальше каждое нажатие перестраивает слишком много. */
internal const val MAX_CARETS = 10_000
