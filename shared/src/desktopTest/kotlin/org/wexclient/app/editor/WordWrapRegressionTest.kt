package org.wexclient.app.editor

import org.wexclient.app.editor.lang.Languages
import kotlin.random.Random
import kotlin.test.*

class WordWrapRegressionTest {
    private fun snapshot(text: String) = HighlightModel(Languages.PLAIN).also { it.reset(text) }.snapshot
    private fun layout(text: String, columns: Int) = FastLayout(snapshot(text), 4, false, columns)
    private fun parts(layout: FastLayout, line: Int = 0): List<String> =
        (layout.firstRowOf(line) until layout.firstRowOf(line) + layout.rowsOf(line)).map { row ->
            val slice = layout.sliceOf(line, row)
            layout.shownLine(line).text.substring(slice.first, slice.last)
        }

    @Test fun `words move intact to the next visual row`() {
        assertEquals(listOf("alpha ", "beta ", "gamma"), parts(layout("alpha beta gamma", 9)))
    }
    @Test fun `unbroken tokens still fit the viewport`() {
        assertEquals(listOf("abcdefgh", "ijklmnop", "qr"), parts(layout("abcdefghijklmnopqr", 8)))
    }
    @Test fun `indentation does not create an empty visual row`() {
        assertEquals(listOf("    bbbb", "bbbbbbbb", "bbbbbbbb"), parts(layout("\t" + "b".repeat(20), 8)))
    }
    @Test fun `large target columns stop at this visual row`() {
        val l = layout("alpha beta gamma", 9)
        assertEquals(6, l.offsetAt(0, Int.MAX_VALUE))
        assertEquals(11, l.offsetAt(1, 100))
        assertEquals(16, l.offsetAt(2, 100))
        assertEquals(6, l.offsetAt(1, -10))
    }
    @Test fun `wrap boundary has two valid visual caret positions`() {
        val l = layout("alpha beta gamma", 9)
        assertEquals(1 to 0, l.rowAndColumn(6))
        assertEquals(0 to 6, l.rowAndColumn(6, previousRow = true))
        assertEquals(2 to 5, l.rowAndColumn(16, previousRow = true))
    }
    @Test fun `surrogate pairs and combining marks stay together at wrap`() {
        for (text in listOf("a😀bc😀def", "abе\u0301fg", "👩\u200D💻ab")) for (columns in 1..7) {
            val l = layout(text, columns)
            val rows = parts(l)
            assertEquals(text, rows.joinToString(""))
            for (row in rows) {
                assertFalse(row.isEmpty())
                assertFalse(Character.isLowSurrogate(row.first()))
                assertFalse(Character.isHighSurrogate(row.last()))
                assertFalse(Character.getType(row.first()) == Character.NON_SPACING_MARK.toInt())
                assertFalse(row.first() == '\u200D' || row.last() == '\u200D')
            }
        }
    }
    @Test fun `tabs preserve source offset round trips`() {
        val text = "\talpha beta\tlonger text"
        val l = layout(text, 9)
        for (offset in 0..text.length) {
            val (row, col) = l.rowAndColumn(offset)
            assertEquals(offset, l.offsetAt(row, col), "offset=$offset row=$row col=$col")
        }
    }
    @Test fun `wrap partitions every line without dropping text`() {
        val random = Random(109)
        repeat(100) {
            val text = buildString { repeat(random.nextInt(1, 300)) { append("abcde -/\t"[random.nextInt(9)]) } }
            val l = layout(text, random.nextInt(1, 50))
            assertEquals(l.shownLine(0).text, parts(l).joinToString(""))
            for (offset in 0..text.length) {
                val (row, col) = l.rowAndColumn(offset)
                assertEquals(offset, l.offsetAt(row, col), "text=$text offset=$offset")
            }
        }
    }
    @Test fun `width changes invalidate row boundaries but not source offsets`() {
        val snap = snapshot("alpha beta gamma delta epsilon")
        val holder = LayoutHolder()
        val narrow = holder.get(snap, 4, false, 10)
        assertSame(narrow, holder.get(snap, 4, false, 10))
        val wide = holder.get(snap, 4, false, 20)
        assertNotSame(narrow, wide)
        assertTrue(wide.totalRows < narrow.totalRows)
        for (offset in 0..snap.text.length) {
            val (row, col) = wide.rowAndColumn(offset)
            assertEquals(offset, wide.offsetAt(row, col))
        }
    }
    @Test fun `end of visual row remains there until caret or layout changes`() {
        val ctrl = CodeEditorController("alpha beta gamma")
        val l = layout(ctrl.text, 10)
        val state = FastSurfaceState(ctrl)
        state.setCaretRow(l, 6, 0)
        assertEquals(0 to 6, state.caretPosition(l, 6))
        assertEquals(1 to 1, state.caretPosition(l, 7))
        assertEquals(1 to 0, state.caretPosition(layout(ctrl.text, 10), 6))
        state.setCaretRow(l, 6, 1)
        assertEquals(1 to 0, state.caretPosition(l, 6))
    }
    @Test fun `folded lines stay hidden with variable row lengths`() {
        val snap = snapshot("alpha beta gamma\nsecond line\nthird line\nlast")
        val l = FastLayout(snap, 4, false, 9, listOf(0..snap.lineEnd(2)))
        assertEquals(3, l.rowsOf(0))
        assertEquals(0, l.rowsOf(1))
        assertEquals(0, l.rowsOf(2))
        assertEquals(3, l.lineOfRow(3))
    }
    @Test fun `wrap setting belongs to its document and is default for new ones`() {
        val original = EditorPreferences.softWrap
        try {
            EditorPreferences.updateSoftWrap(false)
            val first = CodeEditorController("one")
            val second = CodeEditorController("two")
            first.updateSoftWrap(true)
            assertTrue(first.softWrap)
            assertFalse(second.softWrap)
            assertTrue(CodeEditorController("three").softWrap)
        } finally { EditorPreferences.updateSoftWrap(original) }
    }
}
