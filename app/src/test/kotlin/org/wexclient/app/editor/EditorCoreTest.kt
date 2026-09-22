package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.SmaliLexer
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.editor.lang.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorCoreTest {
    private fun tokens(lang: Language, vararg lines: String): List<List<Pair<String, Tok>>> {
        var state = 0
        return lines.map { line ->
            val out = ArrayList<Token>()
            state = lang.lexer.tokenize(line, state, out)
            out.map { line.substring(it.start, it.end) to it.type }
        }
    }

    private fun snap(lang: Language, text: String): HighlightSnapshot {
        val m = HighlightModel(lang)
        m.reset(text)
        return m.snapshot
    }

    /** Текст с курсором «|» (или выделением «[» … «]»). */
    private fun v(s: String): TextFieldValue {
        val sel = s.indexOf('[')
        if (sel >= 0) {
            val end = s.indexOf(']')
            return TextFieldValue(s.removeRange(end, end + 1).removeRange(sel, sel + 1), TextRange(sel, end - 1))
        }
        val c = s.indexOf('|')
        return TextFieldValue(s.removeRange(c, c + 1), TextRange(c))
    }

    private fun show(value: TextFieldValue): String {
        val t = value.text
        return if (value.selection.collapsed) t.substring(0, value.selection.start) + "|" + t.substring(value.selection.start)
        else t.substring(0, value.selection.min) + "[" + t.substring(value.selection.min, value.selection.max) + "]" + t.substring(value.selection.max)
    }

    private fun cfg(lang: Language, indent: String = "    ") = EditConfig(lang, indent, 4)

    // ---------- Лексеры ----------

    @Test
    fun `kotlin lexer handles keywords strings templates and multiline comments`() {
        val t = tokens(Languages.KOTLIN, "fun main() { val s = \"a\${x}b\$y\" /* start", "still comment */ println(1L)")
        assertTrue("fun" to Tok.KEYWORD in t[0])
        assertTrue("main" to Tok.FUNCTION in t[0])
        assertTrue("\${x}" to Tok.VARIABLE in t[0])
        assertTrue("\$y" to Tok.VARIABLE in t[0])
        assertTrue("/* start" to Tok.COMMENT in t[0])
        assertTrue("still comment */" to Tok.COMMENT in t[1])
        assertTrue("println" to Tok.BUILTIN in t[1])
        assertTrue("1L" to Tok.NUMBER in t[1])
    }

    @Test
    fun `lua block comments win over line comments and rust lifetimes are not strings`() {
        val lua = tokens(Languages.LUA, "--[[ block", "end ]] local x = 1 -- tail")
        assertEquals(Tok.COMMENT, lua[0].single().second)
        assertTrue("local" to Tok.KEYWORD in lua[1])
        assertTrue("-- tail" to Tok.COMMENT in lua[1])
        val rust = tokens(Languages.RUST, "fn f<'a>(x: &'a str) -> char { 'x' }")
        assertTrue("'a" to Tok.LABEL in rust[0])
        assertTrue("'x'" to Tok.STRING in rust[0])
    }

    @Test
    fun `smali lexer knows opcodes registers labels types and members`() {
        assertTrue("invoke-virtual" in SmaliLexer.opcodes, "dexlib2 opcode names: ${SmaliLexer.opcodes.take(5)}")
        assertTrue("const/4" in SmaliLexer.opcodes)
        val t = tokens(
            Languages.SMALI,
            ".method public onCreate(Landroid/os/Bundle;)V",
            "    invoke-virtual {p0, v1}, Lcom/a/B;->foo(I)V",
            "    iget-object v0, p0, Lcom/a/B;->mName:Ljava/lang/String;",
            "    if-eqz v0, :cond_0 # comment"
        )
        assertTrue(".method" to Tok.DIRECTIVE in t[0])
        assertTrue("onCreate" to Tok.FUNCTION in t[0])
        assertTrue("Landroid/os/Bundle;" to Tok.TYPE in t[0])
        assertTrue("invoke-virtual" to Tok.OPCODE in t[1])
        assertTrue("p0" to Tok.REGISTER in t[1])
        assertTrue("Lcom/a/B;" to Tok.TYPE in t[1])
        assertTrue("foo" to Tok.FUNCTION in t[1])
        assertTrue("mName" to Tok.PROPERTY in t[2])
        assertTrue("Ljava/lang/String;" to Tok.TYPE in t[2])
        assertTrue(":cond_0" to Tok.LABEL in t[3])
        assertTrue("# comment" to Tok.COMMENT in t[3])
    }

    @Test
    fun `xml lexer handles android attributes values and multiline comments`() {
        val t = tokens(Languages.XML, "<TextView android:text=\"@string/app\" a='x&amp;y'/> <!-- c", "c -->")
        assertTrue("<TextView" to Tok.TAG in t[0])
        assertTrue("android:" to Tok.MODIFIER in t[0])
        assertTrue("text" to Tok.ATTRIBUTE in t[0])
        assertTrue("@string/app" to Tok.LINK in t[0])
        assertTrue("&amp;" to Tok.ENTITY in t[0])
        assertTrue("c -->" to Tok.COMMENT in t[1])
    }

    @Test
    fun `html lexer switches to js and css inside script and style`() {
        val t = tokens(Languages.HTML, "<script>", "const x = 1; // hi", "</script><style>a { color: red }</style>")
        assertTrue("const" to Tok.KEYWORD in t[1])
        assertTrue("// hi" to Tok.COMMENT in t[1])
        assertTrue("color" to Tok.PROPERTY in t[2])
        assertTrue("</script" to Tok.TAG in t[2])
    }

    @Test
    fun `shell heredoc and kdl nodes`() {
        val sh = tokens(Languages.SHELL, "cat <<EOF > f", "if not code \$x", "EOF", "echo done")
        assertEquals(Tok.STRING, sh[1].single().second)
        assertEquals(Tok.META, sh[2].single().second)
        assertTrue("echo" to Tok.BUILTIN in sh[3])
        val kdl = tokens(Languages.KDL, "binds {", "    Mod+T hotkey-overlay-title=\"Term\" { spawn \"alacritty\"; }", "}")
        assertTrue("binds" to Tok.TAG in kdl[0])
        assertTrue("Mod+T" to Tok.TAG in kdl[1])
        assertTrue("hotkey-overlay-title" to Tok.PROPERTY in kdl[1])
        assertTrue("spawn" to Tok.TAG in kdl[1])
    }

    @Test
    fun `language detection by name shebang and content`() {
        assertEquals("kotlin", Languages.detect("build.gradle.kts").id)
        assertEquals("smali", Languages.detect("MainActivity.smali").id)
        assertEquals("shell", Languages.detect("PKGBUILD").id)
        assertEquals("kdl", Languages.detect("config.kdl").id)
        assertEquals("proguard", Languages.detect("proguard-rules.pro").id)
        assertEquals("python", Languages.detect("tool", "#!/usr/bin/env python3\nprint(1)").id)
        assertEquals("json", Languages.detect("config", "{\n  \"layer\": \"top\"\n}").id)
        assertEquals("ini", Languages.detect("pacman.conf.pacnew").id)
        assertEquals("xml", Languages.detect("noext", "<?xml version=\"1.0\"?><a/>").id)
        assertEquals("cpp", Languages.detect("x.h", "namespace foo { class A {}; }").id)
        assertEquals("qml", Languages.detect("Bar.qml").id)
        assertTrue(Languages.all.size >= 60, "languages: ${Languages.all.size}")
        assertEquals("  ", Languages.detectIndent("a:\n  b:\n    c: 1\n  d: 2\n", Languages.YAML))
        assertEquals("\t", Languages.detectIndent("func a() {\n\tx()\n\tif y {\n\t\tz()\n\t}\n}\n", Languages.GO))
    }

    // ---------- Модель подсветки ----------

    @Test
    fun `incremental highlight equals full relex after edits`() {
        val text0 = "fun a() {\n    val s = \"x\"\n}\n/* c */\nfun b() = 1\n"
        val m = HighlightModel(Languages.KOTLIN)
        m.reset(text0)
        var text = text0
        val edits = listOf(
            TextEdit(0, "", "/*"),
            TextEdit(2, "", "\n\n"),
            TextEdit(0, "/*\n\n", ""),
            TextEdit(text0.indexOf("\"x\""), "\"x\"", "\"\"\"multi\nline\"\"\""),
        )
        for (e in edits) {
            val newText = text.substring(0, e.start) + e.inserted + text.substring(e.start + e.removed.length)
            m.update(newText, e)
            val full = HighlightModel(Languages.KOTLIN).apply { reset(newText) }
            assertEquals(full.lineCount, m.lineCount)
            for (i in 0 until full.lineCount) {
                assertTrue(full.snapshot.lineTokens(i).contentEquals(m.snapshot.lineTokens(i)), "line $i after edit '${e.inserted}'")
            }
            text = newText
        }
    }


    // ---------- Операции ----------

    @Test
    fun `auto close pairs skip closers and wrap selection`() {
        val c = cfg(Languages.KOTLIN)
        val s = snap(Languages.KOTLIN, "foo")
        assertEquals("foo(|)", show(TextOps.onCharTyped(v("foo|"), '(', c, s)!!))
        assertEquals("foo()|", show(TextOps.onCharTyped(v("foo(|)"), ')', c, snap(Languages.KOTLIN, "foo()"))!!))
        assertEquals("(\"[abc]\")", show(TextOps.onCharTyped(v("([abc])"), '"', c, s)!!))
        assertEquals("x = \"|\"", show(TextOps.onCharTyped(v("x = |"), '"', c, s)!!))
        assertNull(TextOps.onCharTyped(v("don|"), '\'', c, s))
        assertEquals("s = \"\"\"|\"\"\"", show(TextOps.onCharTyped(v("s = \"\"|"), '"', c, snap(Languages.KOTLIN, "s = \"\""))!!))
    }

    @Test
    fun `enter indents after brace and splits pairs`() {
        val c = cfg(Languages.KOTLIN)
        val text = "fun a() {}"
        val r = TextOps.newline(v("fun a() {|}"), c, snap(Languages.KOTLIN, text))
        assertEquals("fun a() {\n    |\n}", show(r))
        val r2 = TextOps.newline(v("    foo(x)|"), c, snap(Languages.KOTLIN, "    foo(x)"))
        assertEquals("    foo(x)\n    |", show(r2))
        val py = cfg(Languages.PYTHON)
        assertEquals("def f():\n    |", show(TextOps.newline(v("def f():|"), py, snap(Languages.PYTHON, "def f():"))))
        assertEquals("    return 1\n|", show(TextOps.newline(v("    return 1|"), py, snap(Languages.PYTHON, "    return 1"))))
        val sh = cfg(Languages.SHELL)
        val shText = "if x; then\n    echo\n    fi"
        assertEquals("if x; then\n    echo\nfi\n|", show(TextOps.newline(TextFieldValue(shText, TextRange(shText.length)), sh, snap(Languages.SHELL, shText))))
        val md = cfg(Languages.MARKDOWN, "  ")
        assertEquals("- a\n- |", show(TextOps.newline(v("- a|"), md, snap(Languages.MARKDOWN, "- a"))))
        assertEquals("1. a\n2. |", show(TextOps.newline(v("1. a|"), md, snap(Languages.MARKDOWN, "1. a"))))
        val xml = cfg(Languages.XML)
        val xt = "<a></a>"
        assertEquals("<a>\n    |\n</a>", show(TextOps.newline(v("<a>|</a>"), xml, snap(Languages.XML, xt))))
        val doc = "/**|"
        assertEquals("/**\n * |\n */", show(TextOps.newline(v(doc), c, snap(Languages.KOTLIN, "/**"))))
    }

    @Test
    fun `xml closing tags are inserted`() {
        val c = cfg(Languages.XML)
        assertEquals("<LinearLayout a=\"b\">|</LinearLayout>", show(TextOps.onCharTyped(v("<LinearLayout a=\"b\"|"), '>', c, snap(Languages.XML, "<LinearLayout a=\"b\""))!!))
        val t = "<a>\n    <b>\n    </b>\n    <"
        assertEquals("<a>\n    <b>\n    </b>\n</a>|", show(TextOps.onCharTyped(TextFieldValue(t, TextRange(t.length)), '/', c, snap(Languages.XML, t))!!))
        assertNull(TextOps.onCharTyped(v("<br/|"), '>', c, snap(Languages.XML, "<br/")))
    }

    @Test
    fun `line operations duplicate delete move comment join case`() {
        val c = cfg(Languages.KOTLIN)
        // Как в IntelliJ: курсор переходит на копию
        assertEquals("a\nb\nb|", show(TextOps.duplicate(v("a\nb|"))))
        // Колонка курсора сохраняется
        assertEquals("a\nc|", show(TextOps.deleteLines(v("a\nb|\nc"))))
        assertEquals("b|\na\nc", show(TextOps.moveLines(v("a\nb|\nc"), up = true)!!))
        assertEquals("a\nc\nb|", show(TextOps.moveLines(v("a\nb|\nc"), up = false)!!))
        assertEquals("    // x\ny|", show(TextOps.toggleLineComment(v("    x|\ny"), c)))
        assertEquals("    x\ny|", show(TextOps.toggleLineComment(v("    // x|\ny"), c)))
        assertEquals("a |b", show(TextOps.joinLines(v("a|\n    b"), c)))
        assertEquals("[FOO]", show(TextOps.toggleCase(v("[foo]")) { it.isLetter() }))
        assertEquals("<!-- <a/> -->", TextOps.toggleLineComment(v("<a/>|"), cfg(Languages.XML)).text)
        // Выделение от начала строки остаётся от начала — вместе с новым отступом
        assertEquals("[    x\n    y]", show(TextOps.indentLines(v("[x\ny]"), c)))
        assertEquals("[x\ny]", show(TextOps.unindent(v("    [x\n    y]"), c)))
        assertEquals("    |x", show(TextOps.smartHome(v("    x|"), false)))
        assertEquals("|    x", show(TextOps.smartHome(v("    |x"), false)))
    }

    @Test
    fun `ctrl w extends selection step by step`() {
        val lang = Languages.KOTLIN
        val text = "fun a() {\n    foo(\"hello world\", barBaz)\n}"
        val s = snap(lang, text)
        val c = cfg(lang)
        var value = TextFieldValue(text, TextRange(text.indexOf("world") + 2))
        val steps = ArrayList<String>()
        repeat(20) {
            val r = TextOps.expandSelection(value, c, s) ?: return@repeat
            value = value.copy(selection = r)
            steps.add(text.substring(r.min, r.max))
        }
        assertEquals("world", steps[0])
        assertEquals("hello world", steps[1])
        assertEquals("\"hello world\"", steps[2])
        assertTrue("\"hello world\", barBaz" in steps)
        assertTrue("foo(\"hello world\", barBaz)" in steps)
        assertEquals(text, steps.last())
        // camelCase: сначала часть слова
        val v2 = TextFieldValue(text, TextRange(text.indexOf("Baz") + 1))
        assertEquals("Baz", TextOps.expandSelection(v2, c, s)!!.let { text.substring(it.min, it.max) })
    }

    @Test
    fun `backspace removes pairs and smart unindents`() {
        val c = cfg(Languages.KOTLIN)
        assertEquals("foo|", show(TextOps.onBackspace(v("foo(|)"), c, snap(Languages.KOTLIN, "foo()"))!!))
        assertEquals("    |x", show(TextOps.onBackspace(v("      |x"), c, snap(Languages.KOTLIN, "      x"))!!))
        assertNull(TextOps.onBackspace(v("ab|"), c, snap(Languages.KOTLIN, "ab")))
    }

    @Test
    fun `history merges typing and undoes redoes`() {
        val h = EditHistory()
        var value = TextFieldValue("")
        for (ch in "ab c") {
            val before = value
            value = TextFieldValue(value.text + ch, TextRange(value.text.length + 1))
            h.record(TextEdit.diff(before.text, value.text), before.selection, value.selection, EditKind.TYPING)
        }
        value = h.undo(value)!!
        assertEquals("ab ", value.text)
        value = h.undo(value)!!
        assertEquals("", value.text)
        value = h.redo(value)!!
        assertEquals("ab ", value.text)
        assertNull(h.redo(h.redo(value)!!))
    }

    // ---------- Кодировки ----------

    @Test
    fun `codec detects bom cp1251 and line separators`() {
        val utf = TextCodec.decode("привет\r\nмир".toByteArray())
        assertEquals("привет\nмир", utf.text)
        assertEquals(LineSeparator.CRLF, utf.format.lineSeparator)
        assertEquals("привет\r\nмир", String(TextCodec.encode(utf.text, utf.format)))
        val cp = TextCodec.decode("Привет, мир".toByteArray(charset("windows-1251")))
        assertEquals("windows-1251", cp.format.charset.name())
        assertEquals("Привет, мир", cp.text)
        val koi = TextCodec.decode("съешь ещё этих мягких булок".toByteArray(charset("KOI8-R")))
        assertEquals("KOI8-R", koi.format.charset.name())
        val bom = TextCodec.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray())
        assertTrue(bom.format.bom)
        assertEquals("x", bom.text)
        assertEquals(4, TextCodec.encode("x", bom.format).size)
        val ex = runCatching { TextCodec.encode("😀", TextFileFormat(charset("windows-1251"))) }.exceptionOrNull()
        assertNotNull(ex)
    }

    // ---------- Подсказки ----------

    @Test
    fun `fuzzy matcher prefers prefixes and humps`() {
        val a = FuzzyMatcher.score("tv", "TextView")
        val b = FuzzyMatcher.score("tv", "activity")
        assertTrue(a > b)
        assertTrue(FuzzyMatcher.score("lw", "android:layout_width") > FuzzyMatcher.score("lw", "android:allowBackup"))
        assertEquals(FuzzyMatcher.NO_MATCH, FuzzyMatcher.score("zz", "TextView"))
        assertTrue(FuzzyMatcher.score("inv", "invoke-virtual") > FuzzyMatcher.score("inv", "move-result"))
    }

    private fun complete(lang: Language, textWithCaret: String, explicit: Boolean = false): CompletionResult? {
        val c = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(c, c + 1)
        val s = snap(lang, text)
        return CompletionEngine.complete(
            CompletionRequest(text, c, lang, s, WordIndexer.build(text, lang, 1), explicit, null)
        )
    }

    @Test
    fun `completion contexts`() {
        val k = complete(Languages.KOTLIN, "fun main() { pri| }")!!
        assertTrue(k.items.take(3).any { it.label == "println" }, k.items.take(5).map { it.label }.toString())
        val smaliOp = complete(Languages.SMALI, ".method a()V\n    invoke-s|")!!
        assertEquals("invoke-static", smaliOp.items.first().label)
        val smaliDir = complete(Languages.SMALI, ".me|")!!
        assertTrue(smaliDir.items.any { it.label == ".method" })
        val member = complete(Languages.SMALI, ".method public foo(I)V\n.end method\n.method b()V\n    invoke-virtual {p0}, La;->fo|")!!
        assertTrue(member.items.any { it.label == "foo(I)V" }, member.items.take(5).map { it.label }.toString())
        val reg = complete(Languages.SMALI, ".method public foo(IJ)V\n    .locals 2\n    move v|")!!
        assertEquals(listOf("v0", "v1"), reg.items.map { it.label }.filter { it.startsWith("v") }.sorted())
        val attr = complete(Languages.XML, "<TextView and|")!!
        assertTrue(attr.items.first().label.startsWith("android:"))
        val value = complete(Languages.XML, "<TextView android:layout_width=\"|\"")!!
        assertTrue(value.items.any { it.label == "match_parent" })
        val perm = complete(Languages.XML, "<uses-permission android:name=\"INTER|\"")!!
        assertEquals("android.permission.INTERNET", perm.items.first().label)
        val tag = complete(Languages.XML, "<manifest><appli|")!!
        assertEquals("application", tag.items.first().label)
        val close = complete(Languages.XML, "<resources><string>x</string></|", explicit = true)!!
        assertEquals("resources", close.items.first().label)
        val css = complete(Languages.CSS, "a { disp| }")!!
        assertEquals("display", css.items.first().label)
        val cmd = complete(Languages.SHELL, "gre|")!!
        assertTrue(cmd.items.any { it.label == "grep" })
        val sql = complete(Languages.SQL, "SEL|")!!
        assertEquals("SELECT", sql.items.first().label)
        assertNull(complete(Languages.KOTLIN, "// pri|"))
    }

    @Test
    fun `snippet expansion with mirrors and final position`() {
        val e = SnippetExpander.expand("for (\${1:i} in \${2:x}) {\n    \$0\n}", "  ", "\t")
        assertEquals("for (i in x) {\n  \t\n  }", e.text)
        assertEquals(2, e.stops.size)
        assertEquals("for (i in x) {\n  \t".length, e.finalOffset)
        val m = SnippetExpander.expand("\${1:v0} \$1 \$\$", "", "    ")
        assertEquals("v0 v0 $", m.text)
        assertEquals(2, m.stops[0].size)
    }

    // ---------- Структура, ошибки, форматирование ----------

    @Test
    fun `folding and outline`() {
        val kt = "class A {\n    fun b() {\n        x()\n    }\n}\n"
        val s = snap(Languages.KOTLIN, kt)
        val folds = FoldingEngine.compute(s)
        assertEquals(listOf(0, 1), folds.map { it.startLine })
        val outline = OutlineEngine.compute(s, folds)
        assertEquals(listOf("A", "b"), outline.map { it.name })
        assertEquals(ItemKind.FUNCTION, outline[1].kind)
        assertEquals(3, outline[1].endLine)
        val smali = ".class public La/B;\n.field private x:I\n.method public foo(ILjava/lang/String;)V\n    .locals 0\n    return-void\n.end method\n"
        val ss = snap(Languages.SMALI, smali)
        val sf = FoldingEngine.compute(ss)
        assertEquals(1, sf.size)
        val so = OutlineEngine.compute(ss, sf)
        assertEquals(listOf("B", "x", "foo"), so.map { it.name })
        assertTrue(so[2].detail.startsWith("(int, String): void"))
        val py = "def a():\n    if x:\n        pass\n\nclass B:\n    pass\n"
        val pf = FoldingEngine.compute(snap(Languages.PYTHON, py))
        assertEquals(listOf(0, 1, 4), pf.map { it.startLine })
        val xml = "<a>\n  <b>\n  </b>\n</a>"
        assertEquals(listOf(0, 1), FoldingEngine.compute(snap(Languages.XML, xml)).map { it.startLine })
        assertEquals(listOf("a", "b"), OutlineEngine.compute(snap(Languages.XML, xml), emptyList()).map { it.name })
    }

    @Test
    fun `json and bracket diagnostics`() {
        val bad = JsonChecker.check("{\"a\": 1,, }", lenient = false)
        assertTrue(bad.any { it.severity == Severity.ERROR })
        assertTrue(JsonChecker.check("{\"a\": [1, 2]}", lenient = false).isEmpty())
        val warn = JsonChecker.check("{\"a\": 1, // c\n}", lenient = false)
        assertTrue(warn.all { it.severity == Severity.WARNING } && warn.size == 2)
        val br = DiagnosticsEngine.quick(snap(Languages.KOTLIN, "fun a() { foo(]\n"), "a.kt")
        assertTrue(br.any { it.message.contains("Ожидалась") })
        val str = DiagnosticsEngine.quick(snap(Languages.JAVA, "String s = \"abc;\nint x;"), "A.java")
        assertTrue(str.any { it.message == "Незакрытая строка" })
        val xml = DiagnosticsEngine.quick(snap(Languages.XML, "<a><b></a>"), "a.xml")
        assertTrue(xml.isNotEmpty())
    }

    @Test
    fun `smali syntax errors are located`() {
        val text = ".class public La/B;\n.super Ljava/lang/Object;\n.method public foo()V\n    .locals 0\n    bogus-op v0\n    return-void\n.end method\n"
        val s = snap(Languages.SMALI, text)
        val d = DiagnosticsEngine.slow(s, "B.smali", externalTools = false)
        assertTrue(d.isNotEmpty())
        assertEquals(4, s.lineOf(d.first().start), d.first().message)
    }

    @Test
    fun `formatters`() {
        val json = "{\"a\":1,\"b\":[1,2],\"c\":{}}"
        val f = CodeFormatter.format(json, snap(Languages.JSON, json), "  ", 4, null)
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ],\n  \"c\": {}\n}\n", f.text)
        val xml = "<a><b x=\"1\">t</b><c/></a>"
        assertEquals("<a>\n    <b x=\"1\">t</b>\n    <c />\n</a>\n", XmlFormatter.format(xml, "    ", false))
        val kt = "fun a() {\nif (x) {\ny()\n}\n}"
        assertEquals("fun a() {\n    if (x) {\n        y()\n    }\n}", Reindenter.reindent(snap(Languages.KOTLIN, kt), "    ", 4))
        val smali = ".method a()V\n.locals 0\nreturn-void\n.end method"
        val sf = CodeFormatter.format(smali, snap(Languages.SMALI, smali), "    ", 4, null)
        assertEquals(".method a()V\n    .locals 0\n    return-void\n.end method", sf.text)
    }

    @Test
    fun `search and replace with regex groups`() {
        val text = "foo1 bar foo22 Foo3"
        val m = SearchEngine.findAll(text, SearchOptions("foo(\\d+)", regex = true))
        assertEquals(3, m.count)
        val cs = SearchEngine.findAll(text, SearchOptions("foo", caseSensitive = true))
        assertEquals(2, cs.count)
        val ww = SearchEngine.findAll("a ab a", SearchOptions("a", wholeWord = true))
        assertEquals(2, ww.count)
        assertEquals("n=22", SearchEngine.replacementFor(text, m.start(1), m.end(1), SearchOptions("foo(\\d+)", regex = true), "n=$1"))
        val (out, n) = SearchEngine.replaceAll(text, SearchOptions("foo(\\d+)", regex = true), "[$1]")
        assertEquals("[1] bar [22] [3]", out)
        assertEquals(3, n)
        assertNotNull(SearchEngine.findAll(text, SearchOptions("(", regex = true)).error)
        assertFalse(m.truncated)
    }

    @Test
    fun `huge list numbers continue and formatter output is complete`() {
        val md = cfg(Languages.MARKDOWN)
        assertEquals(
            "12345678901. a\n12345678902. |",
            show(TextOps.newline(v("12345678901. a|"), md, snap(Languages.MARKDOWN, "12345678901. a")))
        )
        // Весь stdout без обрезки и без примеси stderr
        val big = "x".repeat(300_000) + "\nконец\n"
        val r = ToolLocator.run(listOf("sh", "-c", "cat; echo warn >&2"), big, timeoutMs = 10_000, separateErrors = true, maxChars = Int.MAX_VALUE)!!
        assertEquals(0, r.exitCode)
        assertEquals(big, r.output)
        assertEquals("warn\n", r.errors)
        kotlin.test.assertTrue(r.complete)
        val cut = ToolLocator.run(listOf("cat"), big, timeoutMs = 10_000)!!
        kotlin.test.assertFalse(cut.complete)
    }

    @Test
    fun `token lookup matches the linear scan and bracket matching is fast on huge lines`() {
        val src = buildString {
            repeat(40) {
                append("val s$it = \"a\\n\${x + $it}b\" // note (not code) {\n")
                append("/* block ( [ { */ fun f$it(a: Int) = listOf(a, \"x)\", 'c')\n")
            }
        }
        val snap = snap(Languages.KOTLIN, src)
        fun reference(offset: Int): Tok? {
            val line = snap.lineOf(offset)
            val rel = offset - snap.lineStart(line)
            val t = snap.lineTokens(line)
            var k = t.size - 3
            while (k >= 0) {
                if (t[k] <= rel && rel < t[k + 1]) return Tok.values()[t[k + 2]]
                k -= 3
            }
            return null
        }
        for (i in src.indices) assertEquals(reference(i), snap.tokenAt(i), "offset $i")

        fun json(items: Int) = buildString {
            append('{')
            repeat(items) { append("\"k$it\":[1,{\"a\":\"(x)\"}],") }
            append("\"end\":0}")
        }
        // Пара в пределах окна поиска находится, огромная строка не подвешивает
        for ((items, found) in listOf(8_000 to true, 60_000 to false)) {
            val text = json(items)
            val jsnap = snap(Languages.detect("a.json", text), text)
            val started = System.nanoTime()
            val pair = TextOps.bracketPairNear(text, 0, jsnap)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 3000, "поиск пары скобок ($items): $ms мс")
            assertEquals(if (found) text.length - 1 else -1, pair?.second ?: -1)
        }
    }

    @Test
    fun `catastrophic regex is interrupted instead of hanging`() {
        val text = "a".repeat(40) + "\n" + "x".repeat(1000)
        val o = SearchOptions(query = "((a*)*)*b", regex = true)
        val started = System.nanoTime()
        val r = SearchEngine.findAll(text, o, timeoutMs = 300)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 5000, "поиск: $ms мс")
        assertEquals(0, r.count)
        assertNotNull(r.error)
        kotlin.test.assertFailsWith<RegexTimeout> { SearchEngine.replaceAll(text, o, "z", timeoutMs = 300) }
        // Обычная замена — без изменений
        assertEquals("b-b" to 2, SearchEngine.replaceAll("a-a", SearchOptions(query = "a"), "b"))
        assertEquals("x\ny" to 1, SearchEngine.replaceAll("x,y", SearchOptions(query = ",", regex = true), "\\n"))
    }

    @Test
    fun `linked rename updates every occurrence quickly`() {
        val ctrl = CodeEditorController("val foo = 1\nprint(foo + foo)\n// foo\n")
        ctrl.select(5)
        assertEquals(4, ctrl.startRename())
        fun type(newText: String, caret: Int) = ctrl.applyEdit(TextFieldValue(newText, TextRange(caret)))
        val t0 = ctrl.text
        type(t0.substring(0, 4) + "barx" + t0.substring(7), 8)
        assertEquals("val barx = 1\nprint(barx + barx)\n// barx\n", ctrl.text)
        assertEquals(TextRange(8), ctrl.value.selection)
        val t1 = ctrl.text
        type(t1.substring(0, 8) + "Y" + t1.substring(8), 9)
        assertEquals("val barxY = 1\nprint(barxY + barxY)\n// barxY\n", ctrl.text)
        // Правка второго вхождения (курсор в нём) — тоже распространяется
        val t2 = ctrl.text
        val second = t2.indexOf("barxY", 10)
        type(t2.substring(0, second) + "z" + t2.substring(second + 5), second + 1)
        assertEquals("val z = 1\nprint(z + z)\n// z\n", ctrl.text)

        // Тысячи вхождений в большом тексте — без подвисаний
        val big = buildString { repeat(4000) { append("item = item + 1 // строка $it\n") } }
        val c2 = CodeEditorController(big)
        c2.select(1)
        assertEquals(5000, c2.startRename())
        val started = System.nanoTime()
        val b0 = c2.text
        c2.applyEdit(TextFieldValue(b0.substring(0, 4) + "s" + b0.substring(4), TextRange(5)))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2000, "правка: $ms мс")
        assertTrue(c2.text.startsWith("items = items + 1"), c2.text.take(40))
        assertEquals(5000, Regex("\\bitems\\b").findAll(c2.text).count())
    }
}

