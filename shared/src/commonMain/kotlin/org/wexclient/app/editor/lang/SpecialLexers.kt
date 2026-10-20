package org.wexclient.app.editor.lang

private fun MutableList<Token>.add(start: Int, end: Int, type: Tok) {
    if (end > start) add(Token(start, end, type))
}

private fun scanQuoted(line: String, i: Int, q: Char, out: MutableList<Token>, tok: Tok = Tok.STRING): Int {
    val n = line.length
    var j = i + 1
    var seg = i
    while (j < n && line[j] != q) {
        if (line[j] == '\\' && j + 1 < n) {
            out.add(seg, j, tok)
            out.add(j, j + 2, Tok.ESCAPE)
            j += 2
            seg = j
        } else j++
    }
    val end = if (j < n) j + 1 else n
    out.add(seg, end, tok)
    return end
}

/** Smali (baksmali): директивы, опкоды, регистры, метки, типы, ссылки на члены. */
object SmaliLexer : LineLexer {
    val opcodes: Set<String> by lazy {
        runCatching { org.jf.dexlib2.Opcode.values().map { it.name }.toSet() }.getOrDefault(emptySet())
    }
    val directives = setOf(
        ".class", ".super", ".implements", ".source", ".field", ".end", ".method", ".annotation",
        ".subannotation", ".enum", ".registers", ".locals", ".array-data", ".packed-switch",
        ".sparse-switch", ".catch", ".catchall", ".line", ".param", ".parameter", ".prologue",
        ".epilogue", ".local", ".restart", ".debug", ".source"
    )
    val accessFlags = setOf(
        "public", "private", "protected", "static", "final", "synchronized", "volatile", "bridge",
        "transient", "varargs", "native", "interface", "abstract", "strict", "strictfp", "synthetic",
        "annotation", "enum", "constructor", "declared-synchronized", "build", "runtime", "system"
    )

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        var firstWord = true
        var afterArrow = false
        var inSignature = false
        var directive: String? = null
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                c == '"' || c == '\'' -> {
                    i = scanQuoted(line, i, c, out)
                    firstWord = false
                }
                c == '.' && firstWord -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '-')) j++
                    directive = line.substring(i, j)
                    out.add(i, j, Tok.DIRECTIVE)
                    i = j
                    firstWord = false
                }
                c == ':' && i + 1 < n && (line[i + 1].isLetterOrDigit() || line[i + 1] == '_') &&
                    (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in "{,") -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    out.add(i, j, Tok.LABEL)
                    i = j
                    firstWord = false
                }
                line.startsWith("->", i) -> {
                    out.add(i, i + 2, Tok.OPERATOR)
                    i += 2
                    afterArrow = true
                }
                c == '(' -> {
                    inSignature = true
                    out.add(i, i + 1, Tok.BRACKET)
                    i++
                }
                c == ')' -> {
                    out.add(i, i + 1, Tok.BRACKET)
                    i++
                    // Тип возврата сразу после ')'
                    i = descriptor(line, i, out)
                    inSignature = false
                }
                c == '[' || (c == 'L' && isClassDescriptor(line, i)) -> {
                    i = descriptor(line, i, out)
                }
                inSignature && c in "VZBSCIJFD" -> {
                    out.add(i, i + 1, Tok.TYPE)
                    i++
                }
                c == '-' && i + 1 < n && line[i + 1].isDigit() || c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                    out.add(i, j, Tok.NUMBER)
                    i = j
                    firstWord = false
                }
                c.isLetter() || c == '_' || c == '<' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_/$<>")) j++
                    val word = line.substring(i, j)
                    val t = when {
                        directive == ".end" && out.lastOrNull()?.type == Tok.DIRECTIVE -> Tok.DIRECTIVE
                        firstWord && word in opcodes -> Tok.OPCODE
                        word.matches(REGISTER) -> Tok.REGISTER
                        afterArrow || (directive == ".method" && j < n && line[j] == '(') -> Tok.FUNCTION
                        directive == ".field" && j < n && line[j] == ':' -> Tok.PROPERTY
                        word in accessFlags && directive != null -> Tok.MODIFIER
                        word == "true" || word == "false" || word == "null" -> Tok.CONSTANT
                        else -> Tok.TEXT
                    }
                    if (t == Tok.FUNCTION && afterArrow) afterArrow = false
                    // field-ссылка: ->name:Type
                    val finalType = if (t == Tok.FUNCTION && j < n && line[j] == ':') Tok.PROPERTY else t
                    out.add(i, j, finalType)
                    i = j
                    firstWord = false
                }
                c == ':' -> {
                    out.add(i, i + 1, Tok.OPERATOR)
                    i++
                    // Тип поля после ':'
                    if (i < n && (line[i] == 'L' || line[i] == '[' || line[i] in "VZBSCIJFD")) i = descriptor(line, i, out)
                }
                c == '=' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> i++
            }
        }
        return 0
    }

    private val REGISTER = Regex("[vp]\\d+")

    private fun isClassDescriptor(line: String, i: Int): Boolean {
        val semi = line.indexOf(';', i)
        if (semi < 0) return false
        return line.substring(i + 1, semi).all { it.isLetterOrDigit() || it in "/_$-" }
    }

    /** Lcom/x/Y; / [I / примитив — один токен типа. */
    private fun descriptor(line: String, start: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = start
        while (i < n && line[i] == '[') i++
        if (i < n && line[i] == 'L') {
            val semi = line.indexOf(';', i)
            if (semi > 0) {
                out.add(start, semi + 1, Tok.TYPE)
                return semi + 1
            }
        } else if (i < n && line[i] in "VZBSCIJFD") {
            out.add(start, i + 1, Tok.TYPE)
            return i + 1
        }
        return i
    }
}

/**
 * Листинг байт-кода JVM (ASM Textifier, как javap -c): инструкции, директивы, метки,
 * дескрипторы, ссылки на поля и методы.
 */
object JvmBytecodeLexer : LineLexer {
    val opcodes: Set<String> by lazy {
        runCatching { org.objectweb.asm.util.Printer.OPCODES.filter { !it.isNullOrEmpty() }.toSet() }.getOrDefault(emptySet())
    }
    private val directives = setOf(
        "LINENUMBER", "LOCALVARIABLE", "MAXSTACK", "MAXLOCALS", "TRYCATCHBLOCK", "FRAME", "INNERCLASS",
        "OUTERCLASS", "NESTHOST", "NESTMEMBER", "PERMITTEDSUBCLASS", "RECORDCOMPONENT", "ATTRIBUTE",
        "REQUIRE", "EXPORT", "OPEN", "USE", "PROVIDE", "MAINCLASS", "PACKAGE"
    )
    private val typeDirectives = setOf("INNERCLASS", "OUTERCLASS", "NESTHOST", "NESTMEMBER", "PERMITTEDSUBCLASS")
    private val modifiers = setOf(
        "public", "private", "protected", "static", "final", "synchronized", "volatile", "bridge", "transient",
        "varargs", "native", "abstract", "strictfp", "strict", "synthetic", "mandated", "deprecated", "open",
        "transitive", "static_phase"
    )
    // После них идут имена типов: class a/B extends a/C implements a/D a/E
    private val typeListKeywords = setOf("class", "interface", "enum", "record", "module", "extends", "implements", "throws")
    private val keywords = typeListKeywords + "default"
    private val constants = setOf(
        "null", "true", "false", "T_BOOLEAN", "T_CHAR", "T_FLOAT", "T_DOUBLE", "T_BYTE", "T_SHORT", "T_INT",
        "T_LONG", "FULL", "SAME", "SAME1", "APPEND", "CHOP"
    )
    // NEW java/lang/String, CHECKCAST Foo — операнд-тип
    private val typeOperand = setOf("NEW", "ANEWARRAY", "CHECKCAST", "INSTANCEOF")
    private val label = Regex("L\\d+")
    private val special = Regex("-?(?:NaN|Infinity)[FD]?")
    private const val PRIMITIVES = "VZBSCIJFD"

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        while (i < n && line[i] == ' ') i++
        val indent = i
        var prev: Tok? = null
        var opcode: String? = null
        var directive: String? = null
        var typeList = false
        var afterDot = false
        while (i < n) {
            val c = line[i]
            val start = i
            if (c == ' ' || c == '\t') {
                i++
                continue
            }
            val desc = if (c == '[' || c == 'L') descriptorEnd(line, i) else -1
            val type: Tok? = when {
                line.startsWith("//", i) -> {
                    out.add(i, n, Tok.COMMENT)
                    return 0
                }
                c == '"' -> {
                    i = scanQuoted(line, i, '"', out)
                    prev = Tok.STRING
                    null
                }
                c == '@' && i + 1 < n && line[i + 1] == 'L' && classDescriptorEnd(line, i + 1) > 0 -> {
                    i = classDescriptorEnd(line, i + 1)
                    Tok.ANNOTATION
                }
                line.startsWith("@interface", i) -> {
                    i += "@interface".length
                    typeList = true
                    Tok.KEYWORD
                }
                line.startsWith("(itf)", i) -> {
                    i += 5
                    Tok.MODIFIER
                }
                // Дескриптор метода: (Ljava/lang/String;I)V; у аннотации в скобках — значения
                c == '(' && prev != Tok.ANNOTATION -> {
                    out.add(i, i + 1, Tok.BRACKET)
                    i++
                    while (i < n) {
                        val e = descriptorEnd(line, i)
                        if (e < 0) break
                        out.add(i, e, Tok.TYPE)
                        i = e
                    }
                    if (i < n && line[i] == ')') {
                        out.add(i, i + 1, Tok.BRACKET)
                        i++
                        val e = descriptorEnd(line, i)
                        if (e > 0) {
                            out.add(i, e, Tok.TYPE)
                            i = e
                        }
                    }
                    prev = Tok.TYPE
                    null
                }
                desc > 0 -> {
                    i = desc
                    Tok.TYPE
                }
                c in "()[]{}" -> {
                    i++
                    Tok.BRACKET
                }
                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    i++
                    while (i < n && (line[i].isLetterOrDigit() || line[i] == '.')) i++
                    Tok.NUMBER
                }
                c == '.' -> {
                    out.add(i, i + 1, Tok.PUNCTUATION)
                    i++
                    afterDot = true
                    continue
                }
                c == '=' || c == ':' -> {
                    i++
                    Tok.OPERATOR
                }
                c == ',' -> {
                    i++
                    Tok.PUNCTUATION
                }
                c.isLetter() || c == '_' || c == '$' || c == '<' || c == '-' -> {
                    i = wordEnd(line, i)
                    val word = line.substring(start, i)
                    var next = i
                    while (next < n && line[next] == ' ') next++
                    val nextChar = if (next < n) line[next] else ' '
                    val owner = i + 1 < n && line[i] == '.' && (line[i + 1].isLetter() || line[i + 1] in "_$<")
                    when {
                        afterDot -> when {
                            nextChar == '(' -> Tok.FUNCTION
                            word == "class" -> Tok.KEYWORD
                            nextChar == ':' -> Tok.PROPERTY
                            owner -> Tok.TYPE
                            else -> Tok.CONSTANT
                        }
                        label.matches(word) -> Tok.LABEL
                        prev == null && word in opcodes -> {
                            opcode = word
                            Tok.OPCODE
                        }
                        word in directives -> {
                            directive = word
                            Tok.DIRECTIVE
                        }
                        owner -> Tok.TYPE
                        i < n && line[i] == '(' -> Tok.FUNCTION
                        word in modifiers -> Tok.MODIFIER
                        word in keywords -> {
                            if (word in typeListKeywords) typeList = true
                            Tok.KEYWORD
                        }
                        word in constants -> Tok.CONSTANT
                        special.matches(word) -> Tok.NUMBER
                        nextChar == '=' -> Tok.PROPERTY
                        opcode in typeOperand -> Tok.TYPE
                        directive == "LOCALVARIABLE" && prev == Tok.DIRECTIVE -> Tok.VARIABLE
                        directive in typeDirectives -> Tok.TYPE
                        // Имя поля после его типа: «private final I count», «RECORDCOMPONENT I x»
                        prev == Tok.TYPE && (indent == 2 || directive == "RECORDCOMPONENT") -> Tok.PROPERTY
                        word.length == 1 && word[0] in PRIMITIVES -> Tok.TYPE
                        typeList || '/' in word -> Tok.TYPE
                        else -> Tok.TEXT
                    }
                }
                else -> {
                    i++
                    null
                }
            }
            if (type != null) {
                out.add(start, i, type)
                prev = type
            }
            afterDot = false
        }
        return 0
    }

    /** Имя с пакетом (java/lang/Object), <init>, lambda$run$0, Kotlin-имена с «-». */
    private fun wordEnd(line: String, start: Int): Int {
        var j = start + 1
        if (line[start] == '<') {
            while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$')) j++
            return if (j < line.length && line[j] == '>') j + 1 else j
        }
        while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_$/-")) j++
        return j
    }

    /** [[I, Lcom/x/Y; — индекс после дескриптора поля, иначе -1. */
    private fun descriptorEnd(line: String, start: Int): Int {
        var i = start
        while (i < line.length && line[i] == '[') i++
        if (i >= line.length) return -1
        return when (line[i]) {
            'L' -> classDescriptorEnd(line, i)
            in PRIMITIVES -> i + 1
            else -> -1
        }
    }

    private fun classDescriptorEnd(line: String, start: Int): Int {
        var j = start + 1
        while (j < line.length) {
            val ch = line[j]
            when {
                ch == ';' -> return if (j > start + 1) j + 1 else -1
                ch.isLetterOrDigit() || ch in "/_$-" -> j++
                else -> return -1
            }
        }
        return -1
    }
}

/** KDL (конфиги niri): узлы, свойства, строки, #true/#false. */
object KdlLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        var depth = state
        if (depth > 0) {
            // Внутри /* */ (с вложенностью)
            while (i < n && depth > 0) {
                when {
                    line.startsWith("/*", i) -> { depth++; i += 2 }
                    line.startsWith("*/", i) -> { depth--; i += 2 }
                    else -> i++
                }
            }
            out.add(0, i, Tok.COMMENT)
            if (depth > 0) return depth
        }
        var expectNode = true
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("//", i) -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                line.startsWith("/-", i) -> {
                    out.add(i, i + 2, Tok.COMMENT); i += 2
                }
                line.startsWith("/*", i) -> {
                    var d = 1
                    var j = i + 2
                    while (j < n && d > 0) {
                        when {
                            line.startsWith("/*", j) -> { d++; j += 2 }
                            line.startsWith("*/", j) -> { d--; j += 2 }
                            else -> j++
                        }
                    }
                    out.add(i, j, Tok.COMMENT)
                    if (d > 0) return d
                    i = j
                }
                c == '"' -> {
                    val start = i
                    i = scanQuoted(line, i, '"', out)
                    if (i < n && line[i] == '=') {
                        out.removeAll { it.start >= start }
                        out.add(start, i, Tok.PROPERTY)
                    } else if (expectNode) {
                        expectNode = false
                    }
                }
                c == 'r' && i + 1 < n && (line[i + 1] == '"' || line[i + 1] == '#') -> {
                    val hashes = line.substring(i + 1).takeWhile { it == '#' }.length
                    val close = "\"" + "#".repeat(hashes)
                    val end = line.indexOf(close, i + 2 + hashes).let { if (it < 0) n else it + close.length }
                    out.add(i, end, Tok.STRING)
                    i = end
                }
                c in "{}" -> {
                    out.add(i, i + 1, Tok.BRACKET)
                    expectNode = true
                    i++
                }
                c == ';' -> {
                    expectNode = true; i++
                }
                c == '=' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                c == '(' -> {
                    val end = line.indexOf(')', i).let { if (it < 0) n else it + 1 }
                    out.add(i, end, Tok.ANNOTATION) // (type)annotation
                    i = end
                }
                c == '#' -> {
                    var j = i + 1
                    while (j < n && line[j].isLetter()) j++
                    out.add(i, j, Tok.CONSTANT)
                    i = j
                }
                c.isDigit() || ((c == '-' || c == '+') && i + 1 < n && line[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "._")) j++
                    out.add(i, j, Tok.NUMBER)
                    i = j
                }
                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in "{};=()\"") j++
                    if (j == i) j++
                    val word = line.substring(i, j)
                    val t = when {
                        j < n && line[j] == '=' -> Tok.PROPERTY
                        word == "true" || word == "false" || word == "null" -> Tok.CONSTANT
                        expectNode -> Tok.TAG
                        else -> Tok.STRING
                    }
                    out.add(i, j, t)
                    if (expectNode && t == Tok.TAG) expectNode = false
                    i = j
                }
            }
        }
        return 0
    }
}

/** Makefile: цели, переменные, директивы, рецепты (как shell). */
class MakefileLexer(private val shell: LineLexer) : LineLexer {
    private val directives = setOf(
        "include", "-include", "sinclude", "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif",
        "define", "endef", "export", "unexport", "override", "private", "vpath", ".PHONY", ".SUFFIXES",
        ".DEFAULT", ".PRECIOUS", ".INTERMEDIATE", ".SECONDARY", ".SILENT", ".ONESHELL"
    )
    private val assign = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*(\\?=|:=|::=|\\+=|!=|=)")
    private val target = Regex("^([^\\s:=#][^:=#]*?)\\s*:(?!=)")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        if (line.startsWith("\t")) {
            val tmp = ArrayList<Token>()
            shell.tokenize(line, 0, tmp)
            out.addAll(tmp)
            makeVars(line, 0, out)
            return 0
        }
        val hash = line.indexOf('#')
        val code = if (hash >= 0) line.substring(0, hash) else line
        val firstWord = code.trim().substringBefore(' ')
        when {
            firstWord in directives -> {
                val s = line.indexOf(firstWord)
                out.add(s, s + firstWord.length, Tok.KEYWORD)
            }
            else -> assign.find(code)?.let { m ->
                val g = m.groups[1]!!
                out.add(g.range.first, g.range.last + 1, Tok.PROPERTY)
                val op = m.groups[2]!!
                out.add(op.range.first, op.range.last + 1, Tok.OPERATOR)
            } ?: target.find(code)?.let { m ->
                val g = m.groups[1]!!
                out.add(g.range.first, g.range.last + 1, Tok.FUNCTION)
            }
        }
        makeVars(code, 0, out)
        if (hash >= 0) out.add(hash, line.length, Tok.COMMENT)
        return 0
    }

    private fun makeVars(line: String, from: Int, out: MutableList<Token>) {
        var i = line.indexOf('$', from)
        while (i >= 0 && i + 1 < line.length) {
            val next = line[i + 1]
            val end = when {
                next == '(' || next == '{' -> CLikeLexer.matchBracket(line, i + 1)
                next in "@<^?*%+|" || next.isLetterOrDigit() -> i + 2
                else -> i + 1
            }
            if (end > i + 1) out.add(i, end, Tok.VARIABLE)
            i = line.indexOf('$', maxOf(end, i + 1))
        }
    }
}

/** Dockerfile: инструкции + остальное как shell. */
class DockerfileLexer(private val shell: LineLexer) : LineLexer {
    private val instructions = setOf(
        "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
        "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL"
    )

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#")) {
            out.add(line.indexOf('#'), line.length, Tok.COMMENT)
            return 0
        }
        val start = line.length - trimmed.length
        val word = trimmed.takeWhile { !it.isWhitespace() }
        val tmp = ArrayList<Token>()
        if (state == 0 && word.uppercase() in instructions) {
            out.add(start, start + word.length, Tok.KEYWORD)
            val rest = line.substring(start + word.length)
            shell.tokenize(rest, 0, tmp)
            tmp.forEach { out.add(it.start + start + word.length, it.end + start + word.length, it.type) }
            // AS в "FROM image AS stage"
            if (word.uppercase() == "FROM") DOCKER_AS_RE.find(line)?.let { m ->
                out.add(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1, Tok.KEYWORD)
            }
        } else {
            shell.tokenize(line, 0, tmp)
            out.addAll(tmp)
        }
        return if (line.trimEnd().endsWith("\\")) 1 else 0
    }
}

/** Логи и logcat: уровни, время, исключения, ссылки. */
object LogLexer : LineLexer {
    private val time = Regex("\\b(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?|\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?)")
    private val level = Regex("\\b(FATAL|CRITICAL|ERROR|ERR|SEVERE|WARNING|WARN|INFO|NOTICE|DEBUG|TRACE|VERBOSE)\\b|(?<=\\s|^)([VDIWEFA])(?=[/ ])")
    private val exception = Regex("\\b([\\w$.]+(Exception|Error|Throwable))\\b")
    private val stack = Regex("^\\s+at\\s+.*$|^\\s*\\.\\.\\. \\d+ more$|^Caused by:.*$")
    private val url = Regex("\\bhttps?://\\S+")
    private val number = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val quoted = Regex("\"[^\"]*\"")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        if (stack.matches(line)) {
            out.add(0, line.length, if (line.trimStart().startsWith("Caused by")) Tok.INVALID else Tok.COMMENT)
            return 0
        }
        val taken = BooleanArray(line.length)
        fun put(r: IntRange, t: Tok) {
            if ((r.first..r.last).any { taken[it] }) return
            for (k in r) taken[k] = true
            out.add(r.first, r.last + 1, t)
        }
        time.findAll(line).forEach { put(it.range, Tok.NUMBER) }
        level.findAll(line).forEach { m ->
            val w = m.value.uppercase()
            val t = when {
                w.startsWith("F") || w.startsWith("C") || w.startsWith("E") || w.startsWith("S") || w == "A" -> Tok.DELETED
                w.startsWith("W") -> Tok.CONSTANT
                w.startsWith("I") || w.startsWith("N") -> Tok.INSERTED
                else -> Tok.COMMENT
            }
            put(m.range, t)
        }
        exception.findAll(line).forEach { put(it.range, Tok.INVALID) }
        url.findAll(line).forEach { put(it.range, Tok.LINK) }
        quoted.findAll(line).forEach { put(it.range, Tok.STRING) }
        number.findAll(line).forEach { put(it.range, Tok.NUMBER) }
        out.sortBy { it.start }
        return 0
    }
}

/** CSV/TSV: колонки разными цветами (учитывая кавычки). */
class CsvLexer(private val delimiter: Char) : LineLexer {
    private val colors = listOf(Tok.COLUMN_0, Tok.COLUMN_1, Tok.COLUMN_2, Tok.COLUMN_3, Tok.COLUMN_4, Tok.COLUMN_5)

    // state: 1 — внутри многострочного поля в кавычках (+ номер колонки в старших битах)
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        var col = state shr 1
        var inQuotes = state and 1 == 1
        var start = 0
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && line[i + 1] == '"') i++ else inQuotes = false
                }
            } else if (c == '"') {
                inQuotes = true
            } else if (c == delimiter) {
                out.add(start, i, colors[col % colors.size])
                out.add(i, i + 1, Tok.OPERATOR)
                col++
                start = i + 1
            }
            i++
        }
        out.add(start, n, colors[col % colors.size])
        return if (inQuotes) (col shl 1) or 1 else 0
    }
}

/** ProGuard / R8 rules. */
object ProguardLexer : LineLexer {
    private val keywords = setOf(
        "class", "interface", "enum", "extends", "implements", "public", "private", "protected",
        "static", "final", "abstract", "native", "synchronized", "volatile", "transient", "strictfp",
        "synthetic", "bridge", "varargs", "includedescriptorclasses", "allowshrinking",
        "allowoptimization", "allowobfuscation", "@interface"
    )
    private val special = setOf("<init>", "<clinit>", "<fields>", "<methods>", "*", "**", "***", "...", "%")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                c == '-' && (i == 0 || line[i - 1].isWhitespace()) && i + 1 < n && line[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit())) j++
                    out.add(i, j, Tok.DIRECTIVE)
                    i = j
                }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "._$*")) j++
                    out.add(i, j, Tok.ANNOTATION)
                    i = j
                }
                c == '"' || c == '\'' -> i = scanQuoted(line, i, c, out)
                c in "{}();" -> {
                    if (c != ';') out.add(i, i + 1, Tok.BRACKET)
                    i++
                }
                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in "{}();,") j++
                    if (j == i) j++
                    val w = line.substring(i, j)
                    val t = when {
                        w in keywords || w.removePrefix("!") in keywords -> Tok.KEYWORD
                        w in special -> Tok.CONSTANT
                        w.contains('.') || w.firstOrNull()?.isUpperCase() == true -> Tok.TYPE
                        else -> Tok.TEXT
                    }
                    out.add(i, j, t)
                    i = j
                }
            }
        }
        return 0
    }
}

/** Ассемблер (x86 GAS/NASM, ARM): метки, директивы, мнемоники, регистры. */
object AsmLexer : LineLexer {
    private val registers = Regex(
        "(?i)%?(r[abcd]x|e[abcd]x|[abcd][xlh]|r[sd]i|e[sd]i|[sd]il?|r[sb]p|e[sb]p|[sb]pl?|r(8|9|1[0-5])[dwb]?|" +
            "[xw]([12]?\\d|3[01])|sp|lr|pc|fp|xzr|wzr|r([0-9]|1[0-5])|[xyz]mm\\d+|[cdefgs]s|rip|eip|cpsr)"
    )

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        var expectMnemonic = true
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c == ';' || (c == '#' && (i == 0 || line[i - 1].isWhitespace()) && (i + 1 >= n || !line[i + 1].isDigit() && line[i + 1] != '-' && line[i + 1] != '0')) ||
                    line.startsWith("//", i) || (c == '@' && (i == 0 || line[i - 1].isWhitespace())) -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                c == '"' || c == '\'' -> i = scanQuoted(line, i, c, out)
                c == '#' && i == line.indexOfFirst { !it.isWhitespace() } && i + 1 < n && line[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && line[j].isLetter()) j++
                    out.add(i, j, Tok.PREPROCESSOR)
                    i = j
                    expectMnemonic = false
                }
                c == '.' && expectMnemonic -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    if (j < n && line[j] == ':') {
                        out.add(i, j + 1, Tok.LABEL)
                        i = j + 1
                    } else {
                        out.add(i, j, Tok.DIRECTIVE)
                        i = j
                        expectMnemonic = false
                    }
                }
                c == '#' || c == '$' || c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "x_")) j++
                    out.add(i, j, Tok.NUMBER)
                    i = j
                }
                c in "[](){}" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++
                }
                c.isLetter() || c in "_%." -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "_.$@")) j++
                    val w = line.substring(i, j)
                    val t = when {
                        j < n && line[j] == ':' -> {
                            j++
                            Tok.LABEL
                        }
                        registers.matches(w) -> Tok.REGISTER
                        expectMnemonic -> {
                            expectMnemonic = false
                            if (w.lowercase() in setOf("section", "global", "extern", "db", "dw", "dd", "dq", "equ", "times", "bits", "org")) Tok.DIRECTIVE else Tok.OPCODE
                        }
                        else -> Tok.TEXT
                    }
                    out.add(i, j, t)
                    i = j
                }
                else -> i++
            }
        }
        return 0
    }
}

/** Windows Batch (.bat/.cmd). */
object BatchLexer : LineLexer {
    private val keywords = setOf(
        "echo", "set", "if", "else", "goto", "call", "for", "in", "do", "exit", "not", "exist",
        "defined", "errorlevel", "setlocal", "endlocal", "shift", "pause", "cd", "pushd", "popd",
        "start", "cls", "title", "copy", "move", "del", "mkdir", "rmdir", "ren", "type", "equ",
        "neq", "lss", "leq", "gtr", "geq", "choice", "timeout", "off", "on", "nul"
    )

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val trimmed = line.trimStart()
        val start = line.length - trimmed.length
        if (trimmed.lowercase().startsWith("rem ") || trimmed.lowercase() == "rem" || trimmed.startsWith("::")) {
            out.add(start, line.length, Tok.COMMENT)
            return 0
        }
        if (trimmed.startsWith(":")) {
            out.add(start, line.length, Tok.LABEL)
            return 0
        }
        val n = line.length
        var i = 0
        while (i < n) {
            val c = line[i]
            when {
                c == '%' || c == '!' -> {
                    val close = line.indexOf(c, i + 1)
                    val end = when {
                        c == '%' && i + 1 < n && line[i + 1] == '%' -> minOf(n, i + 3)
                        c == '%' && i + 1 < n && line[i + 1].isDigit() -> i + 2
                        close > i + 1 -> close + 1
                        else -> i + 1
                    }
                    if (end > i + 1) out.add(i, end, Tok.VARIABLE)
                    i = end
                }
                c == '"' -> i = scanQuoted(line, i, '"', out)
                c == '@' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    if (line.substring(i, j).lowercase() in keywords) out.add(i, j, Tok.KEYWORD)
                    i = j
                }
                c in "|&<>=" -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> i++
            }
        }
        return 0
    }
}

/** LaTeX. */
object LatexLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        var inMath = state == 1
        var mathStart = 0
        while (i < n) {
            val c = line[i]
            when {
                c == '%' && (i == 0 || line[i - 1] != '\\') -> {
                    if (inMath) out.add(mathStart, i, Tok.CODE)
                    out.add(i, n, Tok.COMMENT)
                    return if (inMath) 1 else 0
                }
                c == '$' && (i == 0 || line[i - 1] != '\\') -> {
                    val len = if (line.startsWith("$$", i)) 2 else 1
                    if (inMath) {
                        out.add(mathStart, i + len, Tok.CODE)
                        inMath = false
                    } else {
                        inMath = true
                        mathStart = i
                    }
                    i += len
                }
                inMath -> i++
                c == '\\' && i + 1 < n -> {
                    var j = i + 1
                    if (line[j].isLetter()) {
                        while (j < n && line[j].isLetter()) j++
                    } else j++
                    val cmd = line.substring(i, j)
                    val isEnv = cmd == "\\begin" || cmd == "\\end"
                    out.add(i, j, if (isEnv || cmd in setOf("\\section", "\\subsection", "\\chapter", "\\documentclass", "\\usepackage")) Tok.DIRECTIVE else Tok.KEYWORD)
                    if (isEnv && j < n && line[j] == '{') {
                        val close = line.indexOf('}', j)
                        if (close > j) {
                            out.add(j + 1, close, Tok.TYPE)
                            j = close + 1
                        }
                    }
                    i = j
                }
                c in "{}[]" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++
                }
                c == '&' -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> i++
            }
        }
        if (inMath) out.add(mathStart, n, Tok.CODE)
        return if (inMath) 1 else 0
    }
}

/** Lisp-семейство (Clojure, Scheme, Racket, Emacs Lisp, Fennel). */
object LispLexer : LineLexer {
    private val special = setOf(
        "def", "defn", "defn-", "defmacro", "defun", "defvar", "defparameter", "defconstant", "define",
        "define-syntax", "let", "let*", "letrec", "loop", "recur", "fn", "lambda", "if", "when", "unless",
        "cond", "case", "do", "doseq", "dotimes", "for", "ns", "require", "import", "quote", "set!",
        "begin", "progn", "and", "or", "not", "try", "catch", "finally", "throw", "defstruct", "defclass",
        "defmethod", "defrecord", "defprotocol", "deftype", "cl-defun", "use-package", "setq", "setf"
    )

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var i = 0
        var afterParen = false
        if (state > 0) {
            val end = line.indexOf("|#")
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
                c == ';' -> {
                    out.add(i, n, Tok.COMMENT); return 0
                }
                line.startsWith("#|", i) -> {
                    val end = line.indexOf("|#", i + 2)
                    if (end < 0) {
                        out.add(i, n, Tok.COMMENT); return 1
                    }
                    out.add(i, end + 2, Tok.COMMENT)
                    i = end + 2
                }
                c == '"' -> i = scanQuoted(line, i, '"', out)
                c in "([{" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++; afterParen = true
                }
                c in ")]}" -> {
                    out.add(i, i + 1, Tok.BRACKET); i++; afterParen = false
                }
                c in "'`~@^#" -> {
                    out.add(i, i + 1, Tok.OPERATOR); i++
                }
                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in "()[]{}\";,") j++
                    val w = line.substring(i, j)
                    val t = when {
                        w.startsWith(":") -> Tok.CONSTANT
                        w.first().isDigit() || (w.length > 1 && w[0] in "+-" && w[1].isDigit()) -> Tok.NUMBER
                        w in setOf("nil", "true", "false", "t", "#t", "#f") -> Tok.CONSTANT
                        afterParen && w in special -> Tok.KEYWORD
                        afterParen -> Tok.FUNCTION
                        else -> Tok.TEXT
                    }
                    out.add(i, j, t)
                    afterParen = false
                    i = j
                }
            }
        }
        return 0
    }
}

/** .gitignore / .dockerignore и подобные. */
object IgnoreLexer : LineLexer {
    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val t = line.trimStart()
        when {
            t.startsWith("#") -> out.add(0, line.length, Tok.COMMENT)
            t.startsWith("!") -> {
                val s = line.indexOf('!')
                out.add(s, s + 1, Tok.OPERATOR)
                out.add(s + 1, line.length, Tok.INSERTED)
            }
            t.isNotEmpty() -> {
                line.forEachIndexed { idx, ch ->
                    if (ch == '*' || ch == '?' || ch == '[' || ch == ']') out.add(idx, idx + 1, Tok.OPERATOR)
                }
                if (line.trimEnd().endsWith("/")) out.add(line.trimEnd().length - 1, line.trimEnd().length, Tok.KEYWORD)
            }
        }
        return 0
    }
}

private val DOCKER_AS_RE = Regex("\\s(AS|as)\\s")
