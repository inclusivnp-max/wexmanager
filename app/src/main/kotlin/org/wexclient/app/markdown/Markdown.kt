package org.wexclient.app.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterNode
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

enum class MdAlign { NONE, LEFT, CENTER, RIGHT }

/** Кусок текста с оформлением. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val highlight: Boolean = false,
    val link: String? = null,
    /** [[заметка]] Obsidian */
    val wiki: Boolean = false,
    /** #тег Obsidian */
    val tag: Boolean = false,
    val math: Boolean = false
)

/** Картинка; [link] — ссылка вокруг (значки в README). */
data class MdImage(val src: String, val alt: String, val link: String? = null, val width: Int? = null)

sealed interface MdInline {
    data class Spans(val spans: List<MdSpan>) : MdInline
    data class Picture(val image: MdImage) : MdInline
    data object Break : MdInline
}

/** Врезка Obsidian / GitHub: > [!note] Заголовок */
data class MdCallout(val kind: String, val title: String)

data class MdListItem(
    val blocks: List<MdBlock>,
    /** null — обычный пункт, иначе задача */
    val checked: Boolean?,
    /** Где в исходнике стоит « » или «x» задачи — для переключения из просмотра */
    val markerOffset: Int?
)

sealed interface MdBlock {
    data class Heading(val level: Int, val content: List<MdInline>, val anchor: String) : MdBlock
    data class Paragraph(val content: List<MdInline>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>, val callout: MdCallout?) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>, val align: List<MdAlign>) : MdBlock
    data object Rule : MdBlock
    data class Html(val content: List<MdInline>, val centered: Boolean) : MdBlock
    data class Properties(val entries: List<Pair<String, List<String>>>) : MdBlock
}

/** Markdown → блоки для просмотра: CommonMark + GitHub (таблицы, задачи, зачёркивание) + Obsidian. */
object Markdown {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
                YamlFrontMatterExtension.create()
            )
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    fun parse(text: String): List<MdBlock> {
        // %%комментарии%% Obsidian: пробелами, чтобы смещения задач не поехали
        val source = hideComments(text)
        return blocks(parser.parse(source), source, offsets = true)
    }

    private val comment = Regex("%%[\\s\\S]*?%%")

    private fun hideComments(text: String): String {
        if (!text.contains("%%")) return text
        return comment.replace(text) { m -> m.value.replace(Regex("[^\\n]"), " ") }
    }

    private fun blocks(parent: Node, src: String, offsets: Boolean): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var n = parent.firstChild
        while (n != null) {
            block(n, src, offsets)?.let(out::add)
            n = n.next
        }
        return out
    }

    private fun block(n: Node, src: String, offsets: Boolean): MdBlock? = when (n) {
        is Heading -> MdBlock.Heading(n.level, inlines(n), anchor(plainText(n)))
        is Paragraph -> inlines(n).takeIf { it.isNotEmpty() }?.let { MdBlock.Paragraph(it) }
        is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim().substringBefore(' ').substringBefore('{'), n.literal.removeSuffix("\n"))
        is IndentedCodeBlock -> MdBlock.Code("", n.literal.removeSuffix("\n"))
        is BlockQuote -> quote(n, src, offsets)
        is BulletList -> MdBlock.ListBlock(false, 1, items(n, src, offsets))
        is OrderedList -> MdBlock.ListBlock(true, n.markerStartNumber ?: 1, items(n, src, offsets))
        is ThematicBreak -> MdBlock.Rule
        is HtmlBlock -> html(n.literal)
        is TableBlock -> table(n)
        is YamlFrontMatterBlock -> properties(n)
        else -> null
    }

    // ---------- Цитаты и врезки ----------

    private val calloutHead = Regex("^\\s*\\[!([A-Za-z][\\w-]*)\\]([+-])?\\s*(.*)$")

    private fun quote(q: BlockQuote, src: String, offsets: Boolean): MdBlock {
        val lines = q.sourceSpans.map { span ->
            val raw = src.substring(span.inputIndex, (span.inputIndex + span.length).coerceAtMost(src.length))
            // Снимаем «>» одного уровня
            raw.replaceFirst(Regex("^\\s{0,3}> ?"), "")
        }
        val head = lines.firstOrNull()?.let { calloutHead.find(it) }
        if (head == null) return MdBlock.Quote(blocks(q, src, offsets), null)
        val kind = head.groupValues[1].lowercase()
        val title = head.groupValues[3].ifBlank { kind.replaceFirstChar { it.uppercase() } }
        // Тело врезки — отдельный документ: смещения задач в нём не совпадают с исходником
        val body = lines.drop(1).joinToString("\n")
        return MdBlock.Quote(blocks(parser.parse(body), body, offsets = false), MdCallout(kind, title))
    }

    // ---------- Списки ----------

    private val taskBox = Regex("\\[[ xX]\\]")

    private fun items(list: ListBlock, src: String, offsets: Boolean): List<MdListItem> {
        val out = ArrayList<MdListItem>()
        var n = list.firstChild
        while (n != null) {
            if (n is org.commonmark.node.ListItem) {
                val marker = firstDescendant(n) { it is TaskListItemMarker } as TaskListItemMarker?
                var offset: Int? = null
                if (marker != null && offsets) {
                    n.sourceSpans.firstOrNull()?.let { span ->
                        val lineEnd = src.indexOf('\n', span.inputIndex).let { if (it < 0) src.length else it }
                        taskBox.find(src.substring(span.inputIndex, lineEnd))?.let { m -> offset = span.inputIndex + m.range.first + 1 }
                    }
                }
                out.add(MdListItem(blocks(n, src, offsets), marker?.isChecked, offset))
            }
            n = n.next
        }
        return out
    }

    private fun firstDescendant(n: Node, depth: Int = 2, test: (Node) -> Boolean): Node? {
        var c = n.firstChild
        while (c != null) {
            if (test(c)) return c
            if (depth > 1) firstDescendant(c, depth - 1, test)?.let { return it }
            c = c.next
        }
        return null
    }

    // ---------- Таблицы и свойства ----------

    private fun table(t: TableBlock): MdBlock {
        val header = ArrayList<List<MdInline>>()
        val rows = ArrayList<List<List<MdInline>>>()
        val align = ArrayList<MdAlign>()
        fun cells(row: TableRow, collectAlign: Boolean): List<List<MdInline>> {
            val out = ArrayList<List<MdInline>>()
            var c = row.firstChild
            while (c != null) {
                if (c is TableCell) {
                    out.add(inlines(c))
                    if (collectAlign) {
                        align.add(
                            when (c.alignment) {
                                TableCell.Alignment.LEFT -> MdAlign.LEFT
                                TableCell.Alignment.CENTER -> MdAlign.CENTER
                                TableCell.Alignment.RIGHT -> MdAlign.RIGHT
                                else -> MdAlign.NONE
                            }
                        )
                    }
                }
                c = c.next
            }
            return out
        }
        var section = t.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    if (section is TableHead) header.addAll(cells(row, collectAlign = true)) else rows.add(cells(row, false))
                }
                row = row.next
            }
            section = section.next
        }
        return MdBlock.Table(header, rows, align)
    }

    private fun properties(b: YamlFrontMatterBlock): MdBlock? {
        val entries = ArrayList<Pair<String, List<String>>>()
        var n = b.firstChild
        while (n != null) {
            if (n is YamlFrontMatterNode) entries.add(n.key to n.values.map { it.trim().trim('"', '\'') })
            n = n.next
        }
        return if (entries.isEmpty()) null else MdBlock.Properties(entries)
    }

    // ---------- Строки ----------

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null
    )

    private class InlineBuilder {
        val out = ArrayList<MdInline>()
        private val spans = ArrayList<MdSpan>()

        fun span(s: MdSpan) {
            if (s.text.isEmpty()) return
            val last = spans.lastOrNull()
            // Соседние куски с одинаковым оформлением — одним
            if (last != null && last.copy(text = "") == s.copy(text = "")) spans[spans.size - 1] = last.copy(text = last.text + s.text)
            else spans.add(s)
        }

        fun flush() {
            if (spans.isNotEmpty()) {
                out.add(MdInline.Spans(spans.toList()))
                spans.clear()
            }
        }

        fun picture(image: MdImage) {
            flush()
            out.add(MdInline.Picture(image))
        }

        fun lineBreak() {
            flush()
            out.add(MdInline.Break)
        }

        fun build(): List<MdInline> {
            flush()
            // Пробелы по краям абзаца не нужны
            return out.filterNot { it is MdInline.Spans && it.spans.all { s -> s.text.isBlank() } }
                .dropWhile { it is MdInline.Break }.dropLastWhile { it is MdInline.Break }
        }
    }

    private fun inlines(parent: Node): List<MdInline> {
        val b = InlineBuilder()
        walk(parent, Style(), b)
        return b.build()
    }

    private fun walk(n: Node, st: Style, out: InlineBuilder) {
        var c = n.firstChild
        while (c != null) {
            when (c) {
                is Text -> extras(c.literal, st, out)
                is Code -> out.span(MdSpan(c.literal, code = true, link = st.link))
                is Emphasis -> walk(c, st.copy(italic = true), out)
                is StrongEmphasis -> walk(c, st.copy(bold = true), out)
                is Strikethrough -> walk(c, st.copy(strike = true), out)
                is Link -> walk(c, st.copy(link = c.destination), out)
                is Image -> out.picture(MdImage(c.destination, plainText(c), st.link))
                is SoftLineBreak -> out.span(MdSpan(" ", st.bold, st.italic, st.strike, link = st.link))
                is HardLineBreak -> out.lineBreak()
                is HtmlInline -> htmlInline(c.literal, st, out)
                is TaskListItemMarker -> Unit
                else -> walk(c, st, out)
            }
            c = c.next
        }
    }

    // ==выделение==, [[заметка|подпись]], #тег, $формула$
    private val extra = Regex("==([^=\\n]+)==|\\[\\[([^\\]|\\n]+)(?:\\|([^\\]\\n]+))?\\]\\]|(?<![\\w#/&])#([\\p{L}_][\\p{L}\\p{N}_/-]*)|(?<![\\\\$\\w])\\$([^$\\n]+?)\\$(?![\\w$])")

    private fun extras(text: String, st: Style, out: InlineBuilder) {
        fun plain(t: String) = out.span(MdSpan(t, st.bold, st.italic, st.strike, link = st.link))
        var pos = 0
        for (m in extra.findAll(text)) {
            if (m.range.first > pos) plain(text.substring(pos, m.range.first))
            val base = MdSpan("", st.bold, st.italic, st.strike, link = st.link)
            when {
                m.groups[1] != null -> out.span(base.copy(text = m.groupValues[1], highlight = true))
                m.groups[2] != null -> {
                    val target = m.groupValues[2].trim()
                    val label = m.groups[3]?.value?.trim() ?: target.substringAfterLast('/').substringBefore('#')
                    out.span(base.copy(text = label, wiki = true, link = st.link ?: "wiki:$target"))
                }
                m.groups[4] != null -> out.span(base.copy(text = "#" + m.groupValues[4], tag = true))
                else -> out.span(base.copy(text = m.groupValues[5], math = true))
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) plain(text.substring(pos))
    }

    private fun plainText(n: Node): String {
        val sb = StringBuilder()
        fun go(x: Node) {
            var c = x.firstChild
            while (c != null) {
                when (c) {
                    is Text -> sb.append(c.literal)
                    is Code -> sb.append(c.literal)
                    is SoftLineBreak, is HardLineBreak -> sb.append(' ')
                    else -> go(c)
                }
                c = c.next
            }
        }
        go(n)
        return sb.toString()
    }

    /** Якорь заголовка, как на GitHub: строчные, пробелы → «-», без знаков. */
    fun anchor(title: String): String =
        title.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s"), "-")

    // ---------- HTML ----------

    private val tagRe = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--[\\s\\S]*?-->")
    private val attrRe = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val breakTags = setOf("br", "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "summary", "details", "center")

    private fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1].lowercase() to decodeEntities(it.groupValues[2].trim('"', '\'')) }

    private fun html(literal: String): MdBlock? {
        val out = InlineBuilder()
        htmlInline(literal, Style(), out, blockLevel = true)
        val content = out.build()
        if (content.isEmpty()) return null
        val centered = Regex("(?i)align\\s*=\\s*[\"']?center|<center").containsMatchIn(literal)
        return MdBlock.Html(content, centered)
    }

    private fun htmlInline(literal: String, st: Style, out: InlineBuilder, blockLevel: Boolean = false) {
        var style = st
        val stack = ArrayList<Style>()
        var pos = 0
        for (m in tagRe.findAll(literal)) {
            if (m.range.first > pos) {
                val text = decodeEntities(literal.substring(pos, m.range.first))
                val clean = if (blockLevel) text.replace(Regex("\\s+"), " ") else text
                if (clean.isNotBlank() || !blockLevel) out.span(MdSpan(clean, style.bold, style.italic, style.strike, link = style.link))
            }
            pos = m.range.last + 1
            if (m.value.startsWith("<!--")) continue
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val a = attrs(m.groupValues[3])
            when {
                tag == "img" -> a["src"]?.let { src ->
                    out.picture(MdImage(src, a["alt"].orEmpty(), style.link, a["width"]?.removeSuffix("px")?.toIntOrNull()))
                }
                tag == "br" -> out.lineBreak()
                closing -> {
                    if (stack.isNotEmpty() && tag in setOf("a", "b", "strong", "i", "em", "s", "del", "strike", "h1", "h2", "h3", "h4", "h5", "h6")) {
                        style = stack.removeAt(stack.size - 1)
                    }
                    if (blockLevel && tag in breakTags) out.lineBreak()
                }
                tag == "a" -> {
                    stack.add(style)
                    style = style.copy(link = a["href"] ?: style.link)
                }
                tag in setOf("b", "strong", "h1", "h2", "h3", "h4", "h5", "h6") -> {
                    stack.add(style)
                    style = style.copy(bold = true)
                }
                tag in setOf("i", "em") -> {
                    stack.add(style)
                    style = style.copy(italic = true)
                }
                tag in setOf("s", "del", "strike") -> {
                    stack.add(style)
                    style = style.copy(strike = true)
                }
            }
        }
        if (pos < literal.length) {
            val text = decodeEntities(literal.substring(pos))
            val clean = if (blockLevel) text.replace(Regex("\\s+"), " ") else text
            if (clean.isNotBlank() || !blockLevel) out.span(MdSpan(clean, style.bold, style.italic, style.strike, link = style.link))
        }
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "copy" to "©", "mdash" to "—", "ndash" to "–", "hellip" to "…")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                // &#1114112; и подобные — не символ: оставляем как написано, а не падаем
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.takeIf(::isCharCode)?.let { String(Character.toChars(it)) }
                v.startsWith("#") -> v.substring(1).toIntOrNull()?.takeIf(::isCharCode)?.let { String(Character.toChars(it)) }
                else -> entities[v]
            } ?: m.value
        }
    }

    private fun isCharCode(cp: Int): Boolean = Character.isValidCodePoint(cp) && cp != 0 && cp !in 0xD800..0xDFFF

    /** Переключить задачу [offset] (символ между скобками) в исходнике. */
    fun toggleTask(text: String, offset: Int): String {
        if (offset !in text.indices) return text
        val c = text[offset]
        val next = if (c == ' ') 'x' else if (c == 'x' || c == 'X') ' ' else return text
        return text.substring(0, offset) + next + text.substring(offset + 1)
    }
}
