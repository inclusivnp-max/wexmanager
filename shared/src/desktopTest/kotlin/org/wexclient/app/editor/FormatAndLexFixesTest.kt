package org.wexclient.app.editor

import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.editor.lang.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форматирование не портит текст, лексеры не зависают и не путают синтаксис. */
class FormatAndLexFixesTest {
    private fun snap(lang: Language, text: String): HighlightSnapshot =
        HighlightModel(lang).also { it.reset(text) }.snapshot

    private fun tokens(lang: Language, vararg lines: String): List<List<Pair<String, Tok>>> {
        var state = 0
        return lines.map { line ->
            val out = ArrayList<Token>()
            state = lang.lexer.tokenize(line, state, out)
            out.map { line.substring(it.start, it.end) to it.type }
        }
    }

    // ---------- переотступ ----------

    @Test
    fun `reindent keeps multiline string contents including blank lines and trailing spaces`() {
        val q = "\"\"\""
        val kt = "fun f() {\nval s = $q  \n    first\n\n        indented\n   \n$q\n}"
        val out = Reindenter.reindent(snap(Languages.KOTLIN, kt), "    ", 4)
        assertEquals("fun f() {\n    val s = $q  \n    first\n\n        indented\n   \n$q\n}", out)
    }

    @Test
    fun `reindent keeps raw string lines that start with a template`() {
        val q = "\"\"\""
        val kt = "fun f() {\nval s = $q\n\$name is\n  \${x}\n$q\n}"
        val out = Reindenter.reindent(snap(Languages.KOTLIN, kt), "    ", 4)
        assertEquals("fun f() {\n    val s = $q\n\$name is\n  \${x}\n$q\n}", out)
    }

    @Test
    fun `reindent aligns every line of a line comment block`() {
        val kt = "fun f() {\n        // one\n        // two\n  x()\n}"
        assertEquals("fun f() {\n    // one\n    // two\n    x()\n}", Reindenter.reindent(snap(Languages.KOTLIN, kt), "    ", 4))
    }

    @Test
    fun `reindent leaves perl and php heredocs alone`() {
        val pl = "sub f {\nprint <<\"EOF\";\n  body\nEOF\nreturn 1;\n}"
        assertEquals("sub f {\n    print <<\"EOF\";\n  body\nEOF\n    return 1;\n}", Reindenter.reindent(snap(Languages.PERL, pl), "    ", 4))
        val php = "function f() {\n\$a = <<<EOT\nraw\nEOT;\nreturn \$a;\n}"
        assertEquals("function f() {\n    \$a = <<<EOT\nraw\nEOT;\n    return \$a;\n}", Reindenter.reindent(snap(Languages.PHP, php), "    ", 4))
    }

    @Test
    fun `reindent refuses a file too big to lex instead of flattening it`() {
        val kt = "fun f() {\n    x()\n}"
        val model = HighlightModel(Languages.KOTLIN).also { it.maxLexChars = 5; it.reset(kt) }
        val r = CodeFormatter.format(kt, model.snapshot, "    ", 4, null)
        assertNull(r.text)
        assertNotNull(r.error)
    }

    // ---------- XML, JSON ----------

    @Test
    fun `xml formatter treats a stray less-than as text`() {
        assertEquals("<p>1 < 2</p>\n", XmlFormatter.format("<p>1 < 2</p>", "    ", true))
        assertEquals("<div>\n    <p><%= user.name %></p>\n</div>\n", XmlFormatter.format("<div><p><%= user.name %></p></div>", "    ", true))
    }

    @Test
    fun `json formatter keeps json lines and json5 strings intact`() {
        val lines = "{\"a\":1}\n{\"b\":2}\n"
        val jsonl = CodeFormatter.format(lines, snap(Languages.JSON, lines), "  ", 4, java.nio.file.Path.of("data.jsonl"))
        assertNull(jsonl.text)
        assertNotNull(jsonl.error)
        // Несколько значений подряд в обычном файле — не склеиваются в «}{»
        val two = CodeFormatter.format(lines, snap(Languages.JSON, lines), "  ", 4, null)
        assertEquals("{\n  \"a\": 1\n}\n{\n  \"b\": 2\n}\n", two.text)
        val json5 = "{key: 'a  b', other: 1}"
        val f5 = CodeFormatter.format(json5, snap(Languages.JSON, json5), "  ", 4, java.nio.file.Path.of("conf.json5"))
        assertEquals("{\n  key: 'a  b',\n  other: 1\n}\n", f5.text)
        assertTrue(DiagnosticsEngine.quick(snap(Languages.JSON, json5), "conf.json5").none { it.severity == Severity.ERROR })
    }

    @Test
    fun `external output over the cap is reported incomplete`() {
        // Программа выдала чуть больше предела и успешно завершилась — вывод всё равно неполный
        val r = ToolLocator.run(listOf("sh", "-c", "head -c 4200000 /dev/zero; true"), null, timeoutMs = 10_000, maxChars = Int.MAX_VALUE)
            ?: return
        assertEquals(0, r.exitCode)
        assertFalse(r.complete)
    }

    // ---------- лексеры ----------

    @Test
    fun `xml lexer handles a long single-line file quickly`() {
        val line = buildString { repeat(100_000) { append("<a b=\"1\">x</a>") } }
        val started = System.nanoTime()
        val s = snap(Languages.XML, line)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 3000, "разбор: $ms мс")
        assertEquals(Tok.TAG, s.tokenAt(0))
        val t = tokens(Languages.XML, "<x android:id=\"@+id/a\">&amp; &nbsp</x>")[0]
        assertTrue("android:" to Tok.MODIFIER in t)
        assertTrue("id" to Tok.ATTRIBUTE in t)
        assertTrue("&amp;" to Tok.ENTITY in t)
    }

    @Test
    fun `markdown rule of thousands of dashes does not overflow the stack`() {
        val t = tokens(Languages.MARKDOWN, "-".repeat(50_000), "- - -", "--")
        assertEquals(Tok.META, t[0].single().second)
        assertEquals(Tok.META, t[1].single().second)
        assertTrue(t[2].none { it.second == Tok.META })
    }

    @Test
    fun `shell arithmetic shift is not a heredoc`() {
        val t = tokens(Languages.SHELL, "size=\$((1 << n))", "echo done", "cat <<EOF", "text", "EOF")
        assertTrue(t[1].none { it.second == Tok.STRING }, t[1].toString())
        assertEquals(Tok.STRING, t[3].single().second)
        assertTrue(t[4].none { it.second == Tok.STRING })
    }

    @Test
    fun `script tag at the end of a line switches to javascript`() {
        val t = tokens(Languages.HTML, "<script", "  type=\"module\">", "let a = 1;", "</script>")
        assertTrue("let" to Tok.KEYWORD in t[2], t[2].toString())
    }

    @Test
    fun `ruby block comment opens only at the start of a line`() {
        val t = tokens(Languages.RUBY, "x=begin_at", "puts x")
        assertTrue(t[0].none { it.second == Tok.COMMENT }, t[0].toString())
        assertTrue("puts" to Tok.KEYWORD in t[1])
        val c = tokens(Languages.RUBY, "=begin", "anything", "=end", "puts 1")
        assertEquals(Tok.COMMENT, c[1].single().second)
        assertTrue("puts" to Tok.KEYWORD in c[3])
    }

    @Test
    fun `line inside a template literal is not an unterminated string`() {
        val js = "const s = `\n'quoted start\n\${x}\n`;\n"
        val d = DiagnosticsEngine.quick(snap(Languages.JAVASCRIPT, js), "a.js")
        assertTrue(d.none { it.message.contains("Незакрытая") }, d.joinToString { it.message })
        // Настоящая незакрытая строка по-прежнему находится
        val bad = "const s = 'open\nconst t = 1;\n"
        assertTrue(DiagnosticsEngine.quick(snap(Languages.JAVASCRIPT, bad), "b.js").any { it.message.contains("Незакрытая") })
    }
}
