package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Чтение и правка книг Excel и документов Word. */
class OfficeDocumentsTest {

    private fun zip(suffix: String, files: Map<String, String>): Path {
        val path = Files.createTempFile("wex-office", suffix)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, text) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    private fun book(sheetXml: String, shared: String? = null): Path = zip(
        ".xlsx",
        buildMap {
            put(
                "xl/workbook.xml",
                """<?xml version="1.0"?><workbook xmlns:r="http://x"><sheets>
                   <sheet name="Отчёт" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships>
                   <Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
            )
            if (shared != null) put("xl/sharedStrings.xml", shared)
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
    )

    // ---------------------------------------------------------------- Excel

    @Test
    fun `читает значения, общие строки и формулы`() {
        val path = book(
            sheetXml = """<?xml version="1.0"?><worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2"><v>1250</v></c><c r="B2"><f>A2*2</f><v>2500</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>Прямо в листе</t></is></c></row>
            </sheetData></worksheet>""",
            shared = """<?xml version="1.0"?><sst><si><t>Товар</t></si><si><t>Цена</t></si></sst>"""
        )

        val doc = XlsxDocument.open(path)
        val sheet = doc.sheets.single()

        assertEquals("Отчёт", sheet.name)
        assertEquals("Товар", sheet.cell(1, 1)?.text)
        assertEquals("Цена", sheet.cell(1, 2)?.text)
        assertEquals("1250", sheet.cell(2, 1)?.text)
        assertEquals("2500", sheet.cell(2, 2)?.text)
        assertEquals("=A2*2", sheet.cell(2, 2)?.formula)
        assertEquals("Прямо в листе", sheet.cell(3, 1)?.text)
        assertEquals(3, sheet.rows)
        assertEquals(2, sheet.columns)
    }

    @Test
    fun `имена столбцов переводятся в номера и обратно`() {
        assertEquals("A", XlsxDocument.columnName(1))
        assertEquals("Z", XlsxDocument.columnName(26))
        assertEquals("AA", XlsxDocument.columnName(27))
        assertEquals("BC", XlsxDocument.columnName(55))
        assertEquals(1, XlsxDocument.columnNumber("A"))
        assertEquals(27, XlsxDocument.columnNumber("AA"))
        assertEquals(12 to 3, XlsxDocument.parseRef("C12"))
    }

    @Test
    fun `правка ячейки сохраняется в книгу`() {
        val path = book(
            sheetXml = """<?xml version="1.0"?><worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c></row>
            </sheetData></worksheet>""",
            shared = """<?xml version="1.0"?><sst><si><t>Старое</t></si></sst>"""
        )

        val doc = XlsxDocument.open(path)
        doc.save(mapOf("Отчёт" to mapOf("A1" to "Новое значение")))

        val reopened = XlsxDocument.open(path)
        assertEquals("Новое значение", reopened.sheets.single().cell(1, 1)?.text)
    }

    @Test
    fun `правка добавляет ячейку и строку, которых не было`() {
        val path = book(
            sheetXml = """<?xml version="1.0"?><worksheet><sheetData>
                <row r="1"><c r="A1"><v>1</v></c></row>
            </sheetData></worksheet>"""
        )

        val doc = XlsxDocument.open(path)
        doc.save(mapOf("Отчёт" to mapOf("B1" to "рядом", "A5" to "ниже")))

        val sheet = XlsxDocument.open(path).sheets.single()
        assertEquals("рядом", sheet.cell(1, 2)?.text)
        assertEquals("ниже", sheet.cell(5, 1)?.text)
        assertEquals("1", sheet.cell(1, 1)?.text)
    }

    @Test
    fun `особые символы в значении не ломают книгу`() {
        val path = book(sheetXml = """<?xml version="1.0"?><worksheet><sheetData><row r="1"><c r="A1"><v>0</v></c></row></sheetData></worksheet>""")
        XlsxDocument.open(path).save(mapOf("Отчёт" to mapOf("A1" to "a < b & c > d")))
        assertEquals("a < b & c > d", XlsxDocument.open(path).sheets.single().cell(1, 1)?.text)
    }

    @Test
    fun `число остаётся числом, а текст текстом`() {
        val path = book(sheetXml = """<?xml version="1.0"?><worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""")
        XlsxDocument.open(path).save(mapOf("Отчёт" to mapOf("A1" to "42", "B1" to "3,5", "C1" to "12 штук")))

        val sheet = XlsxDocument.open(path).sheets.single()
        assertEquals("42", sheet.cell(1, 1)?.text)
        assertEquals("3.5", sheet.cell(1, 2)?.text)
        assertEquals("12 штук", sheet.cell(1, 3)?.text)

        // Число записано значением, а не строкой — иначе Excel сочтёт его текстом
        val xml = java.util.zip.ZipFile(path.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString()
        }
        assertTrue(xml.contains("""<c r="A1"><v>42</v></c>"""))
        assertTrue(xml.contains("""t="inlineStr""""))
    }

    // ---------------------------------------------------------------- Word

    private fun document(bodyXml: String): Path = zip(
        ".docx",
        mapOf(
            "word/document.xml" to """<?xml version="1.0"?>
                <w:document xmlns:w="http://w"><w:body>$bodyXml</w:body></w:document>""",
            "docProps/core.xml" to """<?xml version="1.0"?>
                <cp:coreProperties xmlns:cp="http://cp"><dc:title xmlns:dc="http://dc">Договор</dc:title>
                <dc:creator xmlns:dc="http://dc">Иванов</dc:creator></cp:coreProperties>"""
        )
    )

    @Test
    fun `читает абзацы, заголовки и начертание`() {
        val path = document(
            """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Заголовок</w:t></w:r></w:p>
            <w:p><w:r><w:t>Обычный текст </w:t></w:r>
                 <w:r><w:rPr><w:b/></w:rPr><w:t>жирный</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>Пункт списка</w:t></w:r></w:p>
            """
        )

        val doc = DocxDocument.open(path)
        val paragraphs = doc.blocks.filterIsInstance<DocBlock.Paragraph>()

        assertEquals(3, paragraphs.size)
        assertEquals(1, paragraphs[0].heading)
        assertEquals("Заголовок", paragraphs[0].text)
        assertEquals("Обычный текст жирный", paragraphs[1].text)
        assertTrue(paragraphs[1].runs.last().bold)
        assertTrue(!paragraphs[1].runs.first().bold)
        assertEquals(0, paragraphs[2].listLevel)
        assertEquals("Договор", doc.properties["Название"])
        assertEquals("Иванов", doc.properties["Автор"])
    }

    @Test
    fun `читает таблицу`() {
        val path = document(
            """
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>Имя</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Цена</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>Болт</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>12</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            """
        )

        val table = DocxDocument.open(path).blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertEquals("Имя", table.rows[0][0].single().text)
        assertEquals("12", table.rows[1][1].single().text)
    }

    @Test
    fun `правка абзаца сохраняется и не трогает соседние`() {
        val path = document(
            """
            <w:p><w:r><w:t>Первый</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Второй</w:t></w:r></w:p>
            <w:p><w:r><w:t>Третий</w:t></w:r></w:p>
            """
        )

        DocxDocument.open(path).save(mapOf(1 to "Второй, но переписанный"))

        val paragraphs = DocxDocument.open(path).blocks.filterIsInstance<DocBlock.Paragraph>()
        assertEquals("Первый", paragraphs[0].text)
        assertEquals("Второй, но переписанный", paragraphs[1].text)
        assertEquals("Третий", paragraphs[2].text)
        // Оформление абзаца сохраняется
        assertTrue(paragraphs[1].runs.first().bold)
    }

    @Test
    fun `перенос строки и табуляция попадают в текст`() {
        val path = document("""<w:p><w:r><w:t>Строка</w:t><w:br/><w:t>вторая</w:t><w:tab/><w:t>хвост</w:t></w:r></w:p>""")
        val text = DocxDocument.open(path).blocks.filterIsInstance<DocBlock.Paragraph>().single().text
        assertEquals("Строка\nвторая\tхвост", text)
    }

    @Test
    fun `не документ Word распознаётся по-человечески`() {
        val path = zip(".docx", mapOf("hello.txt" to "нет"))
        val error = runCatching { DocxDocument.open(path) }.exceptionOrNull()
        assertTrue(error?.message?.contains("не документ Word", ignoreCase = true) == true)
    }
}
