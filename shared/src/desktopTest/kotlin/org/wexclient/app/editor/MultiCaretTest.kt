package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.Languages
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Несколько кареток: правка во всех сразу, отмена одним шагом, вхождения, поиск в выделении. */
class MultiCaretTest {
    private fun ctrl(text: String): CodeEditorController =
        CodeEditorController(text).also { it.applyLanguage(Languages.KOTLIN) }

    private fun cfg(c: CodeEditorController) = c.config(4, autoClose = true)

    /** Текст с каретками «|» (выделения не нужны для этих проверок). */
    private fun show(c: CodeEditorController): String {
        val sb = StringBuilder(c.text)
        for (r in c.carets().sortedByDescending { it.end }) sb.insert(r.end, '|')
        return sb.toString()
    }

    private fun carets(c: CodeEditorController, vararg at: Int) =
        c.setCarets(at.map { TextRange(it) }, TextRange(at.last()))

    private fun type(c: CodeEditorController, s: String) =
        c.editCarets(EditKind.TYPING) { text, sel -> CaretOps.type(text, sel, s, cfg(c)) }

    @Test
    fun `overlapping carets merge and the primary survives`() {
        val (list, primary) = normalizeCarets(
            listOf(TextRange(5), TextRange(2, 6), TextRange(10), TextRange(10)),
            TextRange(10),
            20
        )
        assertEquals(listOf(TextRange(2, 6), TextRange(10)), list)
        assertEquals(1, primary)
    }

    @Test
    fun `typing goes to every caret and undoes as one step`() {
        val c = ctrl("aa\nbb\ncc\n")
        carets(c, 0, 3, 6)
        type(c, "x")
        type(c, "y")
        assertEquals("xy|aa\nxy|bb\nxy|cc\n", show(c))
        assertTrue(c.isDirty)
        assertTrue(c.undo())
        assertEquals("|aa\n|bb\n|cc\n", show(c))
        assertFalse(c.undo(), "набор подряд — один шаг отмены")
        assertTrue(c.redo())
        assertEquals("xy|aa\nxy|bb\nxy|cc\n", show(c))
        assertEquals(c.text, c.model.text)
    }

    @Test
    fun `auto pairs backspace and delete work per caret`() {
        val c = ctrl("f\ng\n")
        carets(c, 1, 3)
        type(c, "(")
        assertEquals("f(|)\ng(|)\n", show(c))
        c.editCarets(EditKind.DELETE) { t, s -> CaretOps.backspace(t, s, cfg(c), word = false) }
        assertEquals("f|\ng|\n", show(c))
        c.editCarets(EditKind.DELETE) { t, s -> CaretOps.backspace(t, s, cfg(c), word = false) }
        assertEquals("|\n|\n", show(c))
        // Соседние каретки съедают общий кусок без ошибок и сливаются
        val d = ctrl("hello world")
        carets(d, 3, 5)
        d.editCarets(EditKind.DELETE) { t, s -> CaretOps.backspace(t, s, cfg(d), word = true) }
        assertEquals("| world", show(d))
    }

    @Test
    fun `alt j selects the word then next occurrences and alt shift j takes one back`() {
        val c = ctrl("val foo = foo + food\nfoo()\n")
        c.select(5)
        assertTrue(c.selectNextOccurrence())
        assertEquals(TextRange(4, 7), c.value.selection)
        assertTrue(c.selectNextOccurrence())
        assertTrue(c.selectNextOccurrence())
        // «food» — не отдельное слово: третье вхождение — в следующей строке
        assertEquals(listOf(TextRange(4, 7), TextRange(10, 13), TextRange(21, 24)), c.carets())
        assertFalse(c.selectNextOccurrence(), "по кругу — только уже выделенные")
        assertTrue(c.unselectLastOccurrence())
        assertEquals(listOf(TextRange(4, 7), TextRange(10, 13)), c.carets())
        assertEquals(TextRange(10, 13), c.value.selection)
        type(c, "bar")
        assertEquals("val bar = bar + food\nfoo()\n", c.text)
    }

    @Test
    fun `select all occurrences and paste one line per caret`() {
        val c = ctrl("a = x\nb = x\nc = x\n")
        c.select(4)
        assertEquals(3, c.selectAllOccurrences())
        c.pasteAtCarets("1\n2\n3\n")
        assertEquals("a = 1\nb = 2\nc = 3\n", c.text)
        // Строк в буфере не столько, сколько кареток, — весь текст в каждую
        c.pasteAtCarets("zz")
        assertEquals("a = 1zz\nb = 2zz\nc = 3zz\n", c.text)
        // Копирование нескольких выделений — через перевод строки
        c.setCarets(listOf(TextRange(4, 7), TextRange(12, 15), TextRange(20, 23)), TextRange(20, 23))
        assertEquals("1zz\n2zz\n3zz", c.caretClipboardText())
    }

    @Test
    fun `line commands apply to every caret line`() {
        val c = ctrl("one\n    two\nthree\n")
        carets(c, 1, 6)
        assertTrue(c.toggleCommentAtCarets())
        assertEquals("// one\n//     two\nthree\n", c.text)
        assertTrue(c.toggleCommentAtCarets())
        assertEquals("one\n    two\nthree\n", c.text)
        c.shiftLinesAtCarets(indent = true)
        assertEquals("    one\n        two\nthree\n", c.text)
        // Каретки уехали вместе с текстом своих строк
        assertEquals("    o|ne\n      |  two\nthree\n", show(c))
        c.shiftLinesAtCarets(indent = false)
        assertEquals("one\n    two\nthree\n", c.text)
        c.deleteLinesAtCarets()
        assertEquals("three\n", c.text)
        assertTrue(c.undo())
        assertEquals("one\n    two\nthree\n", c.text)
    }

    @Test
    fun `carets at line ends and vertical carets`() {
        val c = ctrl("ab\ncdef\ng\n")
        c.select(0, 9)
        assertTrue(c.caretsAtLineEnds())
        assertEquals(listOf(TextRange(2), TextRange(7), TextRange(9)), c.carets())
        val d = ctrl("abcd\nx\nabcd\n")
        d.select(3)
        val lay = FastLayout(d.snapshot, 4, false, 0)
        assertTrue(d.addCaretVertically(true, lay::rowAndColumn, lay::offsetAt, lay.totalRows))
        assertTrue(d.addCaretVertically(true, lay::rowAndColumn, lay::offsetAt, lay.totalRows))
        // Короткая строка не сбивает колонку: третья каретка снова в колонке 3
        assertEquals(listOf(TextRange(3), TextRange(6), TextRange(10)), d.carets())
    }

    @Test
    fun `single caret edit or click drops the extra carets`() {
        val c = ctrl("abc\ndef\n")
        carets(c, 1, 5)
        assertTrue(c.hasMultipleCarets)
        c.applyEdit(TextFieldValue("Xabc\ndef\n", TextRange(1)))
        assertFalse(c.hasMultipleCarets)
        carets(c, 1, 5)
        c.onFieldChange(c.value.copy(selection = TextRange(2)), cfg(c), readOnly = false)
        assertFalse(c.hasMultipleCarets)
        carets(c, 1, 5)
        assertTrue(c.clearExtraCarets())
        assertEquals(listOf(TextRange(5)), c.carets())
    }

    @Test
    fun `random multi caret edits undo and redo exactly`() {
        val rnd = Random(11)
        val pieces = listOf("a", "б", " ", "\n", "\t", "()", "{", "😀")
        repeat(150) { round ->
            val start = buildString { repeat(rnd.nextInt(120)) { append(pieces[rnd.nextInt(pieces.size)]) } }
            val c = ctrl(start)
            val states = arrayListOf(c.text)
            repeat(1 + rnd.nextInt(25)) {
                val len = c.text.length
                val n = 1 + rnd.nextInt(5)
                val at = List(n) { if (len == 0) 0 else rnd.nextInt(len + 1) }
                c.setCarets(at.map { TextRange(it, (it + rnd.nextInt(3)).coerceAtMost(len)) }, TextRange(at.last()))
                when (rnd.nextInt(5)) {
                    0 -> type(c, pieces[rnd.nextInt(pieces.size)])
                    1 -> c.editCarets(EditKind.DELETE) { t, s -> CaretOps.backspace(t, s, cfg(c), word = rnd.nextBoolean()) }
                    2 -> c.editCarets(EditKind.DELETE) { t, s -> CaretOps.delete(t, s, cfg(c), word = rnd.nextBoolean()) }
                    3 -> c.editCarets(EditKind.OTHER) { t, s -> CaretOps.newline(t, s, cfg(c)) }
                    else -> c.pasteAtCarets(List(c.carets().size) { "p$it" }.joinToString("\n"))
                }
                if (c.text != states.last()) states.add(c.text)
                assertEquals(c.text, c.model.text, "раунд $round: модель")
            }
            val final = c.text
            while (c.undo()) Unit
            assertEquals(start, c.text, "раунд $round: после полной отмены")
            while (c.redo()) Unit
            assertEquals(final, c.text, "раунд $round: после полного повтора")
        }
    }

    // ---------- поиск в выделении ----------

    @Test
    fun `search within a region sees word boundaries around it`() {
        val text = "cat cat\ncat concat cat\ncat"
        val region = 8 until 22
        val m = SearchEngine.findAll(text, SearchOptions("cat", wholeWord = true), within = region)
        assertEquals(2, m.count)
        assertEquals(8, m.start(0))
        assertEquals(19, m.start(1))
        // Граница куска посреди слова — это не граница слова
        assertEquals(0, SearchEngine.findAll(text, SearchOptions("cat", wholeWord = true), within = 15 until 18).count)
        val (out, n) = SearchEngine.replaceAll(text, SearchOptions("cat", wholeWord = true), "dog", within = region)
        assertEquals(2, n)
        assertEquals("cat cat\ndog concat dog\ncat", out)
    }

    @Test
    fun `search scope follows edits`() {
        val c = ctrl("aaa\nbbb\nccc\n")
        c.searchScope = TextRange(4, 8)
        c.applyEdit(TextFieldValue("XXaaa\nbbb\nccc\n", TextRange(2)))
        assertEquals(TextRange(6, 10), c.searchScope)
        // Правка внутри области меняет её конец
        c.applyEdit(TextFieldValue("XXaaa\nbYYbb\nccc\n", TextRange(9)))
        assertEquals(TextRange(6, 12), c.searchScope)
        // Правка после — не трогает
        c.applyEdit(TextFieldValue("XXaaa\nbYYbb\ncccZ\n", TextRange(16)))
        assertEquals(TextRange(6, 12), c.searchScope)
    }
}
