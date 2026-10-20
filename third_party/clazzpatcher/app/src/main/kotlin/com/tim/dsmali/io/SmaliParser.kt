package com.tim.dsmali.io

import com.tim.dsmali.asm.SmaliInsnEmitter
import com.tim.dsmali.asm.SmaliLiterals
import com.tim.dsmali.asm.SmaliScanner
import com.tim.dsmali.data.SmaliDebugKind
import com.tim.dsmali.SmaliParseException
import com.tim.dsmali.model.SmaliAnnotation
import com.tim.dsmali.model.SmaliBodyElement
import com.tim.dsmali.model.SmaliAnnotationElement
import com.tim.dsmali.model.SmaliCatchDirective
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliDebug
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliInsn
import com.tim.dsmali.model.SmaliLabel
import com.tim.dsmali.model.SmaliMethod

/**
 * Разбор текста smali (синтаксис baksmali/smali 3.x) в [SmaliClass].
 *
 * - комментарии `#` вне строковых/символьных литералов отбрасываются;
 * - `.param pN` — номер регистра (p0 у нестатического метода — `this`), в модели хранится
 *   индекс параметра; поддерживается и `.param vN`, и старый `.parameter`;
 * - аннотации после `.field`/`.param` без `.end field`/`.end param` относятся к классу/методу
 *   (как в грамматике smali);
 * - многострочные значения (массивы, `.subannotation`) и payload'ы хранятся текстом,
 *   строки без отступов и комментариев, через `\n`;
 * - неизвестная директива или строка — ошибка с номером строки.
 *
 * Потокобезопасен: состояние разбора создаётся на каждый вызов.
 */
object SmaliParser {

    /** Разобрать класс; `null` — текст не содержит ничего, кроме пробелов и комментариев. */
    fun parseClass(text: String): SmaliClass? =
        Parser(if (text.startsWith("\uFEFF")) text.substring(1) else text).parse() // BOM не часть текста

    /** Строка без комментария (`#` вне литералов) и пробелов по краям. */
    fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        if (hash < 0) return line.trim()
        var i = 0
        val n = line.length
        while (i < n) {
            when (line[i]) {
                '#' -> return line.substring(0, i).trim()
                '"', '\'' -> {
                    val q = line[i]
                    i++
                    while (i < n && line[i] != q) {
                        if (line[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return line.trim()
    }

    private class Parser(text: String) {
        private val lines = text.lines()
        private var idx = 0
        private var lineNo = 0
        private var line = ""

        private var cls: SmaliClass? = null
        private var superSeen = false
        private var sourceSeen = false

        private fun error(msg: String): Nothing =
            throw SmaliParseException(lineNo, "SmaliParser: строка $lineNo: $msg: \"$line\"")

        /** Следующая непустая строка (без комментария) или null. */
        private fun next(): String? {
            while (idx < lines.size) {
                val l = stripComment(lines[idx++])
                if (l.isNotEmpty()) {
                    lineNo = idx
                    line = l
                    return l
                }
            }
            return null
        }

        /** Подсмотреть следующую непустую строку, не потребляя её. */
        private fun peek(): String? {
            var i = idx
            while (i < lines.size) {
                val l = stripComment(lines[i++])
                if (l.isNotEmpty()) return l
            }
            return null
        }

        fun parse(): SmaliClass? {
            try {
                while (true) {
                    val l = next() ?: break
                    topLevel(l)
                }
            } catch (e: SmaliParseException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw SmaliParseException(lineNo, "SmaliParser: строка $lineNo: ${e.message}: \"$line\"", e)
            }
            cls?.let { if (!superSeen) it.superClass = null }
            return cls
        }

        /** Имя директивы: первое слово, для `.end`/`.restart` — два слова. */
        private fun directive(l: String): String {
            var end = 0
            while (end < l.length && !l[end].isWhitespace()) end++
            val first = l.substring(0, end)
            if (first == ".end" || first == ".restart") {
                var e2 = end
                while (e2 < l.length && l[e2].isWhitespace()) e2++
                var e3 = e2
                while (e3 < l.length && !l[e3].isWhitespace()) e3++
                if (e3 > e2) return first + " " + l.substring(e2, e3)
            }
            return first
        }

        /** Строка — ровно директива [d] без аргументов. */
        private fun isBare(l: String?, d: String): Boolean =
            l != null && l.startsWith(".") && splitWs(l).joinToString(" ") == d

        /** Аргументы директивы (после её имени). */
        private fun args(l: String, d: String): String = l.substring(minOf(d.length, l.length)).trim()

        /** Директивы до `.class` (эталонный smali допускает любой порядок): применяются после неё. */
        private val beforeClass = ArrayList<String>()

        private fun topLevel(l: String) {
            val d = directive(l)
            val c = cls
            if (c == null) {
                if (d == ".source" || d == ".super" || d == ".implements") {
                    beforeClass.add(l)
                    return
                }
                if (d != ".class") error("Ожидалась директива .class")
                classDirective(l)
                for (pending in beforeClass) topLevel(pending)
                beforeClass.clear()
                return
            }
            when (d) {
                ".class" -> error("Повторная директива .class")
                ".super" -> {
                    if (superSeen) error("Повторная директива .super")
                    superSeen = true
                    c.superClass = typeArg(args(l, d))
                }
                ".source" -> {
                    if (sourceSeen) error("Повторная директива .source")
                    sourceSeen = true
                    c.sourceFile = SmaliLiterals.parseString(args(l, d))
                }
                ".implements" -> c.interfaces.add(typeArg(args(l, d)))
                ".annotation" -> c.annotations.add(annotation(l))
                ".field" -> field(c, l)
                ".method" -> method(c, l)
                else -> error("Неизвестная директива $d")
            }
        }

        private fun typeArg(a: String): String {
            val s = SmaliScanner(a)
            val t = s.type()
            s.expectEnd()
            return t
        }

        /** Слова через пробелы; пробелы внутри обратных кавычек (имена DEX 040+) слово не делят. */
        private fun splitWs(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                val st = i
                var quoted = false
                while (i < s.length && (quoted || !s[i].isWhitespace())) {
                    if (s[i] == '`') quoted = !quoted
                    i++
                }
                if (i > st) out.add(s.substring(st, i))
            }
            return out
        }

        private fun classDirective(l: String) {
            val tokens = splitWs(args(l, ".class"))
            if (tokens.isEmpty()) error("Нет дескриптора класса")
            val desc = typeArg(tokens.last())
            if (!desc.startsWith("L") || !desc.endsWith(";")) error("Некорректный дескриптор класса $desc")
            cls = SmaliClass(desc).apply { accessFlags.addAll(tokens.subList(0, tokens.size - 1)) }
        }

        // ---- Поля ----

        private fun field(c: SmaliClass, l: String) {
            val at = lineNo
            val rest = args(l, ".field")
            // флаги ... имя:тип [= значение]
            var i = 0
            val flags = ArrayList<String>()
            var nameType: String? = null
            while (i < rest.length) {
                while (i < rest.length && rest[i].isWhitespace()) i++
                val st = i
                var quoted = false
                while (i < rest.length && (quoted || (!rest[i].isWhitespace() && rest[i] != '='))) {
                    if (rest[i] == '`') quoted = !quoted
                    i++
                }
                if (i == st) break
                val tok = rest.substring(st, i)
                if (tok.indexOf(':') >= 0) { nameType = tok; break }
                flags.add(tok)
            }
            if (nameType == null) error("Ожидалось имя:тип поля")
            // Имя в обратных кавычках (с пробелами) может содержать `:` только внутри кавычек — их в SimpleName нет.
            val colon = if (nameType.startsWith("`")) nameType.indexOf(':', nameType.indexOf('`', 1).coerceAtLeast(0)) else nameType.indexOf(':')
            val name = nameType.substring(0, colon).removeSurrounding("`")
            val type = typeArg(nameType.substring(colon + 1))
            if (name.isEmpty()) error("Пустое имя поля")
            val tail = rest.substring(i).trim()
            val value = when {
                tail.isEmpty() -> null
                tail.startsWith("=") -> {
                    val v = tail.substring(1).trim()
                    if (v.isEmpty()) error("Нет значения после '='")
                    collectValue(v)
                }
                else -> error("Лишний текст после поля: $tail")
            }
            val f = SmaliField(flags, name, type, initialValue = value)
            f.line = at
            c.fields.add(f)

            val anns = trailingAnnotations()
            if (isBare(peek(), ".end field")) {
                next()
                f.annotations.addAll(anns)
            } else {
                c.annotations.addAll(anns)
            }
        }

        /** Аннотации, идущие подряд (для .field/.param). */
        private fun trailingAnnotations(): List<SmaliAnnotation> {
            val out = ArrayList<SmaliAnnotation>()
            while (true) {
                val p = peek() ?: break
                if (directive(p) != ".annotation") break
                out.add(annotation(next()!!))
            }
            return out
        }

        // ---- Аннотации ----

        private fun annotation(l: String): SmaliAnnotation {
            val tokens = splitWs(args(l, ".annotation"))
            if (tokens.size != 2) error("Ожидалось: .annotation <видимость> <тип>")
            val vis = tokens[0]
            if (vis != "build" && vis != "runtime" && vis != "system") error("Неизвестная видимость аннотации $vis")
            val ann = SmaliAnnotation(visibility = vis, type = typeArg(tokens[1]))
            while (true) {
                val el = next() ?: error("Нет .end annotation")
                if (isBare(el, ".end annotation")) return ann
                val eq = el.indexOf('=')
                if (eq <= 0) error("Ожидался элемент аннотации имя = значение")
                val name = el.substring(0, eq).trim()
                if (name.isEmpty() || !name.all { SmaliScanner.isNameChar(it) }) error("Некорректное имя элемента аннотации")
                val v = el.substring(eq + 1).trim()
                if (v.isEmpty()) error("Нет значения элемента $name")
                ann.elements.add(SmaliAnnotationElement(name, collectValue(v)))
            }
        }

        /** Значение, возможно многострочное (`{ ... }`, `.subannotation ... .end subannotation`). */
        private fun collectValue(first: String): String {
            var depth = depthDelta(first)
            if (depth == 0) return first
            val sb = StringBuilder(first)
            while (depth > 0) {
                val l = next() ?: error("Незавершённое значение")
                sb.append('\n').append(l)
                depth += depthDelta(l)
            }
            if (depth < 0) error("Лишняя закрывающая скобка в значении")
            return sb.toString()
        }

        private fun depthDelta(l: String): Int {
            var d = 0
            var i = 0
            val n = l.length
            while (i < n) {
                when (l[i]) {
                    '"', '\'' -> {
                        val q = l[i]
                        i++
                        while (i < n && l[i] != q) {
                            if (l[i] == '\\') i++
                            i++
                        }
                    }
                    '{' -> d++
                    '}' -> d--
                    '.' -> {
                        if (l.startsWith(".end subannotation", i)) { d--; i += 17 }
                        else if (l.startsWith(".subannotation", i)) { d++; i += 13 }
                    }
                }
                i++
            }
            return d
        }

        // ---- Методы ----

        private fun method(c: SmaliClass, l: String) {
            val tokens = splitWs(args(l, ".method"))
            if (tokens.isEmpty()) error("Нет сигнатуры метода")
            val sig = tokens.last()
            // Имя в обратных кавычках (пробелы, DEX 040+): скобка ищется после закрывающей кавычки.
            val nameEnd = if (sig.startsWith("`")) sig.indexOf('`', 1) + 1 else 0
            val open = sig.indexOf('(', nameEnd)
            if (open <= 0 || nameEnd < 0) error("Некорректная сигнатура метода $sig")
            val s = SmaliScanner(sig, open)
            val (params, ret) = try { s.proto() } catch (e: IllegalArgumentException) { error("Некорректная сигнатура метода $sig") }
            if (!s.atEnd()) error("Некорректная сигнатура метода $sig")
            val name = sig.substring(0, open).removeSurrounding("`")
            if (name.isEmpty()) error("Пустое имя метода")
            val m = SmaliMethod(
                accessFlags = tokens.subList(0, tokens.size - 1).toMutableList(),
                name = name,
                returnType = ret,
                paramTypes = params.toMutableList()
            )
            m.line = lineNo
            c.methods.add(m)
            methodBody(m)
        }

        private fun methodBody(m: SmaliMethod) {
            val isStatic = "static" in m.accessFlags
            val paramRegs = m.paramTypes.size + m.paramTypes.count { it == "J" || it == "D" } + if (isStatic) 0 else 1
            val seenParams = HashSet<Int>()
            var oldParamCounter = 0

            while (true) {
                val l = next() ?: error("Нет .end method")
                val c0 = l[0]
                if (c0 == ':') {
                    val s = SmaliScanner(l)
                    val label = s.label()
                    if (!s.atEnd()) error("Лишний текст после метки")
                    m.body.add(SmaliLabel(label).also { it.line = lineNo })
                    continue
                }
                if (c0 != '.') {
                    var sp = 0
                    while (sp < l.length && !l[sp].isWhitespace() && l[sp] != ':') sp++
                    if (SmaliInsnEmitter.opcodeByName(l.substring(0, sp)) == null) {
                        error("Неизвестная инструкция ${l.substring(0, sp)}")
                    }
                    m.body.add(SmaliInsn(l).also { it.line = lineNo })
                    continue
                }
                val at = lineNo
                when (val d = directive(l)) {
                    ".end method" -> { noArgs(l, d); return }
                    ".registers", ".locals" -> {
                        if (m.registers >= 0 || m.locals >= 0) error("Повторная директива .registers/.locals")
                        val n = try { SmaliLiterals.parseInt(args(l, d)) } catch (e: NumberFormatException) { error("Некорректное число регистров") }
                        if (n < 0 || n > 0xFFFF) error("Некорректное число регистров $n")
                        if (d == ".registers") m.registers = n else m.locals = n
                    }
                    ".param" -> {
                        val s = SmaliScanner(l, d.length)
                        s.skipWs()
                        val kind = s.text.getOrNull(s.pos)
                        val raw = s.register(0, Int.MAX_VALUE)
                        val regNum = when (kind) {
                            'p', 'P' -> raw
                            else -> {
                                val regs = if (m.registers >= 0) m.registers else if (m.locals >= 0) m.locals + paramRegs else
                                    error(".param v-регистр до .registers/.locals")
                                raw - (regs - paramRegs)
                            }
                        }
                        val name = if (s.tryConsume(',')) s.stringLiteral() else null
                        if (!s.atEnd()) error("Лишний текст в .param")
                        val index = paramIndex(m, isStatic, regNum)
                        if (!seenParams.add(index)) error("Параметр ${index} описан повторно")
                        setParam(m, index, name)
                    }
                    ".parameter" -> {
                        val a = args(l, d)
                        val name = if (a.isEmpty()) null else SmaliLiterals.parseString(a)
                        val index = oldParamCounter++
                        if (index >= m.paramTypes.size) error("Директив .parameter больше, чем параметров")
                        if (!seenParams.add(index)) error("Параметр ${index} описан повторно")
                        setParam(m, index, name, ".end parameter")
                    }
                    ".annotation" -> m.annotations.add(annotation(l))
                    ".prologue" -> { noArgs(l, d); m.body.add(SmaliDebug(SmaliDebugKind.PROLOGUE, l).at(at)) }
                    ".epilogue" -> { noArgs(l, d); m.body.add(SmaliDebug(SmaliDebugKind.EPILOGUE, l).at(at)) }
                    ".line" -> m.body.add(SmaliDebug(SmaliDebugKind.LINE, l).at(at))
                    ".local" -> m.body.add(SmaliDebug(SmaliDebugKind.LOCAL, l).at(at))
                    ".end local" -> m.body.add(SmaliDebug(SmaliDebugKind.END_LOCAL, l).at(at))
                    ".restart local" -> m.body.add(SmaliDebug(SmaliDebugKind.RESTART_LOCAL, l).at(at))
                    ".source" -> m.body.add(SmaliDebug(SmaliDebugKind.SOURCE, l).at(at))
                    ".catch", ".catchall" -> m.catches.add(catchDirective(l, d))
                    ".packed-switch" -> m.body.add(SmaliInsn(payload(l, ".end packed-switch")).at(at))
                    ".sparse-switch" -> m.body.add(SmaliInsn(payload(l, ".end sparse-switch")).at(at))
                    ".array-data" -> m.body.add(SmaliInsn(payload(l, ".end array-data")).at(at))
                    else -> error("Неизвестная директива $d")
                }
            }
        }

        private fun <T : SmaliBodyElement> T.at(line: Int): T = also { it.line = line }

        private fun noArgs(l: String, d: String) {
            if (args(l, d).isNotEmpty()) error("У $d нет аргументов")
        }

        /** Имя параметра и аннотации (если за ними следует .end param/.end parameter). */
        private fun setParam(m: SmaliMethod, index: Int, name: String?, endDirective: String = ".end param") {
            if (name != null) {
                while (m.paramNames.size <= index) m.paramNames.add(null)
                m.paramNames[index] = name
            }
            val anns = trailingAnnotations()
            if (isBare(peek(), endDirective)) {
                next()
                if (anns.isNotEmpty()) m.parameterAnnotations.getOrPut(index) { mutableListOf() }.addAll(anns)
            } else {
                m.annotations.addAll(anns)
            }
        }

        /** Регистр `.param` (номер относительно p0) → индекс параметра. */
        private fun paramIndex(m: SmaliMethod, isStatic: Boolean, regNum: Int): Int {
            if (!isStatic && regNum == 0) error("p0 нестатического метода — это this, а не параметр")
            var r = if (isStatic) 0 else 1
            for ((i, t) in m.paramTypes.withIndex()) {
                if (r == regNum) return i
                r += if (t == "J" || t == "D") 2 else 1
                if (regNum < r) error("Регистр указывает на вторую половину wide-параметра")
            }
            error("Регистр не является регистром параметра")
        }

        private fun catchDirective(l: String, d: String): SmaliCatchDirective {
            val s = SmaliScanner(l, d.length)
            val type = if (d == ".catch") s.type() else null
            s.expect('{')
            val start = s.label()
            if (!s.tryConsume("..")) error("Ожидалось '..' в диапазоне try")
            val end = s.label()
            s.expect('}')
            val handler = s.label()
            if (!s.atEnd()) error("Лишний текст в $d")
            return SmaliCatchDirective(type, start, end, handler)
        }

        private fun payload(first: String, end: String): String {
            val sb = StringBuilder(first)
            while (true) {
                val l = next() ?: error("Нет $end")
                sb.append('\n').append(l)
                if (directive(l) == end) {
                    if (!isBare(l, end)) error("Лишний текст после $end")
                    return sb.toString()
                }
                if (l.startsWith(".") && !l.startsWith(".end")) error("Директива внутри payload")
            }
        }
    }
}
