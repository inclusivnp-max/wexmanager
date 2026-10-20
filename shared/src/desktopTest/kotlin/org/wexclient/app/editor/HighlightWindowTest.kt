package org.wexclient.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Раньше подсветка собиралась на весь текст сразу (одна раскладка поля ввода), и приходилось
 * ограничивать её окном вокруг видимой части. Теперь строка раскрашивается только когда её
 * рисуют, поэтому проверяем именно это: спаны ставятся ровно на свою строку и её видимый кусок.
 */
class HighlightWindowTest {
    private val styles = Array<SpanStyle?>(Tok.values().size) { SpanStyle(color = Color.Red) }

    @Test
    fun `раскрашивается только та строка, которую рисуют`() {
        val text = (0 until 1000).joinToString("\n") { "val x$it = \"s$it\" // c" }
        val model = HighlightModel(Languages.detect("a.kt"))
        model.reset(text)
        val snap = model.snapshot

        val line = 100
        val shown = ShownLine.of(
            snap.text.substring(snap.lineStart(line), snap.lineEnd(line)),
            tabSize = 4,
            whitespace = false
        )
        val built = buildShownLine(shown, snap.lineTokens(line), styles, 0..shown.text.length)
        assertTrue(built.spanStyles.isNotEmpty(), "строка раскрашена")
        assertTrue(built.spanStyles.all { it.start >= 0 && it.end <= shown.text.length }, "спаны внутри строки")
    }

    @Test
    fun `у строки без токенов спанов нет`() {
        val model = HighlightModel(Languages.PLAIN)
        model.reset("просто текст\nбез кода\n")
        val snap = model.snapshot
        val shown = ShownLine.of("просто текст", tabSize = 4, whitespace = false)
        val built = buildShownLine(shown, snap.lineTokens(0), styles, 0..shown.text.length)
        assertEquals(0, built.spanStyles.size)
    }
}
