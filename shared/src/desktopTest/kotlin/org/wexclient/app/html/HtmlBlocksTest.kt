package org.wexclient.app.html

import org.wexclient.app.markdown.MdBlock
import org.wexclient.app.markdown.MdInline
import org.wexclient.app.markdown.MdSpan
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Страница HTML → блоки просмотра. Разметка из интернета бывает любой, падать нельзя. */
class HtmlBlocksTest {

    private fun blocks(html: String) = HtmlBlocks.render(html)

    private fun textOf(content: List<MdInline>): String = buildString {
        content.forEach { item -> if (item is MdInline.Spans) item.spans.forEach { append(it.text) } }
    }.trim()

    private fun spans(content: List<MdInline>): List<MdSpan> =
        content.filterIsInstance<MdInline.Spans>().flatMap { it.spans }

    @Test
    fun `заголовки и абзацы`() {
        val result = blocks("<h1>Привет</h1><p>Первый абзац</p><h2>Раздел</h2><p>Второй</p>")
        assertEquals(4, result.size)
        val h1 = result[0] as MdBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Привет", textOf(h1.content))
        assertEquals("Первый абзац", textOf((result[1] as MdBlock.Paragraph).content))
        assertEquals(2, (result[2] as MdBlock.Heading).level)
    }

    @Test
    fun `заголовок вкладки показывается, если своего нет`() {
        val withTitle = blocks("<html><head><title>Моя страница</title></head><body><p>текст</p></body></html>")
        assertEquals("Моя страница", textOf((withTitle[0] as MdBlock.Heading).content))
        // Если <h1> есть, дублировать не надо
        val withH1 = blocks("<html><head><title>Заголовок вкладки</title></head><body><h1>Свой</h1></body></html>")
        assertEquals("Свой", textOf((withH1[0] as MdBlock.Heading).content))
        assertEquals(1, withH1.size)
    }

    @Test
    fun `оформление текста`() {
        val result = blocks("<p>обычный <b>жирный</b> <i>наклонный</i> <s>зачёркнутый</s> <code>код</code> <mark>важное</mark></p>")
        val all = spans((result[0] as MdBlock.Paragraph).content)
        assertTrue(all.any { it.text == "жирный" && it.bold })
        assertTrue(all.any { it.text == "наклонный" && it.italic })
        assertTrue(all.any { it.text == "зачёркнутый" && it.strike })
        assertTrue(all.any { it.text == "код" && it.code })
        assertTrue(all.any { it.text == "важное" && it.highlight })
    }

    @Test
    fun `ссылки и картинки`() {
        val result = blocks(
            """<p>Смотри <a href="https://example.com/путь">сюда</a></p>
               <p><a href="/link"><img src="pic.png" alt="картинка" width="120"></a></p>"""
        )
        val link = spans((result[0] as MdBlock.Paragraph).content).first { it.text == "сюда" }
        assertEquals("https://example.com/путь", link.link)
        val picture = (result[1] as MdBlock.Paragraph).content.filterIsInstance<MdInline.Picture>().single()
        assertEquals("pic.png", picture.image.src)
        assertEquals("картинка", picture.image.alt)
        assertEquals(120, picture.image.width)
        assertEquals("/link", picture.image.link)
    }

    @Test
    fun `списки, в том числе с задачами`() {
        val result = blocks(
            """<ul><li>раз</li><li>два</li></ul>
               <ol start="3"><li>третий</li></ol>
               <ul><li><input type="checkbox" checked> сделано</li><li><input type="checkbox"> нет</li></ul>"""
        )
        val bullets = result[0] as MdBlock.ListBlock
        assertFalse(bullets.ordered)
        assertEquals(2, bullets.items.size)
        val ordered = result[1] as MdBlock.ListBlock
        assertTrue(ordered.ordered)
        assertEquals(3, ordered.start)
        val tasks = result[2] as MdBlock.ListBlock
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
    }

    @Test
    fun `список определений — термин и пояснение одним пунктом`() {
        val list = blocks("<dl><dt>Ключ</dt><dd>значение ключа</dd><dt>Второй</dt><dd>и его пояснение</dd></dl>")
            .filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(2, list.items.size)
        val first = list.items[0].blocks.filterIsInstance<MdBlock.Paragraph>()
        assertEquals("Ключ", textOf(first[0].content))
        assertTrue(spans(first[0].content).all { it.bold })
        assertEquals("значение ключа", textOf(first[1].content))
    }

    @Test
    fun `таблица с шапкой`() {
        val table = blocks(
            """<table><thead><tr><th>Имя</th><th align="right">Цена</th></tr></thead>
               <tbody><tr><td>Хлеб</td><td>40</td></tr><tr><td>Соль</td><td>15</td></tr></tbody></table>"""
        ).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Имя", "Цена"), table.header.map { textOf(it) })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Соль", "15"), table.rows[1].map { textOf(it) })
        assertEquals(org.wexclient.app.markdown.MdAlign.RIGHT, table.align[1])
    }

    @Test
    fun `код сохраняет отступы и язык`() {
        val code = blocks(
            "<pre><code class=\"language-kotlin\">fun main() {\n    println(\"привет\")\n}</code></pre>"
        ).filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertContains(code.code, "    println(\"привет\")")
    }

    @Test
    fun `цитата, черта и переносы`() {
        val result = blocks("<blockquote><p>цитата</p></blockquote><hr><p>строка<br>вторая</p>")
        val quote = result[0] as MdBlock.Quote
        assertEquals("цитата", textOf((quote.blocks[0] as MdBlock.Paragraph).content))
        assertTrue(result[1] is MdBlock.Rule)
        val paragraph = result[2] as MdBlock.Paragraph
        assertTrue(paragraph.content.any { it is MdInline.Break })
    }

    @Test
    fun `скрипты и стили не показываются`() {
        val result = blocks(
            """<style>body { color: red; }</style>
               <script>alert('опасно'); document.write("<p>подделка</p>");</script>
               <p>только это</p>"""
        )
        val text = result.filterIsInstance<MdBlock.Paragraph>().joinToString { textOf(it.content) }
        assertEquals("только это", text)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color"))
    }

    @Test
    fun `мусорная разметка не ломает разбор`() {
        val result = blocks(
            """<div><p>первый<div>второй</p></div></span></div>
               <p title="a > b">с кавычками</p>
               <ul><li>без закрытия<li>второй пункт</ul>
               <b>жирный без закрытия"""
        )
        val text = result.joinToString(" ") { block ->
            when (block) {
                is MdBlock.Paragraph -> textOf(block.content)
                is MdBlock.ListBlock -> block.items.joinToString(" ") { item ->
                    item.blocks.filterIsInstance<MdBlock.Paragraph>().joinToString(" ") { textOf(it.content) }
                }
                else -> ""
            }
        }
        assertContains(text, "первый")
        assertContains(text, "второй")
        assertContains(text, "с кавычками")
        assertContains(text, "без закрытия")
        assertContains(text, "жирный без закрытия")
    }

    @Test
    fun `сущности и пробелы`() {
        val result = blocks("<p>a &amp; b &lt;тег&gt; &quot;в кавычках&quot; &#1071; &nbsp; конец</p>")
        val text = textOf((result[0] as MdBlock.Paragraph).content)
        assertContains(text, "a & b <тег> \"в кавычках\" Я")
        // Переводы строк разметки не должны превращаться в разрывы
        val multiline = blocks("<p>первая\n   строка\n\tвторая</p>")
        assertEquals("первая строка вторая", textOf((multiline[0] as MdBlock.Paragraph).content))
    }

    @Test
    fun `пустая и не-HTML строка не роняют разбор`() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("<<<>>>").size <= 2)
        assertContains(
            blocks("просто текст без тегов").filterIsInstance<MdBlock.Paragraph>().joinToString { textOf(it.content) },
            "просто текст"
        )
    }

    @Test
    fun `встроенное видео и рамки показываются ссылкой`() {
        val result = blocks("""<iframe src="https://example.com/video"></iframe>""")
        val text = result.filterIsInstance<MdBlock.Paragraph>().joinToString { textOf(it.content) }
        assertContains(text, "встроенная страница")
        assertContains(text, "https://example.com/video")
    }

    @Test
    fun `большая страница разбирается быстро`() {
        val page = buildString {
            append("<html><body>")
            repeat(3000) { i ->
                append("<div class=\"row\"><h3>Заголовок $i</h3><p>Текст <b>$i</b> со <a href=\"/x/$i\">ссылкой</a></p></div>")
            }
            append("</body></html>")
        }
        val started = System.nanoTime()
        val result = blocks(page)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(6000, result.size)
        assertTrue(ms < 3000, "разбор занял $ms мс")
    }
}
