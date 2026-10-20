package com.tim.csmali.io

import com.tim.csmali.asm.SmaliInsnEmitter
import com.tim.csmali.asm.SmaliLiterals
import com.tim.csmali.asm.SmaliScanner
import com.tim.csmali.data.SmaliDebugKind
import com.tim.csmali.model.*
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.TypePath.TypePathEntry

object SmaliParser {

    fun parseClass(text: String): SmaliClass? =
        Parser(if (text.startsWith("\uFEFF")) text.substring(1) else text).parse()

    /** Строка без комментария `#…` (вне строковых и символьных литералов) и крайних пробелов. */
    fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        return trimWs(line, 0, if (hash < 0) line.length else commentStart(line, 0, line.length))
    }

    /** Подстрока [from, to) без пробельных символов по краям (быстрее kotlin trim на горячем пути). */
    private fun trimWs(s: String, from: Int, to: Int): String {
        var a = from
        var b = to
        while (a < b && s[a] <= ' ') a++
        while (b > a && s[b - 1] <= ' ') b--
        return if (a == 0 && b == s.length) s else if (a == b) "" else s.substring(a, b)
    }

    /**
     * Строки текста сразу без комментариев и крайних пробелов — один проход и одна копия на строку.
     * Концы строк: \n, \r\n и одиночный \r; поиск — интринсиком indexOf.
     */
    private fun strippedLines(text: String): Array<String> {
        val n = text.length
        val out = ArrayList<String>(n / 32 + 1)
        val loneCr = text.indexOf('\r').let { it >= 0 && (0 until n).any { i -> text[i] == '\r' && (i + 1 >= n || text[i + 1] != '\n') } }
        var start = 0
        var hash = text.indexOf('#')
        while (true) {
            var nl = if (loneCr) nextBreak(text, start) else text.indexOf('\n', start)
            if (nl < 0) nl = n
            var end = nl
            if (end > start && text[end - 1] == '\r') end--
            if (hash in start until end) end = commentStart(text, start, end)
            out.add(trimWs(text, start, end))
            if (nl >= n) break
            start = if (loneCr && text[nl] == '\r' && nl + 1 < n && text[nl + 1] == '\n') nl + 2 else nl + 1
            if (hash in 0 until start) hash = text.indexOf('#', start)
        }
        return out.toTypedArray()
    }

    private fun nextBreak(text: String, from: Int): Int {
        for (i in from until text.length) if (text[i] == '\n' || text[i] == '\r') return i
        return -1
    }

    /** Начало комментария `#` в строке [start, end) вне литералов; нет — [end]. */
    private fun commentStart(s: String, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            when (s[i]) {
                '#' -> return i
                '"', '\'' -> {
                    val q = s[i]
                    i++
                    while (i < end && s[i] != q) {
                        if (s[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return end
    }

    private val END_WORDS = arrayOf("local", "method", "field", "annotation", "subannotation", "param", "tableswitch", "lookupswitch", "typeannotation")
    private val END_DIRECTIVES = Array(END_WORDS.size) { ".end " + END_WORDS[it] }
    private val COMMON = arrayOf(".line", ".local", ".limit", ".catch", ".catchall", ".param", ".annotation", ".method", ".field", ".signature")

    private class Parser(text: String) {
        /** Строки без комментариев и крайних пробелов. */
        private val lines = strippedLines(text)
        private var idx = 0
        private var lineNo = 0
        private var line = ""

        private var cls: SmaliClass? = null
        private var superSeen = false
        private var sourceSeen = false

        private fun error(msg: String): Nothing =
            throw IllegalArgumentException("SmaliParser: строка $lineNo: $msg: \"$line\"")

        private fun line(i: Int): String = lines[i]

        private fun next(): String? {
            while (idx < lines.size) {
                val l = line(idx++)
                if (l.isNotEmpty()) {
                    lineNo = idx
                    line = l
                    return l
                }
            }
            return null
        }

        private fun peek(): String? {
            var i = idx
            while (i < lines.size) {
                val l = line(i++)
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
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("SmaliParser:") == true) throw e
                throw IllegalArgumentException("SmaliParser: строка $lineNo: ${e.message}: \"$line\"", e)
            }
            cls?.let { if (!superSeen) it.superClass = null }
            return cls
        }

        /** Директива строки (`.line`, `.end local`, …): частые — готовыми строками, без выделения памяти. */
        private fun directive(l: String): String {
            var end = 0
            while (end < l.length && l[end] > ' ') end++
            if ((end == 4 && l.startsWith(".end")) || (end == 8 && l.startsWith(".restart"))) {
                var e2 = end
                while (e2 < l.length && l[e2] <= ' ') e2++
                var e3 = e2
                while (e3 < l.length && l[e3] > ' ') e3++
                if (e3 > e2) {
                    if (end == 4) for (k in END_WORDS.indices) {
                        val w = END_WORDS[k]
                        if (e3 - e2 == w.length && l.regionMatches(e2, w, 0, w.length)) return END_DIRECTIVES[k]
                    }
                    return l.substring(0, end) + " " + l.substring(e2, e3)
                }
            }
            for (d in COMMON) if (end == d.length && l.startsWith(d)) return d
            return l.substring(0, end)
        }

        private fun isBare(l: String?, d: String): Boolean =
            l != null && l.startsWith(".") && splitWs(l).joinToString(" ") == d

        private fun args(l: String, d: String): String = l.substring(minOf(d.length, l.length)).trim()

        /** Разбиение по пробелам; пробелы внутри обратных кавычек (имена с пробелами) не делят токен. */
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

        private fun topLevel(l: String) {
            val d = directive(l)
            val c = cls
            if (c == null) {
                if (d != ".class") error("Ожидалась директива .class")
                classDirective(l)
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
                ".version" -> {
                    val toks = splitWs(args(l, d))
                    if (toks.isEmpty() || toks.size > 2) error("Ожидалось: .version <major> [minor]")
                    c.majorVersion = number(toks[0], 45..0xFFFF)
                    c.minorVersion = if (toks.size == 2) number(toks[1], 0..0xFFFF) else 0
                }
                ".signature" -> c.signature = SmaliLiterals.parseString(args(l, d))
                ".sourcedebug" -> c.sourceDebugExtension = SmaliLiterals.parseString(args(l, d))
                ".attribute" -> c.rawAttributes.add(rawAttribute(l, d))
                ".annotation" -> c.annotations.add(annotation(l))
                ".typeannotation" -> c.typeAnnotations.add(typeAnnotation(l))
                ".field" -> field(c, l)
                ".method" -> method(c, l)
                ".inner" -> c.innerClasses.add(innerClass(l))
                ".nesthost" -> c.nestHost = typeArg(args(l, d))
                ".nestmember" -> c.nestMembers.add(typeArg(args(l, d)))
                ".permits" -> c.permittedSubclasses.add(typeArg(args(l, d)))
                ".record-component" -> { c.recordComponents.add(recordComponent(l)); c.isRecord = true }
                ".record" -> c.isRecord = true
                ".enclosing" -> enclosingMethod(c, l)
                ".module" -> c.module = module(l)
                else -> error("Неизвестная директива $d")
            }
        }

        private fun number(text: String, range: IntRange): Int {
            val n = try { SmaliLiterals.parseInt(text) } catch (e: NumberFormatException) { error("Некорректное число $text") }
            if (n !in range) error("Число $n вне диапазона ${range.first}..${range.last}")
            return n
        }

        /** `.attribute "Имя", "hex"` — нестандартный атрибут байт-в-байт. */
        private fun rawAttribute(l: String, d: String): SmaliRawAttribute {
            val s = SmaliScanner(l, d.length)
            val name = s.stringLiteral()
            s.expect(',')
            val hex = s.stringLiteral()
            s.expectEnd()
            if (hex.length % 2 != 0) error("Нечётное число шестнадцатеричных цифр в .attribute")
            val data = ByteArray(hex.length / 2) { i ->
                val hi = Character.digit(hex[2 * i], 16)
                val lo = Character.digit(hex[2 * i + 1], 16)
                if (hi < 0 || lo < 0) error("Некорректная шестнадцатеричная цифра в .attribute")
                ((hi shl 4) or lo).toByte()
            }
            return SmaliRawAttribute(name, data)
        }

        private fun typeArg(a: String): String {
            val s = SmaliScanner(a)
            val t = s.type()
            s.expectEnd()
            return t
        }

        private fun classDirective(l: String) {
            val tokens = splitWs(args(l, ".class"))
            if (tokens.isEmpty()) error("Нет дескриптора класса")
            val desc = tokens.last()
            if (!desc.startsWith("L") || !desc.endsWith(";")) error("Некорректный дескриптор класса $desc")
            cls = SmaliClass(desc).apply { accessFlags.addAll(tokens.subList(0, tokens.size - 1)) }
        }

        // ---- поля ----

        private fun field(c: SmaliClass, l: String) {
            val rest = args(l, ".field")
            var i = 0
            val flags = ArrayList<String>()
            var nameType: String? = null
            while (i < rest.length) {
                while (i < rest.length && rest[i].isWhitespace()) i++
                val st = i
                var quoted = false
                var colon = false
                while (i < rest.length && (quoted || (!rest[i].isWhitespace() && rest[i] != '='))) {
                    if (rest[i] == '`') quoted = !quoted
                    else if (rest[i] == ':' && !quoted) colon = true
                    i++
                }
                if (i == st) break
                val tok = rest.substring(st, i)
                if (colon) { nameType = tok; break }
                flags.add(tok)
            }
            if (nameType == null) error("Ожидалось имя:тип поля")
            val ns = SmaliScanner(nameType)
            val name = try { ns.memberName() } catch (e: IllegalArgumentException) { error("Пустое имя поля") }
            ns.expect(':')
            val type = ns.type()
            if (!ns.atEnd()) error("Лишний текст в имени:типе поля $nameType")
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
            c.fields.add(f)

            var signature: String? = null
            val anns = ArrayList<SmaliAnnotation>()
            val typeAnns = ArrayList<SmaliTypeAnnotation>()
            while (true) {
                val p = peek() ?: break
                when (directive(p)) {
                    ".signature" -> {
                        val ll = next()!!
                        signature = SmaliLiterals.parseString(args(ll, ".signature"))
                    }
                    ".annotation" -> anns.add(annotation(next()!!))
                    ".typeannotation" -> typeAnns.add(typeAnnotation(next()!!))
                    ".attribute" -> f.rawAttributes.add(rawAttribute(next()!!, ".attribute"))
                    else -> break
                }
            }
            if (isBare(peek(), ".end field")) next()
            f.signature = signature
            f.annotations.addAll(anns)
            f.typeAnnotations.addAll(typeAnns)
        }

        // ---- аннотации ----

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

        /**
         * `.typeannotation 0xTT <target_info> [kind:arg …]`, затем `.annotation …` и `.end typeannotation`.
         * Формат target_info — как печатает SmaliWriter: `empty`, `typeparam N`, `supertype N`,
         * `typeparambound N M`, `formalparam N`, `throws N`, `localvar start:len:index …`, `catch N`,
         * `offset N`, `typearg offset N`.
         */
        private fun typeAnnotation(l: String): SmaliTypeAnnotation {
            val tokens = splitWs(args(l, ".typeannotation"))
            if (tokens.size < 2) error("Ожидалось: .typeannotation 0xTT <target_info>")
            val targetType = number(tokens[0], 0..0xFF)
            var i = 1
            fun int(): Int = if (i < tokens.size) number(tokens[i++], 0..0xFFFF) else error("Не хватает числа в target_info")
            fun keyword(k: String) { if (tokens.getOrNull(i++) != k) error("Для target_type 0x${Integer.toHexString(targetType)} ожидалось $k") }
            val info: TargetInfo = when (targetType) {
                0x00, 0x01 -> { keyword("typeparam"); TypeParameterTarget(int()) }
                0x10 -> { keyword("supertype"); SuperTypeTarget(int()) }
                0x11, 0x12 -> { keyword("typeparambound"); TypeParameterBoundTarget(int(), int()) }
                0x13, 0x14, 0x15 -> { keyword("empty"); EmptyTarget }
                0x16 -> { keyword("formalparam"); FormalParameterTarget(int()) }
                0x17 -> { keyword("throws"); ThrowsTarget(int()) }
                0x40, 0x41 -> {
                    keyword("localvar")
                    val entries = ArrayList<LocalvarTarget.LocalvarEntry>()
                    while (i < tokens.size && tokens[i].count { it == ':' } == 2) {
                        val p = tokens[i++].split(':').map { number(it, 0..0xFFFF) }
                        entries.add(LocalvarTarget.LocalvarEntry(p[0], p[1], p[2]))
                    }
                    LocalvarTarget(entries)
                }
                0x42 -> { keyword("catch"); CatchTarget(int()) }
                in 0x43..0x46 -> { keyword("offset"); OffsetTarget(int()) }
                in 0x47..0x4B -> { keyword("typearg"); TypeArgumentTarget(int(), number(tokens.getOrNull(i++) ?: error("Нет индекса аргумента"), 0..0xFF)) }
                else -> error("Неизвестный target_type 0x${Integer.toHexString(targetType)}")
            }
            val path = ArrayList<TypePathEntry>()
            while (i < tokens.size) {
                val p = tokens[i++].split(':')
                if (p.size != 2) error("Ожидался элемент type_path вида kind:arg")
                path.add(TypePathEntry(number(p[0], 0..3), number(p[1], 0..0xFF)))
            }
            val annLine = next() ?: error("Нет .annotation внутри .typeannotation")
            if (directive(annLine) != ".annotation") error("Ожидалась .annotation внутри .typeannotation")
            val ann = annotation(annLine)
            val end = next()
            if (!isBare(end, ".end typeannotation")) error("Ожидалось .end typeannotation")
            return SmaliTypeAnnotation(targetType, info, TypePath(path), ann)
        }

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

        // ---- методы ----

        private fun method(c: SmaliClass, l: String) {
            val tokens = splitWs(args(l, ".method"))
            if (tokens.isEmpty()) error("Нет сигнатуры метода")
            val sig = tokens.last()
            val s = SmaliScanner(sig)
            val (name, proto) = try {
                s.memberName() to s.proto()
            } catch (e: IllegalArgumentException) {
                error("Некорректная сигнатура метода $sig")
            }
            if (!s.atEnd()) error("Некорректная сигнатура метода $sig")
            val m = SmaliMethod(
                accessFlags = tokens.subList(0, tokens.size - 1).toMutableList(),
                name = name,
                returnType = proto.second,
                paramTypes = proto.first.toMutableList()
            )
            c.methods.add(m)
            methodBody(m)
        }

        private fun methodBody(m: SmaliMethod) {
            val isStatic = "static" in m.accessFlags
            val seenParams = HashSet<Int>()

            while (true) {
                val l = next() ?: error("Нет .end method")
                val c0 = l[0]
                if (c0 == ':') {
                    val s = SmaliScanner(l)
                    val label = s.label()
                    if (!s.atEnd()) error("Лишний текст после метки")
                    m.body.add(SmaliLabel(label))
                    continue
                }
                if (c0 != '.') {
                    var sp = 0
                    while (sp < l.length && l[sp] != ' ' && l[sp] != '\t') sp++
                    val mnemonic = if (sp == l.length) l else l.substring(0, sp)
                    if (SmaliInsnEmitter.opcodeByName(mnemonic) == null) error("Неизвестная инструкция $mnemonic")
                    m.body.add(SmaliInsn(if (mnemonic == "tableswitch" || mnemonic == "lookupswitch") switchBlock(l, mnemonic) else l))
                    continue
                }
                when (val d = directive(l)) {
                    ".end method" -> { noArgs(l, d); return }
                    ".limit" -> {
                        val toks = splitWs(args(l, d))
                        if (toks.size != 2) error("Ожидалось .limit stack N или .limit locals N")
                        val n = number(toks[1], 0..0xFFFF)
                        when (toks[0]) {
                            "stack" -> m.maxStack = n
                            "locals" -> m.maxLocals = n
                            else -> error("Неизвестный .limit ${toks[0]}")
                        }
                    }
                    ".param" -> param(m, l, d, isStatic, seenParams)
                    ".signature" -> m.signature = SmaliLiterals.parseString(args(l, d))
                    ".throws" -> for (t in args(l, d).split(',')) m.exceptions.add(typeArg(t.trim()))
                    ".annotation-default" -> m.annotationDefault = collectValue(args(l, d))
                    ".annotation" -> m.annotations.add(annotation(l))
                    ".typeannotation" -> m.typeAnnotations.add(typeAnnotation(l))
                    ".attribute" -> m.rawAttributes.add(rawAttribute(l, d))
                    ".codeattribute" -> m.rawCodeAttributes.add(rawAttribute(l, d))
                    ".line" -> m.body.add(SmaliDebug(SmaliDebugKind.LINE, l))
                    ".local" -> m.body.add(SmaliDebug(SmaliDebugKind.LOCAL, l))
                    ".end local" -> m.body.add(SmaliDebug(SmaliDebugKind.END_LOCAL, l))
                    ".catch", ".catchall" -> m.catches.add(catchDirective(l, d))
                    ".prologue", ".epilogue", ".restart local", ".source", ".registers", ".locals", ".parameter" ->
                        error("Директива $d относится к Dalvik (dsmali) и не поддерживается csmali")
                    else -> error("Неизвестная директива $d")
                }
            }
        }

        /** Многострочный switch: строки до `.end tableswitch`/`.end lookupswitch` — одна инструкция. */
        private fun switchBlock(first: String, mnemonic: String): String {
            val sb = StringBuilder(first)
            val end = ".end $mnemonic"
            while (true) {
                val l = next() ?: error("Нет $end")
                sb.append('\n').append(l)
                if (l == end) return sb.toString()
                if (l.startsWith(".")) error("Ожидалась цель $mnemonic или $end")
            }
        }

        /** `.param pN[, "имя"|null][, флаги]` + аннотации параметра до `.end param`. */
        private fun param(m: SmaliMethod, l: String, d: String, isStatic: Boolean, seen: MutableSet<Int>) {
            val s = SmaliScanner(l, d.length)
            s.skipWs()
            if (s.text.getOrNull(s.pos) != 'p') error(".param принимает только p-регистр (слот параметра)")
            s.pos++
            val start = s.pos
            while (s.pos < s.text.length && s.text[s.pos].isDigit()) s.pos++
            if (s.pos == start) error("Ожидался номер регистра после p")
            val index = paramIndex(m, isStatic, s.text.substring(start, s.pos).toInt())
            if (!seen.add(index)) error("Параметр $index описан повторно")
            if (s.tryConsume(',')) {
                val name = if (s.tryConsume("null")) null else s.stringLiteral()
                val flags = if (s.tryConsume(',')) SmaliLiterals.parseInt(s.word()) else 0
                while (m.methodParameters.size <= index) m.methodParameters.add(null)
                m.methodParameters[index] = SmaliMethodParameter(name, flags)
            }
            if (!s.atEnd()) error("Лишний текст в .param")
            val anns = trailingAnnotations()
            if (isBare(peek(), ".end param")) {
                next()
                if (anns.isNotEmpty()) m.parameterAnnotations.getOrPut(index) { mutableListOf() }.addAll(anns)
            } else {
                // Без .end param аннотации относятся к самому методу.
                m.annotations.addAll(anns)
            }
        }

        private fun noArgs(l: String, d: String) {
            if (args(l, d).isNotEmpty()) error("У $d нет аргументов")
        }

        private fun paramIndex(m: SmaliMethod, isStatic: Boolean, regNum: Int): Int {
            if (!isStatic && regNum == 0) error("p0 нестатического метода — это this, а не параметр")
            var r = if (isStatic) 0 else 1
            for ((i, t) in m.paramTypes.withIndex()) {
                if (r == regNum) return i
                r += if (t == "J" || t == "D") 2 else 1
                if (regNum < r) error("Слот указывает на вторую половину wide-параметра")
            }
            error("Слот не является слотом параметра")
        }

        private fun trailingAnnotations(): List<SmaliAnnotation> {
            val out = ArrayList<SmaliAnnotation>()
            while (true) {
                val p = peek() ?: break
                if (directive(p) != ".annotation") break
                out.add(annotation(next()!!))
            }
            return out
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

        // ---- прочее ----

        private fun innerClass(l: String): SmaliInnerClass {
            val tokens = splitWs(args(l, ".inner"))
            if (tokens.isEmpty()) error("Пустая директива .inner")
            var i = 0
            val flags = ArrayList<String>()
            while (i < tokens.size && !tokens[i].startsWith("L") && !tokens[i].startsWith("[")) {
                flags.add(tokens[i]); i++
            }
            if (i >= tokens.size) error(".inner без типа")
            val inner = tokens[i++]
            var outer: String? = null
            var innerName: String? = null
            if (i < tokens.size && tokens[i] == "of") {
                i++
                if (i < tokens.size) {
                    outer = if (tokens[i] == "null") null else tokens[i]
                    i++
                }
            }
            if (i < tokens.size && tokens[i] == "as") {
                i++
                if (i < tokens.size) innerName = tokens[i]
            }
            return SmaliInnerClass(inner, outer, innerName, flags)
        }

        private fun recordComponent(l: String): SmaliRecordComponent {
            val tokens = splitWs(args(l, ".record-component"))
            if (tokens.size != 1) error("Ожидалось: .record-component имя:тип")
            val nameType = tokens[0]
            val colon = nameType.indexOf(':')
            if (colon <= 0) error("Некорректный .record-component: $nameType")
            val name = nameType.substring(0, colon)
            val type = nameType.substring(colon + 1)
            typeArg(type)
            val rc = SmaliRecordComponent(name, type)
            while (true) {
                val p = peek() ?: break
                when (directive(p)) {
                    ".signature" -> {
                        val ll = next()!!
                        rc.signature = SmaliLiterals.parseString(args(ll, ".signature"))
                    }
                    ".annotation" -> rc.annotations.add(annotation(next()!!))
                    ".typeannotation" -> rc.typeAnnotations.add(typeAnnotation(next()!!))
                    else -> break
                }
            }
            if (isBare(peek(), ".end record-component")) next()
            return rc
        }

        private fun enclosingMethod(c: SmaliClass, l: String) {
            val a = args(l, ".enclosing")
            val arrow = a.indexOf("->")
            if (arrow < 0) {
                c.enclosingMethod = typeArg(a) to null
            } else {
                val outer = typeArg(a.substring(0, arrow).trim())
                val method = a.substring(arrow + 2).trim()
                c.enclosingMethod = outer to method
            }
        }

        private fun module(l: String): SmaliModule {
            val tokens = splitWs(args(l, ".module"))
            val name = tokens.firstOrNull() ?: error(".module без имени")
            if (tokens.size > 1) error("Лишний текст после имени модуля (версия задаётся директивой .version)")
            val m = SmaliModule(name = name)
            while (true) {
                val nxt = next() ?: error("Нет .end module")
                if (isBare(nxt, ".end module")) return m
                val d = directive(nxt)
                val a = args(nxt, d)
                when (d) {
                    ".flag" -> m.flags = SmaliLiterals.parseInt(a)
                    ".version" -> m.version = SmaliLiterals.parseString(a)
                    ".package" -> m.packages.add(a)
                    ".mainclass" -> m.mainClass = typeArg(a)
                    ".requires" -> {
                        val toks = splitWs(a)
                        if (toks.isEmpty()) error(".requires без модуля")
                        val mod = toks[0]
                        var flags = 0
                        var ver: String? = null
                        var i = 1
                        while (i < toks.size) {
                            when (toks[i]) {
                                "flag" -> { flags = SmaliLiterals.parseInt(toks[++i]) }
                                "version" -> { ver = toks[++i] }
                            }
                            i++
                        }
                        m.requires.add(SmaliModule.Requires(mod, flags, ver))
                    }
                    ".exports" -> {
                        val toks = splitWs(a)
                        if (toks.isEmpty()) error(".exports без пакета")
                        val pkg = toks[0]
                        var flags = 0
                        val to = ArrayList<String>()
                        var i = 1
                        while (i < toks.size) {
                            when (toks[i]) {
                                "flag" -> { flags = SmaliLiterals.parseInt(toks[++i]) }
                                "to" -> { for (j in i + 1 until toks.size) to.add(toks[j]); i = toks.size }
                            }
                            i++
                        }
                        m.exports.add(SmaliModule.Exports(pkg, flags, to))
                    }
                    ".opens" -> {
                        val toks = splitWs(a)
                        if (toks.isEmpty()) error(".opens без пакета")
                        val pkg = toks[0]
                        var flags = 0
                        val to = ArrayList<String>()
                        var i = 1
                        while (i < toks.size) {
                            when (toks[i]) {
                                "flag" -> { flags = SmaliLiterals.parseInt(toks[++i]) }
                                "to" -> { for (j in i + 1 until toks.size) to.add(toks[j]); i = toks.size }
                            }
                            i++
                        }
                        m.opens.add(SmaliModule.Opens(pkg, flags, to))
                    }
                    ".uses" -> m.uses.add(typeArg(a))
                    ".provides" -> {
                        val toks = splitWs(a)
                        if (toks.isEmpty()) error(".provides без сервиса")
                        val service = typeArg(toks[0])
                        val with = ArrayList<String>()
                        var i = 1
                        while (i < toks.size) {
                            if (toks[i] == "with") {
                                i++
                                while (i < toks.size) with.add(typeArg(toks[i++]))
                                break
                            }
                            i++
                        }
                        m.provides.add(SmaliModule.Provides(service, with))
                    }
                    else -> error("Неизвестная директива внутри .module: $d")
                }
            }
        }
    }
}