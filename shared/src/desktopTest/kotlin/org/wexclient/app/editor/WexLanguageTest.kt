package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.*
import org.wexclient.app.markdown.markdownCodeLanguage
import org.wexclient.app.ui.TypeFilter
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.nio.file.Path
import kotlin.test.*

class WexLanguageTest {
    private val lang = Languages.WEX
    private fun snap(text: String) = HighlightModel(lang).also { it.reset(text) }.snapshot
    private fun complete(source: String, explicit: Boolean = false): CompletionResult? {
        val caret = source.indexOf('|'); require(caret >= 0)
        val text = source.removeRange(caret, caret + 1)
        return CompletionEngine.complete(CompletionRequest(text, caret, lang, snap(text), WordIndexer.build(text, lang, 1), explicit, null))
    }
    private fun tokens(vararg lines: String): List<List<Pair<String, Tok>>> {
        var state = 0
        return lines.map { line ->
            val out = ArrayList<Token>()
            state = lang.lexer.tokenize(line, state, out)
            out.forEach { assertTrue(it.start >= 0 && it.start < it.end && it.end <= line.length) }
            out.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
            out.map { line.substring(it.start, it.end) to it.type }
        }
    }

    @Test fun `wex extension selects language file type and code filter`() {
        assertSame(lang, Languages.detect("hello.wex"))
        assertSame(lang, Languages.detect("MAIN.WEX"))
        assertSame(lang, Languages.byId("wex"))
        assertSame(lang, markdownCodeLanguage("wex"))
        assertNotSame(lang, Languages.detect("data.wexb"))
        assertEquals(VfsItemType.CODE_WEX, VfsEntry.detectType("MAIN.WEX", false))
        assertTrue(TypeFilter.CODE.accepts(VfsEntry("main.wex", Path.of("main.wex"), false, false, 1, 0, VfsItemType.CODE_WEX, "wex")))
    }

    @Test fun `only compiler keywords and current intrinsics are suggested`() {
        val keywordNames = lang.vocabulary.words.filter { it.kind == ItemKind.KEYWORD }.map { it.text }.toSet()
        assertEquals(setOf("var", "const", "fn", "return", "if", "else", "while", "import", "export"), keywordNames)
        val expected = setOf("log", "print", "sleep_ms", "hex", "security_check", "process.pid") +
            "find_module read_u8 read_u16 read_u32 read_u64 read_ptr read_float write_u8 write_u32 write_float patch restore read_chain scan_pattern dump alloc free".split(' ').map { "memory.$it" } +
            "begin end text button checkbox slider separator progress draw_line draw_rect draw_text".split(' ').map { "gui.$it" }
        assertEquals(expected, WexSupport.builtins.map { it.name }.toSet())
        assertEquals(34, expected.size)
        assertTrue(lang.vocabulary.words.none { it.text in setOf("for", "class", "break", "continue", "memory.write_u64") })
    }

    @Test fun `declarations control literals and function names have distinct tokens`() {
        val t = tokens("export fn greet(name) {", "const LIMIT = 10; var running = true;", "if (running && LIMIT >= 1) { return null; } else { while (false) {} }")
        assertTrue("export" to Tok.KEYWORD in t[0]); assertTrue("fn" to Tok.KEYWORD in t[0]); assertTrue("greet" to Tok.FUNCTION in t[0])
        assertTrue("LIMIT" to Tok.CONSTANT in t[1]); assertTrue("running" to Tok.VARIABLE in t[1]); assertTrue("true" to Tok.CONSTANT in t[1])
        for (word in listOf("if", "return", "else", "while")) assertTrue(word to Tok.CONTROL in t[2])
        assertTrue("null" to Tok.CONSTANT in t[2]); assertTrue("&&" to Tok.OPERATOR in t[2]); assertTrue(">=" to Tok.OPERATOR in t[2])
    }

    @Test fun `numeric forms match decimal float and hex grammar`() {
        val t = tokens("var n = 123 + 12.50 + 0xA9 + 0Xff;", "var bad = 0x; # 'x'")
        assertEquals(listOf("123", "12.50", "0xA9", "0Xff"), t[0].filter { it.second == Tok.NUMBER }.map { it.first })
        assertTrue("0x" to Tok.INVALID in t[1]); assertTrue("#" to Tok.INVALID in t[1]); assertTrue("'" to Tok.INVALID in t[1])
        assertTrue(t[1].none { it.second == Tok.STRING })
    }

    @Test fun `strings keep comment markers and escaped quotes inside literal`() {
        val t = tokens("var text = \"// not a comment /* either */ \\\"quoted\\\" \\n\"; // end")[0]
        assertEquals(listOf("// end"), t.filter { it.second == Tok.COMMENT }.map { it.first })
        assertEquals(listOf("\\\"", "\\\"", "\\n"), t.filter { it.second == Tok.ESCAPE }.map { it.first })
    }

    @Test fun `multiline strings and nonnested comments preserve state and resume code`() {
        val t = tokens("var text = \"first", "/* string, not comment */", "last\"; log(text);", "/* outer /* inner", "*/ var count = 2;")
        assertEquals(listOf("/* string, not comment */" to Tok.STRING), t[1])
        assertTrue("log" to Tok.BUILTIN in t[2]); assertTrue("var" to Tok.KEYWORD in t[4]); assertTrue("2" to Tok.NUMBER in t[4])
        assertEquals(Tok.COMMENT, t[3][0].second)
    }

    @Test fun `member completion opens after dot and contains only its namespace`() {
        for (module in WexSupport.modules) {
            val result = complete("$module.|")!!
            assertEquals(WexSupport.builtins.filter { it.name.startsWith("$module.") }.map { it.name.substringAfter('.') }.toSet(), result.items.map { it.label }.toSet())
            assertTrue(result.items.all { '.' !in it.label && it.kind == ItemKind.FUNCTION })
        }
        assertNull(complete("unknown.ri|", explicit = true))
    }

    @Test fun `member prefix replaces only member including existing suffix`() {
        val result = complete("memory.re|ad_u32")!!
        assertTrue(result.items.any { it.label == "read_u32" })
        assertEquals(7, result.replaceStart); assertEquals(15, result.wordEnd)
        assertTrue(result.items.first { it.label == "read_u32" }.detail.contains("addr"))
    }

    @Test fun `imports offer modules not function calls`() {
        assertEquals(setOf("memory", "gui", "process"), complete("import |")!!.items.map { it.label }.toSet())
        val item = complete("import mem|;")!!.items.first()
        assertEquals("memory", item.insertText); assertFalse(item.retrigger)
        assertEquals(ItemKind.MODULE, item.kind)
    }

    @Test fun `calls insert editable arguments without duplicating parentheses`() {
        val item = complete("memory.write_f|")!!.items.first { it.label == "write_float" }
        assertTrue(item.isSnippet)
        val expanded = SnippetExpander.expand(item.insertText, "", "    ")
        assertEquals("write_float(addr, value)", expanded.text); assertEquals(2, expanded.stops.size)
        val existing = complete("memory.write_f|(address, value)")!!.items.first { it.label == "write_float" }
        assertEquals("write_float", existing.insertText); assertFalse(existing.isSnippet)
        val noArgs = complete("process.pi|")!!.items.first { it.label == "pid" }
        assertEquals("pid()", SnippetExpander.expand(noArgs.insertText, "", "    ").text)
    }

    @Test fun `module completion chains members without duplicate dots`() {
        val module = complete("mem|")!!.items.first { it.label == "memory" }
        assertEquals("memory.", module.insertText); assertTrue(module.retrigger)
        val existing = complete("mem|ory.read_u32(1)")!!.items.first { it.label == "memory" }
        assertEquals("memory", existing.insertText)
    }

    @Test fun `code completions do not appear inside strings comments or numbers`() {
        for (source in listOf("// memory.re|", "/* gui.bu| */", "var s = \"memory.re|\";", "var n = 123|")) {
            assertNull(complete(source), source)
        }
        assertNull(complete("/* gui.bu| */", explicit = true))
        assertNull(complete("var s = \"memory.re|\";", explicit = true))
    }

    @Test fun `local identifiers and functions remain available`() {
        val result = complete("fn measure(value) { return value; }\nvar sampleCount = 3;\nmea|")!!
        assertTrue(result.items.any { it.label == "measure" && it.kind == ItemKind.FUNCTION })
        assertTrue(complete("var sampleCount = 3;\nsampleC|")!!.items.any { it.label == "sampleCount" })
        assertTrue(complete("he|")!!.items.any { it.label == "hex" && it.detail.contains("value") })
    }

    @Test fun `snippets expand valid WEX constructs with tab stops`() {
        val function = complete("fn|")!!.items.first { it.isSnippet && it.label == "fn" }
        val expanded = SnippetExpander.expand(function.insertText, "", "    ")
        assertEquals("fn name(args) {\n    \n}", expanded.text)
        assertEquals(2, expanded.stops.size)
        assertTrue(lang.vocabulary.snippets.none { it.trigger in setOf("for", "class", "try") })
    }

    @Test fun `outline includes exports functions imports variables and constants`() {
        val text = "import gui;\nconst LIMIT = 8;\nvar count = 1;\nexport fn tick(value) {\n    if (value > 0) {\n        return value;\n    }\n}\n"
        val snapshot = snap(text); val folds = FoldingEngine.compute(snapshot)
        assertTrue(folds.size >= 2)
        val outline = OutlineEngine.compute(snapshot, folds).map { it.name to it.kind }
        for (item in listOf("gui" to ItemKind.MODULE, "LIMIT" to ItemKind.CONSTANT, "count" to ItemKind.VARIABLE, "tick" to ItemKind.FUNCTION)) assertTrue(item in outline, outline.toString())
    }

    @Test fun `braces and comments use existing editor indentation and pairing`() {
        val text = "fn tick() { // callback"
        val value = TextOps.newline(TextFieldValue(text, TextRange(text.length)), EditConfig(lang, "    ", 4), snap(text))
        assertEquals(text + "\n    ", value.text)
        assertEquals("//", lang.lineComment); assertEquals("/*" to "*/", lang.blockComment)
        assertEquals("()[]{}\"\"", lang.autoPairs)
    }

    @Test fun `actual language examples highlight without invalid or overlapping tokens`() {
        for (name in listOf("hello", "advanced_features", "game_mod", "process_telemetry_hud")) {
            val text = javaClass.getResource("/wex/$name.wex")!!.readText()
            val all = tokens(*text.split('\n').toTypedArray()).flatten()
            assertFalse(all.any { it.second == Tok.INVALID }, name + ": " + all.filter { it.second == Tok.INVALID })
        }
    }
}
