package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.Languages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Кэши и история редактора не растут без предела на больших файлах и долгом вводе. */
class EditorMemoryBoundsTest {

    @Test
    fun `кэш показанных строк ограничен и вытесненная строка считается заново`() {
        val lines = MAX_SHOWN_LINES * 3
        val text = (0 until lines).joinToString("\n") { "line $it\twith tab" }
        val snap = HighlightModel(Languages.PLAIN).also { it.reset(text) }.snapshot
        val lay = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0)
        for (line in 0 until lines) lay.shownLine(line)
        assertTrue(lay.shownCount <= MAX_SHOWN_LINES, "${lay.shownCount}")
        // Первая строка давно вытеснена — раскладка та же, что и в первый раз
        val expected = ShownLine.of("line 0\twith tab", tabSize = 4, whitespace = false)
        assertEquals(expected.text, lay.shownLine(0).text)
        assertEquals(0 to 8, lay.rowAndColumn(7))
        assertEquals(7, lay.offsetAt(0, 8))
    }

    @Test
    fun `ввод многими каретками не выходит за предел истории`() {
        val h = EditHistory(maxSteps = 100_000, maxChars = 20_000)
        repeat(5_000) { k ->
            val layer = List(10) { i -> TextEdit(i * 2, "", "a") }
            h.recordCarets(layer, TextRange(k), TextRange(k + 1), emptyList(), emptyList(), EditKind.TYPING)
        }
        assertTrue(h.storedChars <= 20_000, "${h.storedChars}")
        assertTrue(h.canUndo)
    }

    @Test
    fun `мелкие правки весят больше своих знаков`() {
        val h = EditHistory(maxSteps = 100_000, maxChars = 65_000)
        repeat(10_000) { h.record(TextEdit(it, "", "x"), TextRange(it), TextRange(it + 1), EditKind.OTHER) }
        assertTrue(h.storedChars <= 65_000, "${h.storedChars}")
        var value = TextFieldValue("x".repeat(10_000), TextRange(10_000))
        var steps = 0
        while (true) {
            value = h.undo(value) ?: break
            steps++
        }
        // Раньше все 10 000 правок в один знак считались за 10 000 знаков и оставались в истории
        assertTrue(steps < 10_000, "$steps")
        assertEquals(10_000 - steps, value.text.length)
    }

    @Test
    fun `сброшенный повтор уходит из счёта`() {
        val h = EditHistory()
        h.record(TextEdit(0, "", "abc"), TextRange(0), TextRange(3), EditKind.OTHER)
        assertEquals("", h.undo(TextFieldValue("abc", TextRange(3)))!!.text)
        h.record(TextEdit(0, "", "x"), TextRange(0), TextRange(1), EditKind.OTHER)
        val fresh = EditHistory()
        fresh.record(TextEdit(0, "", "x"), TextRange(0), TextRange(1), EditKind.OTHER)
        assertEquals(fresh.storedChars, h.storedChars)
    }
}
