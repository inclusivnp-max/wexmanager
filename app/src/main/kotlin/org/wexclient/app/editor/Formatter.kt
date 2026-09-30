package org.wexclient.app.editor

import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Tok
import java.io.File
import java.nio.file.Path

class FormatResult(val text: String?, val error: String?, val tool: String)

/** Форматирование: встроенные JSON/XML/smali/переотступ и внешние gofmt, clang-format, qmlformat… */
object CodeFormatter {
    private val CLANG_LANGS = mapOf(
        "c" to "x.c", "cpp" to "x.cpp", "objc" to "x.m", "java" to "X.java", "javascript" to "x.js",
        "typescript" to "x.ts", "protobuf" to "x.proto", "csharp" to "x.cs", "glsl" to "x.glsl"
    )
    private val REINDENT_LANGS = setOf(
        "kotlin", "java", "groovy", "scala", "c", "cpp", "objc", "csharp", "go", "rust", "swift", "dart",
        "javascript", "typescript", "qml", "php", "css", "scss", "kdl", "glsl", "protobuf", "graphql", "hcl",
        "solidity", "zig", "d", "odin", "awk", "json", "perl", "r", "powershell", "tcl", "nix"
    )

    fun describe(lang: Language): String? = when {
        lang.id == "json" -> "встроенный JSON"
        lang.id == "xml" || lang.id == "html" -> "встроенный XML"
        lang.id == "smali" -> "встроенный smali"
        lang.id == "go" && ToolLocator.find("gofmt") != null -> "gofmt"
        lang.id == "qml" && ToolLocator.find("qmlformat") != null -> "qmlformat"
        lang.id == "fish" && ToolLocator.find("fish_indent") != null -> "fish_indent"
        lang.id == "shell" && ToolLocator.find("shfmt") != null -> "shfmt"
        lang.id == "python" && ToolLocator.find("ruff") != null -> "ruff"
        lang.id == "python" && ToolLocator.find("black") != null -> "black"
        lang.id == "rust" && ToolLocator.find("rustfmt") != null -> "rustfmt"
        lang.id == "lua" && ToolLocator.find("stylua") != null -> "stylua"
        lang.id in CLANG_LANGS && ToolLocator.find("clang-format") != null -> "clang-format"
        lang.id in REINDENT_LANGS -> "выравнивание отступов"
        lang.significantIndent || lang.id == "yaml" -> "очистка пробелов"
        else -> null
    }

    fun format(text: String, snap: HighlightSnapshot, indentUnit: String, tabSize: Int, filePath: Path?): FormatResult {
        val lang = snap.language
        return try {
            when {
                lang.id == "json" -> jsonFormat(text, indentUnit, filePath?.fileName?.toString().orEmpty().lowercase())
                lang.id == "xml" || lang.id == "html" -> FormatResult(XmlFormatter.format(text, indentUnit, lang.id == "html"), null, "XML")
                lang.id == "smali" -> FormatResult(smaliFormat(text, indentUnit), null, "smali")
                lang.id == "go" && ToolLocator.find("gofmt") != null -> external(listOf(ToolLocator.find("gofmt")!!), text, "gofmt")
                lang.id == "qml" && ToolLocator.find("qmlformat") != null -> withTempFile("Format.qml", text) { f ->
                    val indentArgs = if (indentUnit == "\t") listOf("-t") else listOf("-w", indentUnit.length.toString())
                    external(listOf(ToolLocator.find("qmlformat")!!) + indentArgs + f.absolutePath, null, "qmlformat", original = text)
                }
                lang.id == "fish" && ToolLocator.find("fish_indent") != null -> external(listOf(ToolLocator.find("fish_indent")!!), text, "fish_indent")
                lang.id == "shell" && ToolLocator.find("shfmt") != null -> external(
                    listOf(ToolLocator.find("shfmt")!!, "-i", if (indentUnit == "\t") "0" else indentUnit.length.toString()), text, "shfmt"
                )
                lang.id == "python" && ToolLocator.find("ruff") != null -> external(listOf(ToolLocator.find("ruff")!!, "format", "-"), text, "ruff")
                lang.id == "python" && ToolLocator.find("black") != null -> external(listOf(ToolLocator.find("black")!!, "-q", "-"), text, "black")
                lang.id == "rust" && ToolLocator.find("rustfmt") != null -> external(listOf(ToolLocator.find("rustfmt")!!, "--emit", "stdout"), text, "rustfmt")
                lang.id == "lua" && ToolLocator.find("stylua") != null -> external(listOf(ToolLocator.find("stylua")!!, "-"), text, "stylua")
                lang.id in CLANG_LANGS && ToolLocator.find("clang-format") != null -> {
                    val assumed = filePath?.toString() ?: CLANG_LANGS.getValue(lang.id)
                    val width = if (indentUnit == "\t") tabSize else indentUnit.length
                    val style = if (hasClangConfig(filePath)) "file" else
                        "{BasedOnStyle: LLVM, IndentWidth: $width, TabWidth: $tabSize, UseTab: ${if (indentUnit == "\t") "ForIndentation" else "Never"}, ColumnLimit: 120, AllowShortFunctionsOnASingleLine: Empty}"
                    external(listOf(ToolLocator.find("clang-format")!!, "--assume-filename=$assumed", "--style=$style"), text, "clang-format")
                }
                // Без разбора лексером скобок не видно — «выравнивание» сняло бы все отступы
                lang.id in REINDENT_LANGS && !snap.lexed ->
                    FormatResult(null, "Файл слишком большой для разбора — отступы не выровнять", "отступы")
                lang.id in REINDENT_LANGS -> FormatResult(Reindenter.reindent(snap, indentUnit, tabSize), null, "отступы")
                else -> FormatResult(TextOps.trimTrailingWhitespace(text), null, "пробелы")
            }
        } catch (e: Exception) {
            FormatResult(null, e.message ?: e.javaClass.simpleName, "")
        }
    }

    private fun hasClangConfig(path: Path?): Boolean {
        var dir = path?.parent?.toFile() ?: return false
        repeat(12) {
            if (File(dir, ".clang-format").exists() || File(dir, "_clang-format").exists()) return true
            dir = dir.parentFile ?: return false
        }
        return false
    }

    private fun <T> withTempFile(name: String, text: String, block: (File) -> T): T {
        val dir = org.wexclient.app.util.ProcessScratch.work.newDir("fmt-").toFile()
        val f = File(dir, name)
        try {
            f.writeText(text)
            return block(f)
        } finally {
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    /**
     * Результат внешнего форматтера заменяет весь документ, поэтому берём только полный stdout:
     * обрезанный или смешанный с предупреждениями вывод после Ctrl+S испортил бы файл.
     */
    private fun external(cmd: List<String>, input: String?, tool: String, original: String? = input): FormatResult {
        val r = ToolLocator.run(cmd, input, timeoutMs = 15_000, separateErrors = true, maxChars = Int.MAX_VALUE)
            ?: return FormatResult(null, "Не удалось запустить $tool", tool)
        if (r.timedOut) return FormatResult(null, "$tool: превышено время ожидания", tool)
        if (r.exitCode != 0) {
            val why = r.errors.ifBlank { r.output }.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(300)
            return FormatResult(null, "$tool: $why", tool)
        }
        if (!r.complete) return FormatResult(null, "$tool: вывод прочитан не полностью — текст не изменён", tool)
        if (r.output.isBlank() && !original.isNullOrBlank()) return FormatResult(null, "$tool: пустой результат — текст не изменён", tool)
        return FormatResult(r.output, null, tool)
    }

    // ---------- JSON с сохранением комментариев ----------

    private fun jsonFormat(text: String, indent: String, fileName: String): FormatResult {
        // В JSON Lines каждая запись — одна строка: разложенные по строкам записи файл бы сломали
        if (fileName.endsWith(".jsonl") || fileName.endsWith(".ndjson")) {
            return FormatResult(null, "JSON Lines не форматируется: каждая запись должна остаться одной строкой", "JSON")
        }
        // JSON5 строгая проверка не пропустит (ключи без кавычек, одинарные кавычки) — форматируем как есть
        if (!fileName.endsWith(".json5")) {
            val errors = JsonChecker.check(text, lenient = true).filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) return FormatResult(null, "Сначала исправьте ошибку JSON: ${errors.first().message}", "JSON")
        }
        val out = StringBuilder(text.length + text.length / 4)
        var depth = 0
        var i = 0
        val n = text.length
        var lineStart = true
        fun newline() {
            // Без висячих пробелов
            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
            out.append('\n')
            repeat(depth) { out.append(indent) }
            lineStart = true
        }
        fun nextSignificant(from: Int): Char? {
            var k = from
            while (k < n && text[k].isWhitespace()) k++
            return text.getOrNull(k)
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '\uFEFF' -> i++
                c.isWhitespace() -> {
                    // Пустые строки между элементами сохраняем (не больше одной)
                    if (c == '\n') {
                        var k = i + 1
                        var blank = false
                        while (k < n && text[k].isWhitespace()) {
                            if (text[k] == '\n') blank = true
                            k++
                        }
                        if (blank && !lineStart && out.isNotEmpty() && out.last() != '{' && out.last() != '[' && k < n && text[k] != '}' && text[k] != ']') {
                            if (out.endsWith(",")) {
                                out.append('\n')
                            }
                        }
                        i = k
                    } else i++
                }
                // Одинарные кавычки — строка JSON5: её пробелы не должны пропасть
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && text[i] != c) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i = minOf(n, i + 1)
                    out.append(text, start, i)
                    lineStart = false
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    if (!lineStart) out.append(' ')
                    out.append(text, i, end)
                    i = end
                    newline()
                    // Следующая строка может закрывать блок — поправим отступ
                    if (nextSignificant(i) == '}' || nextSignificant(i) == ']') {
                        repeat(indent.length) { if (out.isNotEmpty() && out.last() != '\n') out.setLength(out.length - 1) }
                    }
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    if (!lineStart) out.append(' ')
                    out.append(text, i, end)
                    i = end
                    lineStart = false
                }
                c == '{' || c == '[' -> {
                    val close = if (c == '{') '}' else ']'
                    // Несколько значений подряд — каждое с новой строки, а не «}{»
                    if (depth == 0 && !lineStart && out.isNotEmpty()) newline()
                    out.append(c)
                    i++
                    if (nextSignificant(i) == close) {
                        var k = i
                        while (k < n && text[k].isWhitespace()) k++
                        out.append(close)
                        i = k + 1
                        lineStart = false
                    } else {
                        depth++
                        newline()
                    }
                }
                c == '}' || c == ']' -> {
                    depth = maxOf(0, depth - 1)
                    if (!lineStart) newline() else {
                        // Уже на новой строке — пересчитать отступ
                        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
                        repeat(depth) { out.append(indent) }
                    }
                    out.append(c)
                    lineStart = false
                    i++
                }
                c == ',' -> {
                    out.append(',')
                    i++
                    // Комментарий на той же строке остаётся на ней
                    var k = i
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    if (k + 1 < n && text[k] == '/' && text[k + 1] == '/') {
                        lineStart = false
                        i = k
                    } else newline()
                }
                c == ':' -> {
                    out.append(": ")
                    i++
                    while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
                    lineStart = false
                }
                else -> {
                    val start = i
                    while (i < n && !text[i].isWhitespace() && text[i] !in ",:{}[]\"'/") i++
                    if (i == start) i++
                    out.append(text, start, i)
                    lineStart = false
                }
            }
        }
        val result = out.toString().trimEnd() + "\n"
        return FormatResult(result, null, "JSON")
    }

    // ---------- smali ----------

    private val smaliBlockOpen = Regex("^\\.(method|annotation|subannotation|packed-switch|sparse-switch|array-data|param|parameter|field)\\b")
    private val smaliBlockClose = Regex("^\\.end\\s+(method|annotation|subannotation|packed-switch|sparse-switch|array-data|param|parameter|field)\\b")

    private fun smaliFormat(text: String, indent: String): String {
        val out = StringBuilder(text.length)
        val stack = ArrayList<String>()
        val lines = text.split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (idx > 0) out.append('\n')
            if (line.isEmpty()) continue
            smaliBlockClose.find(line)?.let { m ->
                val name = m.groupValues[1].let { if (it == "parameter") "param" else it }
                val pos = stack.lastIndexOf(name)
                if (pos >= 0) while (stack.size > pos) stack.removeAt(stack.size - 1)
            }
            // .field/.param без .end — блоком не считаем, пока не увидим аннотацию внутри
            val depth = stack.size
            repeat(depth) { out.append(indent) }
            out.append(line)
            smaliBlockOpen.find(line)?.let { m ->
                val name = m.groupValues[1].let { if (it == "parameter") "param" else it }
                if (name == "field" || name == "param") {
                    // Блок только если следующая значимая строка — .annotation
                    var nextIdx = idx + 1
                    while (nextIdx < lines.size && lines[nextIdx].isBlank()) nextIdx++
                    val next = lines.getOrNull(nextIdx)?.trim().orEmpty()
                    if (next.startsWith(".annotation")) stack.add(name)
                } else stack.add(name)
            }
        }
        return out.toString()
    }
}

/** Переотступ по скобкам: меняет только ведущие пробелы, не трогая строки внутри многострочных литералов. */
object Reindenter {
    /** Начала here-doc: Perl `<<"EOF"`, `<<~EOF`, PHP `<<<EOT`. Тело и концовка идут как есть. */
    private val perlHeredoc = Regex("<<(~?)([\"']?)([A-Za-z_]\\w*)\\2")
    private val phpHeredoc = Regex("<<<[ \t]*([\"']?)([A-Za-z_]\\w*)\\1")

    fun reindent(snap: HighlightSnapshot, indent: String, tabSize: Int): String {
        val text = snap.text
        val sb = StringBuilder(text.length + 256)
        var depth = 0
        var prevEndedInside = false
        // Ждём концовок here-doc (по порядку): до них строки не трогаем
        val heredocs = ArrayDeque<String>()
        for (line in 0 until snap.lineCount) {
            val ls = snap.lineStart(line)
            val le = snap.lineEnd(line)
            val raw = text.substring(ls, le)
            if (line > 0) sb.append('\n')
            val t = snap.lineTokens(line)
            val trimmed = raw.trim()
            if (heredocs.isNotEmpty()) {
                sb.append(raw)
                if (isHeredocEnd(trimmed, heredocs.first())) heredocs.removeFirst()
                continue
            }
            val firstNonWs = raw.indexOfFirst { !it.isWhitespace() }
            // Строка тянется дальше незакрытой (многострочный литерал или комментарий): лексер
            // закончил её не в нуле, и последний кусок — строка или комментарий
            val endsInsideHere = snap.endState(line) != 0 && (if (t.isEmpty()) prevEndedInside else endsInside(t, raw.length))
            // Продолжение многострочной строки/комментария — не трогаем (кроме строк " * ").
            // Смотрим на знак в начале строки: внутри литерала и отступ — его текст, а первым
            // непробельным знаком может оказаться подстановка $name
            if (prevEndedInside) {
                val headTok = if (t.isEmpty()) null else tokenAt(t, 0)
                val firstTok = if (t.isEmpty()) null else tokenAt(t, maxOf(0, firstNonWs))
                val comment = headTok.isCommentPart()
                if (t.isEmpty() || comment || headTok.isStringPart()) {
                    when {
                        comment && firstTok.isCommentPart() && trimmed.startsWith("*") -> {
                            repeat(depth) { sb.append(indent) }
                            sb.append(' ').append(trimmed)
                        }
                        // Внутри комментария хвостовые пробелы не нужны, внутри строки — это её текст
                        comment -> sb.append(raw.trimEnd())
                        else -> sb.append(raw)
                    }
                    prevEndedInside = endsInsideHere
                    continue
                }
            }
            if (trimmed.isEmpty()) {
                prevEndedInside = false
                continue
            }
            // Ведущие закрывающие скобки уменьшают отступ этой строки
            var leadingClosers = 0
            var k = firstNonWs
            while (k < raw.length && raw[k] in ")]}" && tokenAt(t, k) == Tok.BRACKET) {
                leadingClosers++
                k++
                while (k < raw.length && raw[k] == ' ') k++
            }
            var lineDepth = maxOf(0, depth - leadingClosers)
            // Продолжение выражения: .call(), ?.x, &&, ||, ?:
            if (leadingClosers == 0 && (trimmed.startsWith(".") && !trimmed.startsWith("..") || trimmed.startsWith("?.") ||
                    trimmed.startsWith("&&") || trimmed.startsWith("||") || trimmed.startsWith("?:") ||
                    (trimmed.startsWith("? ") || trimmed.startsWith(": ")) && snap.language.id != "css")
            ) lineDepth++
            // case/default внутри switch — на уровень глубже тела уже учтено скобками; метки goto/private: не двигаем
            repeat(lineDepth) { sb.append(indent) }
            // Хвостовые пробелы срезаем, только если строка не уходит дальше внутри литерала
            sb.append(raw, firstNonWs, if (endsInsideHere) raw.length else raw.trimEnd().length)
            // Пересчёт глубины по скобкам кода
            var j = 0
            while (j < t.size) {
                if (t[j + 2] == Tok.BRACKET.ordinal) {
                    for (p in t[j] until t[j + 1]) {
                        when (raw[p]) {
                            '(', '[', '{' -> depth++
                            ')', ']', '}' -> depth = maxOf(0, depth - 1)
                        }
                    }
                }
                j += 3
            }
            heredocStarts(snap.language.id, raw, t).forEach { heredocs.addLast(it) }
            prevEndedInside = endsInsideHere
        }
        return sb.toString()
    }

    /**
     * Here-doc, начатые в строке кода. Лексеры Perl и PHP их не знают, и без этого тело и концовка
     * получали отступ — а концовка Perl `EOF` должна стоять с начала строки, иначе скрипт не
     * разбирается. Если начало найдено зря, остаток файла просто не выравнивается.
     */
    private fun heredocStarts(lang: String, raw: String, t: IntArray): List<String> {
        val re = when (lang) {
            "perl" -> perlHeredoc
            "php" -> phpHeredoc
            else -> return emptyList()
        }
        if (!raw.contains("<<")) return emptyList()
        return re.findAll(raw).filter { m ->
            val tok = tokenAt(t, m.range.first)
            tok != Tok.STRING && tok != Tok.COMMENT && tok != Tok.DOC_COMMENT
        }.map { m -> if (lang == "php") m.groupValues[2] else m.groupValues[3] }.toList()
    }

    /** Концовка here-doc: сам разделитель (в PHP 7.3+ — с отступом и знаком после). */
    private fun isHeredocEnd(trimmed: String, name: String): Boolean =
        trimmed == name || (trimmed.startsWith(name) && trimmed.length > name.length &&
            !trimmed[name.length].isLetterOrDigit() && trimmed[name.length] != '_')

    private fun tokenAt(t: IntArray, rel: Int): Tok? {
        var k = 0
        while (k < t.size) {
            if (t[k] <= rel && rel < t[k + 1]) return HighlightSnapshot.TOKS[t[k + 2]]
            k += 3
        }
        return null
    }

    private fun endsInside(t: IntArray, len: Int): Boolean {
        if (t.isEmpty()) return false
        val type = HighlightSnapshot.TOKS[t[t.size - 1]]
        return t[t.size - 2] == len && (type.isStringPart() || type.isCommentPart())
    }

    private fun Tok?.isStringPart() = this == Tok.STRING || this == Tok.ESCAPE || this == Tok.VARIABLE

    private fun Tok?.isCommentPart() = this == Tok.COMMENT || this == Tok.DOC_COMMENT || this == Tok.DOC_TAG
}

/** Форматирование XML: теги по строкам, атрибуты Android-стилем, текст внутри коротких элементов — в строку. */
object XmlFormatter {
    private sealed class Node {
        class Tag(val raw: String, val name: String, val closing: Boolean, val selfClosing: Boolean) : Node()
        class Text(val raw: String) : Node()
        class Other(val raw: String) : Node() // комментарии, CDATA, <?..?>, <!DOCTYPE>
    }

    private val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val rawTextTags = setOf("script", "style", "pre", "textarea")

    private fun tokenize(s: String): List<Node> {
        val out = ArrayList<Node>()
        var i = 0
        val n = s.length
        // «<», за которым не имя, не «/», «!» или «?», — просто знак в тексте: «1 < 2», «<%= x %>»
        fun startsTag(at: Int): Boolean {
            val c = s.getOrNull(at + 1) ?: return false
            return c.isLetter() || c == '_' || c == ':' || c == '!' || c == '?' ||
                (c == '/' && s.getOrNull(at + 2)?.let { it.isLetter() || it == '_' || it == ':' } == true)
        }
        while (i < n) {
            if (s[i] != '<' || !startsTag(i)) {
                var next = s.indexOf('<', i + 1)
                while (next >= 0 && !startsTag(next)) next = s.indexOf('<', next + 1)
                if (next < 0) next = n
                out.add(Node.Text(s.substring(i, next)))
                i = next
                continue
            }
            val end = when {
                s.startsWith("<!--", i) -> s.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                s.startsWith("<![CDATA[", i) -> s.indexOf("]]>", i).let { if (it < 0) n else it + 3 }
                s.startsWith("<?", i) -> s.indexOf("?>", i).let { if (it < 0) n else it + 2 }
                s.startsWith("<!", i) -> s.indexOf('>', i).let { if (it < 0) n else it + 1 }
                else -> {
                    var k = i + 1
                    var q: Char? = null
                    while (k < n) {
                        val c = s[k]
                        if (q != null) {
                            if (c == q) q = null
                        } else if (c == '"' || c == '\'') q = c
                        else if (c == '>') break
                        k++
                    }
                    minOf(n, k + 1)
                }
            }
            val raw = s.substring(i, end)
            if (raw.startsWith("<!") || raw.startsWith("<?")) out.add(Node.Other(raw))
            else {
                val closing = raw.startsWith("</")
                val name = Regex("^</?([\\w.:-]+)").find(raw)?.groupValues?.get(1) ?: ""
                out.add(Node.Tag(raw, name, closing, raw.endsWith("/>")))
            }
            i = end
            // Содержимое <script>/<style> — как есть
            val last = out.last()
            if (last is Node.Tag && !last.closing && !last.selfClosing && last.name.lowercase() in rawTextTags) {
                val close = s.indexOf("</${last.name}", i, ignoreCase = true).let { if (it < 0) n else it }
                if (close > i) out.add(Node.Other(s.substring(i, close)))
                i = close
            }
        }
        return out
    }

    private val attrRe = Regex("([^\\s=/>]+)(\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?")

    private fun formatTag(tag: Node.Tag, indent: String, unit: String): String {
        if (tag.closing) return "</${tag.name}>"
        val inner = tag.raw.removePrefix("<").removeSuffix(">").removeSuffix("/").trim()
        val attrsPart = inner.substring(tag.name.length).trim()
        val attrs = attrRe.findAll(attrsPart).map { m ->
            val v = m.groups[3]?.value
            if (v != null) "${m.groupValues[1]}=${v}" else m.groupValues[1]
        }.toList()
        val end = if (tag.selfClosing) " />" else ">"
        if (attrs.isEmpty()) return "<${tag.name}$end".replace(" />", if (tag.selfClosing) " />" else "")
        val oneLine = "<${tag.name} ${attrs.joinToString(" ")}$end"
        val wasMultiline = tag.raw.contains('\n')
        if (attrs.size < 2 || (!wasMultiline && oneLine.length + indent.length <= 120)) return oneLine
        // Android Studio: первый атрибут на строке тега, остальные — с отступом
        val sb = StringBuilder("<${tag.name} ${attrs[0]}")
        for (a in attrs.drop(1)) sb.append('\n').append(indent).append(unit).append(a)
        sb.append(end)
        return sb.toString()
    }

    fun format(text: String, unit: String, html: Boolean): String {
        val nodes = tokenize(text)
        val sb = StringBuilder(text.length + text.length / 5)
        var depth = 0
        var i = 0
        fun ind() = unit.repeat(depth)
        fun line(s: String) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(ind()).append(s)
        }
        while (i < nodes.size) {
            when (val node = nodes[i]) {
                is Node.Text -> {
                    val t = node.raw.trim()
                    if (t.isNotEmpty()) {
                        for (part in t.split('\n')) if (part.isNotBlank()) line(part.trim())
                    } else if (node.raw.count { it == '\n' } >= 2 && sb.isNotEmpty() && i + 1 < nodes.size) {
                        // Сохраняем одну пустую строку-разделитель
                        val nextIsClosing = (nodes[i + 1] as? Node.Tag)?.closing == true
                        if (!nextIsClosing) sb.append('\n')
                    }
                }
                is Node.Other -> {
                    val raw = node.raw
                    if (raw.startsWith("<!--") || raw.startsWith("<?") || raw.startsWith("<!")) {
                        val lines = raw.split('\n')
                        line(lines[0].trimEnd())
                        for (l in lines.drop(1)) sb.append('\n').append(l.trimEnd())
                    } else {
                        // Сырой текст script/style — без изменений
                        sb.append(raw.trimEnd().let { if (it.isBlank()) "" else it })
                    }
                }
                is Node.Tag -> {
                    if (node.closing) {
                        depth = maxOf(0, depth - 1)
                        line(formatTag(node, ind(), unit))
                    } else {
                        val formatted = formatTag(node, ind(), unit)
                        val isVoid = html && node.name.lowercase() in voids
                        // <tag>короткий текст</tag> — в одну строку
                        val text = nodes.getOrNull(i + 1) as? Node.Text
                        val close = nodes.getOrNull(i + 2) as? Node.Tag
                        if (!node.selfClosing && !isVoid && text != null && close != null && close.closing && close.name == node.name &&
                            !text.raw.trim().contains('\n') && text.raw.trim().length <= 200
                        ) {
                            line(formatted + text.raw.trim() + "</${node.name}>")
                            i += 3
                            continue
                        }
                        if (!node.selfClosing && !isVoid && close == null && (nodes.getOrNull(i + 1) as? Node.Tag)?.let { it.closing && it.name == node.name } == true) {
                            line(formatted + "</${node.name}>")
                            i += 2
                            continue
                        }
                        line(formatted)
                        if (!node.selfClosing && !isVoid) {
                            depth++
                            if (node.name.lowercase() in rawTextTags) {
                                val raw = nodes.getOrNull(i + 1) as? Node.Other
                                if (raw != null && !raw.raw.startsWith("<")) {
                                    sb.append(raw.raw.trimEnd())
                                    i++
                                }
                            }
                        }
                    }
                }
            }
            i++
        }
        return sb.toString() + "\n"
    }
}
