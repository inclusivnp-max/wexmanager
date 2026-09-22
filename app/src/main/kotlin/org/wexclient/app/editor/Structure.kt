package org.wexclient.app.editor

import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.Tok

enum class FoldKind { BLOCK, COMMENT, TAG, REGION, SECTION }

/** Сворачиваемый участок [start, end) — от конца строки-заголовка до закрывающей части. */
class FoldRegion(val start: Int, val end: Int, val startLine: Int, val endLine: Int, val kind: FoldKind) {
    override fun equals(other: Any?) = other is FoldRegion && other.start == start && other.end == end
    override fun hashCode() = start * 31 + end
}

class OutlineItem(
    val name: String,
    val kind: ItemKind,
    val detail: String,
    val offset: Int,
    val line: Int,
    val depth: Int,
    var endLine: Int = line
)

private fun HighlightSnapshot.lineText(line: Int) = text.substring(lineStart(line), lineEnd(line))

private fun HighlightSnapshot.firstTokenType(line: Int): Tok? {
    val t = lineTokens(line)
    if (t.isEmpty()) return null
    val first = lineText(line).indexOfFirst { !it.isWhitespace() }
    if (first < 0) return null
    var k = 0
    while (k < t.size) {
        if (t[k] <= first && first < t[k + 1]) return HighlightSnapshot.TOKS[t[k + 2]]
        k += 3
    }
    return null
}

private fun indentOf(s: String, tabSize: Int = 4): Int {
    var w = 0
    for (c in s) {
        when (c) {
            ' ' -> w++
            '\t' -> w = (w / tabSize + 1) * tabSize
            else -> return w
        }
    }
    return -1 // пустая строка
}

object FoldingEngine {
    private const val MAX_REGIONS = 30_000
    private val BRACE_LANGS_EXCLUDED = setOf("text", "log", "csv", "tsv", "markdown", "diff", "smali", "ignore", "properties", "dotenv", "batch")
    private val INDENT_LANGS = setOf(
        "python", "pine", "yaml", "nim", "haskell", "shell", "fish", "lua", "ruby", "crystal", "elixir", "julia", "vim",
        "cmake", "meson", "pascal", "vb", "erlang", "tcl", "ocaml", "makefile", "coffee", "nix", "asm", "sql"
    )
    private val regionStart = Regex("^\\s*(?://|#|--|;|<!--|/\\*)\\s*(?:#?region\\b|<editor-fold\\b)(.*)$", RegexOption.IGNORE_CASE)
    private val regionEnd = Regex("^\\s*(?://|#|--|;|<!--|/\\*)\\s*(?:#?endregion\\b|</editor-fold>)", RegexOption.IGNORE_CASE)
    private val smaliOpen = Regex("^\\s*\\.(method|annotation|subannotation|field|param|parameter|packed-switch|sparse-switch|array-data)\\b")
    private val smaliClose = Regex("^\\s*\\.end\\s+([\\w-]+)")
    private val voidHtml = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    fun compute(snap: HighlightSnapshot): List<FoldRegion> {
        val lang = snap.language
        val out = ArrayList<FoldRegion>()
        when {
            lang.id == "smali" -> smali(snap, out)
            lang.id == "jvm-bytecode" -> bytecode(snap, out)
            lang.markup -> markup(snap, out)
            lang.id == "markdown" -> markdown(snap, out)
            lang.id == "ini" || lang.id == "toml" || lang.id == "properties" -> sections(snap, out)
            lang.id == "diff" -> diff(snap, out)
            lang.id == "latex" -> latex(snap, out)
        }
        if (lang.id !in BRACE_LANGS_EXCLUDED && !lang.markup) braces(snap, out)
        if (lang.id !in BRACE_LANGS_EXCLUDED && !lang.markup) blockComments(snap, out)
        if (lang.id in INDENT_LANGS) indentation(snap, out)
        regions(snap, out)
        // По одному участку на строку-заголовок (первый найденный — точнее)
        val byLine = LinkedHashMap<Int, FoldRegion>()
        for (r in out) {
            if (r.end <= r.start || r.endLine <= r.startLine) continue
            byLine.putIfAbsent(r.startLine, r)
        }
        return byLine.values.sortedBy { it.start }.take(MAX_REGIONS)
    }

    private fun add(snap: HighlightSnapshot, out: MutableList<FoldRegion>, start: Int, end: Int, kind: FoldKind) {
        if (out.size >= MAX_REGIONS || end <= start) return
        val sl = snap.lineOf(start)
        val el = snap.lineOf(end)
        if (el > sl) out.add(FoldRegion(start, end, sl, el, kind))
    }

    private fun braces(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val stack = ArrayList<Long>() // (offset shl 1) | (1 если '[')
        val text = snap.text
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            if (t.isEmpty()) continue
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                if (t[k + 2] == Tok.BRACKET.ordinal) {
                    for (p in base + t[k] until base + t[k + 1]) {
                        when (text[p]) {
                            '{', '[' -> if (stack.size < 10_000) stack.add((p.toLong() shl 1) or (if (text[p] == '[') 1L else 0L))
                            '}', ']' -> {
                                val want = if (text[p] == ']') 1L else 0L
                                val idx = stack.indexOfLast { it and 1L == want }
                                if (idx >= 0) {
                                    val open = (stack[idx] shr 1).toInt()
                                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                                    add(snap, out, open + 1, p, FoldKind.BLOCK)
                                }
                            }
                        }
                    }
                }
                k += 3
            }
        }
    }

    private fun blockComments(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val text = snap.text
        var line = 0
        while (line < snap.lineCount) {
            val t = snap.lineTokens(line)
            if (t.size >= 3) {
                val lastType = HighlightSnapshot.TOKS[t[t.size - 1]]
                val lastEnd = t[t.size - 2]
                val lineLen = snap.lineEnd(line) - snap.lineStart(line)
                if ((lastType == Tok.COMMENT || lastType == Tok.DOC_COMMENT) && lastEnd == lineLen) {
                    // Начало комментария в этой строке
                    var k = t.size - 3
                    while (k >= 3 && t[k] == t[k - 2] && HighlightSnapshot.TOKS[t[k - 1]].let { it == Tok.COMMENT || it == Tok.DOC_COMMENT || it == Tok.DOC_TAG }) k -= 3
                    val start = snap.lineStart(line) + t[k]
                    val open = when {
                        text.startsWith("/**", start) -> 3
                        text.startsWith("/*", start) || text.startsWith("{-", start) || text.startsWith("(*", start) || text.startsWith("#[", start) -> 2
                        text.startsWith("<!--", start) -> 4
                        text.startsWith("--[[", start) -> 4
                        else -> 0
                    }
                    if (open > 0) {
                        val closer = when {
                            text.startsWith("{-", start) -> "-}"
                            text.startsWith("(*", start) -> "*)"
                            text.startsWith("#[", start) -> "]#"
                            text.startsWith("<!--", start) -> "-->"
                            text.startsWith("--[[", start) -> "]]"
                            else -> "*/"
                        }
                        val close = text.indexOf(closer, start + open)
                        if (close > 0) {
                            add(snap, out, start + open, close, FoldKind.COMMENT)
                            line = snap.lineOf(close) + 1
                            continue
                        }
                    }
                }
            }
            line++
        }
    }

    private fun indentation(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        // Стек (строка, отступ) — блок закрывается первой строкой с отступом не больше
        val stack = ArrayList<IntArray>()
        var lastContent = -1
        fun close(untilIndent: Int) {
            while (stack.isNotEmpty() && stack.last()[1] >= untilIndent) {
                val (line, _) = stack.removeAt(stack.size - 1).let { it[0] to it[1] }
                if (lastContent > line) add(snap, out, snap.lineEnd(line), snap.lineEnd(lastContent), FoldKind.BLOCK)
            }
        }
        for (line in 0 until snap.lineCount) {
            val s = snap.lineText(line)
            val ind = indentOf(s)
            if (ind < 0) continue
            val tt = snap.firstTokenType(line)
            if (tt == Tok.STRING && stack.isNotEmpty()) {
                lastContent = line
                continue
            }
            close(ind)
            stack.add(intArrayOf(line, ind))
            lastContent = line
        }
        close(-1)
    }

    private fun regions(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val stack = ArrayList<Int>()
        for (line in 0 until snap.lineCount) {
            val tt = snap.firstTokenType(line)
            if (tt != Tok.COMMENT && tt != Tok.DOC_COMMENT) continue
            val s = snap.lineText(line)
            when {
                regionEnd.containsMatchIn(s) -> if (stack.isNotEmpty()) {
                    val open = stack.removeAt(stack.size - 1)
                    add(snap, out, snap.lineEnd(open), snap.lineEnd(line), FoldKind.REGION)
                }
                regionStart.containsMatchIn(s) -> stack.add(line)
            }
        }
    }

    private fun smali(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val stack = ArrayList<Pair<String, Int>>()
        for (line in 0 until snap.lineCount) {
            val s = snap.lineText(line)
            val trimmed = s.trimStart()
            if (!trimmed.startsWith(".")) continue
            val close = smaliClose.find(s)
            if (close != null) {
                var name = close.groupValues[1]
                if (name == "parameter") name = "param"
                val idx = stack.indexOfLast { it.first == name }
                if (idx >= 0) {
                    val openLine = stack[idx].second
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                    add(snap, out, snap.lineEnd(openLine), snap.lineStart(line) + (s.length - trimmed.length), FoldKind.BLOCK)
                }
                continue
            }
            val open = smaliOpen.find(s) ?: continue
            var name = open.groupValues[1]
            if (name == "parameter") name = "param"
            // .field без аннотаций не закрывается — сбрасываем их при новом .field/.method
            if (name == "field" || name == "method") stack.removeAll { it.first == "field" || it.first == "param" }
            stack.add(name to line)
        }
    }

    private val bytecodeNotHeader = setOf(Tok.COMMENT, Tok.DIRECTIVE, Tok.ANNOTATION)

    /** Листинг байт-кода: тело метода — строки глубже его заголовка (отступ 2). */
    private fun bytecode(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        var header = -1
        var last = -1
        for (line in 0..snap.lineCount) {
            val ind = if (line < snap.lineCount) indentOf(snap.lineText(line)) else 0
            if (ind < 0) continue
            if (ind > 2) {
                last = line
                continue
            }
            if (header >= 0 && last > header) add(snap, out, snap.lineEnd(header), snap.lineEnd(last), FoldKind.BLOCK)
            header = if (ind == 2 && snap.firstTokenType(line) !in bytecodeNotHeader) line else -1
            last = header
        }
    }

    private val tagRe = Regex("<(/?)([A-Za-z_][\\w.:-]*)")

    private fun markup(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val text = snap.text
        val stack = ArrayList<Pair<String, Int>>()
        var pending: String? = null
        var pendingStart = -1
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            if (t.isEmpty()) continue
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                val type = HighlightSnapshot.TOKS[t[k + 2]]
                val s = base + t[k]
                val e = base + t[k + 1]
                if (type == Tok.TAG) {
                    val piece = text.substring(s, e)
                    when {
                        piece.startsWith("</") -> {
                            val name = piece.substring(2)
                            val idx = stack.indexOfLast { it.first == name }
                            if (idx >= 0) {
                                val open = stack[idx].second
                                while (stack.size > idx) stack.removeAt(stack.size - 1)
                                add(snap, out, open, s, FoldKind.TAG)
                            }
                            pending = null
                        }
                        piece.startsWith("<") -> {
                            pending = piece.substring(1)
                            pendingStart = s
                        }
                        piece == "/>" -> pending = null
                        piece == ">" -> {
                            val p = pending
                            if (p != null && p.lowercase() !in voidHtml && stack.size < 5000) stack.add(p to e)
                            pending = null
                        }
                    }
                } else if (type == Tok.COMMENT && text.startsWith("<!--", s)) {
                    val close = text.indexOf("-->", s + 4)
                    if (close > 0) add(snap, out, s + 4, close, FoldKind.COMMENT)
                }
                k += 3
            }
        }
        if (pendingStart < 0) return
    }

    private fun markdown(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val heads = ArrayList<IntArray>() // (строка, уровень)
        var fenceStart = -1
        for (line in 0 until snap.lineCount) {
            val s = snap.lineText(line)
            val trimmed = s.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (fenceStart < 0) fenceStart = line else {
                    add(snap, out, snap.lineEnd(fenceStart), snap.lineStart(line) + (s.length - trimmed.length), FoldKind.BLOCK)
                    fenceStart = -1
                }
                continue
            }
            if (fenceStart >= 0) continue
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && (trimmed.length == level || trimmed[level] == ' ')) {
                    while (heads.isNotEmpty() && heads.last()[1] >= level) {
                        val h = heads.removeAt(heads.size - 1)
                        val end = lastContentBefore(snap, line)
                        if (end > h[0]) add(snap, out, snap.lineEnd(h[0]), snap.lineEnd(end), FoldKind.SECTION)
                    }
                    heads.add(intArrayOf(line, level))
                }
            }
        }
        val end = lastContentBefore(snap, snap.lineCount)
        for (h in heads) if (end > h[0]) add(snap, out, snap.lineEnd(h[0]), snap.lineEnd(end), FoldKind.SECTION)
    }

    private fun lastContentBefore(snap: HighlightSnapshot, line: Int): Int {
        var l = line - 1
        while (l > 0 && snap.lineText(l).isBlank()) l--
        return l
    }

    private fun sections(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        var open = -1
        for (line in 0 until snap.lineCount) {
            val s = snap.lineText(line).trimStart()
            if (s.startsWith("[") && snap.firstTokenType(line) == Tok.SECTION) {
                if (open >= 0) {
                    val end = lastContentBefore(snap, line)
                    if (end > open) add(snap, out, snap.lineEnd(open), snap.lineEnd(end), FoldKind.SECTION)
                }
                open = line
            }
        }
        if (open >= 0) {
            val end = lastContentBefore(snap, snap.lineCount)
            if (end > open) add(snap, out, snap.lineEnd(open), snap.lineEnd(end), FoldKind.SECTION)
        }
    }

    private fun diff(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        var file = -1
        var hunk = -1
        for (line in 0..snap.lineCount) {
            val s = if (line < snap.lineCount) snap.lineText(line) else "diff "
            val isFile = s.startsWith("diff ")
            val isHunk = s.startsWith("@@")
            if ((isHunk || isFile) && hunk >= 0 && line - 1 > hunk) {
                add(snap, out, snap.lineEnd(hunk), snap.lineEnd(line - 1), FoldKind.BLOCK)
                hunk = -1
            }
            if (isFile) {
                if (file >= 0 && line - 1 > file) add(snap, out, snap.lineEnd(file), snap.lineEnd(line - 1), FoldKind.SECTION)
                file = if (line < snap.lineCount) line else -1
            }
            if (isHunk) hunk = line
        }
    }

    private val beginEnd = Regex("\\\\(begin|end)\\{([^}]+)}")

    private fun latex(snap: HighlightSnapshot, out: MutableList<FoldRegion>) {
        val stack = ArrayList<Pair<String, Int>>()
        for (m in beginEnd.findAll(snap.text)) {
            if (m.groupValues[1] == "begin") stack.add(m.groupValues[2] to m.range.last + 1)
            else {
                val idx = stack.indexOfLast { it.first == m.groupValues[2] }
                if (idx >= 0) {
                    val open = stack[idx].second
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                    add(snap, out, open, m.range.first, FoldKind.BLOCK)
                }
            }
        }
    }
}

object OutlineEngine {
    private const val MAX_ITEMS = 5000

    private class Rule(val regex: Regex, val kind: ItemKind, val name: Int = 1, val detail: Int = 0, val maxDepth: Int = 99)

    private const val KT_MODS = "(?:(?:public|private|protected|internal|abstract|open|final|sealed|data|enum|annotation|inner|value|inline|companion|override|suspend|operator|infix|tailrec|external|lateinit|const|actual|expect|@\\w+(?:\\([^)]*\\))?)\\s+)*"
    private const val J_MODS = "(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp|sealed|non-sealed|transient|volatile|@\\w+(?:\\([^)]*\\))?)\\s+)*"
    private const val CONTROL = "(?!(?:if|for|while|switch|return|new|else|catch|do|try|throw|case|sizeof|elif|when)\\b)"

    private val rules: Map<String, List<Rule>> = run {
        val kotlin = listOf(
            Rule(Regex("^\\s*$KT_MODS(class|interface|object|typealias)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*${KT_MODS}companion\\s+object\\b\\s*([A-Za-z_]\\w*)?"), ItemKind.TYPE, 1),
            Rule(Regex("^\\s*${KT_MODS}fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?([A-Za-z_`][\\w`]*)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*${KT_MODS}(?:val|var)\\s+(?:[\\w.<>?]+\\.)?([A-Za-z_]\\w*)\\s*(?::\\s*([\\w.<>?, ]+))?"), ItemKind.PROPERTY, 1, 2, maxDepth = 1)
        )
        val java = listOf(
            Rule(Regex("^\\s*$J_MODS(class|interface|enum|record|@interface)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*$J_MODS$CONTROL(?:<[^>]+>\\s+)?[\\w.<>\\[\\]?,]+(?:\\s*<[^>]*>)?\\s+([A-Za-z_]\\w*)\\s*\\(([^;{]*)\\)?\\s*(?:throws [\\w., ]+)?\\s*\\{?\\s*$"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*$J_MODS([A-Z]\\w*)\\s*\\(([^;{]*)\\)\\s*\\{?\\s*$"), ItemKind.FUNCTION, 1, 2)
        )
        val cLike = listOf(
            Rule(Regex("^\\s*(?:typedef\\s+)?(struct|union|enum|class|namespace)\\s+([A-Za-z_]\\w*)\\s*[{:]?\\s*$"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)"), ItemKind.CONSTANT),
            Rule(Regex("^$CONTROL(?:[\\w:*&<>,~]+\\s+)*?[\\w:*&<>~]*?([A-Za-z_~][\\w:~]*)\\s*\\(([^;]*)\\)\\s*(?:const)?\\s*(?:override)?\\s*\\{?\\s*$"), ItemKind.FUNCTION, 1, 2, maxDepth = 1)
        )
        val js = listOf(
            Rule(Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?(class|interface|enum|type|namespace)\\s+([A-Za-z_$][\\w$]*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*=>|[\\w$]+\\s*=>)"), ItemKind.FUNCTION),
            Rule(Regex("^\\s*(?:(?:public|private|protected|static|async|readonly|override|get|set)\\s+)*$CONTROL([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*[^{]+)?\\{\\s*$"), ItemKind.FUNCTION, 1, 2, maxDepth = 3)
        )
        val qml = listOf(
            Rule(Regex("^\\s*([A-Z][\\w.]*)\\s*\\{"), ItemKind.TYPE),
            Rule(Regex("^\\s*id\\s*:\\s*([\\w]+)"), ItemKind.CONSTANT),
            Rule(Regex("^\\s*(?:readonly\\s+|required\\s+|default\\s+)*property\\s+[\\w<>.]+\\s+([\\w]+)"), ItemKind.PROPERTY),
            Rule(Regex("^\\s*signal\\s+([\\w]+)"), ItemKind.FUNCTION),
            Rule(Regex("^\\s*function\\s+([\\w]+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val python = listOf(
            Rule(Regex("^\\s*class\\s+([A-Za-z_]\\w*)\\s*(\\([^)]*\\))?"), ItemKind.TYPE, 1, 2),
            Rule(Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val pine = listOf(
            Rule(Regex("^(indicator|strategy|library)\\s*\\(\\s*(?:title\\s*=\\s*)?[\"']([^\"']*)"), ItemKind.MODULE, 2, 1),
            Rule(Regex("^\\s*(?:export\\s+)?(type|enum)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:export\\s+)?(?:method\\s+)?([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)\\s*=>"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^([A-Za-z_]\\w*)\\s*=\\s*(input(?:\\.\\w+)?)\\s*\\("), ItemKind.PROPERTY, 1, 2)
        )
        val go = listOf(
            Rule(Regex("^type\\s+([A-Za-z_]\\w*)\\s+(struct|interface|func|[\\w.\\[\\]*]+)"), ItemKind.TYPE, 1, 2),
            Rule(Regex("^func\\s+(?:\\(([^)]*)\\)\\s*)?([A-Za-z_]\\w*)\\s*\\("), ItemKind.FUNCTION, 2, 1)
        )
        val rust = listOf(
            Rule(Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(struct|enum|trait|union|mod|type)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*impl(?:<[^>]*>)?\\s+([^{]+?)\\s*\\{"), ItemKind.MODULE),
            Rule(Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:const\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+\"[^\"]*\"\\s+)?fn\\s+([A-Za-z_]\\w*)\\s*(?:<[^>]*>)?\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*macro_rules!\\s*([A-Za-z_]\\w*)"), ItemKind.FUNCTION)
        )
        val shell = listOf(
            Rule(Regex("^\\s*function\\s+([\\w:.-]+)"), ItemKind.FUNCTION),
            Rule(Regex("^\\s*([\\w:.-]+)\\s*\\(\\s*\\)"), ItemKind.FUNCTION)
        )
        val lua = listOf(
            Rule(Regex("^\\s*(?:local\\s+)?function\\s+([\\w.:]+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*(?:local\\s+)?([\\w.:]+)\\s*=\\s*function\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val ruby = listOf(
            Rule(Regex("^\\s*(class|module)\\s+([\\w:]+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*def\\s+([\\w.?!=]+)\\s*(\\(.*)?"), ItemKind.FUNCTION, 1, 2)
        )
        val php = listOf(
            Rule(Regex("^\\s*(?:abstract\\s+|final\\s+)?(class|interface|trait|enum)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:(?:public|private|protected|static|abstract|final)\\s+)*function\\s+&?([A-Za-z_]\\w*)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val csharp = listOf(
            Rule(Regex("^\\s*(?:(?:public|private|protected|internal|static|abstract|sealed|partial|readonly|record)\\s+)*(class|struct|interface|enum|record|namespace)\\s+([\\w.]+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:(?:public|private|protected|internal|static|virtual|override|async|abstract|sealed|extern|unsafe|new)\\s+)+$CONTROL[\\w.<>\\[\\]?,]+\\s+([A-Za-z_]\\w*)\\s*\\(([^;]*)\\)\\s*\\{?\\s*$"), ItemKind.FUNCTION, 1, 2)
        )
        val swift = listOf(
            Rule(Regex("^\\s*(?:(?:public|private|fileprivate|internal|open|final)\\s+)*(class|struct|enum|protocol|extension|actor)\\s+([\\w.]+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:(?:public|private|fileprivate|internal|open|final|static|override|mutating|@\\w+)\\s+)*func\\s+([\\w]+)\\s*(?:<[^>]*>)?\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val dart = listOf(
            Rule(Regex("^\\s*(?:abstract\\s+)?(class|mixin|enum|extension)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:static\\s+)?(?:Future<[^>]*>|[\\w<>?]+)\\s+([a-z_]\\w*)\\s*\\(([^)]*)\\)\\s*(?:async\\s*)?\\{"), ItemKind.FUNCTION, 1, 2)
        )
        val scala = listOf(
            Rule(Regex("^\\s*(?:(?:case|abstract|sealed|final|private|protected|implicit)\\s+)*(class|object|trait|enum)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:(?:override|private|protected|final|implicit|inline)\\s+)*def\\s+([\\w]+)"), ItemKind.FUNCTION)
        )
        val groovy = listOf(
            Rule(Regex("^\\s*(?:(?:public|private|protected|static|abstract)\\s+)*(class|interface|enum|trait)\\s+([A-Za-z_]\\w*)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:(?:public|private|protected|static)\\s+)*def\\s+([\\w]+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*tasks?\\.(?:register|create)\\s*\\(?\\s*[\"']([\\w-]+)"), ItemKind.FUNCTION),
            Rule(Regex("^([a-zA-Z_][\\w.]*)\\s*(?:\\([^)]*\\))?\\s*\\{\\s*$"), ItemKind.MODULE, maxDepth = 1),
            Rule(Regex("^\\s{2,8}([a-zA-Z_][\\w]*)\\s*(?:\\([^)]*\\))?\\s*\\{\\s*$"), ItemKind.MODULE, maxDepth = 2)
        )
        val sql = listOf(
            Rule(Regex("^\\s*create\\s+(?:or\\s+replace\\s+)?(?:temp\\s+|temporary\\s+|unique\\s+|materialized\\s+)?(table|view|index|function|procedure|trigger|type|schema|sequence)\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`\\[\\]]+)", RegexOption.IGNORE_CASE), ItemKind.TYPE, 2, 1)
        )
        val css = listOf(
            Rule(Regex("^\\s*(@[\\w-]+[^{;]*?)\\s*\\{"), ItemKind.DIRECTIVE),
            Rule(Regex("^\\s*([^@\\s{}/;][^{};]*?)\\s*\\{"), ItemKind.TYPE)
        )
        val make = listOf(
            Rule(Regex("^([\\w./%$(){}-][\\w./%$(){} -]*?)\\s*::?(?!=)(.*)$"), ItemKind.FUNCTION, 1, 2)
        )
        val docker = listOf(
            Rule(Regex("^\\s*FROM\\s+(\\S+)(?:\\s+AS\\s+(\\S+))?", RegexOption.IGNORE_CASE), ItemKind.MODULE, 1, 2)
        )
        val md = listOf(Rule(Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$"), ItemKind.MODULE, 2, 1))
        val ini = listOf(Rule(Regex("^\\s*\\[+([^\\]]+)]+"), ItemKind.MODULE))
        val latex = listOf(Rule(Regex("\\\\(part|chapter|section|subsection|subsubsection|paragraph)\\*?\\{([^}]*)}"), ItemKind.MODULE, 2, 1))
        val elixir = listOf(
            Rule(Regex("^\\s*(defmodule|defprotocol|defimpl)\\s+([\\w.]+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:def|defp|defmacro|defmacrop|defguard)\\s+([\\w?!]+)"), ItemKind.FUNCTION)
        )
        val julia = listOf(
            Rule(Regex("^\\s*(?:mutable\\s+)?(struct|module|abstract\\s+type)\\s+([\\w{}]+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*(?:function|macro)\\s+([\\w.!]+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val haskell = listOf(
            Rule(Regex("^(data|newtype|type|class|instance)\\s+([^=]+?)\\s*(?:=|where|$)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^([a-z_][\\w']*)\\s*::\\s*(.+)$"), ItemKind.FUNCTION, 1, 2)
        )
        val simpleFn = { kw: String -> listOf(Rule(Regex("^\\s*(?:$kw)\\s+([\\w.:!?-]+)", RegexOption.IGNORE_CASE), ItemKind.FUNCTION)) }
        val proto = listOf(
            Rule(Regex("^\\s*(message|enum|service|oneof)\\s+(\\w+)"), ItemKind.TYPE, 2, 1),
            Rule(Regex("^\\s*rpc\\s+(\\w+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
        )
        val graphql = listOf(Rule(Regex("^\\s*(type|input|enum|interface|union|scalar|query|mutation|subscription|fragment|schema)\\s+(\\w+)"), ItemKind.TYPE, 2, 1))
        val hcl = listOf(Rule(Regex("^(resource|data|module|variable|output|provider|locals|terraform)\\s*(\"[^\"]*\"(?:\\s+\"[^\"]*\")?)?\\s*\\{"), ItemKind.MODULE, 2, 1))
        val nim = listOf(
            Rule(Regex("^\\s*(proc|func|method|iterator|template|macro|converter)\\s+`?([\\w]+)"), ItemKind.FUNCTION, 2, 1),
            Rule(Regex("^\\s*([A-Z]\\w*)\\*?\\s*=\\s*(object|enum|tuple|ref object|distinct)"), ItemKind.TYPE, 1, 2)
        )
        val zig = listOf(
            Rule(Regex("^\\s*(?:pub\\s+)?fn\\s+(\\w+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2),
            Rule(Regex("^\\s*(?:pub\\s+)?const\\s+(\\w+)\\s*=\\s*(?:extern\\s+|packed\\s+)?(struct|enum|union)"), ItemKind.TYPE, 1, 2)
        )
        mapOf(
            "kotlin" to kotlin, "java" to java, "c" to cLike, "cpp" to cLike, "objc" to cLike, "glsl" to cLike,
            "d" to cLike + java, "odin" to cLike, "javascript" to js, "typescript" to js, "qml" to qml,
            "python" to python, "pine" to pine, "go" to go, "rust" to rust, "shell" to shell, "lua" to lua, "ruby" to ruby,
            "crystal" to ruby, "php" to php, "csharp" to csharp, "swift" to swift, "dart" to dart, "scala" to scala,
            "groovy" to groovy, "sql" to sql, "css" to css, "scss" to css, "makefile" to make, "dockerfile" to docker,
            "markdown" to md, "ini" to ini, "toml" to ini, "latex" to latex, "elixir" to elixir, "julia" to julia,
            "haskell" to haskell, "fish" to simpleFn("function"), "powershell" to simpleFn("function|filter|class"),
            "perl" to simpleFn("sub|package"), "tcl" to simpleFn("proc"), "awk" to simpleFn("function|func"),
            "vim" to simpleFn("fu|fun|func|function!?"), "cmake" to simpleFn("function|macro") ,
            "pascal" to simpleFn("procedure|function|constructor|destructor"), "vb" to simpleFn("(?:public\\s+|private\\s+)?(?:sub|function|class|module)"),
            "protobuf" to proto, "graphql" to graphql, "hcl" to hcl, "nim" to nim, "zig" to zig,
            "solidity" to listOf(
                Rule(Regex("^\\s*(contract|interface|library|struct|enum)\\s+(\\w+)"), ItemKind.TYPE, 2, 1),
                Rule(Regex("^\\s*(?:function|modifier|event)\\s+(\\w+)\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2)
            ),
            "erlang" to listOf(Rule(Regex("^([a-z]\\w*)\\(([^)]*)\\)\\s*(?:when.*)?->"), ItemKind.FUNCTION, 1, 2)),
            "r" to listOf(Rule(Regex("^\\s*([\\w.]+)\\s*(?:<-|=)\\s*function\\s*\\(([^)]*)"), ItemKind.FUNCTION, 1, 2))
        )
    }

    fun compute(snap: HighlightSnapshot, folds: List<FoldRegion>): List<OutlineItem> {
        val lang = snap.language
        val items = when {
            lang.id == "smali" -> smali(snap)
            lang.id == "jvm-bytecode" -> bytecode(snap)
            lang.markup -> markup(snap)
            lang.id == "json" -> jsonKeys(snap)
            lang.id == "yaml" -> yamlKeys(snap)
            lang.id == "kdl" -> kdlNodes(snap)
            else -> byRules(snap, rules[lang.id] ?: return emptyList())
        }
        // Конец элемента — по сворачиваемому участку с той же строки
        if (folds.isNotEmpty()) {
            val byLine = HashMap<Int, FoldRegion>(folds.size * 2)
            for (f in folds) byLine.putIfAbsent(f.startLine, f)
            for (it in items) byLine[it.line]?.let { f -> it.endLine = f.endLine }
        }
        // Локальные переменные внутри функций в структуру не попадают
        if (items.none { it.kind == ItemKind.PROPERTY } || lang.markup || lang.id in setOf("json", "yaml", "kdl", "smali", "jvm-bytecode", "qml")) return items
        val functions = items.filter { it.kind == ItemKind.FUNCTION && it.endLine > it.line }
        return items.filter { item ->
            item.kind != ItemKind.PROPERTY || functions.none { f -> f.line < item.line && f.endLine >= item.line }
        }
    }

    private fun braceDepths(snap: HighlightSnapshot): IntArray {
        val depth = IntArray(snap.lineCount)
        var d = 0
        val text = snap.text
        for (line in 0 until snap.lineCount) {
            depth[line] = d
            val t = snap.lineTokens(line)
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                if (t[k + 2] == Tok.BRACKET.ordinal) {
                    for (p in base + t[k] until base + t[k + 1]) {
                        when (text[p]) {
                            '{' -> d++
                            '}' -> d = maxOf(0, d - 1)
                        }
                    }
                }
                k += 3
            }
        }
        return depth
    }

    private fun byRules(snap: HighlightSnapshot, rules: List<Rule>): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        val lang = snap.language
        val useIndent = lang.significantIndent || lang.id in setOf("shell", "fish", "lua", "ruby", "crystal", "elixir", "julia", "pascal", "vb", "markdown", "latex", "ini", "toml", "makefile", "dockerfile", "sql", "haskell", "nim")
        val depths = if (useIndent) null else braceDepths(snap)
        val lineLimit = 4000
        for (line in 0 until snap.lineCount) {
            if (out.size >= MAX_ITEMS) break
            val tt = snap.firstTokenType(line)
            if (tt == Tok.COMMENT || tt == Tok.DOC_COMMENT || tt == Tok.STRING) continue
            val start = snap.lineStart(line)
            val end = snap.lineEnd(line)
            if (end - start > lineLimit || end == start) continue
            val s = snap.text.substring(start, end)
            if (s.isBlank()) continue
            for (r in rules) {
                val m = r.regex.find(s) ?: continue
                val depth = when {
                    lang.id == "markdown" -> (m.groupValues[1].length - 1).coerceAtLeast(0)
                    lang.id == "latex" -> LATEX_LEVELS.indexOf(m.groupValues[1]).coerceAtLeast(0)
                    depths != null -> depths[line]
                    else -> indentOf(s) / maxOf(1, if (lang.defaultIndent == "\t") 4 else lang.defaultIndent.length)
                }
                if (depth > r.maxDepth) continue
                var name = m.groups[r.name]?.value?.trim()?.trim('`').orEmpty()
                if (name.isEmpty() && m.value.contains("companion")) name = "companion object"
                if (name.isEmpty()) continue
                val detail = if (r.detail > 0) m.groups[r.detail]?.value?.trim().orEmpty() else ""
                val nameIdx = m.groups[r.name]?.range?.first ?: 0
                out.add(OutlineItem(name, r.kind, detail.take(80), start + nameIdx, line, depth))
                break
            }
        }
        return out
    }

    private val LATEX_LEVELS = listOf("part", "chapter", "section", "subsection", "subsubsection", "paragraph")

    private val smaliClass = Regex("^\\.class\\s+(?:[\\w-]+\\s+)*L([\\w/$-]+);")
    private val smaliField = Regex("^\\s*\\.field\\s+((?:[\\w-]+\\s+)*)([\\w$-]+):(\\S+)")
    private val smaliMethod = Regex("^\\s*\\.method\\s+((?:[\\w-]+\\s+)*)([\\w$<>-]+)(\\([^)]*\\)\\S*)")

    private fun smali(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        for (line in 0 until snap.lineCount) {
            val start = snap.lineStart(line)
            val end = snap.lineEnd(line)
            if (end - start < 6 || snap.text[start + (snap.text.substring(start, minOf(end, start + 20)).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))] != '.') continue
            val s = snap.text.substring(start, end)
            smaliClass.find(s)?.let { m ->
                out.add(OutlineItem(m.groupValues[1].substringAfterLast('/'), ItemKind.TYPE, m.groupValues[1].replace('/', '.'), start, line, 0))
            } ?: smaliField.find(s)?.let { m ->
                out.add(OutlineItem(m.groupValues[2], ItemKind.PROPERTY, prettyType(m.groupValues[3]) + modifiersSuffix(m.groupValues[1]), start + m.groups[2]!!.range.first, line, 1))
            } ?: smaliMethod.find(s)?.let { m ->
                out.add(OutlineItem(m.groupValues[2], ItemKind.FUNCTION, prettySignature(m.groupValues[3]) + modifiersSuffix(m.groupValues[1]), start + m.groups[2]!!.range.first, line, 1))
            }
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    private val bytecodeClass = Regex("^(?:[a-z]+\\s+)*(?:class|interface|@interface|enum|module)\\s+([\\w/$.-]+)")
    private val bytecodeMethod = Regex("^  ((?:[a-z_]+\\s+)*)([^\\s(@][^\\s(]*)(\\([^)]*\\)\\S+)")
    private val bytecodeField = Regex("^  ((?:[a-z_]+\\s+)*)(\\[*(?:L[^;\\s]+;|[ZBCSIJFD]))\\s+([^\\s=]+)")

    /** Листинг ASM: класс, затем поля и методы (строки с отступом 2). */
    private fun bytecode(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        for (line in 0 until snap.lineCount) {
            val start = snap.lineStart(line)
            val s = snap.lineText(line)
            // Инструкции и метки (отступ 3+) пропускаем сразу
            if (s.isEmpty() || s.startsWith("   ")) continue
            if (s[0] != ' ') {
                bytecodeClass.find(s)?.let { m ->
                    val name = m.groupValues[1]
                    out.add(OutlineItem(name.substringAfterLast('/'), ItemKind.TYPE, name.replace('/', '.'), start + m.groups[1]!!.range.first, line, 0))
                }
                continue
            }
            bytecodeMethod.find(s)?.let { m ->
                out.add(OutlineItem(m.groupValues[2], ItemKind.FUNCTION, prettySignature(m.groupValues[3]) + modifiersSuffix(m.groupValues[1]), start + m.groups[2]!!.range.first, line, 1))
            } ?: bytecodeField.find(s)?.let { m ->
                out.add(OutlineItem(m.groupValues[3], ItemKind.PROPERTY, prettyType(m.groupValues[2]) + modifiersSuffix(m.groupValues[1]), start + m.groups[3]!!.range.first, line, 1))
            }
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    private fun modifiersSuffix(mods: String): String {
        val m = mods.trim()
        return if (m.isEmpty()) "" else "  · $m"
    }

    /** Ljava/lang/String; -> String, [I -> int[] */
    fun prettyType(desc: String): String {
        var dims = 0
        var i = 0
        while (i < desc.length && desc[i] == '[') {
            dims++; i++
        }
        val base = when (desc.getOrNull(i)) {
            'V' -> "void"; 'Z' -> "boolean"; 'B' -> "byte"; 'S' -> "short"; 'C' -> "char"
            'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"
            'L' -> desc.substring(i + 1).substringBefore(';').substringAfterLast('/').substringAfterLast('$')
            else -> desc
        }
        return base + "[]".repeat(dims)
    }

    fun prettySignature(sig: String): String {
        val params = sig.substringAfter('(').substringBefore(')')
        val ret = sig.substringAfter(')')
        val list = ArrayList<String>()
        var i = 0
        while (i < params.length) {
            val st = i
            while (i < params.length && params[i] == '[') i++
            if (i < params.length && params[i] == 'L') {
                i = params.indexOf(';', i).let { if (it < 0) params.length else it + 1 }
            } else i++
            list.add(prettyType(params.substring(st, i)))
        }
        return "(" + list.joinToString(", ") + "): " + prettyType(ret)
    }

    private val attrName = Regex("\\b(?:android:id|android:name|name|id|app:title|android:title)\\s*=\\s*\"([^\"]*)\"")

    private fun markup(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        val text = snap.text
        var depth = 0
        var pendingName: String? = null
        var pendingOffset = 0
        var pendingLine = 0
        val isHtml = snap.language.id == "html"
        val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            if (t.isEmpty()) continue
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                if (t[k + 2] == Tok.TAG.ordinal) {
                    val s = base + t[k]
                    val piece = text.substring(s, base + t[k + 1])
                    when {
                        piece.startsWith("</") -> depth = maxOf(0, depth - 1)
                        piece.startsWith("<") -> {
                            pendingName = piece.substring(1)
                            pendingOffset = s
                            pendingLine = line
                        }
                        piece == ">" || piece == "/>" -> {
                            val name = pendingName
                            if (name != null && out.size < MAX_ITEMS) {
                                val tagText = text.substring(pendingOffset, minOf(text.length, base + t[k + 1]))
                                val label = attrName.find(tagText)?.groupValues?.get(1)
                                val show = !isHtml || label != null || name.matches(Regex("h[1-6]|section|article|nav|header|footer|main|form|table|body|head"))
                                if (show) {
                                    out.add(
                                        OutlineItem(
                                            name, ItemKind.TAG, label?.removePrefix("@+id/")?.removePrefix("@id/").orEmpty(),
                                            pendingOffset, pendingLine, depth
                                        )
                                    )
                                }
                                if (piece == ">" && name.lowercase() !in voids) depth++
                            }
                            pendingName = null
                        }
                    }
                }
                k += 3
            }
        }
        return out
    }

    private fun jsonKeys(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        val text = snap.text
        var depth = 0
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                val type = t[k + 2]
                val s = base + t[k]
                val e = base + t[k + 1]
                if (type == Tok.BRACKET.ordinal) {
                    when (text[s]) {
                        '{', '[' -> depth++
                        '}', ']' -> depth = maxOf(0, depth - 1)
                    }
                } else if (type == Tok.PROPERTY.ordinal && depth <= 4 && out.size < MAX_ITEMS) {
                    val name = text.substring(s, e).trim('"', '\'')
                    val valueHint = text.substring(e, minOf(snap.lineEnd(line), e + 60)).substringAfter(':').trim().trimEnd(',')
                    val kind = if (valueHint.startsWith("{") || valueHint.startsWith("[")) ItemKind.MODULE else ItemKind.PROPERTY
                    out.add(OutlineItem(name, kind, if (kind == ItemKind.PROPERTY) valueHint.take(40) else "", s, line, (depth - 1).coerceAtLeast(0)))
                }
                k += 3
            }
        }
        return out
    }

    private val yamlKey = Regex("^(\\s*)(?:-\\s+)?([^\\s#'\"-][^:#]*?|\"[^\"]+\"|'[^']+'):(?:\\s+(.*))?$")

    private fun yamlKeys(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        for (line in 0 until snap.lineCount) {
            if (snap.firstTokenType(line) != Tok.PROPERTY && snap.firstTokenType(line) != Tok.LIST) continue
            val s = snap.lineText(line)
            val m = yamlKey.find(s) ?: continue
            val depth = m.groupValues[1].length / 2
            if (depth > 3) continue
            val value = m.groupValues[3].trim()
            out.add(
                OutlineItem(
                    m.groupValues[2].trim('"', '\''), if (value.isEmpty()) ItemKind.MODULE else ItemKind.PROPERTY,
                    value.take(40), snap.lineStart(line) + m.groups[2]!!.range.first, line, depth
                )
            )
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    private fun kdlNodes(snap: HighlightSnapshot): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        val depths = braceDepths(snap)
        val text = snap.text
        for (line in 0 until snap.lineCount) {
            if (depths[line] > 1) continue
            val t = snap.lineTokens(line)
            if (t.isEmpty() || t[2] != Tok.TAG.ordinal) continue
            val base = snap.lineStart(line)
            val name = text.substring(base + t[0], base + t[1])
            val rest = text.substring(base + t[1], snap.lineEnd(line)).trim().removeSuffix("{").trim()
            out.add(OutlineItem(name, if (depths[line] == 0) ItemKind.MODULE else ItemKind.PROPERTY, rest.take(50), base + t[0], line, depths[line]))
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }
}
