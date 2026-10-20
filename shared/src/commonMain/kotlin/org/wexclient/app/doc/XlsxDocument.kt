package org.wexclient.app.doc

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Ячейка таблицы: что показывать и что лежит внутри. */
data class XlsxCell(
    val ref: String,
    val row: Int,
    val column: Int,
    val text: String,
    val formula: String? = null,
    /** Номер оформления из styles.xml — при перезаписи листа его надо вернуть на место. */
    val styleId: String? = null
)

/** Лист книги. Строки и столбцы — с единицы, как в самом Excel. */
data class XlsxSheet(
    val name: String,
    val part: String,
    val cells: Map<String, XlsxCell>,
    val rows: Int,
    val columns: Int
) {
    fun cell(row: Int, column: Int): XlsxCell? = cells[XlsxDocument.ref(row, column)]
}

/**
 * Книга Excel без сторонних библиотек: xlsx — это zip с XML внутри,
 * а StAX из JDK читает их быстро и без лишней памяти.
 */
class XlsxDocument private constructor(
    val path: Path,
    val sheets: List<XlsxSheet>,
    /** Открыт из ods: показываем и выгружаем в CSV, но назад не пишем. */
    val readOnly: Boolean = false,
    val format: String = "XLSX"
) {
    val totalCells: Int get() = sheets.sumOf { it.cells.size }

    companion object {

        /** `A1`, `BC12` — буквенно-цифровая ссылка на ячейку. */
        fun ref(row: Int, column: Int): String = columnName(column) + row

        /** 1 → A, 27 → AA. */
        fun columnName(column: Int): String {
            var n = column
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append(('A' + rem))
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        /** A → 1, AA → 27. */
        fun columnNumber(name: String): Int {
            var result = 0
            for (ch in name) {
                if (!ch.isLetter()) break
                result = result * 26 + (ch.uppercaseChar() - 'A' + 1)
            }
            return result
        }

        /** Разбор ссылки `B7` на строку и столбец. */
        fun parseRef(ref: String): Pair<Int, Int> {
            val letters = ref.takeWhile { it.isLetter() }
            val digits = ref.dropWhile { it.isLetter() }.takeWhile { it.isDigit() }
            return (digits.toIntOrNull() ?: 0) to columnNumber(letters)
        }

        fun looksLikeXlsx(name: String): Boolean =
            name.endsWith(".xlsx", ignoreCase = true) || name.endsWith(".xlsm", ignoreCase = true)

        fun looksLikeOds(name: String): Boolean = name.endsWith(".ods", ignoreCase = true)

        fun supports(name: String): Boolean = looksLikeXlsx(name) || looksLikeOds(name)

        fun openAny(path: Path): XlsxDocument =
            if (looksLikeOds(path.fileName.toString())) openOds(path) else open(path)

        /**
         * Таблица LibreOffice: content.xml с table:table-row и table:table-cell.
         * Повторы (number-columns-repeated, number-rows-repeated) разворачиваются,
         * но хвост пустых ячеек до конца листа не раздуваем.
         */
        fun openOds(path: Path): XlsxDocument {
            ZipFile(path.toFile()).use { zip ->
                val content = zip.entry("content.xml")
                    ?: throw IllegalArgumentException("Это не таблица OpenDocument: внутри нет content.xml")
                val reader = openXmlReader(ByteArrayInputStream(content))
                val sheets = ArrayList<XlsxSheet>()
                var sheetName = ""
                var cells = LinkedHashMap<String, XlsxCell>()
                var row = 0
                var column = 0
                var rowRepeat = 1
                var cellRepeat = 1
                var formula: String? = null
                var value: String? = null
                val text = StringBuilder()
                var inCell = false
                var maxRow = 0
                var maxColumn = 0

                fun attr(name: String): String? = (0 until reader.attributeCount)
                    .firstOrNull { reader.getAttributeLocalName(it) == name }
                    ?.let { reader.getAttributeValue(it) }

                while (reader.hasNext()) {
                    when (reader.next()) {
                        XmlEvents.START_ELEMENT -> when (reader.localName) {
                            "table" -> {
                                sheetName = attr("name") ?: "Лист ${sheets.size + 1}"
                                cells = LinkedHashMap()
                                row = 0
                                maxRow = 0
                                maxColumn = 0
                            }
                            "table-row" -> {
                                rowRepeat = attr("number-rows-repeated")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                                row++
                                column = 0
                            }
                            "table-cell", "covered-table-cell" -> {
                                cellRepeat = attr("number-columns-repeated")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                                formula = attr("formula")?.substringAfter(":")
                                value = attr("value") ?: attr("date-value") ?: attr("boolean-value")
                                text.setLength(0)
                                inCell = true
                            }
                            "p" -> if (inCell && text.isNotEmpty()) text.append('\n')
                        }
                        XmlEvents.CHARACTERS -> if (inCell) text.append(reader.text)
                        XmlEvents.END_ELEMENT -> when (reader.localName) {
                            "table-cell", "covered-table-cell" -> {
                                val shown = text.toString().ifEmpty { value.orEmpty() }
                                repeat(cellRepeat) {
                                    column++
                                    if (shown.isNotEmpty() || formula != null) {
                                        val ref = ref(row, column)
                                        cells[ref] = XlsxCell(ref, row, column, shown, formula?.let { "=$it" })
                                        if (row > maxRow) maxRow = row
                                        if (column > maxColumn) maxColumn = column
                                    }
                                }
                                inCell = false
                            }
                            "table-row" -> {
                                // Повтор строки с содержимым — копируем её; пустые повторы просто пропускаем
                                if (rowRepeat > 1) {
                                    val source = cells.values.filter { it.row == row }
                                    for (extra in 1 until rowRepeat) {
                                        for (cell in source) {
                                            val ref = ref(row + extra, cell.column)
                                            cells[ref] = cell.copy(ref = ref, row = row + extra)
                                        }
                                        if (source.isNotEmpty()) maxRow = maxOf(maxRow, row + extra)
                                    }
                                    row += rowRepeat - 1
                                }
                            }
                            "table" -> sheets += XlsxSheet(sheetName, "content.xml#$sheetName", cells, maxRow, maxColumn)
                        }
                    }
                }
                reader.close()
                if (sheets.isEmpty()) throw IllegalArgumentException("В таблице нет листов")
                return XlsxDocument(path, sheets, readOnly = true, format = "ODS")
            }
        }

        fun open(path: Path): XlsxDocument {
            ZipFile(path.toFile()).use { zip ->
                val shared = zip.entry("xl/sharedStrings.xml")?.let { readSharedStrings(it) } ?: emptyList()
                val rels = zip.entry("xl/_rels/workbook.xml.rels")?.let { readRelations(it) } ?: emptyMap()
                val order = zip.entry("xl/workbook.xml")?.let { readSheetList(it) } ?: emptyList()

                val sheets = ArrayList<XlsxSheet>()
                for ((name, relId) in order) {
                    val target = rels[relId] ?: continue
                    val part = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                    val bytes = zip.entry(part) ?: continue
                    sheets += readSheet(name, part, bytes, shared)
                }
                if (sheets.isEmpty()) throw IllegalArgumentException("В книге нет листов")
                return XlsxDocument(path, sheets)
            }
        }

        private fun ZipFile.entry(name: String): ByteArray? {
            val e: ZipEntry = getEntry(name) ?: return null
            return getInputStream(e).use { it.readBytes() }
        }

        /** Общие строки: текст ячеек хранится отдельным списком, а в листе стоит номер. */
        private fun readSharedStrings(bytes: ByteArray): List<String> {
            val result = ArrayList<String>()
            val reader = openXmlReader(ByteArrayInputStream(bytes))
            val current = StringBuilder()
            var inItem = false
            var inText = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XmlEvents.START_ELEMENT -> when (reader.localName) {
                        "si" -> { inItem = true; current.setLength(0) }
                        "t" -> inText = true
                    }
                    XmlEvents.CHARACTERS -> if (inItem && inText) current.append(reader.text)
                    XmlEvents.END_ELEMENT -> when (reader.localName) {
                        "t" -> inText = false
                        "si" -> { result += current.toString(); inItem = false }
                    }
                }
            }
            reader.close()
            return result
        }

        private fun readRelations(bytes: ByteArray): Map<String, String> {
            val result = HashMap<String, String>()
            val reader = openXmlReader(ByteArrayInputStream(bytes))
            while (reader.hasNext()) {
                if (reader.next() == XmlEvents.START_ELEMENT && reader.localName == "Relationship") {
                    val id = reader.getAttributeValue(null, "Id")
                    val target = reader.getAttributeValue(null, "Target")
                    if (id != null && target != null) result[id] = target
                }
            }
            reader.close()
            return result
        }

        /** Имена листов в порядке вкладок книги. */
        private fun readSheetList(bytes: ByteArray): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            val reader = openXmlReader(ByteArrayInputStream(bytes))
            while (reader.hasNext()) {
                if (reader.next() == XmlEvents.START_ELEMENT && reader.localName == "sheet") {
                    val name = reader.getAttributeValue(null, "name") ?: continue
                    val id = (0 until reader.attributeCount)
                        .firstOrNull { reader.getAttributeLocalName(it) == "id" }
                        ?.let { reader.getAttributeValue(it) } ?: continue
                    result += name to id
                }
            }
            reader.close()
            return result
        }

        private fun readSheet(name: String, part: String, bytes: ByteArray, shared: List<String>): XlsxSheet {
            val cells = LinkedHashMap<String, XlsxCell>()
            var maxRow = 0
            var maxColumn = 0

            val reader = openXmlReader(ByteArrayInputStream(bytes))
            var ref = ""
            var type: String? = null
            var style: String? = null
            var value = StringBuilder()
            var formula: String? = null
            var inValue = false
            var inFormula = false
            var inInline = false

            while (reader.hasNext()) {
                when (reader.next()) {
                    XmlEvents.START_ELEMENT -> when (reader.localName) {
                        "c" -> {
                            ref = reader.getAttributeValue(null, "r").orEmpty()
                            type = reader.getAttributeValue(null, "t")
                            style = reader.getAttributeValue(null, "s")
                            value = StringBuilder()
                            formula = null
                        }
                        "v" -> inValue = true
                        "f" -> inFormula = true
                        "is" -> inInline = true
                        "t" -> if (inInline) inValue = true
                    }
                    XmlEvents.CHARACTERS -> when {
                        inValue -> value.append(reader.text)
                        inFormula -> formula = (formula ?: "") + reader.text
                    }
                    XmlEvents.END_ELEMENT -> when (reader.localName) {
                        "v" -> inValue = false
                        "t" -> if (inInline) inValue = false
                        "is" -> inInline = false
                        "f" -> inFormula = false
                        "c" -> {
                            if (ref.isNotEmpty()) {
                                val raw = value.toString()
                                val text = when (type) {
                                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                    "b" -> if (raw == "1") "ИСТИНА" else "ЛОЖЬ"
                                    else -> raw
                                }
                                val (row, column) = parseRef(ref)
                                if (row > 0 && column > 0 && (text.isNotEmpty() || formula != null)) {
                                    cells[ref] = XlsxCell(ref, row, column, text, formula?.let { "=$it" }, style)
                                    if (row > maxRow) maxRow = row
                                    if (column > maxColumn) maxColumn = column
                                }
                            }
                            ref = ""
                        }
                    }
                }
            }
            reader.close()
            return XlsxSheet(name, part, cells, maxRow, maxColumn)
        }
    }

    /**
     * Запись изменённых ячеек обратно в книгу. Значения кладутся строкой прямо в лист
     * (inlineStr), поэтому общий список строк и остальные листы остаются нетронутыми.
     */
    fun save(edits: Map<String, Map<String, String>>, target: Path = path) {
        if (edits.isEmpty()) return
        val source = path.toFile()
        val temp = File(target.toFile().parentFile, target.toFile().name + ".wex-tmp")
        ZipFile(source).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    val sheet = sheets.firstOrNull { it.part == entry.name }
                    val changes = sheet?.let { edits[it.name] }
                    out.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (changes.isNullOrEmpty()) {
                        out.write(bytes)
                    } else {
                        out.write(applyEdits(bytes, changes))
                    }
                    out.closeEntry()
                }
            }
        }
        Files.move(
            temp.toPath(), target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    /**
     * Лист в CSV: разделитель — точка с запятой (так его без вопросов открывает русский Excel),
     * поля с разделителем, кавычкой или переводом строки берутся в кавычки.
     */
    fun toCsv(cells: Map<String, XlsxCell>, rows: Int, columns: Int, separator: Char = ';'): String = buildString {
        for (row in 1..rows) {
            for (column in 1..columns) {
                if (column > 1) append(separator)
                val text = cells[ref(row, column)]?.text.orEmpty()
                val needsQuotes = text.any { it == separator || it == '"' || it == '\n' || it == '\r' }
                if (needsQuotes) append('"').append(text.replace("\"", "\"\"")).append('"') else append(text)
            }
            append("\r\n")
        }
    }

    /**
     * Полная перезапись листов из готовой модели: так работают вставка и удаление строк
     * и столбцов, когда правка одной ячейки уже не спасает. Оформление ячеек сохраняется.
     */
    fun saveSheets(content: Map<String, Map<String, XlsxCell>>, target: Path = path) {
        if (content.isEmpty()) return
        rewrite(target) { name, bytes ->
            val sheet = sheets.firstOrNull { it.part == name }
            val cells = sheet?.let { content[it.name] }
            if (cells == null) bytes else replaceSheetData(bytes, cells)
        }
    }

    /** Новый пустой лист в конце книги. */
    fun addSheet(name: String, target: Path = path) {
        val part = "xl/worksheets/sheet${sheets.size + 1}_wex.xml"
        val relId = "rIdWex${sheets.size + 1}"
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""

        rewriteWithExtra(target, mapOf(part to sheetXml.toByteArray(Charsets.UTF_8))) { entry, bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            when (entry) {
                "xl/workbook.xml" -> {
                    val maxId = Regex("""sheetId="(\d+)"""").findAll(xml)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: sheets.size
                    val sheetTag = """<sheet name="${escape(name)}" sheetId="${maxId + 1}" r:id="$relId"/>"""
                    xml.replace("</sheets>", "$sheetTag</sheets>").toByteArray(Charsets.UTF_8)
                }
                "xl/_rels/workbook.xml.rels" -> {
                    val rel = """<Relationship Id="$relId" """ +
                        """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                        """Target="worksheets/${part.substringAfterLast('/')}"/>"""
                    xml.replace("</Relationships>", "$rel</Relationships>").toByteArray(Charsets.UTF_8)
                }
                "[Content_Types].xml" -> {
                    val override = """<Override PartName="/$part" """ +
                        """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                    xml.replace("</Types>", "$override</Types>").toByteArray(Charsets.UTF_8)
                }
                else -> bytes
            }
        }
    }

    /** Переписать книгу, пропустив каждый файл через [transform]. */
    private fun rewrite(target: Path, transform: (String, ByteArray) -> ByteArray) =
        rewriteWithExtra(target, emptyMap(), transform)

    /** То же, но с добавлением новых файлов в книгу. */
    private fun rewriteWithExtra(
        target: Path,
        extra: Map<String, ByteArray>,
        transform: (String, ByteArray) -> ByteArray
    ) {
        val temp = File(target.toFile().parentFile, target.toFile().name + ".wex-tmp")
        ZipFile(path.toFile()).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(transform(entry.name, bytes))
                    out.closeEntry()
                }
                for ((name, bytes) in extra) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        Files.move(
            temp.toPath(), target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    /** Новое содержимое листа: строки по порядку, ячейки внутри строки — по столбцам. */
    internal fun replaceSheetData(sheetXml: ByteArray, cells: Map<String, XlsxCell>): ByteArray {
        val xml = sheetXml.toString(Charsets.UTF_8)
        val body = buildString {
            append("<sheetData>")
            for ((row, rowCells) in cells.values.groupBy { it.row }.toSortedMap()) {
                append("""<row r="$row">""")
                for (cell in rowCells.sortedBy { it.column }) {
                    append(cellXml(cell))
                }
                append("</row>")
            }
            append("</sheetData>")
        }
        val empty = Regex("""<sheetData\s*/>""")
        val filled = Regex("""<sheetData>.*?</sheetData>""", RegexOption.DOT_MATCHES_ALL)
        val replaced = when {
            filled.containsMatchIn(xml) -> filled.replace(xml) { body }
            empty.containsMatchIn(xml) -> empty.replace(xml) { body }
            else -> xml
        }
        return replaced.toByteArray(Charsets.UTF_8)
    }

    private fun cellXml(cell: XlsxCell): String {
        val style = cell.styleId?.let { """ s="$it"""" }.orEmpty()
        val formula = cell.formula?.removePrefix("=")?.takeIf { it.isNotBlank() }
        if (formula != null) return """<c r="${cell.ref}"$style><f>${escape(formula)}</f></c>"""
        if (cell.text.isEmpty()) return """<c r="${cell.ref}"$style/>"""
        val number = cell.text.trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { cell.text.trim().none { ch -> ch.isLetter() } }
        return if (number != null) {
            """<c r="${cell.ref}"$style><v>${trimNumber(number)}</v></c>"""
        } else {
            """<c r="${cell.ref}"$style t="inlineStr"><is><t xml:space="preserve">${escape(cell.text)}</t></is></c>"""
        }
    }

    /** Подмена значений ячеек в XML листа: правим текст, структуру не трогаем. */
    internal fun applyEdits(sheetXml: ByteArray, changes: Map<String, String>): ByteArray {
        var xml = sheetXml.toString(Charsets.UTF_8)
        for ((ref, text) in changes) {
            val cellPattern = Regex("""<c r="$ref"(?:\s[^>]*)?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)
            // Число остаётся числом: иначе сортировка и формулы в Excel перестанут его видеть
            val number = text.trim().replace(',', '.').toDoubleOrNull()
                ?.takeIf { text.isNotBlank() && text.trim().none { ch -> ch.isLetter() } }
            val replacement = if (number != null) {
                """<c r="$ref"><v>${trimNumber(number)}</v></c>"""
            } else {
                """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escape(text)}</t></is></c>"""
            }
            xml = if (cellPattern.containsMatchIn(xml)) {
                cellPattern.replace(xml) { replacement }
            } else {
                insertCell(xml, ref, replacement)
            }
        }
        return xml.toByteArray(Charsets.UTF_8)
    }

    /** Ячейки не было — вставляем её в нужную строку, а если и строки нет, создаём и строку. */
    private fun insertCell(xml: String, ref: String, cellXml: String): String {
        val (row, _) = parseRef(ref)
        val rowPattern = Regex("""<row[^>]*\br="$row"[^>]*>""")
        val match = rowPattern.find(xml)
        if (match != null) {
            val insertAt = match.range.last + 1
            return xml.substring(0, insertAt) + cellXml + xml.substring(insertAt)
        }
        val dataEnd = xml.indexOf("</sheetData>")
        if (dataEnd < 0) return xml
        return xml.substring(0, dataEnd) + """<row r="$row">$cellXml</row>""" + xml.substring(dataEnd)
    }

    /** 12.0 пишем как 12 — целое число в книге не должно обрастать хвостом. */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
