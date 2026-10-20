package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OpenDocument, презентации и выгрузка в CSV и Markdown. */
class OtherFormatsTest {

    private fun zip(suffix: String, files: Map<String, String>): Path {
        val path = Files.createTempFile("wex-other", suffix)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, text) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `документ LibreOffice читается с заголовками, списком и жирным`() {
        val path = zip(
            ".odt",
            mapOf(
                "content.xml" to """<?xml version="1.0"?>
                <office:document-content xmlns:office="o" xmlns:text="t" xmlns:style="s" xmlns:fo="f" xmlns:table="tb">
                  <office:automatic-styles>
                    <style:style style:name="T1"><style:text-properties fo:font-weight="bold"/></style:style>
                  </office:automatic-styles>
                  <office:body><office:text>
                    <text:h text:outline-level="1">Отчёт</text:h>
                    <text:p>Обычный <text:span text:style-name="T1">жирный</text:span> текст</text:p>
                    <text:list><text:list-item><text:p>Пункт</text:p></text:list-item></text:list>
                    <table:table><table:table-row>
                      <table:table-cell><text:p>А</text:p></table:table-cell>
                      <table:table-cell><text:p>Б</text:p></table:table-cell>
                    </table:table-row></table:table>
                  </office:text></office:body>
                </office:document-content>"""
            )
        )

        val doc = DocxDocument.openAny(path)
        assertTrue(doc.readOnly)
        assertEquals("ODT", doc.format)
        val paragraphs = doc.blocks.filterIsInstance<DocBlock.Paragraph>()
        assertEquals(1, paragraphs[0].heading)
        assertEquals("Отчёт", paragraphs[0].text)
        assertEquals("Обычный жирный текст", paragraphs[1].text)
        assertTrue(paragraphs[1].runs.any { it.bold && it.text == "жирный" })
        assertEquals(0, paragraphs[2].listLevel)
        val table = doc.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals("Б", table.rows[0][1].single().text)
    }

    @Test
    fun `презентация читается по слайдам`() {
        val slide = { title: String, body: String ->
            """<?xml version="1.0"?><p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree>
               <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>
               <p:sp><p:txBody><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>
               </p:spTree></p:cSld></p:sld>"""
        }
        val path = zip(
            ".pptx",
            mapOf(
                "ppt/slides/slide1.xml" to slide("Введение", "Зачем это всё"),
                "ppt/slides/slide2.xml" to slide("Итоги", "Всё получилось")
            )
        )

        val doc = DocxDocument.openAny(path)
        assertEquals("PPTX", doc.format)
        val texts = doc.blocks.filterIsInstance<DocBlock.Paragraph>().map { it.text }
        assertEquals(listOf("Слайд 1", "Введение", "Зачем это всё", "Слайд 2", "Итоги", "Всё получилось"), texts)
    }

    @Test
    fun `таблица LibreOffice читается с повторами ячеек`() {
        val path = zip(
            ".ods",
            mapOf(
                "content.xml" to """<?xml version="1.0"?>
                <office:document-content xmlns:office="o" xmlns:table="tb" xmlns:text="t">
                  <office:body><office:spreadsheet>
                    <table:table table:name="Данные">
                      <table:table-row>
                        <table:table-cell><text:p>Имя</text:p></table:table-cell>
                        <table:table-cell table:number-columns-repeated="2"><text:p>x</text:p></table:table-cell>
                      </table:table-row>
                      <table:table-row>
                        <table:table-cell office:value="42"><text:p>42</text:p></table:table-cell>
                      </table:table-row>
                    </table:table>
                  </office:spreadsheet></office:body>
                </office:document-content>"""
            )
        )

        val doc = XlsxDocument.openAny(path)
        assertTrue(doc.readOnly)
        val sheet = doc.sheets.single()
        assertEquals("Данные", sheet.name)
        assertEquals("Имя", sheet.cell(1, 1)?.text)
        assertEquals("x", sheet.cell(1, 2)?.text)
        assertEquals("x", sheet.cell(1, 3)?.text)
        assertEquals("42", sheet.cell(2, 1)?.text)
    }

    @Test
    fun `выгрузка в CSV экранирует разделитель и кавычки`() {
        val path = zip(
            ".xlsx",
            mapOf(
                "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns:r="r"><sheets><sheet name="Л" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet><sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>а;б</t></is></c><c r="B1" t="inlineStr"><is><t>"цитата"</t></is></c></row>
                    <row r="2"><c r="A2"><v>1</v></c></row>
                </sheetData></worksheet>"""
            )
        )
        val doc = XlsxDocument.open(path)
        val sheet = doc.sheets.single()
        val csv = doc.toCsv(sheet.cells, sheet.rows, sheet.columns)
        assertEquals("\"а;б\";\"\"\"цитата\"\"\"\r\n1;\r\n", csv)
    }

    @Test
    fun `документ выгружается в Markdown`() {
        val path = zip(
            ".docx",
            mapOf(
                "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Раздел</w:t></w:r></w:p>
                    <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Важно</w:t></w:r><w:r><w:t> и обычно</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>Пункт</w:t></w:r></w:p>
                </w:body></w:document>"""
            )
        )
        val md = DocxDocument.open(path).markdown
        assertTrue(md.contains("## Раздел"))
        assertTrue(md.contains("**Важно** и обычно"))
        assertTrue(md.contains("- Пункт"))
    }
}
