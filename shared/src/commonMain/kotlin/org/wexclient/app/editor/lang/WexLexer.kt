package org.wexclient.app.editor.lang

/** Лексические правила WEX: ASCII-имена, двойные строки и ненестящиеся комментарии. */
internal object WexLexer : LineLexer {
    private const val BLOCK = 1
    private const val STRING = 2
    private fun start(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
    private fun part(c: Char) = start(c) || c in '0'..'9'
    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var i = 0
        var mode = state
        var declaration: Tok? = null
        while (i < line.length) {
            val from = i
            if (mode == BLOCK || mode != STRING && line.startsWith("/*", i)) {
                val end = line.indexOf("*/", if (mode == BLOCK) i else i + 2)
                if (end < 0) {
                    out.add(Token(i, line.length, Tok.COMMENT))
                    return BLOCK
                }
                i = end + 2
                out.add(Token(from, i, Tok.COMMENT))
                mode = 0
                continue
            }
            if (mode == STRING || line[i] == '"') {
                if (mode != STRING) i++
                mode = STRING
                var segment = from
                while (i < line.length) {
                    when (line[i]) {
                        '\\' -> {
                            if (i > segment) out.add(Token(segment, i, Tok.STRING))
                            val end = minOf(i + 2, line.length)
                            out.add(Token(i, end, Tok.ESCAPE))
                            i = end
                            segment = i
                        }
                        '"' -> { i++; mode = 0; break }
                        else -> i++
                    }
                }
                if (i > segment) out.add(Token(segment, i, Tok.STRING))
                if (mode == STRING) return STRING
                continue
            }
            val c = line[i]
            if (c.isWhitespace()) { i++; continue }
            if (line.startsWith("//", i)) {
                out.add(Token(i, line.length, Tok.COMMENT))
                return 0
            }
            if (c in '0'..'9') {
                i++
                var type = Tok.NUMBER
                if (c == '0' && i < line.length && line[i] in "xX") {
                    i++
                    val digits = i
                    while (i < line.length && hex(line[i])) i++
                    if (i == digits) type = Tok.INVALID
                } else {
                    while (i < line.length && line[i] in '0'..'9') i++
                    if (i + 1 < line.length && line[i] == '.' && line[i + 1] in '0'..'9') {
                        i++
                        while (i < line.length && line[i] in '0'..'9') i++
                    }
                }
                out.add(Token(from, i, type))
                continue
            }
            if (start(c)) {
                i++
                while (i < line.length && part(line[i])) i++
                val word = line.substring(from, i)
                var next = i
                while (next < line.length && line[next].isWhitespace()) next++
                val type = when {
                    word in WexSupport.spec.control -> Tok.CONTROL
                    word in WexSupport.spec.keywords -> Tok.KEYWORD
                    word in WexSupport.spec.constants -> Tok.CONSTANT
                    declaration != null -> declaration
                    word in WexSupport.spec.builtins -> Tok.BUILTIN
                    next < line.length && line[next] == '(' -> Tok.FUNCTION
                    else -> Tok.TEXT
                }
                if (type != Tok.TEXT) out.add(Token(from, i, type))
                declaration = when (word) {
                    "fn" -> Tok.FUNCTION
                    "const" -> Tok.CONSTANT
                    "var" -> Tok.VARIABLE
                    "import" -> Tok.BUILTIN
                    else -> null
                }
                continue
            }
            val type = when {
                c in "(){}[]" -> Tok.BRACKET
                c in ";,." -> Tok.PUNCTUATION
                c in "+-*/%=<>!" -> Tok.OPERATOR
                c == '&' && line.startsWith("&&", i) -> Tok.OPERATOR
                c == '|' && line.startsWith("||", i) -> Tok.OPERATOR
                else -> Tok.INVALID
            }
            i++
            if (i < line.length && (c in "=!<>" && line[i] == '=' || c in "&|" && line[i] == c)) i++
            out.add(Token(from, i, type))
            declaration = null
        }
        return mode
    }
}
