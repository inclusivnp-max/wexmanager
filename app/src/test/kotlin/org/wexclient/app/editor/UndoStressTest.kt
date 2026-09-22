package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Случайные правки (набор, удаление, вставка с \r\n): полная отмена и повтор дают исходный и конечный текст. */
class UndoStressTest {
    @Test
    fun `random edits undo and redo exactly`() {
        val rnd = Random(77)
        val pieces = listOf("a", "б", " ", "\n", "\t", "fun ", "\r\n", "😀", "{}", "()")
        repeat(300) { round ->
            val start = buildString { repeat(rnd.nextInt(200)) { append(pieces[rnd.nextInt(pieces.size)]) } }.replace("\r\n", "\n")
            val ctrl = CodeEditorController(start)
            val states = ArrayList<String>()
            states.add(ctrl.value.text)
            repeat(1 + rnd.nextInt(40)) {
                val t = ctrl.value.text
                val pos = if (t.isEmpty()) 0 else rnd.nextInt(t.length + 1)
                val kind = rnd.nextInt(3)
                val newText: String
                val caret: Int
                when (kind) {
                    0 -> { // набор
                        val ins = pieces[rnd.nextInt(pieces.size)]
                        newText = t.substring(0, pos) + ins + t.substring(pos)
                        caret = pos + ins.length
                    }
                    1 -> { // удаление
                        val end = minOf(t.length, pos + rnd.nextInt(5))
                        newText = t.substring(0, pos) + t.substring(end)
                        caret = pos
                    }
                    else -> { // вставка куска
                        val ins = buildString { repeat(rnd.nextInt(6)) { append(pieces[rnd.nextInt(pieces.size)]) } }
                        val end = minOf(t.length, pos + rnd.nextInt(3))
                        newText = t.substring(0, pos) + ins + t.substring(end)
                        caret = pos + ins.length
                    }
                }
                val editKind = if (kind == 0) EditKind.TYPING else if (kind == 1) EditKind.DELETE else EditKind.OTHER
                ctrl.applyEdit(TextFieldValue(newText, TextRange(caret.coerceIn(0, newText.length))), editKind)
                if (ctrl.value.text != states.last()) states.add(ctrl.value.text)
            }
            val final = ctrl.value.text
            // \r\n в редакторе хранится как \n
            assertEquals(false, final.contains('\r'), "раунд $round")
            while (ctrl.undo()) Unit
            assertEquals(start, ctrl.value.text, "раунд $round: после полной отмены")
            while (ctrl.redo()) Unit
            assertEquals(final, ctrl.value.text, "раунд $round: после полного повтора")
            // Модель подсветки идёт за текстом
            assertEquals(final, ctrl.model.text, "раунд $round: модель")
        }
    }
}
