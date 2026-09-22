package org.wexclient.app.editor.lang

private fun MutableList<Token>.add(start: Int, end: Int, type: Tok) {
    if (end > start) add(Token(start, end, type))
}

private fun isNameChar(c: Char) = c.isLetterOrDigit() || c in "_-.:"

/** XML / HTML / SVG / Android-разметка. */
object XmlLexer : LineLexer {
    private const val TEXT = 0
    private const val IN_TAG = 1
    private const val COMMENT = 2
    private const val CDATA = 3
    private const val VALUE_DQ = 4
    private const val VALUE_SQ = 5
    private const val META = 6

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var st = state
        var i = 0
        val n = line.length
        while (i < n) {
            when (st) {
                COMMENT -> {
                    val end = line.indexOf("-->", i)
                    if (end < 0) {
                        out.add(i, n, Tok.COMMENT); return COMMENT
                    }
                    out.add(i, end + 3, Tok.COMMENT)
                    i = end + 3
                    st = TEXT
                }
                CDATA -> {
                    val end = line.indexOf("]]>", i)
                    if (end < 0) {
                        out.add(i, n, Tok.CODE); return CDATA
                    }
                    out.add(i, end + 3, Tok.CODE)
                    i = end + 3
                    st = TEXT
                }
                META -> {
                    val end = line.indexOf('>', i)
                    if (end < 0) {
                        out.add(i, n, Tok.META); return META
                    }
                    out.add(i, end + 1, Tok.META)
                    i = end + 1
                    st = TEXT
                }
                VALUE_DQ, VALUE_SQ -> {
                    val q = if (st == VALUE_DQ) '"' else '\''
                    val end = line.indexOf(q, i)
                    val stop = if (end < 0) n else end + 1
                    valueTokens(line, i, stop, out)
                    i = stop
                    if (end < 0) return st
                    st = IN_TAG
                }
                IN_TAG -> {
                    val c = line[i]
                    when {
                        c.isWhitespace() -> i++
                        line.startsWith("/>", i) -> {
                            out.add(i, i + 2, Tok.TAG); i += 2; st = TEXT
                        }
                        c == '>' || (c == '?' && line.startsWith("?>", i)) -> {
                            val len = if (c == '?') 2 else 1
                            out.add(i, i + len, Tok.TAG); i += len; st = TEXT
                        }
                        c == '=' -> {
                            out.add(i, i + 1, Tok.OPERATOR); i++
                        }
                        c == '"' || c == '\'' -> {
                            val end = line.indexOf(c, i + 1)
                            val stop = if (end < 0) n else end + 1
                            valueTokens(line, i, stop, out)
                            i = stop
                            if (end < 0) return if (c == '"') VALUE_DQ else VALUE_SQ
                        }
                        isNameChar(c) -> {
                            var j = i
                            while (j < n && isNameChar(line[j])) j++
                            val colon = line.indexOf(':', i).takeIf { it in (i + 1) until j }
                            if (colon != null) {
                                // android:layout_width — префикс пространства имён отдельно
                                out.add(i, colon + 1, Tok.MODIFIER)
                                out.add(colon + 1, j, Tok.ATTRIBUTE)
                            } else {
                                out.add(i, j, Tok.ATTRIBUTE)
                            }
                            i = j
                        }
                        else -> i++
                    }
                }
                else -> { // TEXT
                    val lt = line.indexOf('<', i)
                    val amp = line.indexOf('&', i)
                    val next = listOf(lt, amp).filter { it >= 0 }.minOrNull()
                    if (next == null) return TEXT
                    i = next
                    if (line[i] == '&') {
                        val semi = line.indexOf(';', i)
                        if (semi in (i + 2)..(i + 12)) {
                            out.add(i, semi + 1, Tok.ENTITY); i = semi + 1
                        } else i++
                        continue
                    }
                    when {
                        line.startsWith("<!--", i) -> st = COMMENT.also { out.add(i, i + 4, Tok.COMMENT); i += 4 }
                        line.startsWith("<![CDATA[", i) -> st = CDATA.also { out.add(i, i + 9, Tok.META); i += 9 }
                        line.startsWith("<?", i) -> {
                            var j = i + 2
                            while (j < n && isNameChar(line[j])) j++
                            out.add(i, j, Tok.META)
                            i = j
                            st = IN_TAG
                        }
                        line.startsWith("<!", i) -> st = META
                        else -> {
                            var j = i + 1
                            if (j < n && line[j] == '/') j++
                            val nameStart = j
                            while (j < n && isNameChar(line[j])) j++
                            if (j == nameStart) {
                                i++ // одиночный '<' в тексте
                            } else {
                                out.add(i, j, Tok.TAG)
                                i = j
                                st = IN_TAG
                            }
                        }
                    }
                }
            }
        }
        return st
    }

    // Значение атрибута: ссылки на ресурсы Android (@string/x, ?attr/y) и сущности
    private fun valueTokens(line: String, start: Int, end: Int, out: MutableList<Token>) {
        val bodyStart = start + if (line[start] == '"' || line[start] == '\'') 1 else 0
        val ref = bodyStart < end && (line[bodyStart] == '@' || line[bodyStart] == '?') &&
            bodyStart + 1 < end && (line[bodyStart + 1].isLetter() || line[bodyStart + 1] in "+*")
        if (ref) {
            var j = bodyStart
            while (j < end && line[j] != '"' && line[j] != '\'') j++
            out.add(start, bodyStart, Tok.STRING)
            out.add(bodyStart, j, Tok.LINK)
            out.add(j, end, Tok.STRING)
            return
        }
        var pos = start
        var i = start
        while (i < end) {
            if (line[i] == '&') {
                val semi = line.indexOf(';', i)
                if (semi in (i + 2) until end) {
                    out.add(pos, i, Tok.STRING)
                    out.add(i, semi + 1, Tok.ENTITY)
                    i = semi + 1
                    pos = i
                    continue
                }
            }
            i++
        }
        out.add(pos, end, Tok.STRING)
    }
}

/** Markdown: заголовки, списки, цитаты, код, выделение, ссылки. */
object MarkdownLexer : LineLexer {
    private const val NORMAL = 0
    private const val FENCE_BACKTICK = 1
    private const val FENCE_TILDE = 2

    private val heading = Regex("^\\s{0,3}#{1,6}(\\s|$)")
    private val listMarker = Regex("^(\\s*)([-*+]|\\d+[.)])(\\s+)(\\[[ xX]]\\s)?")
    private val hr = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val trimmed = line.trimStart()
        if (state != NORMAL) {
            val closer = if (state == FENCE_BACKTICK) "```" else "~~~"
            out.add(0, line.length, Tok.CODE)
            return if (trimmed.startsWith(closer)) NORMAL else state
        }
        when {
            trimmed.startsWith("```") -> {
                out.add(0, line.length, Tok.CODE); return FENCE_BACKTICK
            }
            trimmed.startsWith("~~~") -> {
                out.add(0, line.length, Tok.CODE); return FENCE_TILDE
            }
            heading.containsMatchIn(line) -> {
                out.add(0, line.length, Tok.HEADING); return NORMAL
            }
            hr.matches(line) -> {
                out.add(0, line.length, Tok.META); return NORMAL
            }
            trimmed.startsWith(">") -> {
                out.add(0, line.length, Tok.QUOTE); return NORMAL
            }
            line.startsWith("    ") || line.startsWith("\t") -> {
                if (trimmed.isNotEmpty() && !listMarker.containsMatchIn(line)) {
                    out.add(0, line.length, Tok.CODE); return NORMAL
                }
            }
        }
        var i = 0
        listMarker.find(line)?.let { m ->
            val g = m.groups[2]!!
            out.add(g.range.first, g.range.last + 1, Tok.LIST)
            m.groups[4]?.let { out.add(it.range.first, it.range.last + 1, Tok.KEYWORD) }
            i = m.range.last + 1
        }
        inline(line, i, out)
        return NORMAL
    }

    private fun inline(line: String, from: Int, out: MutableList<Token>) {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < n -> {
                    out.add(i, i + 2, Tok.ESCAPE); i += 2
                }
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < n && line[i + ticks] == '`') ticks++
                    val close = line.indexOf("`".repeat(ticks), i + ticks)
                    val end = if (close < 0) n else close + ticks
                    out.add(i, end, Tok.CODE)
                    i = end
                }
                (c == '*' || c == '_') && i + 1 < n && line[i + 1] == c -> {
                    val close = line.indexOf("$c$c", i + 2)
                    if (close > i + 2) {
                        out.add(i, close + 2, Tok.STRONG); i = close + 2
                    } else i += 2
                }
                (c == '*' || c == '_') && i + 1 < n && !line[i + 1].isWhitespace() &&
                    (c == '*' || i == 0 || !line[i - 1].isLetterOrDigit()) -> {
                    val close = line.indexOf(c, i + 1)
                    if (close > i + 1 && !line[close - 1].isWhitespace()) {
                        out.add(i, close + 1, Tok.EMPHASIS); i = close + 1
                    } else i++
                }
                c == '~' && line.startsWith("~~", i) -> {
                    val close = line.indexOf("~~", i + 2)
                    if (close > 0) {
                        out.add(i, close + 2, Tok.COMMENT); i = close + 2
                    } else i += 2
                }
                c == '[' || (c == '!' && i + 1 < n && line[i + 1] == '[') -> {
                    val open = if (c == '!') i + 1 else i
                    val close = line.indexOf(']', open)
                    if (close > 0 && close + 1 < n && line[close + 1] == '(') {
                        val paren = line.indexOf(')', close)
                        val end = if (paren < 0) n else paren + 1
                        out.add(i, close + 1, Tok.STRONG)
                        out.add(close + 1, end, Tok.LINK)
                        i = end
                    } else i++
                }
                c == '<' && (line.startsWith("<http", i) || line.startsWith("<mailto", i)) -> {
                    val close = line.indexOf('>', i)
                    val end = if (close < 0) n else close + 1
                    out.add(i, end, Tok.LINK)
                    i = end
                }
                (line.startsWith("http://", i) || line.startsWith("https://", i)) && (i == 0 || line[i - 1].isWhitespace() || line[i - 1] == '(') -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] != ')') j++
                    out.add(i, j, Tok.LINK)
                    i = j
                }
                c == '|' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> i++
            }
        }
    }
}

/** JSON / JSONC / JSON5: ключи отдельно от значений, комментарии. */
object JsonLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        val n = line.length
        if (state == 1) {
            val end = line.indexOf("*/")
            if (end < 0) {
                out.add(0, n, Tok.COMMENT); return 1
            }
            out.add(0, end + 2, Tok.COMMENT)
            i = end + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                line.startsWith("//", i) -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                line.startsWith("/*", i) -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) {
                        out.add(i, n, Tok.COMMENT); return 1
                    }
                    out.add(i, end + 2, Tok.COMMENT)
                    i = end + 2
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    val parts = ArrayList<Token>()
                    var seg = i
                    while (j < n && line[j] != c) {
                        if (line[j] == '\\' && j + 1 < n) {
                            parts.add(Token(seg, j, Tok.STRING))
                            val escEnd = if (line[j + 1] == 'u') minOf(n, j + 6) else j + 2
                            parts.add(Token(j, escEnd, Tok.ESCAPE))
                            j = escEnd
                            seg = j
                        } else j++
                    }
                    val end = if (j < n) j + 1 else n
                    parts.add(Token(seg, end, Tok.STRING))
                    // Ключ — если дальше двоеточие
                    var k = end
                    while (k < n && line[k].isWhitespace()) k++
                    if (k < n && line[k] == ':') {
                        out.add(i, end, Tok.PROPERTY)
                    } else {
                        parts.filter { it.end > it.start }.forEach { out.add(it) }
                    }
                    i = end
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in ".+-")) j++
                    out.add(i, j, Tok.NUMBER)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "_$")) j++
                    val w = line.substring(i, j)
                    var k = j
                    while (k < n && line[k].isWhitespace()) k++
                    val t = when {
                        w == "true" || w == "false" || w == "null" || w == "NaN" || w == "Infinity" -> Tok.CONSTANT
                        k < n && line[k] == ':' -> Tok.PROPERTY // JSON5: ключи без кавычек
                        else -> Tok.INVALID
                    }
                    out.add(i, j, t)
                    i = j
                }
                c in "{}[]" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++
                }
                c == ':' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> i++
            }
        }
        return 0
    }
}

/** YAML: ключи, списки, якоря, теги, скаляры, блочные строки. */
object YamlLexer : LineLexer {
    private val keyRe = Regex("^(\\s*)(- +)?((?:\"[^\"]*\"|'[^']*'|[^\\s#'\"{\\[][^#]*?))\\s*:(?=\\s|$)")
    private val boolRe = Regex("^(true|false|yes|no|on|off|null|~|True|False|TRUE|FALSE|Null|NULL)$")
    private val numRe = Regex("^[-+]?(\\d[\\d_]*(\\.\\d*)?([eE][-+]?\\d+)?|0x[0-9a-fA-F]+|0o[0-7]+|\\.inf|\\.nan)$")

    // state: 0 — обычный; 1000+отступ — внутри блочного скаляра (| или >)
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) n else it }
        if (state >= 1000) {
            val blockIndent = state - 1000
            if (indent >= n || indent > blockIndent) {
                out.add(0, n, Tok.STRING)
                return state
            }
        }
        val trimmed = line.trim()
        if (trimmed == "---" || trimmed == "...") {
            out.add(0, n, Tok.META); return 0
        }
        if (trimmed.startsWith("#")) {
            out.add(indent, n, Tok.COMMENT); return 0
        }
        if (trimmed.startsWith("%")) {
            out.add(indent, n, Tok.DIRECTIVE); return 0
        }
        var i = 0
        keyRe.find(line)?.let { m ->
            m.groups[2]?.let { out.add(it.range.first, it.range.first + 1, Tok.LIST) }
            val k = m.groups[3]!!
            out.add(k.range.first, k.range.last + 1, Tok.PROPERTY)
            val colon = line.indexOf(':', k.range.last + 1)
            out.add(colon, colon + 1, Tok.OPERATOR)
            i = colon + 1
        } ?: run {
            val dash = DASH_RE.find(line)
            if (dash != null) {
                out.add(dash.groups[2]!!.range.first, dash.groups[2]!!.range.first + 1, Tok.LIST)
                i = dash.range.last + 1
            }
        }
        return value(line, i, indent, out)
    }

    private fun value(line: String, from: Int, indent: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = from
        while (i < n && line[i].isWhitespace()) i++
        if (i >= n) return 0
        val c = line[i]
        // Блочный скаляр: следующие строки с большим отступом — текст
        if ((c == '|' || c == '>') && line.substring(i).trim().let { it.length <= 3 || it.substring(1).trimStart().startsWith("#") }) {
            out.add(i, i + 1, Tok.OPERATOR)
            return 1000 + indent
        }
        while (i < n) {
            val ch = line[i]
            when {
                ch.isWhitespace() -> i++
                ch == '#' && (i == 0 || line[i - 1].isWhitespace()) -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                ch == '"' || ch == '\'' -> {
                    val end = line.indexOf(ch, i + 1).let { if (it < 0) n else it + 1 }
                    out.add(i, end, Tok.STRING)
                    i = end
                }
                ch == '&' || ch == '*' -> {
                    var j = i + 1
                    while (j < n && !line[j].isWhitespace() && line[j] !in ",]}") j++
                    out.add(i, j, Tok.LABEL)
                    i = j
                }
                ch == '!' -> {
                    var j = i + 1
                    while (j < n && !line[j].isWhitespace()) j++
                    out.add(i, j, Tok.ANNOTATION)
                    i = j
                }
                ch in "[]{},:" -> {
                    out.add(i, i + 1, if (ch in "[]{}") Tok.BRACKET else Tok.OPERATOR)
                    i++
                }
                else -> {
                    var j = i
                    while (j < n && line[j] !in ",]}" && !(line[j] == '#' && line[j - 1].isWhitespace()) &&
                        !(line[j] == ':' && (j + 1 >= n || line[j + 1] == ' '))
                    ) j++
                    val word = line.substring(i, j).trimEnd()
                    val end = i + word.length
                    out.add(i, end, when {
                        boolRe.matches(word) -> Tok.CONSTANT
                        numRe.matches(word) -> Tok.NUMBER
                        else -> Tok.STRING
                    })
                    i = maxOf(j, i + 1)
                }
            }
        }
        return 0
    }
}

/** INI / .conf / .properties / .env / TOML / systemd / .desktop. */
class IniLexer(private val toml: Boolean = false, private val properties: Boolean = false) : LineLexer {
    private val boolWords = setOf("true", "false", "yes", "no", "on", "off", "enabled", "disabled", "null", "none")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        // Многострочные строки TOML ("""…""", '''…''') и продолжение строк .properties (\)
        if (state == 1 || state == 2) {
            val q = if (state == 1) "\"\"\"" else "'''"
            val end = line.indexOf(q)
            if (end < 0) {
                out.add(0, n, Tok.STRING); return state
            }
            out.add(0, end + 3, Tok.STRING)
            i = end + 3
            return values(line, i, out)
        }
        if (state == 3) {
            out.add(0, n, Tok.STRING)
            return if (line.endsWith("\\")) 3 else 0
        }
        while (i < n && line[i].isWhitespace()) i++
        if (i >= n) return 0
        val c = line[i]
        if (c == '#' || c == ';' || (properties && c == '!')) {
            out.add(i, n, Tok.COMMENT); return 0
        }
        if (c == '[') {
            val close = line.lastIndexOf(']')
            val end = if (close < i) n else close + 1
            out.add(i, end, Tok.SECTION)
            // Комментарий после секции
            val hash = line.indexOf('#', end)
            if (hash >= 0) out.add(hash, n, Tok.COMMENT)
            return 0
        }
        // Ключ до '=' или ':' (или пробела в .properties)
        var j = i
        if (c == '"' || c == '\'') {
            j = line.indexOf(c, i + 1).let { if (it < 0) n else it + 1 }
        } else {
            while (j < n && line[j] != '=' && line[j] != ':' && !(properties && line[j].isWhitespace()) &&
                !(toml && line[j].isWhitespace())
            ) j++
        }
        var k = j
        while (k < n && line[k].isWhitespace()) k++
        if (k < n && (line[k] == '=' || line[k] == ':')) {
            // "export KEY=value" в .env
            if (line.startsWith("export ", i)) {
                out.add(i, i + 6, Tok.KEYWORD)
                out.add(i + 7, j, Tok.PROPERTY)
            } else {
                out.add(i, j, Tok.PROPERTY)
            }
            out.add(k, k + 1, Tok.OPERATOR)
            return values(line, k + 1, out)
        }
        if (properties && j > i) {
            out.add(i, j, Tok.PROPERTY)
            return values(line, j, out)
        }
        // Строка без '=' (директива или просто значение)
        out.add(i, j, Tok.KEYWORD)
        return values(line, j, out)
    }

    private fun values(line: String, from: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                (c == '#' || c == ';') && (i == 0 || line[i - 1].isWhitespace()) && !properties -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                toml && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) -> {
                    val q = line.substring(i, i + 3)
                    val end = line.indexOf(q, i + 3)
                    if (end < 0) {
                        out.add(i, n, Tok.STRING); return if (q == "\"\"\"") 1 else 2
                    }
                    out.add(i, end + 3, Tok.STRING)
                    i = end + 3
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val end = line.indexOf(c, i + 1).let { if (it < 0) n else it + 1 }
                    out.add(i, end, Tok.STRING)
                    i = end
                }
                c == '$' && i + 1 < n && (line[i + 1] == '{' || line[i + 1] == '(' || line[i + 1].isLetter()) -> {
                    val end = if (line[i + 1] == '{' || line[i + 1] == '(') CLikeLexer.matchBracket(line, i + 1) else {
                        var j = i + 1
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                        j
                    }
                    out.add(i, end, Tok.VARIABLE)
                    i = end
                }
                c in "[]{}" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++
                }
                c == '\\' && i == n - 1 && properties -> {
                    out.add(i, n, Tok.ESCAPE); return 3
                }
                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in ",[]{}\"'" &&
                        !(line[j] == '#' && j > 0 && line[j - 1].isWhitespace())
                    ) j++
                    if (j == i) j++
                    val w = line.substring(i, j)
                    val t = when {
                        w.lowercase() in boolWords -> Tok.CONSTANT
                        w.matches(NUMBER_RE) -> Tok.NUMBER
                        w.matches(COLOR_RE) -> Tok.CONSTANT
                        else -> if (properties) Tok.STRING else Tok.TEXT
                    }
                    if (t != Tok.TEXT) out.add(i, j, t)
                    i = j
                }
            }
        }
        return 0
    }
}

/** CSS / SCSS / Less: селекторы, свойства, значения, @-правила. */
class CssLexer(private val scss: Boolean) : LineLexer {
    // state = depth*2 + (в комментарии ? 1 : 0)
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var depth = state shr 1
        var inComment = state and 1 == 1
        val n = line.length
        var i = 0
        var inValue = false
        while (i < n) {
            if (inComment) {
                val end = line.indexOf("*/", i)
                if (end < 0) {
                    out.add(i, n, Tok.COMMENT); return (depth shl 1) or 1
                }
                out.add(i, end + 2, Tok.COMMENT)
                i = end + 2
                inComment = false
                continue
            }
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("/*", i) -> {
                    inComment = true
                    out.add(i, i + 2, Tok.COMMENT)
                    i += 2
                }
                scss && line.startsWith("//", i) && (i == 0 || line[i - 1] != ':') -> {
                    out.add(i, n, Tok.COMMENT); return depth shl 1
                }
                c == '{' -> {
                    depth++; inValue = false; out.add(i, i + 1, Tok.BRACKET); i++
                }
                c == '}' -> {
                    depth = maxOf(0, depth - 1); inValue = false; out.add(i, i + 1, Tok.BRACKET); i++
                }
                c == ';' -> {
                    inValue = false; i++
                }
                c == '"' || c == '\'' -> {
                    val end = line.indexOf(c, i + 1).let { if (it < 0) n else it + 1 }
                    out.add(i, end, Tok.STRING)
                    i = end
                }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    out.add(i, j, if (scss && depth > 0 && !line.startsWith("@include", i) && !line.startsWith("@media", i)) Tok.VARIABLE else Tok.DIRECTIVE)
                    i = j
                }
                (c == '$' && scss) || (c == '-' && line.startsWith("--", i) && !inValue) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    out.add(i, j, Tok.VARIABLE)
                    i = j
                    // Объявление переменной --x: value
                    var k = j
                    while (k < n && line[k].isWhitespace()) k++
                    if (k < n && line[k] == ':') {
                        out.add(k, k + 1, Tok.OPERATOR); i = k + 1; inValue = true
                    }
                }
                inValue || (depth > 0 && isDeclaration(line, i)) -> {
                    if (!inValue) {
                        // Имя свойства
                        var j = i
                        while (j < n && line[j] != ':') j++
                        out.add(i, j, Tok.PROPERTY)
                        out.add(j, j + 1, Tok.OPERATOR)
                        i = j + 1
                        inValue = true
                    } else {
                        i = valueToken(line, i, out)
                    }
                }
                else -> i = selectorToken(line, i, out)
            }
        }
        return (depth shl 1) or (if (inComment) 1 else 0)
    }

    // "prop: value" внутри блока, а не "a:hover {"
    private fun isDeclaration(line: String, i: Int): Boolean {
        val colon = line.indexOf(':', i)
        if (colon < 0) return false
        val name = line.substring(i, colon)
        if (!name.all { it.isLetterOrDigit() || it in "-_" }) return false
        val brace = line.indexOf('{', colon)
        val semi = line.indexOf(';', colon)
        return brace < 0 || (semi in 0 until brace)
    }

    private fun valueToken(line: String, i: Int, out: MutableList<Token>): Int {
        val n = line.length
        val c = line[i]
        return when {
            c == '#' -> {
                var j = i + 1
                while (j < n && line[j].isLetterOrDigit()) j++
                out.add(i, j, Tok.CONSTANT); j
            }
            c.isDigit() || (c in "-+." && i + 1 < n && line[i + 1].isDigit()) -> {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] in ".%")) j++
                out.add(i, j, Tok.NUMBER); j
            }
            line.startsWith("!important", i) -> {
                out.add(i, i + 10, Tok.KEYWORD); i + 10
            }
            c.isLetter() || c == '-' -> {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                if (j < n && line[j] == '(') out.add(i, j, Tok.FUNCTION) else out.add(i, j, Tok.CONSTANT)
                j
            }
            c in "(),/" -> {
                if (c in "()") out.add(i, i + 1, Tok.BRACKET)
                i + 1
            }
            else -> i + 1
        }
    }

    private fun selectorToken(line: String, i: Int, out: MutableList<Token>): Int {
        val n = line.length
        val c = line[i]
        var j = i + 1
        return when (c) {
            '.', '#' -> {
                while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                out.add(i, j, if (c == '.') Tok.TYPE else Tok.CONSTANT); j
            }
            ':' -> {
                if (j < n && line[j] == ':') j++
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '-')) j++
                out.add(i, j, Tok.KEYWORD); j
            }
            '[' -> {
                val end = line.indexOf(']', i).let { if (it < 0) n else it + 1 }
                out.add(i, end, Tok.ATTRIBUTE); end
            }
            '&', '>', '+', '~', '*', ',' -> {
                out.add(i, j, Tok.OPERATOR); j
            }
            else -> {
                if (c.isLetter()) {
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    out.add(i, j, Tok.TAG)
                }
                j
            }
        }
    }
}

/** Unified diff / patch. */
object DiffLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val t = when {
            line.startsWith("+++") || line.startsWith("---") -> Tok.META
            line.startsWith("@@") -> Tok.HUNK
            line.startsWith("+") || line.startsWith(">") -> Tok.INSERTED
            line.startsWith("-") || line.startsWith("<") -> Tok.DELETED
            line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("new file") ||
                line.startsWith("deleted file") || line.startsWith("similarity") || line.startsWith("rename ") -> Tok.KEYWORD
            line.startsWith("!") -> Tok.CONSTANT
            else -> null
        }
        if (t != null) out.add(0, line.length, t)
        return 0
    }
}

// Регулярки — константами: раньше они собирались на каждое слово и каждую строку
private val NUMBER_RE = Regex("[-+]?(0x[0-9a-fA-F]+|\\d[\\d_]*(\\.\\d+)?([eE][-+]?\\d+)?)(%|px|ms|s|dp|sp)?")
private val COLOR_RE = Regex("#[0-9a-fA-F]{3,8}|rgba?\\(.*")
private val DASH_RE = Regex("^(\\s*)(- )")
