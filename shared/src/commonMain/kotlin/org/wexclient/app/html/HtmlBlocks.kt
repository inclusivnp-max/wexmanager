package org.wexclient.app.html

import org.wexclient.app.markdown.MdAlign
import org.wexclient.app.markdown.MdBlock
import org.wexclient.app.markdown.MdImage
import org.wexclient.app.markdown.MdInline
import org.wexclient.app.markdown.MdListItem
import org.wexclient.app.markdown.MdSpan
import org.wexclient.app.markdown.Markdown

/**
 * Страница HTML → те же блоки, которыми показывается Markdown: заголовки, абзацы, списки,
 * таблицы, код, картинки и ссылки. Оформление (CSS) и скрипты не исполняются — это спокойный
 * просмотр содержимого, а не браузер; для настоящего вида есть кнопка «В браузере».
 */
object HtmlBlocks {

    private val SKIP = setOf(
        "script", "style", "head", "meta", "link", "noscript", "template", "svg", "canvas",
        "object", "map", "area", "base", "param", "track", "col", "colgroup"
    )

    private val HEADINGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)

    /** Прозрачные блоки: сами ничего не рисуют, но разделяют абзацы. */
    private val CONTAINERS = setOf(
        "div", "section", "article", "main", "header", "footer", "nav", "aside", "figure",
        "figcaption", "details", "summary", "form", "fieldset", "address", "hgroup", "dialog"
    )

    fun render(source: String): List<MdBlock> {
        val root = HtmlParser.parse(source)
        val body = root.first("body") ?: root
        val builder = Builder()
        builder.children(body, Style())
        val blocks = builder.finish()
        val title = root.first("title")?.text()?.trim().orEmpty()
        // Заголовок вкладки показываем сверху, если своего <h1> на странице нет
        if (title.isEmpty() || blocks.any { it is MdBlock.Heading && it.level == 1 }) return blocks
        val heading = MdBlock.Heading(1, listOf(MdInline.Spans(listOf(MdSpan(title)))), Markdown.anchor(title))
        return listOf(heading) + blocks
    }

    private class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val highlight: Boolean = false,
        val link: String? = null
    )

    private class Builder {
        private val blocks = ArrayList<MdBlock>()
        private val inline = ArrayList<MdInline>()
        private val spans = ArrayList<MdSpan>()

        fun text(value: String, st: Style) {
            if (value.isEmpty()) return
            spans.add(
                MdSpan(
                    text = value,
                    bold = st.bold,
                    italic = st.italic,
                    strike = st.strike,
                    code = st.code,
                    highlight = st.highlight,
                    link = st.link
                )
            )
        }

        fun picture(image: MdImage) {
            flushSpans()
            inline.add(MdInline.Picture(image))
        }

        fun lineBreak() {
            flushSpans()
            inline.add(MdInline.Break)
        }

        private fun flushSpans() {
            if (spans.isEmpty()) return
            inline.add(MdInline.Spans(spans.toList()))
            spans.clear()
        }

        fun takeInline(): List<MdInline> {
            flushSpans()
            val result = inline.toList()
            inline.clear()
            return result
        }

        /** Накопленный текст становится абзацем. */
        fun flushParagraph() {
            val content = takeInline()
            if (content.any(::meaningful)) blocks.add(MdBlock.Paragraph(content))
        }

        fun block(block: MdBlock) {
            flushParagraph()
            blocks.add(block)
        }

        fun finish(): List<MdBlock> {
            flushParagraph()
            return blocks
        }

        fun children(element: HtmlNode.Element, st: Style) {
            for (child in element.children) node(child, this, st)
        }
    }

    private fun meaningful(inline: MdInline): Boolean = when (inline) {
        is MdInline.Spans -> inline.spans.any { it.text.isNotBlank() }
        is MdInline.Picture -> true
        MdInline.Break -> false
    }

    private fun node(node: HtmlNode, b: Builder, st: Style) {
        when (node) {
            is HtmlNode.Text -> {
                val decoded = Markdown.decodeEntities(node.text)
                // В обычном тексте переводы строк и отступы разметки не значат ничего
                val collapsed = decoded.replace(WHITESPACE, " ")
                if (collapsed.isNotBlank() || collapsed == " ") b.text(collapsed, st)
            }
            is HtmlNode.Element -> element(node, b, st)
        }
    }

    private fun element(el: HtmlNode.Element, b: Builder, st: Style) {
        when (val tag = el.tag) {
            in SKIP -> Unit
            "br" -> b.lineBreak()
            "hr" -> b.block(MdBlock.Rule)
            "img" -> image(el, b, st)
            in HEADINGS -> {
                val content = inlineOf(el, st)
                val title = plainText(content)
                b.block(MdBlock.Heading(HEADINGS.getValue(tag), content, Markdown.anchor(title)))
            }
            "p" -> {
                b.flushParagraph()
                b.children(el, st)
                b.flushParagraph()
            }
            "pre" -> {
                val code = el.first("code")
                val language = (code ?: el).attr("class").orEmpty()
                    .split(' ')
                    .firstOrNull { it.startsWith("language-") || it.startsWith("lang-") }
                    ?.substringAfter('-')
                    .orEmpty()
                val text = Markdown.decodeEntities((code ?: el).text()).trim('\n')
                b.block(MdBlock.Code(language, text))
            }
            "blockquote" -> {
                val inner = Builder()
                inner.children(el, st)
                b.block(MdBlock.Quote(inner.finish(), null))
            }
            "ul", "ol" -> b.block(list(el, st))
            "dl" -> b.block(definitions(el, st))
            "table" -> table(el, st)?.let { b.block(it) }
            "a" -> {
                val href = el.attr("href")?.trim()
                b.children(el, if (href.isNullOrEmpty()) st else Style(st.bold, st.italic, st.strike, st.code, st.highlight, href))
            }
            "b", "strong" -> b.children(el, Style(true, st.italic, st.strike, st.code, st.highlight, st.link))
            "i", "em", "cite", "var", "dfn" -> b.children(el, Style(st.bold, true, st.strike, st.code, st.highlight, st.link))
            "s", "del", "strike" -> b.children(el, Style(st.bold, st.italic, true, st.code, st.highlight, st.link))
            "code", "kbd", "samp", "tt" -> b.children(el, Style(st.bold, st.italic, st.strike, true, st.highlight, st.link))
            "mark" -> b.children(el, Style(st.bold, st.italic, st.strike, st.code, true, st.link))
            "iframe", "video", "audio", "source" -> {
                val src = el.attr("src").orEmpty()
                val what = if (tag == "iframe") "встроенная страница" else if (tag == "audio") "звук" else "видео"
                if (src.isNotEmpty()) {
                    b.block(
                        MdBlock.Paragraph(
                            listOf(MdInline.Spans(listOf(MdSpan("[$what: $src]", italic = true, link = src))))
                        )
                    )
                }
                if (tag == "video" || tag == "audio") b.children(el, st)
            }
            in CONTAINERS -> {
                b.flushParagraph()
                b.children(el, st)
                b.flushParagraph()
            }
            // span, label, button, small, sup, sub и всё незнакомое — просто содержимое
            else -> b.children(el, st)
        }
    }

    private fun image(el: HtmlNode.Element, b: Builder, st: Style) {
        val src = el.attr("src") ?: el.attr("data-src") ?: return
        if (src.isBlank()) return
        val width = el.attr("width")?.filter { it.isDigit() }?.toIntOrNull()
        b.picture(MdImage(src = src.trim(), alt = el.attr("alt").orEmpty(), link = st.link, width = width))
    }

    private fun list(el: HtmlNode.Element, st: Style): MdBlock.ListBlock {
        val items = el.children("li").ifEmpty { el.descendants().filter { it.tag == "li" }.toList() }
        val listItems = items.map { li ->
            val inner = Builder()
            inner.children(li, st)
            val checkbox = li.descendants().firstOrNull { it.tag == "input" && it.attr("type")?.lowercase() == "checkbox" }
            MdListItem(inner.finish(), checkbox?.let { it.attrs.containsKey("checked") }, null)
        }
        return MdBlock.ListBlock(
            ordered = el.tag == "ol",
            start = el.attr("start")?.toIntOrNull() ?: 1,
            items = listItems
        )
    }

    /** Список определений: термин жирным, а пояснения к нему — в том же пункте. */
    private fun definitions(el: HtmlNode.Element, st: Style): MdBlock.ListBlock {
        val items = ArrayList<MdListItem>()
        var current = ArrayList<MdBlock>()

        fun flush() {
            if (current.isNotEmpty()) {
                items.add(MdListItem(current.toList(), null, null))
                current = ArrayList()
            }
        }

        for (child in el.children.filterIsInstance<HtmlNode.Element>()) {
            when (child.tag) {
                "dt" -> {
                    flush()
                    val inner = Builder()
                    inner.children(child, Style(true, st.italic, st.strike, st.code, st.highlight, st.link))
                    current.addAll(inner.finish())
                }
                "dd" -> {
                    val inner = Builder()
                    inner.children(child, st)
                    current.addAll(inner.finish())
                }
            }
        }
        flush()
        return MdBlock.ListBlock(ordered = false, start = 1, items = items)
    }

    private fun table(el: HtmlNode.Element, st: Style): MdBlock.Table? {
        val rows = el.descendants().filter { it.tag == "tr" }.toList()
        if (rows.isEmpty()) return null
        fun cells(row: HtmlNode.Element) = row.children.filterIsInstance<HtmlNode.Element>()
            .filter { it.tag == "td" || it.tag == "th" }
        val headerRow = rows.firstOrNull { row -> cells(row).any { it.tag == "th" } } ?: rows.first()
        val header = cells(headerRow).map { inlineOf(it, st) }
        val body = rows.filter { it !== headerRow }.map { row -> cells(row).map { inlineOf(it, st) } }
        val align = cells(headerRow).map { cell ->
            when (cell.attr("align")?.lowercase() ?: alignFromStyle(cell.attr("style"))) {
                "center" -> MdAlign.CENTER
                "right" -> MdAlign.RIGHT
                "left" -> MdAlign.LEFT
                else -> MdAlign.NONE
            }
        }
        if (header.isEmpty() && body.isEmpty()) return null
        return MdBlock.Table(header, body, align)
    }

    private fun alignFromStyle(style: String?): String? {
        val value = style?.lowercase() ?: return null
        val at = value.indexOf("text-align")
        if (at < 0) return null
        return value.substring(at).substringAfter(':').substringBefore(';').trim()
    }

    private fun inlineOf(el: HtmlNode.Element, st: Style): List<MdInline> {
        val inner = Builder()
        inner.children(el, st)
        return inner.takeInline()
    }

    private fun plainText(content: List<MdInline>): String = buildString {
        for (item in content) if (item is MdInline.Spans) item.spans.forEach { append(it.text) }
    }.trim()

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}
