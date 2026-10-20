package org.wexclient.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTest {
    private fun spans(inlines: List<MdInline>): List<MdSpan> = inlines.filterIsInstance<MdInline.Spans>().flatMap { it.spans }

    @Test
    fun `headings, emphasis and links`() {
        val blocks = Markdown.parse(
            """
            # Привет, мир!

            Текст с **жирным**, *курсивом*, ~~зачёркнутым~~, `кодом` и [ссылкой](https://example.com).
            Вторая строка абзаца.
            """.trimIndent()
        )
        val h = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals(1, h.level)
        assertEquals("привет-мир", h.anchor)
        val p = assertIs<MdBlock.Paragraph>(blocks[1])
        val s = spans(p.content)
        assertTrue(MdSpan("жирным", bold = true) in s, s.toString())
        assertTrue(MdSpan("курсивом", italic = true) in s)
        assertTrue(MdSpan("зачёркнутым", strike = true) in s)
        assertTrue(MdSpan("кодом", code = true) in s)
        assertTrue(MdSpan("ссылкой", link = "https://example.com") in s)
        // Мягкий перенос — пробел, соседний простой текст склеен
        assertTrue(s.any { it.text.endsWith(". Вторая строка абзаца.") }, s.toString())
    }

    @Test
    fun `lists, tasks and toggling`() {
        val text = "- [ ] купить хлеб\n- [x] написать тест\n- обычный пункт\n\n3. три\n4. четыре\n"
        val blocks = Markdown.parse(text)
        val list = assertIs<MdBlock.ListBlock>(blocks[0])
        assertEquals(listOf(false, true, null), list.items.map { it.checked })
        val first = list.items[0]
        assertEquals(' ', text[first.markerOffset!!])
        assertEquals('x', text[list.items[1].markerOffset!!])
        assertNull(list.items[2].markerOffset)
        val toggled = Markdown.toggleTask(text, first.markerOffset!!)
        assertTrue(toggled.startsWith("- [x] купить хлеб"))
        assertEquals(text, Markdown.toggleTask(toggled, first.markerOffset!!))
        assertEquals(listOf("купить хлеб"), list.items[0].blocks.map { spans((it as MdBlock.Paragraph).content).joinToString("") { s -> s.text } })
        val ordered = assertIs<MdBlock.ListBlock>(blocks[1])
        assertTrue(ordered.ordered)
        assertEquals(3, ordered.start)
    }

    @Test
    fun `tables, code, callouts and properties`() {
        val blocks = Markdown.parse(
            """
            ---
            title: Заметка
            tags:
              - wex
              - android
            ---

            | Имя | Размер | Тип |
            |:----|-------:|:---:|
            | a.txt | 1 Б | **текст** |

            ```kotlin
            fun main() = println("hi")
            ```

            > [!warning] Осторожно
            > Удаление **безвозвратно**.

            > Обычная цитата
            """.trimIndent()
        )
        val props = assertIs<MdBlock.Properties>(blocks[0])
        assertEquals(listOf("title" to listOf("Заметка"), "tags" to listOf("wex", "android")), props.entries)
        val table = assertIs<MdBlock.Table>(blocks[1])
        assertEquals(listOf(MdAlign.LEFT, MdAlign.RIGHT, MdAlign.CENTER), table.align)
        assertEquals("Размер", spans(table.header[1]).single().text)
        assertEquals(MdSpan("текст", bold = true), spans(table.rows[0][2]).single())
        val code = assertIs<MdBlock.Code>(blocks[2])
        assertEquals("kotlin", code.language)
        assertEquals("fun main() = println(\"hi\")", code.code)
        val callout = assertIs<MdBlock.Quote>(blocks[3])
        assertEquals(MdCallout("warning", "Осторожно"), callout.callout)
        assertTrue(MdSpan("безвозвратно", bold = true) in spans((callout.blocks.single() as MdBlock.Paragraph).content))
        val plainQuote = assertIs<MdBlock.Quote>(blocks[4])
        assertNull(plainQuote.callout)
    }

    @Test
    fun `obsidian syntax`() {
        val p = assertIs<MdBlock.Paragraph>(
            Markdown.parse("Это ==важно== см. [[Проекты/WEX#Сборка|сборку]] и [[Заметка]] #wex/desktop, формула ${'$'}x^2${'$'}, цена 5$ %%скрыто%% конец").single()
        )
        val s = spans(p.content)
        assertTrue(MdSpan("важно", highlight = true) in s, s.toString())
        assertTrue(MdSpan("сборку", wiki = true, link = "wiki:Проекты/WEX#Сборка") in s)
        assertTrue(MdSpan("Заметка", wiki = true, link = "wiki:Заметка") in s)
        assertTrue(MdSpan("#wex/desktop", tag = true) in s)
        assertTrue(MdSpan("x^2", math = true) in s)
        val all = s.joinToString("") { it.text }
        assertTrue("скрыто" !in all && "цена 5$" in all && all.endsWith("конец"), all)
    }

    @Test
    fun `html blocks and badges from readme files`() {
        val blocks = Markdown.parse(
            """
            <p align="center">
              <a href="https://wex.dev"><img src="docs/logo.png" alt="WEX" width="120"></a><br>
              <b>Файловый менеджер</b> &amp; редактор
            </p>

            [![build](https://img.shields.io/badge/build-ok-green.svg)](https://ci.example) текст
            """.trimIndent()
        )
        val html = assertIs<MdBlock.Html>(blocks[0])
        assertTrue(html.centered)
        val picture = html.content.filterIsInstance<MdInline.Picture>().single().image
        assertEquals(MdImage("docs/logo.png", "WEX", "https://wex.dev", 120), picture)
        val text = spans(html.content)
        assertTrue(MdSpan("Файловый менеджер", bold = true) in text, text.toString())
        assertTrue(text.any { it.text.contains("& редактор") })
        val badge = assertIs<MdBlock.Paragraph>(blocks[1]).content
        assertEquals(MdImage("https://img.shields.io/badge/build-ok-green.svg", "build", "https://ci.example"), (badge[0] as MdInline.Picture).image)
        assertEquals("Ok &x", Markdown.decodeEntities("Ok &amp;x"))
        assertEquals("—", Markdown.decodeEntities("&#8212;"))
        // Не символы — остаются как написаны
        assertEquals("&#1114112;", Markdown.decodeEntities("&#1114112;"))
        assertEquals("&#x110000;", Markdown.decodeEntities("&#x110000;"))
        assertEquals("&#xD800;", Markdown.decodeEntities("&#xD800;"))
        assertEquals("&#99999999999;", Markdown.decodeEntities("&#99999999999;"))
        Markdown.parse("<b>&#1114112;</b>\n\n12345678901. пункт\n")
    }
}
