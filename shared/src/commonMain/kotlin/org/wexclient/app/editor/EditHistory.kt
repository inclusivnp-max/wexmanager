package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Одна правка: в позиции [start] текст [removed] заменён на [inserted]. */
class TextEdit(val start: Int, val removed: String, val inserted: String) {
    val removedEnd: Int get() = start + removed.length
    val insertedEnd: Int get() = start + inserted.length
    val delta: Int get() = inserted.length - removed.length
    val isEmpty: Boolean get() = removed.isEmpty() && inserted.isEmpty()

    companion object {
        /** Минимальная правка между двумя текстами (общие префикс и суффикс). */
        fun diff(old: String, new: String): TextEdit {
            val min = minOf(old.length, new.length)
            val prefix = commonPrefix(old, new, min)
            val suffix = commonSuffix(old, new, min - prefix)
            return TextEdit(prefix, old.substring(prefix, old.length - suffix), new.substring(prefix, new.length - suffix))
        }

        // Сравнение блоками через regionMatches (в JDK — векторизованный mismatch): посимвольный
        // цикл по файлу в 5 МБ занимал на каждое нажатие клавиши миллисекунды
        private const val FIRST_BLOCK = 256
        private const val MAX_BLOCK = 1 shl 22

        internal fun commonPrefix(a: String, b: String, limit: Int): Int {
            var p = 0
            var step = FIRST_BLOCK
            while (p + step <= limit && a.regionMatches(p, b, p, step)) {
                p += step
                if (step < MAX_BLOCK) step = step shl 1
            }
            // Первое расхождение (или конец) — в [p, hi]
            var lo = p
            var hi = minOf(p + step, limit)
            while (hi - lo > 32) {
                val mid = (lo + hi) ushr 1
                if (a.regionMatches(lo, b, lo, mid - lo)) lo = mid else hi = mid
            }
            while (lo < hi && a[lo] == b[lo]) lo++
            return lo
        }

        internal fun commonSuffix(a: String, b: String, limit: Int): Int {
            val al = a.length
            val bl = b.length
            var s = 0
            var step = FIRST_BLOCK
            while (s + step <= limit && a.regionMatches(al - s - step, b, bl - s - step, step)) {
                s += step
                if (step < MAX_BLOCK) step = step shl 1
            }
            var lo = s
            var hi = minOf(s + step, limit)
            while (hi - lo > 32) {
                val mid = (lo + hi) ushr 1
                // Совпадают ли символы с конца от lo до mid
                if (a.regionMatches(al - mid, b, bl - mid, mid - lo)) lo = mid else hi = mid
            }
            while (lo < hi && a[al - 1 - lo] == b[bl - 1 - lo]) lo++
            return lo
        }

        /**
         * Слой правок (у каждой каретки своя, не пересекаются, по возрастанию позиций, в координатах
         * текста до слоя) — применить за один проход. null — текст не тот, к которому они относятся.
         */
        internal fun applyLayer(text: String, layer: List<TextEdit>): String? {
            val sb = StringBuilder(text.length + layer.sumOf { it.delta }.coerceAtLeast(0))
            var pos = 0
            for (e in layer) {
                if (e.start < pos || e.removedEnd > text.length || !text.regionMatches(e.start, e.removed, 0, e.removed.length)) return null
                sb.append(text, pos, e.start).append(e.inserted)
                pos = e.removedEnd
            }
            sb.append(text, pos, text.length)
            return sb.toString()
        }

        /** Обратное к [applyLayer]: из текста после слоя — текст до него. */
        internal fun revertLayer(text: String, layer: List<TextEdit>): String? {
            val sb = StringBuilder(text.length)
            var pos = 0
            var shift = 0
            for (e in layer) {
                val at = e.start + shift
                if (at < pos || at + e.inserted.length > text.length || !text.regionMatches(at, e.inserted, 0, e.inserted.length)) return null
                sb.append(text, pos, at).append(e.removed)
                pos = at + e.inserted.length
                shift += e.delta
            }
            sb.append(text, pos, text.length)
            return sb.toString()
        }
    }
}

enum class EditKind { TYPING, DELETE, OTHER }

/**
 * История правок: хранит только изменения, соседний ввод склеивается по словам.
 * [maxChars] — предел в знаках вместе с весом самих правок (см. [EDIT_OVERHEAD]).
 */
class EditHistory(private val maxSteps: Int = 3000, private val maxChars: Long = 24_000_000L) {
    /**
     * Шаг отмены — слои правок по порядку. Обычная правка — один слой с одной правкой; ввод
     * несколькими каретками — по слою на нажатие, в слое по правке на каретку.
     */
    private class Step(
        val layers: MutableList<List<TextEdit>>,
        val selectionBefore: TextRange,
        var selectionAfter: TextRange,
        var time: Long,
        val kind: EditKind,
        val extrasBefore: List<TextRange> = emptyList(),
        var extrasAfter: List<TextRange> = emptyList(),
        val multi: Boolean = false
    ) {
        /** Правка единственная — такую можно склеить с соседним вводом. */
        val single: TextEdit? get() = layers.singleOrNull()?.singleOrNull()

        /** Вес шага в знаках — столько же вычитается, когда шаг уходит из истории. */
        var cost = 0L
    }

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    /** Вес всей истории (отмена и повтор) в знаках; внутреннее — для тестов. */
    internal var storedChars = 0L
        private set
    private var noMerge = false

    /** Меняется при каждой операции — для перерисовки кнопок. */
    var version = 0L
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        storedChars = 0
        version++
    }

    /** Следующая правка не склеится с предыдущей (курсор переместили, сохранили и т.п.). */
    fun breakGroup() {
        noMerge = true
    }

    private fun costOf(edit: TextEdit): Long = edit.removed.length + edit.inserted.length + EDIT_OVERHEAD

    private fun costOf(layer: List<TextEdit>): Long = layer.sumOf { costOf(it) }

    /** Новая правка отменяет повтор — и его вес уходит из счёта, иначе счёт рос без самих шагов. */
    private fun dropRedo() {
        for (step in redoStack) storedChars -= step.cost
        redoStack.clear()
    }

    fun record(edit: TextEdit, selectionBefore: TextRange, selectionAfter: TextRange, kind: EditKind) {
        if (edit.isEmpty) return
        dropRedo()
        val now = System.currentTimeMillis()
        val last = undoStack.lastOrNull()
        val prev = last?.single
        val merged = if (!noMerge && last != null && prev != null && !last.multi && now - last.time < MERGE_MS) {
            tryMerge(prev, last.kind, edit, kind)
        } else null
        noMerge = false
        if (merged != null) {
            val grow = costOf(merged) - costOf(prev!!)
            last.cost += grow
            storedChars += grow
            last.layers[0] = listOf(merged)
            last.selectionAfter = selectionAfter
            last.time = now
        } else {
            val step = Step(mutableListOf(listOf(edit)), selectionBefore, selectionAfter, now, kind)
            step.cost = costOf(edit)
            undoStack.addLast(step)
            storedChars += step.cost
        }
        trim()
    }

    /**
     * Правка несколькими каретками: [layer] — по правке на каретку (по возрастанию позиций, в
     * координатах текста до правки). Ввод и удаление подряд копятся в одном шаге, как и одной кареткой.
     */
    fun recordCarets(
        layer: List<TextEdit>,
        selectionBefore: TextRange,
        selectionAfter: TextRange,
        extrasBefore: List<TextRange>,
        extrasAfter: List<TextRange>,
        kind: EditKind
    ) {
        val edits = layer.filterNot { it.isEmpty }
        if (edits.isEmpty()) return
        dropRedo()
        val now = System.currentTimeMillis()
        val last = undoStack.lastOrNull()
        // Слишком тяжёлый шаг больше не растёт: единственный шаг trim() не трогает, и долгий ввод
        // тысячами кареток копил слои без предела
        val mergeable = !noMerge && last != null && last.multi && last.kind == kind && kind != EditKind.OTHER &&
            now - last.time < MERGE_MS && edits.none { it.inserted.contains('\n') } && last.cost < maxChars / 8
        noMerge = false
        val cost = costOf(edits)
        if (mergeable) {
            last!!.layers.add(edits)
            last.selectionAfter = selectionAfter
            last.extrasAfter = extrasAfter
            last.time = now
            last.cost += cost
        } else {
            val step = Step(mutableListOf(edits), selectionBefore, selectionAfter, now, kind, extrasBefore, extrasAfter, multi = true)
            step.cost = cost
            undoStack.addLast(step)
        }
        storedChars += cost
        trim()
    }

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > maxSteps || storedChars > maxChars)) {
            storedChars -= undoStack.removeFirst().cost
        }
        version++
    }

    private fun tryMerge(prev: TextEdit, prevKind: EditKind, edit: TextEdit, kind: EditKind): TextEdit? {
        if (kind != prevKind || kind == EditKind.OTHER) return null
        return when (kind) {
            EditKind.TYPING -> {
                if (edit.removed.isNotEmpty() || edit.start != prev.insertedEnd) return null
                if (edit.inserted.contains('\n') || prev.inserted.endsWith('\n')) return null
                // Граница слова: после пробела начинается новый шаг отмены
                val lastChar = prev.inserted.lastOrNull()
                val firstChar = edit.inserted.firstOrNull()
                if (lastChar != null && firstChar != null && lastChar.isWhitespace() && !firstChar.isWhitespace()) return null
                TextEdit(prev.start, prev.removed, prev.inserted + edit.inserted)
            }
            EditKind.DELETE -> {
                if (edit.inserted.isNotEmpty() || prev.inserted.isNotEmpty()) return null
                when {
                    // Backspace: удаляем перед предыдущим удалением
                    edit.removedEnd == prev.start -> TextEdit(edit.start, edit.removed + prev.removed, "")
                    // Delete: удаляем с той же позиции
                    edit.start == prev.start -> TextEdit(prev.start, prev.removed + edit.removed, "")
                    else -> null
                }
            }
            EditKind.OTHER -> null
        }
    }

    /** Каретки, которые вернула последняя отмена или повтор (кроме основной). */
    var restoredExtras: List<TextRange> = emptyList()
        private set

    fun undo(current: TextFieldValue): TextFieldValue? {
        val step = undoStack.removeLastOrNull() ?: return null
        var text = current.text
        for (layer in step.layers.asReversed()) {
            text = TextEdit.revertLayer(text, layer) ?: run {
                // История разошлась с текстом (внешняя замена) — безопаснее её сбросить
                clear()
                return null
            }
        }
        redoStack.addLast(step)
        noMerge = true
        version++
        restoredExtras = step.extrasBefore.map { clamp(it, text.length) }
        return TextFieldValue(text, clamp(step.selectionBefore, text.length))
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val step = redoStack.removeLastOrNull() ?: return null
        var text = current.text
        for (layer in step.layers) {
            text = TextEdit.applyLayer(text, layer) ?: run {
                clear()
                return null
            }
        }
        undoStack.addLast(step)
        noMerge = true
        version++
        restoredExtras = step.extrasAfter.map { clamp(it, text.length) }
        return TextFieldValue(text, clamp(step.selectionAfter, text.length))
    }

    private fun clamp(r: TextRange, len: Int) = TextRange(r.start.coerceIn(0, len), r.end.coerceIn(0, len))

    private companion object {
        /** Ввод подряд быстрее этого — один шаг отмены. */
        const val MERGE_MS = 1500L

        /**
         * Вес одной правки сверх её знаков: объект, две строки и место в списке — около 100 байт.
         * Без него тысячи правок в один знак (ввод многими каретками) почти ничего «не весили».
         */
        const val EDIT_OVERHEAD = 64L
    }
}
