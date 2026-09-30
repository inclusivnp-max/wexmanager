package org.wexclient.app.editor

import org.wexclient.app.dex.SmaliAssembler
import org.wexclient.app.dex.SmaliSyntaxException
import org.wexclient.app.editor.lang.Tok
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.wexclient.app.util.startHelper

enum class Severity { ERROR, WARNING, INFO }

class Diagnostic(val start: Int, val end: Int, val severity: Severity, val message: String, val source: String)

/** Поиск внешних инструментов в PATH (один раз). */
object ToolLocator {
    private val cache = HashMap<String, String?>()

    @Synchronized
    fun find(name: String): String? = cache.getOrPut(name) {
        val path = System.getenv("PATH") ?: ""
        val dirs = path.split(File.pathSeparatorChar) + listOf("/usr/lib/qt6/bin", "/usr/local/bin", "/usr/bin")
        dirs.asSequence().map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    /**
     * [errors] — отдельный stderr (при separateErrors), [complete] — вывод прочитан до конца
     * и не обрезан по [maxChars].
     */
    class RunResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val errors: String = "",
        val complete: Boolean = true
    )

    /**
     * Запуск без оболочки, с таймаутом; stdin — [input]. Для форматирования нужен весь stdout
     * без примеси stderr: [separateErrors] и [maxChars] = Int.MAX_VALUE.
     */
    fun run(
        cmd: List<String>,
        input: String?,
        timeoutMs: Long = 4000,
        dir: File? = null,
        separateErrors: Boolean = false,
        maxChars: Int = 200_000
    ): RunResult? = try {
        val pb = ProcessBuilder(cmd).redirectErrorStream(!separateErrors)
        if (dir != null) pb.directory(dir)
        pb.environment()["LC_ALL"] = "C.UTF-8"
        pb.environment()["NO_COLOR"] = "1"
        val p = pb.startHelper()
        val writer = Thread {
            try {
                p.outputStream.use { os -> if (input != null) os.write(input.toByteArray()) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        val out = java.io.ByteArrayOutputStream()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            try {
                // Линтер, выплюнувший гигабайт, не должен съесть память: читаем с запасом и хватит.
                // Что вывод обрезан, запоминаем: форматтер иначе заменил бы документ его началом
                p.inputStream.use {
                    out.write(it.readNBytes(MAX_OUTPUT_BYTES))
                    if (it.read() != -1) truncated.set(true)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        val err = java.io.ByteArrayOutputStream()
        val errReader = if (separateErrors) Thread {
            try {
                p.errorStream.use { it.copyTo(err) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() } else null
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(if (separateErrors) 5000 else 500)
        errReader?.join(1000)
        writer.join(100)
        val text = out.toString(Charsets.UTF_8)
        RunResult(
            exitCode = if (finished) p.exitValue() else -1,
            output = if (text.length > maxChars) text.take(maxChars) else text,
            timedOut = !finished,
            errors = err.toString(Charsets.UTF_8),
            complete = !reader.isAlive && !truncated.get() && text.length <= maxChars
        )
    } catch (_: Exception) {
        null
    }
}

object DiagnosticsEngine {
    private val NO_BRACKET_CHECK = setOf(
        "text", "log", "csv", "tsv", "markdown", "diff", "ignore", "shell", "fish", "makefile", "batch", "latex",
        "asm", "ini", "properties", "dotenv", "yaml", "smali", "lisp", "erlang", "sql", "dockerfile", "vim",
        "haskell", "ocaml", "proguard", "powershell", "perl", "ruby", "crystal", "elixir", "tcl", "r", "cmake", "nix"
    )
    private val SINGLE_LINE_STRINGS = setOf("java", "c", "cpp", "objc", "csharp", "go", "javascript", "typescript", "dart", "json", "glsl", "d", "zig", "odin", "solidity", "kotlin", "qml")

    private val tmpDir: File by lazy { org.wexclient.app.util.ProcessScratch.work.newDir("lint-").toFile() }

    /** Быстрые встроенные проверки (синхронно, миллисекунды). */
    fun quick(snap: HighlightSnapshot, fileName: String): List<Diagnostic> {
        val lang = snap.language
        val out = ArrayList<Diagnostic>()
        when (lang.id) {
            "json" -> if (!fileName.lowercase().endsWith(".json5")) {
                val lower = fileName.lowercase()
                // Конфиги с комментариями (VS Code, tsconfig, waybar) — без предупреждений
                val lenient = lower.endsWith(".jsonc") || lower.endsWith(".json5") || lower.startsWith("tsconfig") ||
                    lower.startsWith("jsconfig") || lower == "settings.json" || lower == "keybindings.json" ||
                    lower == "config" || lower.endsWith(".code-workspace") || !lower.endsWith(".json")
                out += JsonChecker.check(snap.text, lenient)
            }
            "xml" -> out += xml(snap.text)
        }
        if (lang.id !in NO_BRACKET_CHECK && !lang.markup) out += brackets(snap)
        if (lang.id in SINGLE_LINE_STRINGS) out += unterminatedStrings(snap)
        return out
    }

    /** Медленные проверки: smali-сборщик и внешние инструменты. */
    fun slow(snap: HighlightSnapshot, fileName: String, externalTools: Boolean): List<Diagnostic> {
        val lang = snap.language
        val text = snap.text
        return try {
            when (lang.id) {
                "smali" -> smali(snap)
                "shell" -> if (!externalTools) emptyList() else {
                    // .zshrc и компания — zsh, а не bash: bash -n помечал бы его синтаксис ошибками
                    val shebang = text.lineSequence().firstOrNull().orEmpty()
                    val zsh = fileName.endsWith(".zsh") || fileName.removePrefix(".") in ZSH_FILES ||
                        (shebang.startsWith("#!") && shebang.contains("zsh"))
                    val shell = if (zsh) ToolLocator.find("zsh") else ToolLocator.find("bash")
                    if (shell == null) emptyList()
                    else lineMessages(snap, ToolLocator.run(listOf(shell, "-n"), text), Regex("line (\\d+):\\s*(.*)"), "bash")
                }
                "fish" -> ifTool(externalTools, "fish") { fish ->
                    lineMessages(snap, ToolLocator.run(listOf(fish, "--no-execute", "/dev/stdin"), text), Regex("\\(line (\\d+)\\):\\s*(.*)"), "fish")
                }
                // -I: без текущей папки в sys.path — чужой ast.py или yaml.py рядом не выполнится.
                // Cython (.pyx, .pxd) — не Python: его синтаксис ast.parse не разберёт
                "python" -> if (fileName.endsWith(".pyx") || fileName.endsWith(".pxd")) emptyList() else ifTool(externalTools, "python3") { py ->
                    val script = "import ast,sys\ntry:\n    ast.parse(sys.stdin.read())\nexcept SyntaxError as e:\n    print('%s:%s:%s' % (e.lineno or 1, e.offset or 1, e.msg))\n"
                    colMessages(snap, ToolLocator.run(listOf(py, "-I", "-c", script), text), Regex("^(\\d+):(\\d+):(.*)$", RegexOption.MULTILINE), "python")
                }
                "yaml" -> ifTool(externalTools, "python3") { py ->
                    val script = "import sys\ntry:\n    import yaml\nexcept ImportError:\n    sys.exit(0)\ntry:\n    list(yaml.parse(sys.stdin.read()))\nexcept yaml.MarkedYAMLError as e:\n    m = e.problem_mark or e.context_mark\n    print('%s:%s:%s' % ((m.line + 1) if m else 1, (m.column + 1) if m else 1, e.problem or e.context))\n"
                    colMessages(snap, ToolLocator.run(listOf(py, "-I", "-c", script), text), Regex("^(\\d+):(\\d+):(.*)$", RegexOption.MULTILINE), "yaml")
                }
                "toml" -> ifTool(externalTools, "python3") { py ->
                    val script = "import sys\ntry:\n    import tomllib\nexcept ImportError:\n    sys.exit(0)\ntry:\n    tomllib.loads(sys.stdin.read())\nexcept tomllib.TOMLDecodeError as e:\n    print(e)\n"
                    colMessages(snap, ToolLocator.run(listOf(py, "-I", "-c", script), text), Regex("^(.*)\\(at line (\\d+), column (\\d+)\\)", RegexOption.MULTILINE), "toml", msgGroup = 1, lineGroup = 2, colGroup = 3)
                }
                // JSX node --check не понимает
                "javascript" -> if (fileName.endsWith(".jsx")) emptyList() else ifTool(externalTools, "node") { node ->
                    val module = Regex("^\\s*(import|export)\\s", RegexOption.MULTILINE).containsMatchIn(text) || fileName.endsWith(".mjs")
                    withTemp(if (module) "check.mjs" else "check.cjs", text) { f ->
                        val r = ToolLocator.run(listOf(node, "--check", f.absolutePath), null)
                        nodeMessages(snap, r, f.name)
                    }
                }
                // Luau с типами — не Lua 5.x
                "lua" -> if (fileName.endsWith(".luau")) emptyList() else ifTool(externalTools, "luac") { luac ->
                    lineMessages(snap, ToolLocator.run(listOf(luac, "-p", "-"), text), Regex("stdin:(\\d+):\\s*(.*)"), "luac")
                }
                "go" -> ifTool(externalTools, "gofmt") { gofmt ->
                    colMessages(snap, ToolLocator.run(listOf(gofmt, "-e"), text).takeIf { it?.exitCode != 0 }, Regex("<standard input>:(\\d+):(\\d+):\\s*(.*)"), "gofmt")
                }
                "php" -> ifTool(externalTools, "php") { php ->
                    lineMessages(snap, ToolLocator.run(listOf(php, "-l"), text), Regex("error:\\s*(.*?) in .*? on line (\\d+)"), "php", msgFirst = true)
                }
                "qml" -> ifTool(externalTools, "qmllint") { lint ->
                    withTemp("Check.qml", text) { f ->
                        val r = ToolLocator.run(listOf(lint, f.absolutePath), null, timeoutMs = 8000)
                        colMessages(snap, r, Regex("(?:Warning|Error): [^:]*:(\\d+):(\\d+): (.*\\[syntax\\].*)"), "qmllint")
                    }
                }
                "kdl" -> if (externalTools && isNiriConfig(fileName, text)) {
                    ifTool(true, "niri") { niri -> niriMessages(snap, niri) }
                } else emptyList()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ifTool(enabled: Boolean, name: String, block: (String) -> List<Diagnostic>): List<Diagnostic> {
        if (!enabled) return emptyList()
        val path = ToolLocator.find(name) ?: return emptyList()
        return block(path)
    }

    private fun <T> withTemp(name: String, text: String, block: (File) -> T): T {
        val dir = Files.createTempDirectory(tmpDir.toPath(), "c").toFile()
        val f = File(dir, name)
        try {
            f.writeText(text)
            return block(f)
        } finally {
            // Проверка могла оставить свои файлы (кэш байт-кода и т. п.)
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    private fun isNiriConfig(fileName: String, text: String): Boolean =
        fileName == "config.kdl" || text.contains("binds {") || text.contains("spawn-at-startup") ||
            text.contains("window-rule") || text.contains("layout {") || text.contains("input {")

    private fun lineRange(snap: HighlightSnapshot, line1: Int, col1: Int = 0): Pair<Int, Int> {
        val line = (line1 - 1).coerceIn(0, snap.lineCount - 1)
        val ls = snap.lineStart(line)
        val le = snap.lineEnd(line)
        if (col1 > 0) {
            val s = (ls + col1 - 1).coerceIn(ls, le)
            // Подчёркиваем слово от позиции (или один символ)
            var e = s
            while (e < le && (snap.text[e].isLetterOrDigit() || snap.text[e] == '_')) e++
            if (e == s) e = minOf(le, s + 1)
            if (e == s && s > ls) return (s - 1) to s
            return s to e
        }
        var s = ls
        while (s < le && snap.text[s].isWhitespace()) s++
        return if (s < le) s to le else ls to maxOf(le, ls)
    }

    private fun lineMessages(
        snap: HighlightSnapshot,
        r: ToolLocator.RunResult?,
        re: Regex,
        source: String,
        msgFirst: Boolean = false
    ): List<Diagnostic> {
        if (r == null || r.timedOut || r.exitCode == 0) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (m in re.findAll(r.output)) {
            val line = (if (msgFirst) m.groupValues[2] else m.groupValues[1]).toIntOrNull() ?: continue
            val msg = (if (msgFirst) m.groupValues[1] else m.groupValues[2]).trim()
            val (s, e) = lineRange(snap, line)
            out.add(Diagnostic(s, e, Severity.ERROR, msg, source))
            if (out.size > 50) break
        }
        if (out.isEmpty() && r.output.isNotBlank()) {
            val (s, e) = lineRange(snap, 1)
            out.add(Diagnostic(s, e, Severity.ERROR, r.output.lineSequence().first { it.isNotBlank() }.trim(), source))
        }
        return out
    }

    private fun colMessages(
        snap: HighlightSnapshot,
        r: ToolLocator.RunResult?,
        re: Regex,
        source: String,
        msgGroup: Int = 3,
        lineGroup: Int = 1,
        colGroup: Int = 2
    ): List<Diagnostic> {
        if (r == null || r.timedOut) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (m in re.findAll(r.output)) {
            val line = m.groupValues[lineGroup].toIntOrNull() ?: continue
            val col = m.groupValues[colGroup].toIntOrNull() ?: 0
            val (s, e) = lineRange(snap, line, col)
            out.add(Diagnostic(s, e, Severity.ERROR, m.groupValues[msgGroup].trim(), source))
            if (out.size > 50) break
        }
        return out
    }

    private fun nodeMessages(snap: HighlightSnapshot, r: ToolLocator.RunResult?, name: String): List<Diagnostic> {
        if (r == null || r.timedOut || r.exitCode == 0) return emptyList()
        val lines = r.output.lines()
        val loc = lines.firstNotNullOfOrNull { Regex("$name:(\\d+)").find(it) } ?: return emptyList()
        val line = loc.groupValues[1].toInt()
        val caretLine = lines.firstOrNull { it.trimStart().startsWith("^") }
        val col = caretLine?.indexOf('^')?.plus(1) ?: 0
        val msg = lines.firstOrNull { it.contains("Error:") }?.trim() ?: "Синтаксическая ошибка"
        val (s, e) = lineRange(snap, line, col)
        return listOf(Diagnostic(s, e, Severity.ERROR, msg, "node"))
    }

    private fun niriMessages(snap: HighlightSnapshot, niri: String): List<Diagnostic> = withTemp("config.kdl", snap.text) { f ->
        val r = ToolLocator.run(listOf(niri, "validate", "-c", f.absolutePath), null, timeoutMs = 5000) ?: return@withTemp emptyList()
        if (r.timedOut || r.exitCode == 0) return@withTemp emptyList()
        val clean = r.output.replace(Regex("\u001B\\[[0-9;]*m"), "")
        val lines = clean.lines()
        // Последнее сообщение с «×» — суть ошибки (с продолжением на строках «│ …»)
        val lastX = lines.indexOfLast { it.contains('×') }
        if (lastX < 0) return@withTemp emptyList()
        val msg = buildString {
            append(lines[lastX].substringAfter('×').trim())
            var k = lastX + 1
            while (k < lines.size && Regex("^\\s*│\\s").containsMatchIn(lines[k]) && !lines[k].contains("╭─[")) {
                append(' ').append(lines[k].substringAfter('│').trim())
                k++
            }
        }
        // Позиция: строка с маркером «┬» под фрагментом кода, иначе «[файл:строка:колонка]»
        val loc = Regex("\\[[^\\]]*config\\.kdl:(\\d+):(\\d+)]").find(clean)
        var line = loc?.groupValues?.get(1)?.toIntOrNull() ?: 1
        var col = loc?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val marker = lines.indexOfFirst { it.contains('┬') }
        if (marker > 0) {
            for (k in marker - 1 downTo 0) {
                val m = Regex("^\\s*(\\d+)\\s*│").find(lines[k]) ?: continue
                line = m.groupValues[1].toInt()
                val codeStart = lines[k].indexOf('│') + 2
                col = (lines[marker].indexOf('┬') - codeStart + 1).coerceAtLeast(1)
                break
            }
        }
        val (s, e) = lineRange(snap, line, col)
        listOf(Diagnostic(s, e, Severity.ERROR, msg, "niri validate"))
    }

    private fun smali(snap: HighlightSnapshot): List<Diagnostic> {
        if (!snap.text.contains(".class")) return emptyList()
        return try {
            SmaliAssembler.assemble(snap.text)
            emptyList()
        } catch (e: SmaliSyntaxException) {
            // Сборка останавливается на первой ошибке: одна отметка — на её строке
            val (s, en) = lineRange(snap, e.line.coerceAtLeast(1))
            val msg = e.errors.firstOrNull()?.removePrefix("строка ${e.line}: ") ?: "Ошибка smali"
            listOf(Diagnostic(s, en, Severity.ERROR, msg, "smali"))
        }
    }

    private fun xml(text: String): List<Diagnostic> {
        if (text.isBlank()) return emptyList()
        val factory = SAXParserFactory.newInstance()
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {
        }
        factory.isNamespaceAware = false
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val source = org.xml.sax.InputSource(ByteArrayInputStream(bytes)).apply { encoding = "UTF-8" }
            factory.newSAXParser().parse(source, object : DefaultHandler() {
                override fun resolveEntity(publicId: String?, systemId: String?) =
                    org.xml.sax.InputSource(java.io.StringReader(""))
            })
            emptyList()
        } catch (e: SAXParseException) {
            val offset = offsetOf(text, e.lineNumber, e.columnNumber)
            val start = maxOf(0, offset - 1)
            listOf(Diagnostic(start, minOf(text.length, start + 1).coerceAtLeast(start), Severity.ERROR, e.message ?: "Ошибка XML", "xml"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun offsetOf(text: String, line1: Int, col1: Int): Int {
        var line = 1
        var i = 0
        while (line < line1 && i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) return text.length
            i = nl + 1
            line++
        }
        val le = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        return (i + maxOf(0, col1 - 1)).coerceIn(i, le)
    }

    private fun brackets(snap: HighlightSnapshot): List<Diagnostic> {
        val text = snap.text
        val stack = ArrayList<Int>()
        val out = ArrayList<Diagnostic>()
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            val base = snap.lineStart(line)
            var k = 0
            while (k < t.size) {
                if (t[k + 2] == Tok.BRACKET.ordinal) {
                    for (p in base + t[k] until base + t[k + 1]) {
                        val c = text[p]
                        when (c) {
                            '(', '[', '{' -> if (stack.size < 100_000) stack.add(p)
                            ')', ']', '}' -> {
                                val want = TextOps.openerOf(c)
                                if (stack.isEmpty()) {
                                    out.add(Diagnostic(p, p + 1, Severity.ERROR, "Лишняя «$c»", "скобки"))
                                } else {
                                    val top = stack.last()
                                    if (text[top] == want) stack.removeAt(stack.size - 1)
                                    else {
                                        out.add(Diagnostic(p, p + 1, Severity.ERROR, "Ожидалась «${TextOps.closerOf(text[top])}», а не «$c»", "скобки"))
                                        // Восстановление: снимаем до подходящей
                                        val idx = stack.indexOfLast { text[it] == want }
                                        if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                                    }
                                }
                            }
                        }
                    }
                }
                k += 3
            }
            if (out.size > 20) return out
        }
        for (p in stack.takeLast(10)) {
            out.add(Diagnostic(p, p + 1, Severity.ERROR, "Не закрыта «${text[p]}»", "скобки"))
        }
        return out
    }

    private fun unterminatedStrings(snap: HighlightSnapshot): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val text = snap.text
        for (line in 0 until snap.lineCount) {
            val t = snap.lineTokens(line)
            if (t.isEmpty()) continue
            val lastType = t[t.size - 1]
            if (lastType != Tok.STRING.ordinal) continue
            val base = snap.lineStart(line)
            val len = snap.lineEnd(line) - base
            if (t[t.size - 2] != len) continue
            // Начало строкового литерала
            var k = t.size - 3
            while (k >= 3 && t[k - 2] == t[k] && t[k - 1].let { it == Tok.STRING.ordinal || it == Tok.ESCAPE.ordinal || it == Tok.VARIABLE.ordinal }) k -= 3
            val s = base + t[k]
            // Строка начата выше и тянется сюда (шаблон `…`, """…""") — это не новый литерал
            if (t[k] == 0 && line > 0 && snap.endState(line - 1) != 0) continue
            val open = text[s]
            if (open != '"' && open != '\'' && open != '`') continue
            if (text.startsWith("\"\"\"", s) || open == '`') continue
            val lastChar = text[base + len - 1]
            val closed = lastChar == open && base + len - 1 > s && (base + len - 2 < 0 || text[base + len - 2] != '\\' || (base + len - 3 >= 0 && text[base + len - 3] == '\\'))
            if (closed) continue
            // Продолжение многострочной строки на следующей строке — не ошибка
            if (line + 1 < snap.lineCount) {
                val nt = snap.lineTokens(line + 1)
                if (nt.isNotEmpty() && nt[0] == 0 && nt[2] == Tok.STRING.ordinal) continue
            }
            out.add(Diagnostic(s, base + len, Severity.ERROR, "Незакрытая строка", "строки"))
            if (out.size > 20) break
        }
        return out
    }
}

/** Строгий разбор JSON с позициями ошибок (комментарии и висячие запятые — предупреждения). */
object JsonChecker {
    private class Fail(val pos: Int, val msg: String) : Exception(msg)

    fun check(text: String, lenient: Boolean): List<Diagnostic> {
        if (text.isBlank()) return emptyList()
        val warnings = ArrayList<Diagnostic>()
        val p = Parser(text, warnings, lenient)
        return try {
            p.skipWs()
            if (p.i < text.length) p.value(0)
            p.skipWs()
            // JSON Lines: несколько значений подряд
            while (p.i < text.length) {
                if (text[p.i] == '{' || text[p.i] == '[') {
                    p.value(0); p.skipWs()
                } else throw Fail(p.i, "Лишние символы после JSON")
            }
            warnings
        } catch (f: Fail) {
            val s = f.pos.coerceIn(0, text.length)
            warnings + Diagnostic(s, minOf(text.length, s + 1).coerceAtLeast(s), Severity.ERROR, f.msg, "json")
        } catch (_: StackOverflowError) {
            warnings
        }
    }

    private class Parser(val s: String, val warnings: MutableList<Diagnostic>, val lenient: Boolean) {
        var i = 0

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                    i++
                } else if (c == '/' && i + 1 < s.length && (s[i + 1] == '/' || s[i + 1] == '*')) {
                    val start = i
                    if (s[i + 1] == '/') {
                        i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    } else {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) throw Fail(i, "Незакрытый комментарий")
                        i = end + 2
                    }
                    if (!lenient) warnings.add(Diagnostic(start, i, Severity.WARNING, "Комментарии не входят в стандарт JSON", "json"))
                } else break
            }
        }

        fun value(depth: Int) {
            if (depth > 2000) throw Fail(i, "Слишком глубокая вложенность")
            skipWs()
            if (i >= s.length) throw Fail(i, "Ожидалось значение")
            when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true")
                'f' -> lit("false")
                'n' -> lit("null")
                else -> if (c == '-' || c.isDigit()) num() else throw Fail(i, "Неожиданный символ «$c»")
            }
        }

        private fun lit(w: String) {
            if (!s.startsWith(w, i)) throw Fail(i, "Ожидалось $w")
            i += w.length
        }

        private fun num() {
            val start = i
            if (s[i] == '-') i++
            if (i < s.length && s[i] == '0') i++ else {
                if (i >= s.length || !s[i].isDigit()) throw Fail(i, "Некорректное число")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isDigit()) throw Fail(i, "Некорректное число")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) throw Fail(i, "Некорректная экспонента")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i].isLetterOrDigit())) throw Fail(start, "Некорректное число")
        }

        fun str(): String {
            val start = i
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++; return sb.toString()
                    }
                    c == '\\' -> {
                        if (i + 1 >= s.length) throw Fail(i, "Незакрытая строка")
                        when (s[i + 1]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i += 2
                            'u' -> {
                                if (i + 5 >= s.length || !s.substring(i + 2, i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                    throw Fail(i, "Некорректная \\u-последовательность")
                                }
                                i += 6
                            }
                            else -> throw Fail(i, "Недопустимое экранирование «\\${s[i + 1]}»")
                        }
                        sb.append('?')
                    }
                    c == '\n' -> throw Fail(start, "Незакрытая строка")
                    c < ' ' -> throw Fail(i, "Управляющий символ в строке")
                    else -> {
                        sb.append(c); i++
                    }
                }
            }
            throw Fail(start, "Незакрытая строка")
        }

        private fun obj(depth: Int) {
            i++
            val keys = HashSet<String>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++; return
            }
            while (true) {
                skipWs()
                if (i >= s.length) throw Fail(i, "Не закрыт объект «}»")
                if (s[i] == '}') {
                    warnings.add(Diagnostic(i - 1, i, Severity.WARNING, "Висячая запятая", "json"))
                    i++
                    return
                }
                if (s[i] != '"') throw Fail(i, "Ключ должен быть строкой в кавычках")
                val keyStart = i
                val key = str()
                if (!keys.add(key)) warnings.add(Diagnostic(keyStart, i, Severity.WARNING, "Повторяющийся ключ «$key»", "json"))
                skipWs()
                if (i >= s.length || s[i] != ':') throw Fail(i, "Ожидалось «:»")
                i++
                value(depth + 1)
                skipWs()
                if (i >= s.length) throw Fail(i, "Не закрыт объект «}»")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++; return
                    }
                    else -> throw Fail(i, "Ожидалась «,» или «}»")
                }
            }
        }

        private fun arr(depth: Int) {
            i++
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++; return
            }
            while (true) {
                skipWs()
                if (i < s.length && s[i] == ']') {
                    warnings.add(Diagnostic(i - 1, i, Severity.WARNING, "Висячая запятая", "json"))
                    i++
                    return
                }
                value(depth + 1)
                skipWs()
                if (i >= s.length) throw Fail(i, "Не закрыт массив «]»")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++; return
                    }
                    else -> throw Fail(i, "Ожидалась «,» или «]»")
                }
            }
        }
    }
}


private const val MAX_OUTPUT_BYTES = 4 shl 20

/** Конфиги zsh без расширения .zsh (с точкой в начале или без). */
private val ZSH_FILES = setOf("zshrc", "zshenv", "zprofile", "zlogin", "zlogout")
