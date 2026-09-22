package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Tok

/** Настройки, от которых зависят операции: язык, единица отступа, ширина таба. */
class EditConfig(
    val language: Language,
    val indentUnit: String,
    val tabSize: Int,
    val autoClose: Boolean = true
) {
    val unitWidth: Int get() = if (indentUnit == "\t") tabSize else indentUnit.length
}

/** Операции редактирования в духе IntelliJ: чистые функции над текстом и выделением. */
object TextOps {
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"
    private const val SCAN_LIMIT = 300_000

    fun closerOf(c: Char): Char? = when (c) {
        '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> null
    }

    fun openerOf(c: Char): Char? = when (c) {
        ')' -> '('; ']' -> '['; '}' -> '{'; else -> null
    }

    fun lineStartOf(text: String, offset: Int): Int =
        if (offset <= 0) 0 else text.lastIndexOf('\n', (offset - 1).coerceAtMost(text.length - 1)) + 1

    fun lineEndOf(text: String, offset: Int): Int =
        text.indexOf('\n', offset.coerceIn(0, text.length)).let { if (it < 0) text.length else it }

    fun leadingWhitespace(text: String, lineStart: Int): String {
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(lineStart, i)
    }

    /** Видимая ширина отступа (табы — до позиции табуляции). */
    fun indentWidth(indent: CharSequence, tabSize: Int): Int {
        var w = 0
        for (c in indent) w = if (c == '\t') (w / tabSize + 1) * tabSize else w + 1
        return w
    }

    fun makeIndent(width: Int, cfg: EditConfig): String {
        if (width <= 0) return ""
        return if (cfg.indentUnit == "\t") "\t".repeat(width / cfg.tabSize) + " ".repeat(width % cfg.tabSize)
        else " ".repeat(width)
    }

    /** Визуальная колонка позиции (с учётом табов). */
    fun columnOf(text: String, offset: Int, tabSize: Int): Int {
        val ls = lineStartOf(text, offset)
        return indentWidth(text.subSequence(ls, offset.coerceIn(ls, text.length)), tabSize)
    }

    private fun value(text: String, caret: Int) = TextFieldValue(text, TextRange(caret))
    private fun value(text: String, start: Int, end: Int) = TextFieldValue(text, TextRange(start, end))

    fun wordRangeAt(text: String, offset: Int, isWordChar: (Char) -> Boolean): IntRange? {
        if (text.isEmpty()) return null
        var s = offset.coerceIn(0, text.length)
        var e = s
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < text.length && isWordChar(text[e])) e++
        return if (e > s) s until e else null
    }

    // ---------------- Скобки ----------------

    /** Парная скобка к скобке в позиции [pos] (пропуская строки и комментарии). */
    fun matchBracketAt(text: String, pos: Int, syntax: SyntaxInfo): Int? {
        if (pos !in text.indices) return null
        val c = text[pos]
        if (!syntax.isCodeAt(pos)) return null
        closerOf(c)?.let { close ->
            var depth = 0
            var i = pos
            val limit = minOf(text.length, pos + SCAN_LIMIT)
            while (i < limit) {
                val ch = text[i]
                if ((ch == c || ch == close) && syntax.isCodeAt(i)) {
                    if (ch == c) depth++ else {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return null
        }
        openerOf(c)?.let { open ->
            var depth = 0
            var i = pos
            val limit = maxOf(0, pos - SCAN_LIMIT)
            while (i >= limit) {
                val ch = text[i]
                if ((ch == c || ch == open) && syntax.isCodeAt(i)) {
                    if (ch == c) depth++ else {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i--
            }
        }
        return null
    }

    /** Скобки у курсора: сначала символ перед курсором, затем после. */
    fun bracketPairNear(text: String, caret: Int, syntax: SyntaxInfo): Pair<Int, Int>? {
        for (p in intArrayOf(caret - 1, caret)) {
            if (p !in text.indices) continue
            val c = text[p]
            if (c in OPENERS || c in CLOSERS) {
                val m = matchBracketAt(text, p, syntax) ?: continue
                return if (p < m) p to m else m to p
            }
        }
        return null
    }

    /** Ближайшая незакрытая открывающая скобка левее [pos]. */
    fun enclosingOpener(text: String, pos: Int, syntax: SyntaxInfo, which: String = OPENERS): Int? {
        val stack = IntArray(3)
        var i = pos - 1
        val limit = maxOf(0, pos - SCAN_LIMIT)
        while (i >= limit) {
            val ch = text[i]
            val ci = CLOSERS.indexOf(ch)
            val oi = OPENERS.indexOf(ch)
            if ((ci >= 0 || oi >= 0) && syntax.isCodeAt(i)) {
                if (ci >= 0) stack[ci]++
                else if (stack[oi] > 0) stack[oi]--
                else if (ch in which) return i
            }
            i--
        }
        return null
    }

    // ---------------- Ввод символов ----------------

    private fun isQuote(c: Char) = c == '"' || c == '\'' || c == '`'

    private fun pairCloser(cfg: EditConfig, c: Char): Char? {
        val pairs = cfg.language.autoPairs
        var i = 0
        while (i + 1 < pairs.length) {
            if (pairs[i] == c) return pairs[i + 1]
            i += 2
        }
        return null
    }

    private fun isPairCloser(cfg: EditConfig, c: Char): Boolean {
        val pairs = cfg.language.autoPairs
        var i = 1
        while (i < pairs.length) {
            if (pairs[i] == c) return true
            i += 2
        }
        return false
    }

    private val TRIPLE_QUOTE_LANGS = setOf("python", "kotlin", "java", "groovy", "scala", "swift", "dart", "julia", "elixir", "toml", "nim", "graphql", "csharp")

    /**
     * Символ [ch] набран поверх выделения старого значения [old].
     * Возвращает итоговое значение или null, если обычная вставка подходит.
     */
    fun onCharTyped(old: TextFieldValue, ch: Char, cfg: EditConfig, syntax: SyntaxInfo): TextFieldValue? {
        val text = old.text
        val a = old.selection.min
        val b = old.selection.max
        val lang = cfg.language
        val closer = if (cfg.autoClose) pairCloser(cfg, ch) else null

        // Обернуть выделение скобками/кавычками
        if (b > a && closer != null && (ch in OPENERS || isQuote(ch) || ch == '*' || ch == '_' || ch == '$')) {
            val sel = text.substring(a, b)
            if (!sel.contains('\n') || ch in OPENERS) {
                val t = text.substring(0, a) + ch + sel + closer + text.substring(b)
                return value(t, a + 1, b + 1)
            }
        }
        if (b > a) return null

        val next = text.getOrNull(a)
        val prev = text.getOrNull(a - 1)

        // Перешагнуть закрывающую
        if (cfg.autoClose && next == ch && (ch in CLOSERS || isQuote(ch)) && isPairCloser(cfg, ch)) {
            val skip = if (ch in CLOSERS) {
                // Только если скобки сбалансированы: иначе нужна ещё одна закрывающая
                syntax.isCodeAt(a) && matchBracketAt(text, a, syntax) != null
            } else {
                val tok = syntax.tokenAt(a)
                prev != '\\' && (tok == Tok.STRING || tok == Tok.ATTRIBUTE || tok == null || tok == Tok.PROPERTY ||
                    tok == Tok.CODE || countBefore(text, a, ch) % 2 == 1)
            }
            if (skip) return value(text, a + 1)
        }

        // Закрывающая скобка на пустой строке — выровнять по открывающей
        if (ch in CLOSERS && onlyWhitespaceBefore(text, a)) {
            val opener = enclosingOpener(text, a, syntax, openerOf(ch).toString())
            if (opener != null) {
                val openerLine = lineStartOf(text, opener)
                val indent = leadingWhitespace(text, openerLine)
                val ls = lineStartOf(text, a)
                val headPart = text.substring(0, ls) + indent + ch
                return value(headPart + text.substring(a), headPart.length)
            }
        }

        // XML: «>» закрывает открывающий тег — вставляем парный закрывающий
        if (lang.markup && ch == '>' && cfg.autoClose) {
            val inComment = syntax.tokenAt(a - 1).let { it == Tok.COMMENT || it == Tok.CODE || it == Tok.META }
            val tag = if (inComment) null else openTagNameBefore(text, a)
            val closable = tag != null && (lang.id == "xml" || tag.lowercase() !in VOID_HTML)
            if (tag != null && closable && !text.startsWith("</$tag>", a)) {
                val t = text.substring(0, a) + "></" + tag + ">" + text.substring(a)
                return value(t, a + 1)
            }
            return null
        }
        // XML: «</» — дописать имя незакрытого тега
        if (lang.markup && ch == '/' && prev == '<' && cfg.autoClose) {
            val open = innermostOpenTag(text, a - 1)
            if (open != null) {
                val lineStart = lineStartOf(text, a - 1)
                val onlyWs = text.substring(lineStart, a - 1).isBlank()
                var head = text.substring(0, a - 1)
                if (onlyWs) {
                    // Закрывающий тег на своей строке — отступ как у открывающего
                    head = text.substring(0, lineStart) + leadingWhitespace(text, lineStartOf(text, open.second))
                }
                val insert = "</" + open.first + ">"
                val rest = text.substring(a).let { r -> if (r.startsWith(">")) r.substring(1) else r }
                val t = head + insert + rest
                return value(t, head.length + insert.length)
            }
            return null
        }

        if (closer == null) return null
        val tokHere = syntax.tokenAt(a - 1)
        val inCommentOrString = tokHere == Tok.COMMENT || tokHere == Tok.DOC_COMMENT ||
            (tokHere == Tok.STRING && !(prev != null && isQuote(prev) && syntax.tokenAt(a) != Tok.STRING))

        if (ch in OPENERS) {
            if (inCommentOrString && tokHere != Tok.STRING) return null
            if (tokHere == Tok.STRING && syntax.tokenAt(a) == Tok.STRING) return null
            // Перед словом не закрываем: "(|foo" -> "(foo"
            if (next != null && !next.isWhitespace() && next !in ")]};,.:>" && !isQuote(next)) return null
            return value(text.substring(0, a) + ch + closer + text.substring(a), a + 1)
        }

        if (isQuote(ch)) {
            // Тройные кавычки: """|"""
            if (prev == ch && text.getOrNull(a - 2) == ch && lang.id in TRIPLE_QUOTE_LANGS && ch != '`') {
                if (text.getOrNull(a) == ch) return null
                return value(text.substring(0, a) + ch + "$ch$ch$ch" + text.substring(a), a + 1)
            }
            if (prev != null && (prev.isLetterOrDigit() || prev == '_' || prev == '\\')) return null
            if (ch == '\'' && lang.id in setOf("rust", "text", "markdown") && prev != null && prev != ' ' && prev != '(') return null
            if (next != null && !next.isWhitespace() && next !in ")]};,:>" ) return null
            if (tokHere == Tok.STRING && syntax.tokenAt(a) == Tok.STRING) return null
            if (tokHere == Tok.COMMENT || tokHere == Tok.DOC_COMMENT) return null
            return value(text.substring(0, a) + ch + closer + text.substring(a), a + 1)
        }
        // Прочие пары (например, $ в LaTeX, ` в Markdown)
        if (next == null || next.isWhitespace()) {
            return value(text.substring(0, a) + ch + closer + text.substring(a), a + 1)
        }
        return null
    }

    private fun countBefore(text: String, pos: Int, ch: Char): Int {
        val ls = lineStartOf(text, pos)
        var n = 0
        for (i in ls until pos) if (text[i] == ch && (i == 0 || text[i - 1] != '\\')) n++
        return n
    }

    private fun onlyWhitespaceBefore(text: String, pos: Int): Boolean {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') {
            if (text[i] != ' ' && text[i] != '\t') return false
            i--
        }
        return true
    }

    private val VOID_HTML = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "!doctype")

    /** Имя тега, если перед [pos] незакрытый открывающий тег "<name attrs" (без "/" в конце). */
    private fun openTagNameBefore(text: String, pos: Int): String? {
        var i = pos - 1
        val limit = maxOf(0, pos - 4000)
        var quote: Char? = null
        while (i >= limit) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '>' -> return null
                '<' -> {
                    val nameStart = i + 1
                    var j = nameStart
                    while (j < pos && (text[j].isLetterOrDigit() || text[j] in "_-.:")) j++
                    if (j == nameStart) return null
                    if (text.getOrNull(pos - 1) == '/') return null
                    return text.substring(nameStart, j)
                }
            }
            i--
        }
        return null
    }

    /** Самый внутренний незакрытый тег перед [pos]: имя и позиция "<". */
    private val anyTagRe = Regex("<(/?)([A-Za-z_][\\w.:-]*)[^<>]*?(/?)>|<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL)

    fun innermostOpenTag(text: String, pos: Int): Pair<String, Int>? {
        val tagRe = anyTagRe
        val from = maxOf(0, pos - 400_000)
        val stack = ArrayList<Pair<String, Int>>()
        for (m in tagRe.findAll(text.substring(from, pos))) {
            val name = m.groups[2]?.value ?: continue
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[3] == "/"
            when {
                selfClosing -> Unit
                closing -> {
                    val idx = stack.indexOfLast { it.first == name }
                    if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
                name.lowercase() in VOID_HTML -> Unit
                else -> stack.add(name to (from + m.range.first))
            }
        }
        return stack.lastOrNull()
    }

    /** Backspace на значении [old] (курсор без выделения). null — обычное удаление. */
    fun onBackspace(old: TextFieldValue, cfg: EditConfig, syntax: SyntaxInfo): TextFieldValue? {
        val text = old.text
        val c = old.selection.start
        if (!old.selection.collapsed || c <= 0) return null
        val prev = text[c - 1]
        val next = text.getOrNull(c)
        // Пара "(|)" удаляется целиком
        if (cfg.autoClose && next != null && pairCloser(cfg, prev) == next && (prev in OPENERS || isQuote(prev)) &&
            (prev !in OPENERS || syntax.isCodeAt(c - 1))
        ) {
            return value(text.substring(0, c - 1) + text.substring(c + 1), c - 1)
        }
        // Умный Backspace в отступе пробелами: до предыдущей позиции табуляции
        if (prev == ' ' && cfg.indentUnit != "\t") {
            val ls = lineStartOf(text, c)
            if (onlyWhitespaceBefore(text, c) && text.substring(ls, c).all { it == ' ' }) {
                val col = c - ls
                val unit = cfg.unitWidth.coerceAtLeast(1)
                val target = ((col - 1) / unit) * unit
                val start = ls + target
                return value(text.substring(0, start) + text.substring(c), start)
            }
        }
        return null
    }

    // ---------------- Enter ----------------

    enum class NewlineMode { SPLIT, BELOW, ABOVE }

    fun newline(v: TextFieldValue, cfg: EditConfig, syntax: SyntaxInfo, mode: NewlineMode = NewlineMode.SPLIT): TextFieldValue {
        val text = v.text
        val lang = cfg.language
        if (mode == NewlineMode.ABOVE) {
            val ls = lineStartOf(text, v.selection.min)
            val indent = leadingWhitespace(text, ls)
            val t = text.substring(0, ls) + indent + "\n" + text.substring(ls)
            return value(t, ls + indent.length)
        }
        var a = v.selection.min
        var b = v.selection.max
        if (mode == NewlineMode.BELOW) {
            a = lineEndOf(text, v.selection.max)
            b = a
        }
        val ls = lineStartOf(text, a)
        val head = text.substring(0, ls)
        var line = text.substring(ls, a)
        val tail = text.substring(b)
        val lineRestEnd = tail.indexOf('\n').let { if (it < 0) tail.length else it }
        val after = tail.substring(0, lineRestEnd)

        // Строка закрывает блок (end/fi/else) — сдвинуть её влево перед переводом строки
        val dedentRe = lang.dedentLine
        if (dedentRe != null && after.isBlank() && dedentRe.matches(line)) {
            val target = expectedDedent(text, ls, cfg)
            val cur = leadingWhitespace(line, 0)
            if (target != null && indentWidth(target, cfg.tabSize) < indentWidth(cur, cfg.tabSize)) {
                line = target + line.trimStart()
            }
        }

        var baseIndent = leadingWhitespace(line, 0)
        val beforeTrim = stripTrailingComment(line, ls, syntax).trimEnd()

        // Markdown: продолжение списка
        if (lang.id == "markdown") {
            val m = Regex("^(\\s*)([-*+]|(\\d+)([.)]))(\\s+)(\\[[ xX]]\\s+)?").find(line)
            if (m != null) {
                val body = line.substring(m.range.last + 1)
                if (body.isBlank() && after.isBlank()) {
                    // Пустой пункт — заканчиваем список
                    val t = head + m.groupValues[1] + tail
                    return value(t, head.length + m.groupValues[1].length)
                }
                val num = m.groupValues[3]
                // Номер может не влезть в Int («12345678901.»)
                val marker = if (num.isNotEmpty()) "${java.math.BigInteger(num).add(java.math.BigInteger.ONE)}${m.groupValues[4]}" else m.groupValues[2]
                val box = if (m.groupValues[6].isNotEmpty()) "[ ] " else ""
                val insert = "\n" + m.groupValues[1] + marker + m.groupValues[5] + box
                val t = head + line + insert + tail.trimStart(' ', '\t')
                return value(t, head.length + line.length + insert.length)
            }
        }

        // Документирующий комментарий: продолжаем " * "
        val trimmedLine = line.trimStart()
        val tokBefore = syntax.tokenAt(a - 1)
        if ((trimmedLine.startsWith("/**") || trimmedLine.startsWith("/*") || trimmedLine.startsWith("*")) &&
            (tokBefore == Tok.DOC_COMMENT || tokBefore == Tok.COMMENT || tokBefore == Tok.DOC_TAG) &&
            !trimmedLine.startsWith("*/") && !beforeTrim.endsWith("*/")
        ) {
            val star = if (trimmedLine.startsWith("/*")) " * " else "* "
            val prefix = baseIndent + star
            val insert = "\n" + prefix
            val needsClose = trimmedLine.startsWith("/**") && !text.substring(a, minOf(text.length, a + 20000)).let { rest ->
                val close = rest.indexOf("*/")
                val open = rest.indexOf("/*")
                close >= 0 && (open < 0 || close < open)
            }
            val closing = if (needsClose) "\n$baseIndent */" else ""
            val t = head + line + insert + closing + tail.trimStart(' ', '\t')
            return value(t, head.length + line.length + insert.length)
        }

        var extra = ""
        val opensBlock = when {
            lang.markup -> xmlOpensBlock(line)
            lang.indentAfter != null -> lang.indentAfter.containsMatchIn(beforeTrim)
            else -> false
        }
        if (opensBlock) extra = cfg.indentUnit
        if (!opensBlock && lang.dedentAfter?.matches(beforeTrim) == true) {
            baseIndent = removeOneIndent(baseIndent, cfg)
        }
        // Строка до курсора пустая — не оставляем висячие пробелы
        val cleanLine = if (line.isBlank()) "" else line.trimEnd(' ', '\t')
        val restTrimmed = tail.trimStart(' ', '\t')
        val firstAfter = restTrimmed.firstOrNull()
        val lastBefore = beforeTrim.lastOrNull()

        // Между парными скобками или тегами: "{|}" -> три строки
        val betweenBrackets = lastBefore != null && firstAfter != null && closerOf(lastBefore) == firstAfter
        val betweenTags = lang.markup && Regex("<([A-Za-z_][\\w.:-]*)[^<>]*[^/]>$|<([A-Za-z_][\\w.:-]*)>$").containsMatchIn(beforeTrim) &&
            restTrimmed.startsWith("</")
        if ((betweenBrackets || betweenTags) && mode == NewlineMode.SPLIT) {
            val inner = baseIndent + cfg.indentUnit
            val insert = "\n" + inner
            val t = head + cleanLine + insert + "\n" + baseIndent + restTrimmed
            return value(t, head.length + cleanLine.length + insert.length)
        }

        // Строка после курсора начинается с закрывающей — она уходит на уровень выше
        val indent = if (firstAfter != null && firstAfter in CLOSERS && !opensBlock && mode == NewlineMode.SPLIT) {
            val opener = enclosingOpener(text, b + (tail.length - restTrimmed.length), syntax, openerOf(firstAfter).toString())
            if (opener != null) leadingWhitespace(text, lineStartOf(text, opener)) else baseIndent
        } else baseIndent + extra

        val insert = "\n" + indent
        val t = head + cleanLine + insert + (if (mode == NewlineMode.SPLIT) restTrimmed else tail)
        return value(t, head.length + cleanLine.length + insert.length)
    }

    private fun xmlOpensBlock(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (trimmed.endsWith("/>") || trimmed.endsWith("-->") || trimmed.endsWith("?>")) return false
        // Незакрытый "<tag attr" — продолжение атрибутов
        val lastLt = trimmed.lastIndexOf('<')
        val lastGt = trimmed.lastIndexOf('>')
        if (lastLt > lastGt) return !trimmed.startsWith("<!--", lastLt) && trimmed.getOrNull(lastLt + 1) != '/'
        if (!trimmed.endsWith(">")) return false
        // Считаем открытые и закрытые теги строки
        var open = 0
        for (m in Regex("<(/?)[A-Za-z_][\\w.:-]*[^<>]*?(/?)>").findAll(trimmed)) {
            when {
                m.groupValues[2] == "/" -> Unit
                m.groupValues[1] == "/" -> open--
                else -> open++
            }
        }
        if (open > 0) return true
        // Хвост многострочного открывающего тега: "    android:text=\"x\">"
        return open == 0 && !trimmed.contains('<') && trimmed.endsWith(">") && !trimmed.endsWith("/>")
    }

    private fun stripTrailingComment(line: String, lineStart: Int, syntax: SyntaxInfo): String {
        var end = line.length
        while (end > 0) {
            val t = syntax.tokenAt(lineStart + end - 1)
            if (t == Tok.COMMENT || t == Tok.DOC_COMMENT) end-- else break
        }
        return if (end == line.length) line else line.substring(0, end)
    }

    private fun removeOneIndent(indent: String, cfg: EditConfig): String {
        if (indent.isEmpty()) return indent
        if (indent.endsWith("\t")) return indent.dropLast(1)
        val w = indentWidth(indent, cfg.tabSize)
        return makeIndent(((w - 1) / cfg.unitWidth) * cfg.unitWidth, cfg)
    }

    /** Отступ строки-закрывашки: как у строки, открывшей блок, либо на уровень меньше предыдущей. */
    private fun expectedDedent(text: String, lineStart: Int, cfg: EditConfig): String? {
        var p = lineStart - 1
        while (p > 0) {
            val ps = lineStartOf(text, p)
            val prevLine = text.substring(ps, p)
            if (prevLine.isNotBlank()) {
                val prevIndent = leadingWhitespace(prevLine, 0)
                val opener = cfg.language.indentAfter?.containsMatchIn(prevLine.trimEnd()) == true &&
                    cfg.language.dedentLine?.matches(prevLine) != true
                return if (opener) prevIndent else removeOneIndent(prevIndent, cfg)
            }
            p = ps - 1
        }
        return null
    }

    // ---------------- Отступы ----------------

    private data class LineBlock(val start: Int, val end: Int)

    /** Строки, которых касается выделение (конец на начале строки не считается). */
    private fun selectedLines(text: String, sel: TextRange): LineBlock {
        val a = sel.min
        var b = sel.max
        if (b > a && b > 0 && text.getOrNull(b - 1) == '\n') b--
        val start = lineStartOf(text, a)
        val end = lineEndOf(text, b)
        return LineBlock(start, end)
    }

    fun tab(v: TextFieldValue, cfg: EditConfig, syntax: SyntaxInfo): TextFieldValue {
        val text = v.text
        val sel = v.selection
        // Как в IntelliJ: Tab при выделении сдвигает строки
        if (!sel.collapsed) return indentLines(v, cfg)
        val c = sel.start
        val ls = lineStartOf(text, c)
        // Пустая строка / курсор в отступе: сразу к ожидаемому отступу
        if (onlyWhitespaceBefore(text, c)) {
            val le = lineEndOf(text, c)
            val lineBlank = text.substring(c, le).isBlank()
            if (lineBlank) {
                val expected = expectedIndentFor(text, ls, cfg, syntax)
                val curWidth = indentWidth(text.substring(ls, c), cfg.tabSize)
                if (expected != null && indentWidth(expected, cfg.tabSize) > curWidth) {
                    val t = text.substring(0, ls) + expected + text.substring(le)
                    return value(t, ls + expected.length)
                }
            }
        }
        val insert = if (cfg.indentUnit == "\t") "\t" else {
            val col = columnOf(text, c, cfg.tabSize)
            " ".repeat(cfg.unitWidth - col % cfg.unitWidth)
        }
        return value(text.substring(0, c) + insert + text.substring(c), c + insert.length)
    }

    /** Отступ, который получила бы новая строка после предыдущей непустой. */
    fun expectedIndentFor(text: String, lineStart: Int, cfg: EditConfig, syntax: SyntaxInfo): String? {
        var p = lineStart - 1
        while (p >= 0) {
            val ps = lineStartOf(text, p)
            val prevLine = text.substring(ps, p)
            if (prevLine.isNotBlank()) {
                val nv = newline(TextFieldValue(text.substring(0, p), TextRange(p)), cfg, syntax, NewlineMode.SPLIT)
                val nt = nv.text
                val nls = lineStartOf(nt, nv.selection.start)
                return nt.substring(nls, nv.selection.start)
            }
            p = ps - 1
        }
        return null
    }

    /** Сдвиг строк: [shift] — сколько символов добавлено (>0) или удалено (<0) в начале каждой строки. */
    private fun remapAfterLineShift(p: Int, lineStarts: IntArray, shifts: IntArray, keepAtLineStart: Boolean): Int {
        var before = 0
        for (i in lineStarts.indices) {
            val ls = lineStarts[i]
            val next = if (i + 1 < lineStarts.size) lineStarts[i + 1] else Int.MAX_VALUE
            if (p < next || i == lineStarts.lastIndex) {
                val sh = shifts[i]
                val rel = p - ls
                val own = when {
                    sh >= 0 -> if (rel == 0 && keepAtLineStart) 0 else sh
                    else -> -minOf(-sh, rel)
                }
                return ls + before + rel + own
            }
            before += shifts[i]
        }
        return p
    }

    private fun shiftLines(v: TextFieldValue, cfg: EditConfig, indent: Boolean): TextFieldValue {
        val text = v.text
        val block = selectedLines(text, v.selection)
        val lines = text.substring(block.start, block.end).split('\n')
        val starts = IntArray(lines.size)
        val shifts = IntArray(lines.size)
        val sb = StringBuilder()
        var pos = block.start
        for ((i, l) in lines.withIndex()) {
            starts[i] = pos
            if (i > 0) sb.append('\n')
            if (indent) {
                if (l.isNotBlank()) {
                    sb.append(cfg.indentUnit)
                    shifts[i] = cfg.indentUnit.length
                }
                sb.append(l)
            } else {
                var remove = 0
                if (l.startsWith("\t")) remove = 1
                else while (remove < cfg.unitWidth && remove < l.length && l[remove] == ' ') remove++
                shifts[i] = -remove
                sb.append(l, remove, l.length)
            }
            pos += l.length + 1
        }
        if (shifts.all { it == 0 }) return v
        val t = text.substring(0, block.start) + sb + text.substring(block.end)
        val min = v.selection.min
        fun map(p: Int) = remapAfterLineShift(p, starts, shifts, keepAtLineStart = p == min && !v.selection.collapsed)
            .coerceIn(0, t.length)
        return value(t, map(v.selection.start), map(v.selection.end))
    }

    fun indentLines(v: TextFieldValue, cfg: EditConfig): TextFieldValue = shiftLines(v, cfg, indent = true)

    fun unindent(v: TextFieldValue, cfg: EditConfig): TextFieldValue = shiftLines(v, cfg, indent = false)

    // ---------------- Строки ----------------

    fun duplicate(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val sel = v.selection
        if (!sel.collapsed) {
            val piece = text.substring(sel.min, sel.max)
            val t = text.substring(0, sel.max) + piece + text.substring(sel.max)
            return value(t, sel.max, sel.max + piece.length)
        }
        val ls = lineStartOf(text, sel.start)
        val le = lineEndOf(text, sel.start)
        val line = text.substring(ls, le)
        val t = text.substring(0, le) + "\n" + line + text.substring(le)
        return value(t, sel.start + line.length + 1)
    }

    fun deleteLines(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val block = selectedLines(text, v.selection)
        val col = v.selection.start - lineStartOf(text, v.selection.start)
        val (from, to) = if (block.end < text.length) block.start to block.end + 1
        else if (block.start > 0) block.start - 1 to block.end
        else block.start to block.end
        val t = text.substring(0, from) + text.substring(to)
        val ls = if (block.end < text.length) from else lineStartOf(t, from)
        val le = lineEndOf(t, ls)
        return value(t, minOf(ls + col, le))
    }

    fun moveLines(v: TextFieldValue, up: Boolean): TextFieldValue? {
        val text = v.text
        val block = selectedLines(text, v.selection)
        if (up && block.start == 0) return null
        if (!up && block.end >= text.length) return null
        val lines = text.substring(block.start, block.end)
        return if (up) {
            val prevStart = lineStartOf(text, block.start - 1)
            val prev = text.substring(prevStart, block.start - 1)
            val t = text.substring(0, prevStart) + lines + "\n" + prev + text.substring(block.end)
            val shift = -(prev.length + 1)
            value(t, v.selection.start + shift, v.selection.end + shift)
        } else {
            val nextEnd = lineEndOf(text, block.end + 1)
            val next = text.substring(block.end + 1, nextEnd)
            val t = text.substring(0, block.start) + next + "\n" + lines + text.substring(nextEnd)
            val shift = next.length + 1
            value(t, v.selection.start + shift, v.selection.end + shift)
        }
    }

    fun toggleLineComment(v: TextFieldValue, cfg: EditConfig): TextFieldValue {
        val lang = cfg.language
        val token = lang.lineComment
        if (token == null) {
            val bc = lang.blockComment ?: return v
            return toggleBlockLines(v, bc)
        }
        val text = v.text
        val block = selectedLines(text, v.selection)
        val lines = text.substring(block.start, block.end).split('\n')
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) {
            if (lines.size == 1) {
                val t = text.substring(0, block.start) + lines[0] + token + " " + text.substring(block.end)
                return value(t, block.start + lines[0].length + token.length + 1)
            }
            return v
        }
        val trimmedToken = token.trimEnd()
        val allCommented = nonBlank.all { it.trimStart().startsWith(trimmedToken) }
        val minIndent = nonBlank.minOf { it.length - it.trimStart().length }
        val sb = StringBuilder()
        val offsets = IntArray(lines.size)
        for ((i, l) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            if (l.isBlank()) {
                sb.append(l); offsets[i] = 0; continue
            }
            if (allCommented) {
                val idx = l.indexOf(trimmedToken)
                var removeEnd = idx + trimmedToken.length
                if (l.getOrNull(removeEnd) == ' ') removeEnd++
                sb.append(l, 0, idx).append(l, removeEnd, l.length)
                offsets[i] = -(removeEnd - idx)
            } else {
                sb.append(l, 0, minIndent).append(token).append(' ').append(l, minIndent, l.length)
                offsets[i] = token.length + 1
            }
        }
        val t = text.substring(0, block.start) + sb + text.substring(block.end)
        val firstShift = offsets.firstOrNull() ?: 0
        val totalShift = offsets.sum()
        if (v.selection.collapsed && lines.size == 1) {
            // Как в IntelliJ: курсор переходит на следующую строку
            val nextLineStart = block.start + sb.length + 1
            if (nextLineStart <= t.length) {
                val col = v.selection.start - block.start
                val nle = lineEndOf(t, nextLineStart)
                return value(t, minOf(nextLineStart + col, nle))
            }
            return value(t, (v.selection.start + firstShift).coerceIn(block.start, t.length))
        }
        val s = (v.selection.min + firstShift).coerceIn(block.start, t.length)
        val e = (v.selection.max + totalShift).coerceIn(s, t.length)
        return if (v.selection.start <= v.selection.end) value(t, s, e) else value(t, e, s)
    }

    private fun toggleBlockLines(v: TextFieldValue, bc: Pair<String, String>): TextFieldValue {
        val text = v.text
        val block = selectedLines(text, v.selection)
        val content = text.substring(block.start, block.end)
        val trimmed = content.trim()
        val lead = content.length - content.trimStart().length
        if (trimmed.startsWith(bc.first) && trimmed.endsWith(bc.second) && trimmed.length >= bc.first.length + bc.second.length) {
            var inner = trimmed.substring(bc.first.length, trimmed.length - bc.second.length)
            if (inner.startsWith(" ")) inner = inner.substring(1)
            if (inner.endsWith(" ")) inner = inner.dropLast(1)
            val newContent = content.substring(0, lead) + inner
            val t = text.substring(0, block.start) + newContent + text.substring(block.end)
            return value(t, block.start + lead, block.start + newContent.length)
        }
        val newContent = content.substring(0, lead) + bc.first + " " + content.substring(lead) + " " + bc.second
        val t = text.substring(0, block.start) + newContent + text.substring(block.end)
        return value(t, block.start + lead, block.start + newContent.length)
    }

    fun toggleBlockComment(v: TextFieldValue, cfg: EditConfig, syntax: SyntaxInfo): TextFieldValue {
        val bc = cfg.language.blockComment ?: return toggleLineComment(v, cfg)
        val text = v.text
        val sel = v.selection
        if (sel.collapsed) {
            // Курсор внутри комментария — снять его
            val c = sel.start
            if (syntax.tokenAt(c) == Tok.COMMENT || syntax.tokenAt(c - 1) == Tok.COMMENT ||
                syntax.tokenAt(c) == Tok.DOC_COMMENT || syntax.tokenAt(c - 1) == Tok.DOC_COMMENT
            ) {
                val open = text.lastIndexOf(bc.first, c)
                val close = text.indexOf(bc.second, maxOf(open + bc.first.length, c - bc.second.length))
                if (open >= 0 && close >= 0 && close >= c - bc.second.length) {
                    val inner = text.substring(open + bc.first.length, close)
                    val t = text.substring(0, open) + inner + text.substring(close + bc.second.length)
                    return value(t, (c - bc.first.length).coerceIn(open, open + inner.length))
                }
            }
            val insert = bc.first + "  " + bc.second
            val t = text.substring(0, c) + insert + text.substring(c)
            return value(t, c + bc.first.length + 1)
        }
        val a = sel.min
        val b = sel.max
        val piece = text.substring(a, b)
        // Уже закомментировано (маркеры внутри выделения или сразу снаружи)
        if (piece.startsWith(bc.first) && piece.endsWith(bc.second) && piece.length >= bc.first.length + bc.second.length) {
            val inner = piece.substring(bc.first.length, piece.length - bc.second.length)
            val t = text.substring(0, a) + inner + text.substring(b)
            return value(t, a, a + inner.length)
        }
        if (text.startsWith(bc.first, a - bc.first.length) && text.startsWith(bc.second, b)) {
            val t = text.substring(0, a - bc.first.length) + piece + text.substring(b + bc.second.length)
            return value(t, a - bc.first.length, b - bc.first.length)
        }
        val t = text.substring(0, a) + bc.first + piece + bc.second + text.substring(b)
        return value(t, a + bc.first.length, b + bc.first.length)
    }

    fun toggleCase(v: TextFieldValue, isWordChar: (Char) -> Boolean): TextFieldValue {
        val text = v.text
        val range = if (v.selection.collapsed) wordRangeAt(text, v.selection.start, isWordChar) ?: return v
        else v.selection.min until v.selection.max
        val piece = text.substring(range.first, range.last + 1)
        val converted = if (piece.any { it.isLowerCase() }) piece.uppercase() else piece.lowercase()
        if (converted.length != piece.length) return v
        val t = text.substring(0, range.first) + converted + text.substring(range.last + 1)
        return TextFieldValue(t, v.selection)
    }

    fun joinLines(v: TextFieldValue, cfg: EditConfig): TextFieldValue {
        var text = v.text
        val block = selectedLines(text, v.selection)
        var joins = maxOf(1, text.substring(block.start, block.end).count { it == '\n' })
        var pos = lineEndOf(text, block.start)
        var caret = pos
        val comment = cfg.language.lineComment?.trimEnd()
        while (joins-- > 0 && pos < text.length) {
            var next = pos + 1
            while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
            var left = pos
            while (left > 0 && (text[left - 1] == ' ' || text[left - 1] == '\t')) left--
            val leftLine = text.substring(lineStartOf(text, left), left).trimStart()
            // Обе строки — комментарии: "// a" + "// b" -> "// a b"
            if (comment != null && leftLine.startsWith(comment) && text.startsWith(comment, next)) {
                next += comment.length
                while (next < text.length && text[next] == ' ') next++
            }
            val nextChar = text.getOrNull(next)
            val prevChar = text.getOrNull(left - 1)
            val sep = when {
                next >= text.length || nextChar == '\n' -> ""
                prevChar == null || prevChar == '\n' -> ""
                nextChar != null && nextChar in ").,;]" -> ""
                prevChar in "([" -> ""
                else -> " "
            }
            text = text.substring(0, left) + sep + text.substring(next)
            caret = left + sep.length
            pos = lineEndOf(text, left)
        }
        return value(text, if (v.selection.collapsed) caret else block.start)
    }

    fun smartHome(v: TextFieldValue, extend: Boolean): TextFieldValue {
        val text = v.text
        val c = v.selection.end
        val ls = lineStartOf(text, c)
        var first = ls
        while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
        val target = if (c == first) ls else first
        return if (extend) TextFieldValue(text, TextRange(v.selection.start, target)) else TextFieldValue(text, TextRange(target))
    }

    fun smartEnd(v: TextFieldValue, extend: Boolean): TextFieldValue {
        val text = v.text
        val c = v.selection.end
        val le = lineEndOf(text, c)
        var last = le
        while (last > 0 && last > lineStartOf(text, c) && (text[last - 1] == ' ' || text[last - 1] == '\t')) last--
        val target = if (c == last) le else last
        return if (extend) TextFieldValue(text, TextRange(v.selection.start, target)) else TextFieldValue(text, TextRange(target))
    }

    // ---------------- Ctrl+W: расширение выделения ----------------

    /**
     * Следующее, более широкое выделение, как «Extend Selection» в IntelliJ:
     * слово → составное имя → содержимое строки/комментария → содержимое скобок → скобки целиком →
     * строка → элемент XML → блок → весь файл.
     */
    fun expandSelection(v: TextFieldValue, cfg: EditConfig, syntax: HighlightSnapshot?): TextRange? {
        val text = v.text
        if (text.isEmpty()) return null
        val a = v.selection.min
        val b = v.selection.max
        val candidates = ArrayList<IntRange>()
        fun add(s: Int, e: Int) {
            if (s < 0 || e > text.length || e <= s) return
            if (s <= a && e >= b && (s < a || e > b)) candidates.add(s until e)
        }
        val lang = cfg.language
        // 1. Слово
        val w = wordRangeAt(text, if (a == b) a else a, lang::isWordChar)
        if (w != null) add(w.first, w.last + 1)
        // 1a. Часть camelCase / snake_case внутри слова
        if (w != null && a == b) {
            val part = humpRange(text, a, w)
            if (part != null) add(part.first, part.last + 1)
        }
        // 2. Составное имя: a.b.c, Lcom/x/Y;, android:layout_width, --flag
        run {
            val extra = when {
                lang.id == "smali" -> "_$.:/-@;<>"
                lang.markup -> "_$.:/-@"
                else -> "_$.:/-@"
            }
            val ext: (Char) -> Boolean = { c -> c.isLetterOrDigit() || c in extra }
            var s = a
            var e = b
            while (s > 0 && ext(text[s - 1])) s--
            while (e < text.length && ext(text[e])) e++
            // Не захватываем хвостовые ':' и '.'
            while (e > s && text[e - 1] in ".:-") e--
            while (s < e && text[s] in ".:-") s++
            add(s, e)
        }
        // 3. Строка/комментарий по токенам
        if (syntax != null) {
            for (p in intArrayOf(a, maxOf(0, a - 1))) {
                val tr = syntax.tokenRangeAt(p) ?: continue
                val (range, type) = tr
                val s = range.first
                val e = range.last + 1
                if (type == Tok.STRING) {
                    val q = text[s]
                    val quoteLen = if (text.startsWith("\"\"\"", s)) 3 else if (q == '"' || q == '\'' || q == '`') 1 else 0
                    if (quoteLen > 0 && e - s >= quoteLen * 2 && text.startsWith(text.substring(s, s + quoteLen), e - quoteLen)) {
                        add(s + quoteLen, e - quoteLen)
                    }
                    add(s, e)
                } else if (type == Tok.COMMENT || type == Tok.DOC_COMMENT) {
                    add(s, e)
                }
            }
        }
        // 4. Скобки: содержимое без пробелов, содержимое, со скобками, вместе с заголовком
        val si = syntax ?: SyntaxInfo.None
        var from = a
        var guard = 0
        while (guard++ < 40) {
            val open = enclosingOpener(text, from, si) ?: break
            val close = matchBracketAt(text, open, si) ?: break
            if (close < b - 1) {
                from = open
                continue
            }
            val innerS = open + 1
            val innerE = close
            var ts = innerS
            var te = innerE
            while (ts < te && text[ts].isWhitespace()) ts++
            while (te > ts && text[te - 1].isWhitespace()) te--
            add(ts, te)
            add(innerS, innerE)
            add(open, close + 1)
            // Заголовок блока: "fun foo() { … }" / "if (x) { … }"
            val headerStart = lineStartOf(text, open).let { ls ->
                var k = ls
                while (k < open && text[k].isWhitespace()) k++
                k
            }
            add(headerStart, close + 1)
            val lineEndAfter = lineEndOf(text, close + 1)
            if (text.substring(close + 1, lineEndAfter).isBlank()) add(lineStartOf(text, open), lineEndAfter)
            from = open
        }
        // 5. Строка: без отступа, целиком
        val ls = lineStartOf(text, a)
        val le = lineEndOf(text, b)
        var fs = ls
        while (fs < le && text[fs].isWhitespace()) fs++
        var fe = le
        while (fe > fs && text[fe - 1].isWhitespace()) fe--
        add(fs, fe)
        add(ls, le)
        if (le < text.length) add(ls, le + 1)
        // 6. Элементы XML
        if (lang.markup) for (r in xmlElementRanges(text, a, b)) add(r.first, r.last + 1)
        // 7. Блок по отступу (Python/YAML и все остальные — как запасной вариант)
        indentBlock(text, a, b, cfg)?.let { add(it.first, it.last + 1) }
        // 8. Весь файл
        add(0, text.length)
        val best = candidates.minByOrNull { it.last - it.first } ?: return null
        return TextRange(best.first, best.last + 1)
    }

    private fun humpRange(text: String, caret: Int, word: IntRange): IntRange? {
        val s0 = word.first
        val e0 = word.last + 1
        if (e0 - s0 < 3) return null
        var s = caret
        var e = caret
        fun boundary(i: Int): Boolean {
            if (i <= s0 || i >= e0) return true
            val p = text[i - 1]
            val c = text[i]
            return p == '_' || c == '_' || (c.isUpperCase() && p.isLowerCase()) || (c.isDigit() != p.isDigit())
        }
        while (s > s0 && !boundary(s)) s--
        while (e < e0 && !boundary(e)) e++
        if (text.getOrNull(s) == '_') s++
        if (e <= s || (s == s0 && e == e0)) return null
        return s until e
    }

    private fun indentBlock(text: String, a: Int, b: Int, cfg: EditConfig): IntRange? {
        val ls = lineStartOf(text, a)
        val baseIndent = indentWidth(leadingWhitespace(text, ls), cfg.tabSize)
        if (baseIndent == 0) return null
        var start = ls
        // Вверх, пока строки не менее вложены
        while (start > 0) {
            val ps = lineStartOf(text, start - 1)
            val line = text.substring(ps, start - 1)
            if (line.isNotBlank() && indentWidth(leadingWhitespace(line, 0), cfg.tabSize) < baseIndent) break
            start = ps
        }
        var end = lineEndOf(text, b)
        while (end < text.length) {
            val ne = lineEndOf(text, end + 1)
            val line = text.substring(end + 1, ne)
            if (line.isNotBlank() && indentWidth(leadingWhitespace(line, 0), cfg.tabSize) < baseIndent) break
            end = ne
        }
        while (start < end && text[start] == '\n') start++
        return if (end > start) start until end else null
    }

    private fun xmlElementRanges(text: String, a: Int, b: Int): List<IntRange> {
        val tagRe = Regex("<(/?)([A-Za-z_][\\w.:-]*)[^<>]*?(/?)>")
        val scanFrom = maxOf(0, a - 400_000)
        val scanTo = minOf(text.length, b + 400_000)
        val open = ArrayList<Triple<String, Int, Int>>()
        val result = ArrayList<IntRange>()
        for (m in tagRe.findAll(text.substring(scanFrom, scanTo))) {
            val s = scanFrom + m.range.first
            val e = scanFrom + m.range.last + 1
            val name = m.groupValues[2]
            when {
                m.groupValues[3] == "/" -> if (s <= a && e >= b) result.add(s until e)
                m.groupValues[1] == "/" -> {
                    val idx = open.indexOfLast { it.first == name }
                    if (idx >= 0) {
                        val o = open[idx]
                        while (open.size > idx) open.removeAt(open.size - 1)
                        if (o.second <= a && e >= b) {
                            result.add(o.third until s)
                            result.add(o.second until e)
                        }
                    }
                }
                else -> open.add(Triple(name, s, e))
            }
            if (s > b && open.isEmpty()) break
        }
        // Атрибут целиком: name="value"
        val attr = Regex("[\\w.:-]+\\s*=\\s*(\"[^\"]*\"|'[^']*')")
        val ls = lineStartOf(text, a)
        val le = lineEndOf(text, b)
        for (m in attr.findAll(text.substring(ls, le))) {
            val s = ls + m.range.first
            val e = ls + m.range.last + 1
            if (s <= a && e >= b) result.add(s until e)
        }
        return result
    }

    // ---------------- Преобразования ----------------

    /** Преобразовать выделенные строки целиком (или весь текст, если выделения нет). */
    fun transformLines(v: TextFieldValue, fn: (List<String>) -> List<String>): TextFieldValue {
        val text = v.text
        val whole = v.selection.collapsed
        val block = if (whole) LineBlock(0, text.length) else selectedLines(text, v.selection)
        val lines = text.substring(block.start, block.end).split('\n')
        val out = fn(lines).joinToString("\n")
        val t = text.substring(0, block.start) + out + text.substring(block.end)
        return if (whole) value(t, v.selection.start.coerceAtMost(t.length))
        else value(t, block.start, block.start + out.length)
    }

    /** Преобразовать выделение (или слово под курсором). */
    fun transformSelection(v: TextFieldValue, isWordChar: (Char) -> Boolean, fn: (String) -> String): TextFieldValue {
        val text = v.text
        val range = if (v.selection.collapsed) wordRangeAt(text, v.selection.start, isWordChar) ?: return v
        else v.selection.min until v.selection.max
        val piece = text.substring(range.first, range.last + 1)
        val out = fn(piece)
        val t = text.substring(0, range.first) + out + text.substring(range.last + 1)
        return value(t, range.first, range.first + out.length)
    }

    fun trimTrailingWhitespace(text: String): String =
        text.split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }

    fun convertIndentation(text: String, toTabs: Boolean, tabSize: Int): String =
        text.split('\n').joinToString("\n") { line ->
            val ws = line.length - line.trimStart(' ', '\t').length
            val width = indentWidth(line.substring(0, ws), tabSize)
            val indent = if (toTabs) "\t".repeat(width / tabSize) + " ".repeat(width % tabSize) else " ".repeat(width)
            indent + line.substring(ws)
        }

    // Регистр и стиль имён
    private fun splitWords(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val boundary = c == '_' || c == '-' || c == ' ' || c == '.'
            if (boundary) {
                if (cur.isNotEmpty()) out.add(cur.toString())
                cur.clear()
                continue
            }
            if (cur.isNotEmpty() && c.isUpperCase() && (s[i - 1].isLowerCase() || s[i - 1].isDigit() ||
                    (i + 1 < s.length && s[i + 1].isLowerCase() && s[i - 1].isUpperCase()))
            ) {
                out.add(cur.toString())
                cur.clear()
            }
            cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    fun toCamelCase(s: String): String = splitWords(s).mapIndexed { i, w ->
        if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() }
    }.joinToString("")

    fun toPascalCase(s: String): String = splitWords(s).joinToString("") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    fun toSnakeCase(s: String): String = splitWords(s).joinToString("_") { it.lowercase() }
    fun toScreamingSnake(s: String): String = splitWords(s).joinToString("_") { it.uppercase() }
    fun toKebabCase(s: String): String = splitWords(s).joinToString("-") { it.lowercase() }

    fun escapeString(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    fun unescapeString(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    '0' -> append('\u0000')
                    '\\' -> append('\\')
                    '"' -> append('"')
                    '\'' -> append('\'')
                    'u' -> if (i + 6 <= s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            append(code.toChar()); i += 6; continue
                        } else append("\\u")
                    } else append("\\u")
                    else -> {
                        append('\\'); append(n)
                    }
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
