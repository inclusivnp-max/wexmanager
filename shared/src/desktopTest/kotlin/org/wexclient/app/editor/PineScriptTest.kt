package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.editor.lang.Token
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PineScriptTest {
    private val pine = Languages.PINE

    private fun tokens(vararg lines: String): List<List<Pair<String, Tok>>> {
        var state = 0
        return lines.map { line ->
            val out = ArrayList<Token>()
            state = pine.lexer.tokenize(line, state, out)
            // Токены по порядку и без перекрытий
            out.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "перекрытие в «$line»") }
            out.map { line.substring(it.start, it.end) to it.type }
        }
    }

    private fun snap(text: String): HighlightSnapshot {
        val m = HighlightModel(pine)
        m.reset(text)
        return m.snapshot
    }

    private fun show(value: TextFieldValue): String {
        val t = value.text
        return t.substring(0, value.selection.start) + "|" + t.substring(value.selection.start)
    }

    private fun newline(text: String): String {
        val v = TextFieldValue(text, TextRange(text.length))
        return show(TextOps.newline(v, EditConfig(pine, "    ", 4), snap(text)))
    }

    private val script = """
        //@version=6
        indicator("Мой RSI", overlay = false)

        length = input.int(14, "Длина", minval = 1)
        src = input.source(close, "Источник")

        type Pivot
            float price = na
            int bar = 0

        method describe(Pivot this) =>
            str.tostring(this.price)

        smooth(float x, int n) =>
            ta.ema(x, n)

        var float last = na
        value = smooth(ta.rsi(src, length), 3)
        col = value > 70 ? #FF5252 : color.new(#2962FFCC, 20)
        if ta.crossover(value, 30)
            label.new(bar_index, low, "Buy", color = col)
        plot(value, "RSI", color = col)
    """.trimIndent()

    @Test
    fun `pine files are detected and get their own type`() {
        assertEquals(pine, Languages.detect("rsi.pine"))
        assertEquals(pine, Languages.detect("MyStrategy.PINE"))
        assertEquals(pine, Languages.detect("script.txt", script).let { if (it === Languages.PLAIN) Languages.sniff(script) else it })
        assertEquals(pine, Languages.sniff("//@version=5\nstrategy(\"x\")\n"))
        assertEquals(VfsItemType.CODE_PINE, VfsEntry.detectType("rsi.pine", false))
        assertTrue(pine.significantIndent)
    }

    @Test
    fun `pine highlighting`() {
        val t = tokens(
            "//@version=6",
            "indicator(\"Мой RSI\", overlay = false)",
            "var float last = na",
            "col = value > 70 ? #FF5252 : color.new(#2962FFCC, 20)",
            "for i = 0 to 10 by 2",
            "method describe(Pivot this) =>",
            "// обычный комментарий #FF0000",
            "s = \"цвет #FF0000 в строке\""
        )
        assertEquals(listOf("//@version" to Tok.DOC_TAG, "=6" to Tok.DOC_COMMENT), t[0])
        assertTrue("indicator" to Tok.BUILTIN in t[1])
        assertTrue("\"Мой RSI\"" to Tok.STRING in t[1])
        assertTrue("false" to Tok.CONSTANT in t[1])
        assertEquals(listOf("var" to Tok.MODIFIER, "float" to Tok.TYPE, "=" to Tok.OPERATOR, "na" to Tok.CONSTANT), t[2])
        assertTrue("#FF5252" to Tok.CONSTANT in t[3])
        assertTrue("#2962FFCC" to Tok.CONSTANT in t[3])
        assertTrue("color" to Tok.TYPE in t[3])
        assertTrue("new" to Tok.FUNCTION in t[3])
        assertTrue("20" to Tok.NUMBER in t[3])
        assertFalse(t[3].any { it.first == "FF5252" || it.first == "2962" })
        assertTrue("for" to Tok.CONTROL in t[4] && "to" to Tok.CONTROL in t[4] && "by" to Tok.CONTROL in t[4])
        assertTrue("method" to Tok.KEYWORD in t[5])
        assertTrue("describe" to Tok.FUNCTION in t[5])
        assertTrue("=>" to Tok.OPERATOR in t[5])
        assertEquals(listOf("// обычный комментарий #FF0000" to Tok.COMMENT), t[6])
        assertEquals(Tok.STRING, t[7].last().second)
        assertFalse(t[7].any { it.second == Tok.CONSTANT })

        val ta = tokens("x = ta.sma(close, 14)")[0]
        assertTrue("ta" to Tok.BUILTIN in ta)
        assertTrue("sma" to Tok.FUNCTION in ta)
        assertTrue("close" to Tok.BUILTIN in ta)

        // После точки — члены, а не типы/ключевые слова
        val members = tokens("n = input.int(14) + input.float(1.5)", "c = color.red", "// @function Сглаживание")
        assertTrue("int" to Tok.FUNCTION in members[0] && "float" to Tok.FUNCTION in members[0], members[0].toString())
        assertTrue("1.5" to Tok.NUMBER in members[0])
        assertFalse(members[1].any { it.first == "red" })
        assertEquals(listOf("// @function" to Tok.DOC_TAG, " Сглаживание" to Tok.DOC_COMMENT), members[2])
    }

    @Test
    fun `pine indentation`() {
        assertEquals("f(x) =>\n    |", newline("f(x) =>"))
        assertEquals("if close > open\n    |", newline("if close > open"))
        assertEquals("for i = 0 to 10\n    |", newline("for i = 0 to 10"))
        assertEquals("x = switch mode\n    |", newline("x = switch mode"))
        assertEquals("type Pivot\n    |", newline("type Pivot"))
        // Обычное присваивание и сравнение отступ не добавляют
        assertEquals("x = a == b ? 1 : 2\n|", newline("x = a == b ? 1 : 2"))
        assertEquals("plot(close)\n|", newline("plot(close)"))
    }

    @Test
    fun `pine outline and folding`() {
        val s = snap(script)
        val folds = FoldingEngine.compute(s)
        assertTrue(folds.isNotEmpty(), "блоки по отступам сворачиваются")
        val outline = OutlineEngine.compute(s, folds)
        val names = outline.map { it.name to it.kind }
        assertTrue("Мой RSI" to ItemKind.MODULE in names, names.toString())
        assertTrue("Pivot" to ItemKind.TYPE in names, names.toString())
        assertTrue("describe" to ItemKind.FUNCTION in names, names.toString())
        assertTrue("smooth" to ItemKind.FUNCTION in names, names.toString())
        assertTrue("length" to ItemKind.PROPERTY in names, names.toString())
        assertTrue("src" to ItemKind.PROPERTY in names, names.toString())
    }

    private fun complete(textWithCaret: String): CompletionResult? {
        val c = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(c, c + 1)
        return CompletionEngine.complete(CompletionRequest(text, c, pine, snap(text), WordIndexer.build(text, pine, 1), false, null))
    }

    @Test
    fun `pine completion is namespace aware`() {
        val ta = complete("x = ta.cro|")!!
        val labels = ta.items.map { it.label }
        assertTrue("crossover" in labels && "crossunder" in labels, labels.take(10).toString())
        assertFalse(labels.any { it.startsWith("ta.") }, "после «ta.» — только имена членов")
        assertTrue(ta.items.first { it.label == "crossover" }.detail.contains("source1"))

        val colors = complete("c = color.te|")!!
        assertTrue(colors.items.any { it.label == "teal" }, colors.items.take(5).map { it.label }.toString())

        val nested = complete("strategy.commission.pe|")!!
        assertEquals("percent", nested.items.first().label)

        // Без точки — полное имя (набрали «crossover» → ta.crossover)
        val bare = complete("if crossov|")!!
        assertTrue(bare.items.any { it.label == "ta.crossover" }, bare.items.take(5).map { it.label }.toString())

        val snippet = complete("indicator|")!!
        assertTrue(snippet.items.any { it.isSnippet && it.label == "indicator" })

        // Lua тоже получает члены по «table.»
        val lua = CompletionEngine.complete(
            CompletionRequest("table.ins", 9, Languages.LUA, null, null, false, null)
        )!!
        assertTrue(lua.items.any { it.label == "insert" }, lua.items.take(5).map { it.label }.toString())
    }
}
