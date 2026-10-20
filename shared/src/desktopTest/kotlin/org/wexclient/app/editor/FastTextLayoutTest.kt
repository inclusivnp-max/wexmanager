package org.wexclient.app.editor

import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.SyntaxColors
import org.wexclient.app.editor.lang.Tok
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Быстрый редактор считает положение знаков арифметикой по ширине моноширинного знака.
 * Здесь проверяется то, что ломает эту арифметику: табуляция и перенос строк.
 */
class FastTextLayoutTest {

    private fun snapshotOf(text: String): HighlightSnapshot =
        HighlightModel(Languages.PLAIN).also { it.reset(text) }.snapshot

    // ---------- развёртка табуляции ----------

    @Test
    fun `строка без табов показывается как есть`() {
        val shown = ShownLine.of("abc def", tabSize = 4, whitespace = false)
        assertEquals("abc def", shown.text)
        assertEquals(7, shown.width)
        assertEquals(5, shown.columnOf(5))
        assertEquals(5, shown.offsetOf(5))
    }

    @Test
    fun `таб в начале строки растягивается до позиции табуляции`() {
        val shown = ShownLine.of("\tab", tabSize = 4, whitespace = false)
        assertEquals("    ab", shown.text)
        assertEquals(6, shown.width)
        // Сам таб стоит в колонке 0, следующий за ним знак — в колонке 4
        assertEquals(0, shown.columnOf(0))
        assertEquals(4, shown.columnOf(1))
        assertEquals(5, shown.columnOf(2))
    }

    @Test
    fun `таб добирает только до ближайшей позиции табуляции`() {
        // "aaa" занимает колонки 0..2, табу остаётся одна колонка
        val shown = ShownLine.of("aaa\tb", tabSize = 4, whitespace = false)
        assertEquals("aaa b", shown.text)
        assertEquals(5, shown.width)
        assertEquals(4, shown.columnOf(4))
    }

    @Test
    fun `два таба подряд дают две позиции табуляции`() {
        val shown = ShownLine.of("\t\tx", tabSize = 4, whitespace = false)
        assertEquals("        x", shown.text)
        assertEquals(9, shown.width)
        assertEquals(8, shown.columnOf(2))
    }

    @Test
    fun `щелчок внутри таба попадает на сам таб`() {
        val shown = ShownLine.of("\tab", tabSize = 4, whitespace = false)
        // Колонки 0..3 — это один знак табуляции
        assertEquals(0, shown.offsetOf(0))
        assertEquals(0, shown.offsetOf(2))
        assertEquals(0, shown.offsetOf(3))
        assertEquals(1, shown.offsetOf(4))
        // За концом строки — последний возможный знак
        assertEquals(3, shown.offsetOf(99))
    }

    @Test
    fun `пометка пробелов не меняет ширину`() {
        val plain = ShownLine.of("a b\tc", tabSize = 4, whitespace = false)
        val marked = ShownLine.of("a b\tc", tabSize = 4, whitespace = true)
        assertEquals(plain.width, marked.width)
        assertEquals(plain.text.length, marked.text.length)
        assertTrue(marked.text.contains(ShownLine.SPACE_MARK))
        assertTrue(marked.text.contains(ShownLine.TAB_MARK))
    }

    @Test
    fun `размер табуляции учитывается`() {
        val two = ShownLine.of("\tx", tabSize = 2, whitespace = false)
        val eight = ShownLine.of("\tx", tabSize = 8, whitespace = false)
        assertEquals(2, two.columnOf(1))
        assertEquals(8, eight.columnOf(1))
    }

    // ---------- раскладка без переноса ----------

    @Test
    fun `без переноса каждая строка занимает одну экранную`() {
        val snap = snapshotOf("один\nдва\nтри\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0)
        assertEquals(snap.lineCount, layout.totalRows)
        assertEquals(2, layout.firstRowOf(2))
        assertEquals(2, layout.lineOfRow(2))
    }

    @Test
    fun `позиция в тексте и экранная колонка переводятся друг в друга через табы`() {
        val snap = snapshotOf("\t\tкод\nдругая\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0)
        // Третий знак первой строки — «к» — стоит в колонке 8
        val (row, column) = layout.rowAndColumn(2)
        assertEquals(0, row)
        assertEquals(8, column)
        assertEquals(2, layout.offsetAt(0, 8))
        // Щелчок в середине второго таба возвращает сам таб
        assertEquals(1, layout.offsetAt(0, 6))
    }

    @Test
    fun `позиции во второй строке считаются от её начала`() {
        val snap = snapshotOf("первая\nвторая\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0)
        val offset = snap.lineStart(1) + 3
        val (row, column) = layout.rowAndColumn(offset)
        assertEquals(1, row)
        assertEquals(3, column)
        assertEquals(offset, layout.offsetAt(row, column))
    }

    // ---------- раскладка с переносом ----------

    @Test
    fun `длинная строка занимает несколько экранных`() {
        val snap = snapshotOf("a".repeat(25) + "\nкоротко\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 10)
        assertEquals(3, layout.firstRowOf(1)) // 25 знаков при ширине 10 — три экранные строки
        // Строк три: длинная, «коротко» и пустая после последнего перевода строки
        assertEquals(5, layout.totalRows)
        assertEquals(0, layout.lineOfRow(1))
        assertEquals(0, layout.lineOfRow(2))
        assertEquals(1, layout.lineOfRow(3))
    }

    @Test
    fun `при переносе колонка считается внутри экранной строки`() {
        val snap = snapshotOf("a".repeat(25) + "\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 10)
        val (row, column) = layout.rowAndColumn(12)
        assertEquals(1, row)
        assertEquals(2, column)
        assertEquals(12, layout.offsetAt(1, 2))
    }

    @Test
    fun `перенос с табами не теряет позиции`() {
        val snap = snapshotOf("\t" + "b".repeat(20) + "\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 8)
        // Ширина строки: 4 колонки таба + 20 знаков = 24 -> три экранные строки плюс пустая
        assertEquals(4, layout.totalRows)
        val offset = 5 // таб и четыре «b»
        val (row, column) = layout.rowAndColumn(offset)
        assertEquals(offset, layout.offsetAt(row, column))
    }

    @Test
    fun `пустая строка занимает одну экранную`() {
        val snap = snapshotOf("\n\n\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 10)
        assertEquals(snap.lineCount, layout.totalRows)
    }

    @Test
    fun `кусок строки для экранной строки не выходит за её пределы`() {
        val snap = snapshotOf("a".repeat(25) + "\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 10)
        val slice = layout.sliceOf(0, 2)
        assertEquals(20, slice.first)
        assertEquals(25, slice.last)
    }

    // ---------- свёртки ----------

    @Test
    fun `свёрнутый участок прячет свои строки`() {
        val snap = snapshotOf("один\nдва\nтри\nчетыре\n")
        // Свёртка от конца первой строки до конца третьей — прячет строки 2 и 3
        val collapsed = listOf(snap.lineStart(0)..(snap.lineEnd(2)))
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0, collapsed = collapsed)
        assertTrue(layout.isHidden(1))
        assertTrue(layout.isHidden(2))
        assertEquals(false, layout.isHidden(0))
        assertEquals(false, layout.isHidden(3))
        // Было пять строк (с пустой в конце), спрятали две
        assertEquals(snap.lineCount - 2, layout.totalRows)
    }

    @Test
    fun `после свёртки экранные строки идут подряд`() {
        val snap = snapshotOf("один\nдва\nтри\nчетыре\n")
        val collapsed = listOf(snap.lineStart(0)..(snap.lineEnd(2)))
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0, collapsed = collapsed)
        // Первая экранная строка — первая настоящая, вторая — уже «четыре»
        assertEquals(0, layout.lineOfRow(0))
        assertEquals(3, layout.lineOfRow(1))
        assertEquals(1, layout.firstRowOf(3))
    }

    @Test
    fun `позиция за свёрткой попадает в нужную строку`() {
        val snap = snapshotOf("один\nдва\nтри\nчетыре\n")
        val collapsed = listOf(snap.lineStart(0)..(snap.lineEnd(2)))
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0, collapsed = collapsed)
        val offset = snap.lineStart(3) + 2
        val (row, column) = layout.rowAndColumn(offset)
        assertEquals(1, row)
        assertEquals(2, column)
        assertEquals(offset, layout.offsetAt(row, column))
    }

    @Test
    fun `без свёрток скрытых строк нет`() {
        val snap = snapshotOf("один\nдва\n")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 0)
        assertEquals(false, layout.isHidden(0))
        assertEquals(snap.lineCount, layout.totalRows)
    }

    // ---------- подсветка показанной строки ----------

    @Test
    fun `спаны подсветки сдвигаются вместе с табами`() {
        val shown = ShownLine.of("\tkey", tabSize = 4, whitespace = false)
        val styles = arrayOfNulls<SpanStyle>(Tok.values().size)
        styles[Tok.KEYWORD.ordinal] = SyntaxColors.style(Tok.KEYWORD)
        // Токен «key» — знаки 1..4 строки, на экране это колонки 4..7
        val tokens = intArrayOf(1, 4, Tok.KEYWORD.ordinal)
        val built = buildShownLine(shown, tokens, styles, 0..shown.text.length)
        assertEquals("    key", built.text)
        val span = built.spanStyles.single()
        assertEquals(4, span.start)
        assertEquals(7, span.end)
    }

    @Test
    fun `в перенесённой части строки спаны остаются на своих местах`() {
        val shown = ShownLine.of("abcdefghij" + "keyword", tabSize = 4, whitespace = false)
        val styles = arrayOfNulls<SpanStyle>(Tok.values().size)
        styles[Tok.KEYWORD.ordinal] = SyntaxColors.style(Tok.KEYWORD)
        val tokens = intArrayOf(10, 17, Tok.KEYWORD.ordinal)
        val built = buildShownLine(shown, tokens, styles, 10..17)
        assertEquals("keyword", built.text)
        val span = built.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(7, span.end)
    }

    // ---------- слова ----------

    @Test
    fun `переход по словам идёт через разделители`() {
        val text = "один два_три  четыре"
        val isWord: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }
        assertEquals(0, wordStart(text, 4, isWord))
        assertEquals(5, wordStart(text, 12, isWord))
        // Ctrl+стрелка вправо встаёт в начало следующего слова, а не в конец текущего
        assertEquals(5, wordEnd(text, 0, isWord))
        assertEquals(14, wordEnd(text, 5, isWord))
    }

    @Test
    fun `переход по словам не перепрыгивает конец строки`() {
        val text = "слово\nдругое"
        val isWord: (Char) -> Boolean = { it.isLetterOrDigit() }
        assertEquals(5, wordEnd(text, 0, isWord))
        assertEquals(6, wordStart(text, 12, isWord))
    }

    @Test
    fun `на границе строки переход по словам шагает через перевод строки`() {
        val text = "слово\n  другое"
        val isWord: (Char) -> Boolean = { it.isLetterOrDigit() }
        // Ctrl+← в начале строки — в конец предыдущей (раньше каретка стояла на месте)
        assertEquals(5, wordStart(text, 6, isWord))
        // Ctrl+→ в конце строки — на следующую
        assertEquals(6, wordEnd(text, 5, isWord))
        // Из отступа — к началу строки, а не через неё
        assertEquals(6, wordStart(text, 8, isWord))
    }

    @Test
    fun `конец строки ровно на границе переноса остаётся на её последней экранной строке`() {
        val snap = snapshotOf("a".repeat(20) + "\nb")
        val layout = FastLayout(snap, tabSize = 4, whitespace = false, columns = 10)
        assertEquals(2, layout.rowsOf(0))
        // Каретка в конце первой строки — в конце второй экранной строки, а не в начале следующей строки текста
        assertEquals(1 to 10, layout.rowAndColumn(20))
        assertEquals(20, layout.offsetAt(1, 10))
        // Середина строки на границе переноса — по-прежнему начало следующей экранной строки
        assertEquals(1 to 0, layout.rowAndColumn(10))
        assertEquals(2 to 0, layout.rowAndColumn(snap.lineStart(1)))
    }



}
