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
    }
}

enum class EditKind { TYPING, DELETE, OTHER }

/** История правок: хранит только изменения, соседний ввод склеивается по словам. */
class EditHistory(private val maxSteps: Int = 3000, private val maxChars: Long = 60_000_000L) {
    private class Step(
        var edit: TextEdit,
        val selectionBefore: TextRange,
        var selectionAfter: TextRange,
        var time: Long,
        val kind: EditKind
    )

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var storedChars = 0L
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

    fun record(edit: TextEdit, selectionBefore: TextRange, selectionAfter: TextRange, kind: EditKind) {
        if (edit.isEmpty) return
        redoStack.clear()
        val now = System.currentTimeMillis()
        val last = undoStack.lastOrNull()
        val merged = if (!noMerge && last != null && now - last.time < 1500) tryMerge(last, edit, kind) else null
        noMerge = false
        if (merged != null) {
            storedChars += merged.removed.length + merged.inserted.length -
                (last!!.edit.removed.length + last.edit.inserted.length)
            last.edit = merged
            last.selectionAfter = selectionAfter
            last.time = now
        } else {
            undoStack.addLast(Step(edit, selectionBefore, selectionAfter, now, kind))
            storedChars += edit.removed.length + edit.inserted.length
        }
        while (undoStack.size > 1 && (undoStack.size > maxSteps || storedChars > maxChars)) {
            val dropped = undoStack.removeFirst()
            storedChars -= dropped.edit.removed.length + dropped.edit.inserted.length
        }
        version++
    }

    private fun tryMerge(last: Step, edit: TextEdit, kind: EditKind): TextEdit? {
        if (kind != last.kind || kind == EditKind.OTHER) return null
        val prev = last.edit
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

    fun undo(current: TextFieldValue): TextFieldValue? {
        val step = undoStack.removeLastOrNull() ?: return null
        val e = step.edit
        val text = current.text
        if (e.insertedEnd > text.length || text.regionMatches(e.start, e.inserted, 0, e.inserted.length).not()) {
            // История разошлась с текстом (внешняя замена) — безопаснее её сбросить
            clear()
            return null
        }
        redoStack.addLast(step)
        noMerge = true
        version++
        val newText = text.substring(0, e.start) + e.removed + text.substring(e.insertedEnd)
        return TextFieldValue(newText, clamp(step.selectionBefore, newText.length))
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val step = redoStack.removeLastOrNull() ?: return null
        val e = step.edit
        val text = current.text
        if (e.removedEnd > text.length || text.regionMatches(e.start, e.removed, 0, e.removed.length).not()) {
            clear()
            return null
        }
        undoStack.addLast(step)
        noMerge = true
        version++
        val newText = text.substring(0, e.start) + e.inserted + text.substring(e.removedEnd)
        return TextFieldValue(newText, clamp(step.selectionAfter, newText.length))
    }

    private fun clamp(r: TextRange, len: Int) = TextRange(r.start.coerceIn(0, len), r.end.coerceIn(0, len))
}
