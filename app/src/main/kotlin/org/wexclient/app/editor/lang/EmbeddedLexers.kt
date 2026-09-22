package org.wexclient.app.editor.lang

import java.util.concurrent.ConcurrentHashMap

/**
 * Here-doc в shell: `cat <<EOF` … `EOF`. Разделитель хранится в общей таблице,
 * а в состоянии строки — только его номер.
 */
class HeredocLexer(private val inner: LineLexer) : LineLexer {
    private companion object {
        const val FLAG = 0x4000_0000
        const val DASH = 0x2000_0000
        val ids = ConcurrentHashMap<String, Int>()
        val names = ArrayList<String>()
        val start = Regex("<<(-?)\\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\\2")

        @Synchronized
        fun idOf(name: String): Int = ids.getOrPut(name) {
            names.add(name)
            names.size - 1
        }

        @Synchronized
        fun nameOf(id: Int): String = names.getOrElse(id) { "" }
    }

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        if (state and FLAG != 0) {
            val name = nameOf(state and 0xFFFFF)
            val dash = state and DASH != 0
            val probe = if (dash) line.trimStart('\t') else line
            return if (probe == name || probe.trimEnd() == name) {
                if (line.isNotEmpty()) out.add(Token(0, line.length, Tok.META))
                0
            } else {
                if (line.isNotEmpty()) out.add(Token(0, line.length, Tok.STRING))
                state
            }
        }
        val end = inner.tokenize(line, state, out)
        if (end != 0 || !line.contains("<<")) return end
        for (m in start.findAll(line)) {
            val pos = m.range.first
            // "<<" внутри строки или комментария — не here-doc; "<<<" — here-string
            if (line.startsWith("<<<", pos) || (pos > 0 && line[pos - 1] == '<')) continue
            val covering = out.lastOrNull { it.start <= pos && pos < it.end }
            if (covering != null && (covering.type == Tok.STRING || covering.type == Tok.COMMENT)) continue
            out.add(Token(pos, m.range.last + 1, Tok.META))
            val dash = if (m.groupValues[1] == "-") DASH else 0
            return FLAG or dash or idOf(m.groupValues[3])
        }
        return end
    }
}

/**
 * HTML с вложенными <script> и <style>: разметка — [XmlLexer], содержимое — JS и CSS.
 * Состояние: младшие 4 бита — XML, биты 4–5 — режим (0 — разметка, 1 — скрипт, 2 — стиль),
 * биты 6–7 — режим, который включится после «>» многострочного открывающего тега,
 * с 8-го — состояние вложенного лексера.
 */
class HtmlLexer(private val js: LineLexer, private val css: LineLexer) : LineLexer {
    private val openTag = Regex("<(script|style)(?=[\\s>/])", RegexOption.IGNORE_CASE)

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val n = line.length
        var xml = state and 0xF
        var mode = (state shr 4) and 3
        var pending = (state shr 6) and 3
        var inner = state ushr 8
        var pos = 0

        fun sub(from: Int, to: Int, lexer: LineLexer, st: Int): Int {
            if (from >= to) return st
            val tmp = ArrayList<Token>()
            val r = lexer.tokenize(line.substring(from, to), st, tmp)
            for (t in tmp) out.add(Token(t.start + from, t.end + from, t.type))
            return r
        }

        fun encode() = xml or (mode shl 4) or (pending shl 6) or (inner shl 8)

        while (true) {
            if (mode != 0) {
                val close = if (mode == 1) "</script" else "</style"
                val end = line.indexOf(close, pos, ignoreCase = true)
                val segEnd = if (end < 0) n else end
                inner = sub(pos, segEnd, if (mode == 1) js else css, inner)
                if (end < 0) return encode()
                mode = 0
                inner = 0
                xml = 0
                pos = end
            }
            if (pending != 0) {
                // Хвост многострочного <script ...>: режим включается после «>»
                val gt = line.indexOf('>', pos)
                if (gt < 0) {
                    xml = sub(pos, n, XmlLexer, xml)
                    return encode()
                }
                xml = sub(pos, gt + 1, XmlLexer, xml)
                pos = gt + 1
                val selfClosing = gt > 0 && line[gt - 1] == '/'
                mode = if (selfClosing) 0 else pending
                pending = 0
                xml = 0
                continue
            }
            var searchFrom = pos
            while (true) {
                val m = openTag.find(line, searchFrom)
                if (m == null) {
                    xml = sub(pos, n, XmlLexer, xml)
                    return encode()
                }
                val idx = m.range.first
                xml = sub(pos, idx, XmlLexer, xml)
                pos = idx
                if (xml != 0) {
                    // «<script» внутри комментария или значения атрибута
                    searchFrom = idx + 1
                    continue
                }
                val kind = if (m.groupValues[1].equals("script", ignoreCase = true)) 1 else 2
                val gt = line.indexOf('>', idx)
                if (gt < 0) {
                    xml = sub(idx, n, XmlLexer, xml)
                    pending = kind
                    return encode()
                }
                xml = sub(idx, gt + 1, XmlLexer, xml)
                pos = gt + 1
                xml = 0
                if (line[gt - 1] != '/') mode = kind
                break
            }
            if (mode == 0 && pos >= n) return encode()
        }
    }
}

/** Обычный текст: подсвечиваются только ссылки и адреса почты. */
object PlainTextLexer : LineLexer {
    private val url = Regex("\\b(?:https?|ftp|file)://[^\\s<>\"')\\]]+|\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        if (line.indexOf("://") < 0 && line.indexOf('@') < 0) return 0
        for (m in url.findAll(line)) out.add(Token(m.range.first, m.range.last + 1, Tok.LINK))
        return 0
    }
}
