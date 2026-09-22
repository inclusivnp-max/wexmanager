package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Перестройка листа: вставка строк и столбцов, новый лист. */
class SheetEditTest {

    private fun book(): Path {
        val path = Files.createTempFile("wex-sheet", ".xlsx")
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://ct"></Types>""")
            put(
                "xl/workbook.xml",
                """<?xml version="1.0"?><workbook xmlns:r="http://x"><sheets>
                   <sheet name="Лист1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
            )
            put(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0"?><worksheet><sheetData>
                   <row r="1"><c r="A1" s="3" t="inlineStr"><is><t>Имя</t></is></c><c r="B1"><v>10</v></c></row>
                   <row r="2"><c r="A2" t="inlineStr"><is><t>Болт</t></is></c><c r="B2"><v>20</v></c></row>
                </sheetData></worksheet>"""
            )
        }
        return path
    }

    @Test
    fun `перезапись листа сохраняет оформление и типы`() {
        val path = book()
        val doc = XlsxDocument.open(path)
        val sheet = doc.sheets.single()
        val cells = sheet.cells.toMutableMap()
        cells["A3"] = XlsxCell("A3", 3, 1, "Гайка")
        doc.saveSheets(mapOf("Лист1" to cells))

        val reopened = XlsxDocument.open(path).sheets.single()
        assertEquals("Имя", reopened.cell(1, 1)?.text)
        assertEquals("3", reopened.cell(1, 1)?.styleId)
        assertEquals("10", reopened.cell(1, 2)?.text)
        assertEquals("Гайка", reopened.cell(3, 1)?.text)
        assertEquals(3, reopened.rows)
    }

    @Test
    fun `вставка строки сдвигает всё, что ниже`() {
        val path = book()
        val doc = XlsxDocument.open(path)
        val sheet = doc.sheets.single()

        // Вставляем строку перед второй: то, что было ниже, уезжает на строку вниз
        val moved = sheet.cells.values.associate { cell ->
            if (cell.row >= 2) {
                val ref = XlsxDocument.ref(cell.row + 1, cell.column)
                ref to cell.copy(ref = ref, row = cell.row + 1)
            } else {
                cell.ref to cell
            }
        }
        doc.saveSheets(mapOf("Лист1" to moved))

        val reopened = XlsxDocument.open(path).sheets.single()
        assertEquals("Имя", reopened.cell(1, 1)?.text)
        assertEquals(null, reopened.cell(2, 1))
        assertEquals("Болт", reopened.cell(3, 1)?.text)
        assertEquals("20", reopened.cell(3, 2)?.text)
    }

    @Test
    fun `новый лист появляется в книге`() {
        val path = book()
        XlsxDocument.open(path).addSheet("Расчёты")

        val doc = XlsxDocument.open(path)
        assertEquals(listOf("Лист1", "Расчёты"), doc.sheets.map { it.name })
        assertTrue(doc.sheets.last().cells.isEmpty())
    }

    @Test
    fun `формула остаётся формулой`() {
        val path = book()
        val doc = XlsxDocument.open(path)
        val cells = doc.sheets.single().cells.toMutableMap()
        cells["C1"] = XlsxCell("C1", 1, 3, "", formula = "=B1*2")
        doc.saveSheets(mapOf("Лист1" to cells))

        assertEquals("=B1*2", XlsxDocument.open(path).sheets.single().cell(1, 3)?.formula)
    }
}
