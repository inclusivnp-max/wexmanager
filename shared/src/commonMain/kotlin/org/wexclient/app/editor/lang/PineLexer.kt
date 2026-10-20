package org.wexclient.app.editor.lang

/**
 * Pine Script (TradingView): общий лексер плюс особенности языка —
 * аннотации компилятора `//@version=6`, `//@param` и цвета `#RRGGBB` / `#RRGGBBAA`.
 */
class PineLexer(spec: LexSpec) : LineLexer {
    private val inner = CLikeLexer(spec)
    // «//@version=6» и «// @function …» (так пишет шаблон библиотек TradingView)
    private val annotation = Regex("^//\\s?@[A-Za-z_]+")
    // После точки — член пространства имён: input.int, color.new — не тип и не ключевое слово
    private val memberRetype = setOf(Tok.TYPE, Tok.KEYWORD, Tok.CONTROL, Tok.MODIFIER, Tok.CONSTANT, Tok.BUILTIN)
    private val color = Regex("#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9A-Za-z_])")

    override fun tokenize(line: String, state: Int, out: MutableList<Token>): Int {
        val from = out.size
        val result = inner.tokenize(line, state, out)
        var changed = false

        // //@version=6, //@function … — тег отдельным цветом, остальное как документация
        for (k in out.size - 1 downTo from) {
            val t = out[k]
            if (t.type != Tok.COMMENT) continue
            val m = annotation.find(line.substring(t.start, t.end)) ?: continue
            val tagEnd = t.start + m.range.last + 1
            out.removeAt(k)
            out.add(k, Token(t.start, tagEnd, Tok.DOC_TAG))
            if (tagEnd < t.end) out.add(k + 1, Token(tagEnd, t.end, Tok.DOC_COMMENT))
            changed = true
        }

        for (k in out.size - 1 downTo from) {
            val t = out[k]
            if (t.type !in memberRetype || t.start == 0 || line[t.start - 1] != '.') continue
            // «..» и числа вида «1.5» не трогаем
            if (t.start >= 2 && line[t.start - 2] == '.') continue
            var j = t.end
            while (j < line.length && line[j] == ' ') j++
            out.removeAt(k)
            if (j < line.length && line[j] == '(') out.add(k, Token(t.start, t.end, Tok.FUNCTION))
            changed = true
        }

        // Цвета — поверх того, что насчитал общий лексер (там это «число» и «идентификатор»)
        var m = color.find(line)
        while (m != null) {
            val s = m.range.first
            val e = m.range.last + 1
            val inText = (from until out.size).any { k ->
                val t = out[k]
                t.start <= s && s < t.end && (t.type == Tok.STRING || t.type == Tok.COMMENT ||
                    t.type == Tok.DOC_COMMENT || t.type == Tok.DOC_TAG || t.type == Tok.ESCAPE)
            }
            if (!inText) {
                for (k in out.size - 1 downTo from) {
                    val t = out[k]
                    if (t.start < e && t.end > s) out.removeAt(k)
                }
                out.add(Token(s, e, Tok.CONSTANT))
                changed = true
            }
            m = m.next()
        }

        if (changed && out.size - from > 1) {
            val sorted = out.subList(from, out.size).sortedBy { it.start }
            for (i in sorted.indices) out[from + i] = sorted[i]
        }
        return result
    }
}
