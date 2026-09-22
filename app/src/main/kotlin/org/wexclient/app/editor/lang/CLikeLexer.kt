package org.wexclient.app.editor.lang

/**
 * Построчный лексер: принимает состояние конца предыдущей строки, возвращает своё.
 * Состояние — Int, чтобы кэш (строка, состояние) -> токены был дешёвым.
 */
interface LineLexer {
    fun tokenize(line: String, state: Int, out: MutableList<Token>): Int
}

object PlainLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int = 0
}

/** Правило строкового литерала. */
data class StringRule(
    val open: String,
    val close: String = open,
    val escape: Char? = '\\',
    val multiline: Boolean = false,
    /** "${" / "{" / "\\(" — подстановки внутри строки красятся как переменные. */
    val interpolation: List<String> = emptyList(),
    /** $name внутри строки (Kotlin, Groovy, Dart, shell). */
    val dollarIdent: Boolean = false,
    val tok: Tok = Tok.STRING,
    /** Кавычка внутри удваивается ('' в SQL, "" в C# @"...") вместо escape. */
    val doubledQuoteEscape: Boolean = false
)

data class BlockComment(val open: String, val close: String, val doc: Boolean = false, val nested: Boolean = false)

/** Описание языка для [CLikeLexer]: ключевые слова, комментарии, строки и особенности синтаксиса. */
data class LexSpec(
    val keywords: Set<String> = emptySet(),
    val control: Set<String> = emptySet(),
    val modifiers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val constants: Set<String> = emptySet(),
    val caseInsensitive: Boolean = false,
    val lineComments: List<String> = listOf("//"),
    /** Комментарий-строка только в начале слова (shell: `echo a#b` — не комментарий). */
    val lineCommentNeedsBoundary: Boolean = false,
    /** Комментарий только в начале строки (VimScript `"`, Fortran `C`). */
    val lineCommentOnlyAtStart: Boolean = false,
    val blockComments: List<BlockComment> = listOf(BlockComment("/**", "*/", doc = true), BlockComment("/*", "*/")),
    val strings: List<StringRule> = listOf(StringRule("\""), StringRule("'")),
    /** Префиксы строк Python/Rust/C#: r, b, f, rb, @, $… (регистр не важен). */
    val stringPrefixes: Set<String> = emptySet(),
    val identExtra: String = "_",
    val identStartExtra: String = "_",
    val annotationPrefix: Char? = null,
    val variablePrefixes: String = "",
    /** #include/#define в начале строки. */
    val preprocessor: Boolean = false,
    /** Rust: #[attr] / #![attr]. */
    val hashAttributes: Boolean = false,
    /** Rust: println!() — макрос. */
    val macroBang: Boolean = false,
    /** Rust: 'a — время жизни. */
    val lifetimes: Boolean = false,
    val capitalizedTypes: Boolean = true,
    val allCapsConstants: Boolean = true,
    val functionCalls: Boolean = true,
    val declFunction: Set<String> = emptySet(),
    val declType: Set<String> = emptySet(),
    val numberSuffixes: String = "lLfFdDuU",
    val operators: String = "+-*/%=<>!&|^~?:",
    /** JS: /regex/ после оператора или скобки. */
    val regexLiterals: Boolean = false,
    /** QML/Ruby: `name:` — свойство. */
    val colonProperties: Boolean = false,
    /** Ruby/Elixir: `:symbol` — константа. */
    val atoms: Boolean = false
)

class CLikeLexer(private val spec: LexSpec) : LineLexer {
    // Длинные открывающие последовательности проверяются первыми ("""", /** до ", /*)
    private val stringRules = spec.strings.sortedByDescending { it.open.length }
    private val blockRules = spec.blockComments.sortedByDescending { it.open.length }
    private val lineComments = spec.lineComments.sortedByDescending { it.length }

    private fun norm(w: String) = if (spec.caseInsensitive) w.lowercase() else w
    private val keywords = spec.keywords.mapTo(HashSet()) { norm(it) }
    private val control = spec.control.mapTo(HashSet()) { norm(it) }
    private val modifiers = spec.modifiers.mapTo(HashSet()) { norm(it) }
    private val types = spec.types.mapTo(HashSet()) { norm(it) }
    private val builtins = spec.builtins.mapTo(HashSet()) { norm(it) }
    private val constants = spec.constants.mapTo(HashSet()) { norm(it) }
    private val declFunction = spec.declFunction.mapTo(HashSet()) { norm(it) }
    private val declType = spec.declType.mapTo(HashSet()) { norm(it) }
    private val prefixes = spec.stringPrefixes.mapTo(HashSet()) { it.lowercase() }

    /**
     * Отсев по первому символу. Иначе на каждом символе строки перебирались все правила
     * комментариев и строк: на файле в полтора мегабайта это миллионы лишних проверок.
     * Правила задаются латиницей, поэтому хватает таблицы на ASCII; что-то иное — отсев выключен.
     */
    private val blockStarts = firstChars(blockRules.map { it.open })
    private val lineCommentStarts = firstChars(lineComments)
    private val stringStarts = firstChars(
        stringRules.map { it.open } + prefixes.flatMap { listOf(it, it.uppercase()) }
    )

    private fun firstChars(items: List<String>): BooleanArray? {
        val table = BooleanArray(128)
        for (s in items) {
            val c = s.firstOrNull() ?: continue
            if (c.code >= table.size) return null
            table[c.code] = true
        }
        return table
    }

    private fun mayStart(table: BooleanArray?, c: Char): Boolean {
        if (table == null) return true
        val code = c.code
        return code < table.size && table[code]
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c in spec.identStartExtra
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c in spec.identExtra

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        val n = line.length
        var st = state

        // Продолжение многострочного комментария / строки
        if (st != 0) {
            val r = continueState(line, st, out)
            i = r.first
            st = r.second
            if (st != 0) return st
        }

        var pendingDecl: Tok? = null
        var lastSignificant: Tok? = null
        val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) n else it }

        while (i < n) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++
                continue
            }

            // Препроцессор C/C++: #include <x> / #define NAME
            if (spec.preprocessor && c == '#' && i == firstNonSpace) {
                var j = i + 1
                while (j < n && line[j].isWhitespace()) j++
                while (j < n && isIdentPart(line[j])) j++
                out.add(Token(i, j, Tok.PREPROCESSOR))
                val directive = line.substring(i + 1, j).trim()
                i = j
                if (directive == "include" || directive == "import") {
                    while (i < n && line[i].isWhitespace()) i++
                    if (i < n && line[i] == '<') {
                        val close = line.indexOf('>', i)
                        val end = if (close < 0) n else close + 1
                        out.add(Token(i, end, Tok.STRING))
                        i = end
                    }
                }
                lastSignificant = Tok.PREPROCESSOR
                continue
            }

            // Атрибуты Rust #[...]
            if (spec.hashAttributes && c == '#' && (line.startsWith("#[", i) || line.startsWith("#![", i))) {
                val end = matchBracket(line, line.indexOf('[', i))
                out.add(Token(i, end, Tok.ANNOTATION))
                i = end
                continue
            }

            // Блочные комментарии — раньше строчных (в Lua блок начинается с --[[)
            val bc = if (mayStart(blockStarts, c)) blockRules.firstOrNull { line.startsWith(it.open, i) } else null
            if (bc != null) {
                // "/**/" — пустой обычный комментарий, не документация
                val rule = if (bc.doc && line.startsWith(bc.open.dropLast(1) + bc.close, i)) {
                    blockRules.firstOrNull { !it.doc && line.startsWith(it.open, i) } ?: bc
                } else bc
                val idx = blockRules.indexOf(rule)
                val r = scanBlockComment(line, i + rule.open.length, idx, 1, i, out)
                if (r.second != 0) return r.second
                i = r.first
                continue
            }

            // Однострочные комментарии
            val lc = if (mayStart(lineCommentStarts, c)) lineComments.firstOrNull { line.startsWith(it, i) } else null
            if (lc != null && (!spec.lineCommentOnlyAtStart || i == firstNonSpace) &&
                (!spec.lineCommentNeedsBoundary || i == 0 || line[i - 1].isWhitespace() || line[i - 1] in ";|&(")
            ) {
                val isDoc = lc == "//" && (line.startsWith("///", i) || line.startsWith("//!", i))
                emitCommentWithTags(line, i, n, if (isDoc) Tok.DOC_COMMENT else Tok.COMMENT, out)
                return 0
            }

            // Время жизни Rust: 'a — до строк, иначе ' начнёт символьный литерал
            if (spec.lifetimes && c == '\'' && i + 1 < n && isIdentStart(line[i + 1])) {
                var j = i + 1
                while (j < n && isIdentPart(line[j])) j++
                if (j >= n || line[j] != '\'') {
                    out.add(Token(i, j, Tok.LABEL))
                    i = j
                    continue
                }
            }

            // Строки (с префиксами r"", f"", b"" и т.п.)
            val strStart = if (mayStart(stringStarts, c)) stringStartAt(line, i) else null
            if (strStart != null) {
                val (prefixLen, ruleIdx, raw, fmt) = strStart
                val r = scanString(line, i, i + prefixLen + stringRules[ruleIdx].open.length, ruleIdx, raw, fmt, out)
                if (r.second != 0) return r.second
                i = r.first
                lastSignificant = Tok.STRING
                continue
            }

            // Числа
            if (c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit() && (i == 0 || !isIdentPart(line[i - 1])))) {
                val end = scanNumber(line, i)
                out.add(Token(i, end, Tok.NUMBER))
                i = end
                lastSignificant = Tok.NUMBER
                continue
            }

            // Аннотации @Foo / декораторы
            if (spec.annotationPrefix != null && c == spec.annotationPrefix && i + 1 < n && isIdentStart(line[i + 1])) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] == '.' || line[j] == ':')) j++
                out.add(Token(i, j, Tok.ANNOTATION))
                i = j
                continue
            }

            // Символы Ruby/Elixir :name (но не "::" и не "a ? b : c")
            if (spec.atoms && c == ':' && isAtomStart(line, i)) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] in "?!")) j++
                out.add(Token(i, j, Tok.CONSTANT))
                i = j
                lastSignificant = Tok.CONSTANT
                continue
            }

            // Переменные $x, ${x}, @x, %x
            if (c in spec.variablePrefixes && i + 1 < n) {
                val end = scanVariable(line, i)
                if (end > i + 1) {
                    out.add(Token(i, end, Tok.VARIABLE))
                    i = end
                    lastSignificant = Tok.VARIABLE
                    continue
                }
            }

            // Регулярки JS: /.../flags после оператора или открывающей скобки
            if (spec.regexLiterals && c == '/' && (lastSignificant == null || lastSignificant == Tok.OPERATOR ||
                    lastSignificant == Tok.BRACKET || lastSignificant == Tok.PUNCTUATION || lastSignificant == Tok.CONTROL ||
                    lastSignificant == Tok.KEYWORD)
            ) {
                val end = scanRegex(line, i)
                if (end > i + 1) {
                    out.add(Token(i, end, Tok.REGEX))
                    i = end
                    lastSignificant = Tok.REGEX
                    continue
                }
            }

            // Идентификаторы и ключевые слова
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < n && isIdentPart(line[j])) j++
                val word = line.substring(i, j)
                val w = norm(word)
                var type = when {
                    w in control -> Tok.CONTROL
                    w in modifiers -> Tok.MODIFIER
                    w in keywords -> Tok.KEYWORD
                    w in constants -> Tok.CONSTANT
                    w in types -> Tok.TYPE
                    w in builtins -> Tok.BUILTIN
                    else -> Tok.TEXT
                }
                if (type == Tok.TEXT) {
                    type = when {
                        pendingDecl != null -> pendingDecl
                        spec.colonProperties && j < n && line[j] == ':' && (j + 1 >= n || line[j + 1] != ':') -> Tok.PROPERTY
                        spec.macroBang && j < n && line[j] == '!' && (j + 1 >= n || line[j + 1] != '=') -> {
                            j++
                            Tok.FUNCTION
                        }
                        spec.functionCalls && nextNonSpace(line, j) == '(' -> Tok.FUNCTION
                        spec.allCapsConstants && word.length > 1 && word.any { it.isLetter() } &&
                            word.all { it.isUpperCase() || it.isDigit() || it == '_' } -> Tok.CONSTANT
                        spec.capitalizedTypes && word[0].isUpperCase() -> Tok.TYPE
                        else -> Tok.TEXT
                    }
                    pendingDecl = null
                } else {
                    pendingDecl = when (w) {
                        in declFunction -> Tok.FUNCTION
                        in declType -> Tok.TYPE
                        else -> if (type == Tok.MODIFIER) pendingDecl else null
                    }
                }
                if (type != Tok.TEXT) out.add(Token(i, j, type))
                lastSignificant = type
                i = j
                continue
            }

            // Скобки, операторы, пунктуация
            when {
                c in "()[]{}" -> {
                    out.add(Token(i, i + 1, Tok.BRACKET))
                    // После закрывающей скобки "/" — деление, после открывающей — регулярка
                    lastSignificant = if (c in "([{") Tok.BRACKET else Tok.TEXT
                    i++
                }
                c in spec.operators -> {
                    var j = i + 1
                    while (j < n && line[j] in spec.operators && j - i < 3) j++
                    out.add(Token(i, j, Tok.OPERATOR))
                    lastSignificant = Tok.OPERATOR
                    i = j
                }
                else -> {
                    lastSignificant = Tok.PUNCTUATION
                    i++
                }
            }
        }
        return 0
    }

    private data class StrStart(val prefixLen: Int, val ruleIdx: Int, val raw: Boolean, val formatted: Boolean)

    private fun stringStartAt(line: String, i: Int): StrStart? {
        // Без префикса
        for ((idx, rule) in stringRules.withIndex()) {
            if (line.startsWith(rule.open, i)) return StrStart(0, idx, false, false)
        }
        if (prefixes.isEmpty()) return null
        // С префиксом (не часть идентификатора слева)
        if (i > 0 && isIdentPart(line[i - 1])) return null
        for (len in 1..3) {
            if (i + len >= line.length) break
            // Без substring и lowercase: это место зовётся на каждом символе файла
            val p = prefixes.firstOrNull { it.length == len && line.regionMatches(i, it, 0, len, ignoreCase = true) }
                ?: continue
            for ((idx, rule) in stringRules.withIndex()) {
                if (rule.tok == Tok.STRING && line.startsWith(rule.open, i + len)) {
                    return StrStart(len, idx, 'r' in p || p == "@", 'f' in p || p == "$")
                }
            }
        }
        return null
    }

    /** state: 1..99 — комментарий (индекс+1, глубина в старших битах), 100+ — строка. */
    private fun continueState(line: String, state: Int, out: MutableList<Token>): Pair<Int, Int> {
        val kind = state and 0xFF
        return if (kind in 1..99) {
            val depth = (state shr 8).coerceAtLeast(1)
            scanBlockComment(line, 0, kind - 1, depth, 0, out)
        } else {
            val ruleIdx = kind - 100
            val flags = state shr 8
            scanString(line, 0, 0, ruleIdx, flags and 1 != 0, flags and 2 != 0, out)
        }
    }

    private fun scanBlockComment(
        line: String,
        from: Int,
        ruleIdx: Int,
        depth0: Int,
        tokenStart: Int,
        out: MutableList<Token>
    ): Pair<Int, Int> {
        val rule = blockRules[ruleIdx]
        val tok = if (rule.doc) Tok.DOC_COMMENT else Tok.COMMENT
        var depth = depth0
        var i = from
        while (i < line.length) {
            if (rule.nested && line.startsWith(rule.open, i)) {
                depth++
                i += rule.open.length
                continue
            }
            if (line.startsWith(rule.close, i)) {
                depth--
                i += rule.close.length
                if (depth == 0) {
                    emitCommentWithTags(line, tokenStart, i, tok, out)
                    return i to 0
                }
                continue
            }
            i++
        }
        emitCommentWithTags(line, tokenStart, line.length, tok, out)
        return line.length to ((ruleIdx + 1) or (depth shl 8))
    }

    // @param/@return внутри документации — отдельным цветом
    private fun emitCommentWithTags(line: String, start: Int, end: Int, tok: Tok, out: MutableList<Token>) {
        if (start >= end) return
        if (tok != Tok.DOC_COMMENT) {
            out.add(Token(start, end, tok))
            return
        }
        var pos = start
        var i = start
        while (i < end) {
            if (line[i] == '@' && i + 1 < end && line[i + 1].isLetter() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                var j = i + 1
                while (j < end && line[j].isLetter()) j++
                if (i > pos) out.add(Token(pos, i, tok))
                out.add(Token(i, j, Tok.DOC_TAG))
                pos = j
                i = j
            } else i++
        }
        if (pos < end) out.add(Token(pos, end, tok))
    }

    private fun scanString(
        line: String,
        tokenStart: Int,
        bodyStart: Int,
        ruleIdx: Int,
        raw: Boolean,
        formatted: Boolean,
        out: MutableList<Token>
    ): Pair<Int, Int> {
        val rule = stringRules[ruleIdx]
        val interps = if (rule.interpolation.isEmpty() && formatted) FORMAT_INTERP else rule.interpolation
        var segStart = tokenStart
        var i = bodyStart
        val n = line.length
        fun flush(to: Int) {
            if (to > segStart) out.add(Token(segStart, to, rule.tok))
        }
        while (i < n) {
            val c = line[i]
            if (rule.doubledQuoteEscape && line.startsWith(rule.close, i) && line.startsWith(rule.close, i + rule.close.length)) {
                i += rule.close.length * 2
                continue
            }
            if (line.startsWith(rule.close, i)) {
                i += rule.close.length
                flush(i)
                return i to 0
            }
            // Подстановки раньше экранирования: в Swift подстановка начинается с обратной косой
            val interp = if (interps.isEmpty()) null else interps.firstOrNull { line.startsWith(it, i) }
            if (interp != null && !(interp == "{" && line.startsWith("{{", i))) {
                flush(i)
                val open = i + interp.length - 1
                val end = if (line[open] == '{' || line[open] == '(') matchBracket(line, open) else open + 1
                out.add(Token(i, end, Tok.VARIABLE))
                i = end
                segStart = i
                continue
            }
            if (!raw && rule.escape != null && c == rule.escape && i + 1 < n) {
                flush(i)
                val end = escapeEnd(line, i)
                out.add(Token(i, end, Tok.ESCAPE))
                i = end
                segStart = i
                continue
            }
            if (rule.dollarIdent && c == '$' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
                flush(i)
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                out.add(Token(i, j, Tok.VARIABLE))
                i = j
                segStart = i
                continue
            }
            i++
        }
        flush(n)
        return if (rule.multiline) {
            n to ((100 + ruleIdx) or ((if (raw) 1 else 0) shl 8) or ((if (formatted) 2 else 0) shl 8))
        } else {
            n to 0
        }
    }

    private fun escapeEnd(line: String, i: Int): Int {
        val n = line.length
        if (i + 1 >= n) return n
        return when (line[i + 1]) {
            'u' -> if (i + 2 < n && line[i + 2] == '{') (line.indexOf('}', i).takeIf { it > 0 }?.plus(1) ?: n)
            else minOf(n, i + 6)
            'x' -> {
                var j = i + 2
                while (j < n && j < i + 4 && line[j].isHexDigit()) j++
                j
            }
            'U' -> minOf(n, i + 10)
            in '0'..'7' -> {
                var j = i + 1
                while (j < n && j < i + 4 && line[j] in '0'..'7') j++
                j
            }
            else -> i + 2
        }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun scanNumber(line: String, start: Int): Int {
        val n = line.length
        var i = start
        if (line[i] == '0' && i + 1 < n && line[i + 1] in "xX") {
            i += 2
            while (i < n && (line[i].isHexDigit() || line[i] == '_')) i++
            if (i < n && line[i] == '.') {
                i++
                while (i < n && (line[i].isHexDigit() || line[i] == '_')) i++
            }
            if (i < n && line[i] in "pP") {
                i++
                if (i < n && line[i] in "+-") i++
                while (i < n && line[i].isDigit()) i++
            }
        } else if (line[i] == '0' && i + 1 < n && line[i + 1] in "bBoO") {
            i += 2
            while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        } else {
            while (i < n && (line[i].isDigit() || line[i] == '_')) i++
            // "1..10" — диапазон, не дробь
            if (i < n && line[i] == '.' && !(i + 1 < n && line[i + 1] == '.') && (i + 1 >= n || line[i + 1].isDigit() || !isIdentStart(line[i + 1]))) {
                i++
                while (i < n && (line[i].isDigit() || line[i] == '_')) i++
            }
            if (i < n && line[i] in "eE" && i + 1 < n && (line[i + 1].isDigit() || (line[i + 1] in "+-" && i + 2 < n && line[i + 2].isDigit()))) {
                i += 2
                while (i < n && line[i].isDigit()) i++
            }
        }
        // Суффиксы: 10L, 1.5f, 42u, 0u8/i32 (Rust)
        while (i < n && (line[i] in spec.numberSuffixes || (spec.macroBang && line[i].isLetterOrDigit()))) i++
        return i
    }

    private fun scanVariable(line: String, i: Int): Int {
        val n = line.length
        val j = i + 1
        if (j >= n) return i + 1
        return when {
            line[j] == '{' -> matchBracket(line, j)
            line[j] == '(' && line[i] == '$' -> matchBracket(line, j)
            isIdentStart(line[j]) -> {
                var k = j + 1
                while (k < n && isIdentPart(line[k])) k++
                k
            }
            // $1, $@, $#, $?, $$ в shell
            line[i] == '$' && (line[j].isDigit() || line[j] in "@#?$!*-_") -> j + 1
            else -> i + 1
        }
    }

    private fun scanRegex(line: String, start: Int): Int {
        val n = line.length
        var i = start + 1
        var inClass = false
        if (i < n && (line[i] == '/' || line[i] == '*' || line[i] == ' ')) return start + 1
        while (i < n) {
            val c = line[i]
            when {
                c == '\\' -> i += 2
                c == '[' -> { inClass = true; i++ }
                c == ']' -> { inClass = false; i++ }
                c == '/' && !inClass -> {
                    i++
                    while (i < n && line[i].isLetter()) i++
                    return i
                }
                else -> i++
            }
        }
        return start + 1 // не регулярка — просто деление
    }

    private fun isAtomStart(line: String, i: Int): Boolean {
        if (i + 1 >= line.length || !isIdentStart(line[i + 1])) return false
        if (i == 0) return true
        val prev = line[i - 1]
        if (prev == ':' || isIdentPart(prev) || prev == ')' || prev == ']') return false
        if (prev != ' ') return true
        // "a ? b :c" — редкость; ":sym" после пробела — если перед ним разделитель/оператор
        var k = i - 1
        while (k >= 0 && line[k] == ' ') k--
        return k < 0 || line[k] in ",([{=>|&!" || isIdentPart(line[k])
    }

    private fun nextNonSpace(line: String, from: Int): Char? {
        var i = from
        while (i < line.length && line[i] == ' ') i++
        return line.getOrNull(i)
    }

    companion object {
        private val FORMAT_INTERP = listOf("{")

        /** Конец сбалансированной скобки, начиная с открывающей в [open] (или конец строки). */
        fun matchBracket(line: String, open: Int): Int {
            if (open < 0 || open >= line.length) return line.length
            val o = line[open]
            val c = when (o) {
                '(' -> ')'
                '[' -> ']'
                '{' -> '}'
                else -> return open + 1
            }
            var depth = 0
            var i = open
            while (i < line.length) {
                when (line[i]) {
                    o -> depth++
                    c -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                i++
            }
            return line.length
        }
    }
}
