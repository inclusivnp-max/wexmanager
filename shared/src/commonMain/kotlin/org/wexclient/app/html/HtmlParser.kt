package org.wexclient.app.html

/** Узел разметки: либо текст, либо элемент с детьми. */
sealed interface HtmlNode {
    class Text(val text: String) : HtmlNode
    class Element(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val children: MutableList<HtmlNode> = ArrayList()
    ) : HtmlNode {
        fun attr(name: String): String? = attrs[name]

        /** Весь текст внутри, включая вложенные элементы. */
        fun text(): String = buildString { collectText(this@Element, this) }

        fun children(tag: String): List<Element> = children.filterIsInstance<Element>().filter { it.tag == tag }

        fun first(tag: String): Element? = descendants().firstOrNull { it.tag == tag }

        fun descendants(): Sequence<Element> = sequence {
            for (child in children) {
                if (child is Element) {
                    yield(child)
                    yieldAll(child.descendants())
                }
            }
        }
    }
}

private fun collectText(node: HtmlNode, out: StringBuilder) {
    when (node) {
        is HtmlNode.Text -> out.append(node.text)
        is HtmlNode.Element -> node.children.forEach { collectText(it, out) }
    }
}

/**
 * Снисходительный разбор HTML: незакрытые теги, лишние закрывающие и мусор в атрибутах
 * не мешают — страница всё равно показывается. Скрипты и стили не выполняются: их
 * содержимое просто откладывается как текст.
 */
object HtmlParser {

    /** Теги без содержимого. */
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    /** Внутри этих тегов разметки нет — только текст до закрывающего тега. */
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")

    /** Какие открытые теги закрывает начало нового. */
    private val CLOSES = mapOf(
        "li" to setOf("li"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "tr" to setOf("tr", "td", "th"),
        "td" to setOf("td", "th"),
        "th" to setOf("td", "th"),
        "option" to setOf("option"),
        "thead" to setOf("tbody", "tfoot"),
        "tbody" to setOf("thead", "tbody", "tfoot"),
        "tfoot" to setOf("thead", "tbody")
    )

    /** Эти теги закрывают незакрытый абзац. */
    private val CLOSE_PARAGRAPH = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main",
        "nav", "ol", "p", "pre", "section", "table", "ul"
    )

    private const val MAX_NODES = 300_000
    private const val MAX_DEPTH = 100

    fun parse(source: String): HtmlNode.Element {
        val root = HtmlNode.Element("#document")
        val stack = ArrayList<HtmlNode.Element>()
        stack.add(root)
        var nodes = 0
        var i = 0

        fun top() = stack[stack.size - 1]

        fun addText(raw: String) {
            if (raw.isEmpty()) return
            if (nodes++ > MAX_NODES) return
            top().children.add(HtmlNode.Text(raw))
        }

        fun closeTag(tag: String) {
            val at = stack.indexOfLast { it.tag == tag }
            // Закрывающий тег без пары просто пропускаем
            if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
        }

        while (i < source.length) {
            val lt = source.indexOf('<', i)
            if (lt < 0) {
                addText(source.substring(i))
                break
            }
            if (lt > i) addText(source.substring(i, lt))
            // Комментарии, doctype и инструкции
            if (source.startsWith("<!--", lt)) {
                val end = source.indexOf("-->", lt + 4)
                i = if (end < 0) source.length else end + 3
                continue
            }
            if (source.startsWith("<!", lt) || source.startsWith("<?", lt)) {
                val end = source.indexOf('>', lt)
                i = if (end < 0) source.length else end + 1
                continue
            }
            val closing = source.startsWith("</", lt)
            val nameStart = lt + if (closing) 2 else 1
            if (nameStart >= source.length || !(source[nameStart].isLetter())) {
                // «<» сам по себе — это просто текст
                addText("<")
                i = lt + 1
                continue
            }
            var j = nameStart
            while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '-' || source[j] == ':')) j++
            val tag = source.substring(nameStart, j).lowercase()
            val tagEnd = findTagEnd(source, j)
            val inside = source.substring(j, tagEnd).trim()
            i = if (tagEnd < source.length) tagEnd + 1 else source.length

            if (closing) {
                closeTag(tag)
                continue
            }
            val selfClosing = inside.endsWith("/")
            val attrs = parseAttrs(if (selfClosing) inside.dropLast(1) else inside)
            // Незакрытые абзацы и пункты списка закрываются сами
            CLOSES[tag]?.let { closable -> if (top().tag in closable) closeTag(top().tag) }
            if (tag in CLOSE_PARAGRAPH && stack.any { it.tag == "p" } && top().tag == "p") closeTag("p")

            if (nodes++ > MAX_NODES) break
            val element = HtmlNode.Element(tag, attrs)
            top().children.add(element)

            if (tag in VOID || selfClosing) continue
            if (tag in RAW_TEXT) {
                val closeAt = indexOfClosing(source, tag, i)
                val raw = source.substring(i, if (closeAt < 0) source.length else closeAt)
                if (raw.isNotEmpty()) element.children.add(HtmlNode.Text(raw))
                i = if (closeAt < 0) source.length else closeAt + tag.length + 3
                continue
            }
            if (stack.size < MAX_DEPTH) stack.add(element)
        }
        return root
    }

    /** Конец тега с учётом кавычек: <a title="a > b"> не должен обрываться на «>». */
    private fun findTagEnd(source: String, from: Int): Int {
        var i = from
        var quote = ' '
        while (i < source.length) {
            val c = source[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return source.length
    }

    private fun indexOfClosing(source: String, tag: String, from: Int): Int {
        var i = from
        while (true) {
            val at = source.indexOf("</", i, ignoreCase = true)
            if (at < 0) return -1
            val rest = source.substring(at + 2)
            if (rest.startsWith(tag, ignoreCase = true)) {
                val after = rest.getOrNull(tag.length)
                if (after == null || after == '>' || after.isWhitespace()) return at
            }
            i = at + 2
        }
    }

    private fun parseAttrs(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i].isWhitespace() || text[i] == '/')) i++
            if (i >= text.length) break
            val nameStart = i
            while (i < text.length && !text[i].isWhitespace() && text[i] != '=' && text[i] != '/') i++
            val name = text.substring(nameStart, i).lowercase()
            if (name.isEmpty()) {
                i++
                continue
            }
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == '=') {
                i++
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length) {
                    out[name] = ""
                    break
                }
                val quote = text[i]
                if (quote == '"' || quote == '\'') {
                    val end = text.indexOf(quote, i + 1)
                    val value = if (end < 0) text.substring(i + 1) else text.substring(i + 1, end)
                    out[name] = org.wexclient.app.markdown.Markdown.decodeEntities(value)
                    i = if (end < 0) text.length else end + 1
                } else {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace()) i++
                    out[name] = org.wexclient.app.markdown.Markdown.decodeEntities(text.substring(start, i))
                }
            } else {
                out[name] = ""
            }
        }
        return out
    }
}
