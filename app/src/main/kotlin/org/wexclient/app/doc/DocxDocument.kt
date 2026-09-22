package org.wexclient.app.doc

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Кусок текста с одинаковым начертанием. */
data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float? = null,
    val color: String? = null,
    val mono: Boolean = false
)

/** Выравнивание абзаца. */
enum class DocAlign { LEFT, CENTER, RIGHT, JUSTIFY }

/** Куски документа в порядке чтения. */
sealed interface DocBlock {
    /** Абзац: заголовок при [heading] от 1 до 6, пункт списка при [listLevel]. */
    data class Paragraph(
        val runs: List<DocRun>,
        val heading: Int? = null,
        val listLevel: Int? = null,
        val numbered: Boolean = false,
        val align: DocAlign = DocAlign.LEFT,
        /** Номер абзаца по порядку — по нему правка возвращается в файл. */
        val index: Int = 0
    ) : DocBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    /** Таблица: строки, в каждой — ячейки, в каждой ячейке свои абзацы. */
    data class Table(val rows: List<List<List<Paragraph>>>) : DocBlock

    /** Картинка из word/media. */
    data class Picture(val name: String, val bytes: ByteArray) : DocBlock
}

/**
 * Документ Word без сторонних библиотек: docx — zip с XML, читаем его StAX-ом из JDK.
 * Разбираем то, что видно на странице: абзацы с начертанием, списки, таблицы и картинки.
 */
class DocxDocument private constructor(
    val path: Path,
    val blocks: List<DocBlock>,
    val paragraphCount: Int,
    val properties: Map<String, String>,
    /** Открыт из другого формата (odt, pptx): показываем и выгружаем, но назад не пишем. */
    val readOnly: Boolean = false,
    /** Что за формат — для подписи в окне. */
    val format: String = "DOCX"
) {
    val plainText: String
        get() = buildString {
            for (block in blocks) when (block) {
                is DocBlock.Paragraph -> appendLine(block.text)
                is DocBlock.Table -> for (row in block.rows) {
                    appendLine(row.joinToString("\t") { cell -> cell.joinToString(" ") { it.text } })
                }
                is DocBlock.Picture -> appendLine("[картинка ${block.name}]")
            }
        }

    /**
     * Документ в Markdown: заголовки — решётками, списки — дефисами, жирный и курсив — звёздочками,
     * таблицы — таблицами. Удобно для вики, заметок и дальнейшей правки в редакторе.
     */
    val markdown: String
        get() = buildString {
            for (block in blocks) when (block) {
                is DocBlock.Paragraph -> {
                    val text = block.runs.joinToString("") { run ->
                        var t = run.text
                        if (t.isBlank()) return@joinToString t
                        if (run.bold && run.italic) t = "***$t***"
                        else if (run.bold) t = "**$t**"
                        else if (run.italic) t = "*$t*"
                        if (run.mono) t = "`$t`"
                        t
                    }
                    when {
                        block.heading != null -> appendLine("#".repeat(block.heading) + " " + block.text)
                        block.listLevel != null -> appendLine("  ".repeat(block.listLevel) + (if (block.numbered) "1. " else "- ") + text)
                        else -> appendLine(text)
                    }
                    appendLine()
                }
                is DocBlock.Table -> {
                    block.rows.forEachIndexed { index, row ->
                        appendLine("| " + row.joinToString(" | ") { cell -> cell.joinToString(" ") { it.text }.replace("|", "\\|") } + " |")
                        if (index == 0) appendLine("|" + " --- |".repeat(row.size))
                    }
                    appendLine()
                }
                is DocBlock.Picture -> {
                    appendLine("![${block.name}](${block.name})")
                    appendLine()
                }
            }
        }

    companion object {
        private val factory: XMLInputFactory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }

        fun looksLikeDocx(name: String): Boolean = name.endsWith(".docx", ignoreCase = true)

        fun looksLikeOdt(name: String): Boolean = name.endsWith(".odt", ignoreCase = true)

        fun looksLikePptx(name: String): Boolean = name.endsWith(".pptx", ignoreCase = true)

        /** Любой из понятных текстовых форматов: docx, odt, pptx. */
        fun supports(name: String): Boolean = looksLikeDocx(name) || looksLikeOdt(name) || looksLikePptx(name)

        /** Открытие по расширению файла. */
        fun openAny(path: Path): DocxDocument {
            val name = path.fileName.toString()
            return when {
                looksLikeOdt(name) -> openOdt(path)
                looksLikePptx(name) -> openPptx(path)
                else -> open(path)
            }
        }

        /**
         * Документ LibreOffice: content.xml с text:h, text:p, списками и таблицами.
         * Жирный и курсив берутся из автоматических стилей, на которые ссылаются куски текста.
         */
        fun openOdt(path: Path): DocxDocument {
            ZipFile(path.toFile()).use { zip ->
                val content = zip.bytes("content.xml")
                    ?: throw IllegalArgumentException("Это не документ OpenDocument: внутри нет content.xml")
                val reader = factory.createXMLStreamReader(ByteArrayInputStream(content))
                val blocks = ArrayList<DocBlock>()
                val boldStyles = HashSet<String>()
                val italicStyles = HashSet<String>()
                var styleName: String? = null
                var index = 0
                var runs = ArrayList<DocRun>()
                val text = StringBuilder()
                var heading: Int? = null
                var listDepth = 0
                var spanStyle: String? = null
                var inParagraph = false
                var tableRows: ArrayList<ArrayList<ArrayList<DocBlock.Paragraph>>>? = null
                var tableRow: ArrayList<ArrayList<DocBlock.Paragraph>>? = null
                var tableCell: ArrayList<DocBlock.Paragraph>? = null

                fun flushRun() {
                    if (text.isNotEmpty()) {
                        runs += DocRun(
                            text = text.toString(),
                            bold = spanStyle in boldStyles,
                            italic = spanStyle in italicStyles
                        )
                        text.setLength(0)
                    }
                }

                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                            "style" -> styleName = reader.getAttributeValue(null, "name")
                                ?: (0 until reader.attributeCount).firstOrNull { reader.getAttributeLocalName(it) == "name" }
                                    ?.let { reader.getAttributeValue(it) }
                            "text-properties" -> {
                                val weight = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "font-weight" }
                                    ?.let { reader.getAttributeValue(it) }
                                val style = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "font-style" }
                                    ?.let { reader.getAttributeValue(it) }
                                styleName?.let { name ->
                                    if (weight == "bold") boldStyles += name
                                    if (style == "italic") italicStyles += name
                                }
                            }
                            "h" -> {
                                inParagraph = true
                                heading = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "outline-level" }
                                    ?.let { reader.getAttributeValue(it).toIntOrNull() }?.coerceIn(1, 6) ?: 1
                            }
                            "p" -> inParagraph = true
                            "span" -> {
                                flushRun()
                                spanStyle = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "style-name" }
                                    ?.let { reader.getAttributeValue(it) }
                            }
                            "s" -> text.append(' ')
                            "tab" -> text.append('\t')
                            "line-break" -> text.append('\n')
                            "list" -> listDepth++
                            "table" -> tableRows = ArrayList()
                            "table-row" -> if (tableRows != null) tableRow = ArrayList()
                            "table-cell" -> if (tableRow != null) tableCell = ArrayList()
                            "image" -> {
                                val href = (0 until reader.attributeCount)
                                    .firstOrNull { reader.getAttributeLocalName(it) == "href" }
                                    ?.let { reader.getAttributeValue(it) }
                                val data = href?.let { zip.bytes(it) }
                                if (href != null && data != null) blocks += DocBlock.Picture(href.substringAfterLast('/'), data)
                            }
                        }
                        XMLStreamConstants.CHARACTERS -> if (inParagraph) text.append(reader.text)
                        XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                            "span" -> { flushRun(); spanStyle = null }
                            "h", "p" -> {
                                flushRun()
                                val paragraph = DocBlock.Paragraph(
                                    runs = runs.toList(),
                                    heading = heading,
                                    listLevel = if (listDepth > 0) listDepth - 1 else null,
                                    index = index++
                                )
                                val cell = tableCell
                                if (cell != null) cell += paragraph else blocks += paragraph
                                runs = ArrayList()
                                heading = null
                                inParagraph = false
                            }
                            "list" -> listDepth = (listDepth - 1).coerceAtLeast(0)
                            "table-cell" -> { tableCell?.let { tableRow?.add(it) }; tableCell = null }
                            "table-row" -> { tableRow?.let { tableRows?.add(it) }; tableRow = null }
                            "table" -> {
                                tableRows?.let { rows -> blocks += DocBlock.Table(rows.map { row -> row.map { it.toList() } }) }
                                tableRows = null
                            }
                            "style" -> styleName = null
                        }
                    }
                }
                reader.close()
                val props = zip.bytes("meta.xml")?.let { readOdfMeta(it) } ?: emptyMap()
                return DocxDocument(path, blocks, index, props, readOnly = true, format = "ODT")
            }
        }

        /** Свойства документа OpenDocument: название и автор из meta.xml. */
        private fun readOdfMeta(bytes: ByteArray): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            var key: String? = null
            val value = StringBuilder()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> { key = reader.localName; value.setLength(0) }
                    XMLStreamConstants.CHARACTERS -> value.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val name = when (key) {
                            "title" -> "Название"
                            "initial-creator", "creator" -> "Автор"
                            else -> null
                        }
                        if (name != null && value.isNotBlank()) result.putIfAbsent(name, value.toString().trim())
                        key = null
                    }
                }
            }
            reader.close()
            return result
        }

        /**
         * Презентация: каждый слайд — заголовок и его текст по порядку, плюс картинки слайда.
         * Оформление слайдов не воспроизводится — важнее быстро прочитать содержимое.
         */
        fun openPptx(path: Path): DocxDocument {
            ZipFile(path.toFile()).use { zip ->
                val slides = zip.entries().toList().map { it.name }
                    .filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
                    .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
                if (slides.isEmpty()) throw IllegalArgumentException("В презентации нет слайдов")

                val blocks = ArrayList<DocBlock>()
                var index = 0
                slides.forEachIndexed { number, slide ->
                    blocks += DocBlock.Paragraph(listOf(DocRun("Слайд ${number + 1}")), heading = 2, index = index++)
                    val rels = zip.bytes(slide.replace("slides/", "slides/_rels/") + ".rels")
                        ?.let { readRelations(it) } ?: emptyMap()
                    val reader = factory.createXMLStreamReader(ByteArrayInputStream(zip.bytes(slide) ?: return@forEachIndexed))
                    val text = StringBuilder()
                    var inText = false
                    var isTitle = false
                    var inShape = false
                    val runs = ArrayList<DocRun>()
                    while (reader.hasNext()) {
                        when (reader.next()) {
                            XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                                "sp" -> { inShape = true; isTitle = false }
                                "ph" -> {
                                    val type = reader.getAttributeValue(null, "type")
                                    isTitle = type == "title" || type == "ctrTitle"
                                }
                                "t" -> inText = true
                                "blip" -> {
                                    val id = (0 until reader.attributeCount)
                                        .firstOrNull { reader.getAttributeLocalName(it) == "embed" }
                                        ?.let { reader.getAttributeValue(it) }
                                    val target = id?.let { rels[it] }?.removePrefix("../")
                                    val data = target?.let { zip.bytes("ppt/$it") }
                                    if (target != null && data != null) blocks += DocBlock.Picture(target.substringAfterLast('/'), data)
                                }
                            }
                            XMLStreamConstants.CHARACTERS -> if (inText) text.append(reader.text)
                            XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                                "t" -> inText = false
                                "p" -> if (inShape && text.isNotBlank()) {
                                    runs += DocRun(text.toString(), bold = isTitle)
                                    blocks += DocBlock.Paragraph(
                                        runs = listOf(DocRun(text.toString(), bold = isTitle)),
                                        heading = if (isTitle) 3 else null,
                                        index = index++
                                    )
                                    text.setLength(0)
                                } else text.setLength(0)
                                "sp" -> inShape = false
                            }
                        }
                    }
                    reader.close()
                }
                return DocxDocument(path, blocks, index, emptyMap(), readOnly = true, format = "PPTX")
            }
        }

        fun open(path: Path): DocxDocument {
            ZipFile(path.toFile()).use { zip ->
                val document = zip.bytes("word/document.xml")
                    ?: throw IllegalArgumentException("Это не документ Word: внутри нет word/document.xml")
                val rels = zip.bytes("word/_rels/document.xml.rels")?.let { readRelations(it) } ?: emptyMap()
                val media = HashMap<String, ByteArray>()
                for ((_, target) in rels) {
                    if (!target.startsWith("media/")) continue
                    zip.bytes("word/$target")?.let { media[target] = it }
                }
                val props = zip.bytes("docProps/core.xml")?.let { readProperties(it) } ?: emptyMap()
                val (blocks, count) = readBody(document, rels, media)
                return DocxDocument(path, blocks, count, props)
            }
        }

        private fun ZipFile.bytes(name: String): ByteArray? {
            val entry: ZipEntry = getEntry(name) ?: return null
            return getInputStream(entry).use { it.readBytes() }
        }

        private fun readRelations(bytes: ByteArray): Map<String, String> {
            val result = HashMap<String, String>()
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "Relationship") {
                    val id = reader.getAttributeValue(null, "Id")
                    val target = reader.getAttributeValue(null, "Target")
                    if (id != null && target != null) result[id] = target.removePrefix("/word/")
                }
            }
            reader.close()
            return result
        }

        private fun readProperties(bytes: ByteArray): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            var key: String? = null
            val value = StringBuilder()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> { key = reader.localName; value.setLength(0) }
                    XMLStreamConstants.CHARACTERS -> value.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val name = when (key) {
                            "title" -> "Название"
                            "creator" -> "Автор"
                            "lastModifiedBy" -> "Изменил"
                            "created" -> "Создан"
                            "modified" -> "Изменён"
                            else -> null
                        }
                        if (name != null && value.isNotBlank()) result[name] = value.toString().trim()
                        key = null
                    }
                }
            }
            reader.close()
            return result
        }

        private fun readBody(
            bytes: ByteArray,
            rels: Map<String, String>,
            media: Map<String, ByteArray>
        ): Pair<List<DocBlock>, Int> {
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            val blocks = ArrayList<DocBlock>()

            // Разбор идёт поточно, поэтому состояние держим в переменных
            var paragraphIndex = 0
            var runs = ArrayList<DocRun>()
            var text = StringBuilder()
            var bold = false; var italic = false; var underline = false; var strike = false
            var sizePt: Float? = null; var color: String? = null; var mono = false
            var heading: Int? = null; var listLevel: Int? = null; var numbered = false
            var align = DocAlign.LEFT
            var inText = false

            // Таблицы: собираем вложенно, поэтому нужны стеки
            var tableRows: ArrayList<ArrayList<ArrayList<DocBlock.Paragraph>>>? = null
            var tableRow: ArrayList<ArrayList<DocBlock.Paragraph>>? = null
            var tableCell: ArrayList<DocBlock.Paragraph>? = null

            fun finishRun() {
                if (text.isNotEmpty()) {
                    runs += DocRun(text.toString(), bold, italic, underline, strike, sizePt, color, mono)
                    text = StringBuilder()
                }
            }

            fun finishParagraph() {
                finishRun()
                val paragraph = DocBlock.Paragraph(
                    runs = runs.toList(),
                    heading = heading,
                    listLevel = listLevel,
                    numbered = numbered,
                    align = align,
                    index = paragraphIndex++
                )
                val cell = tableCell
                if (cell != null) cell += paragraph else blocks += paragraph
                runs = ArrayList()
                heading = null; listLevel = null; numbered = false; align = DocAlign.LEFT
            }

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "p" -> Unit
                        "r" -> {
                            // Каждый кусок начинается с обычного начертания: иначе жирность
                            // предыдущего куска «протекала» на следующий без своего оформления
                            finishRun()
                            bold = false; italic = false; underline = false; strike = false
                            sizePt = null; color = null; mono = false
                        }
                        "t" -> inText = true
                        "tab" -> text.append('\t')
                        "br" -> text.append('\n')
                        "b" -> { finishRun(); bold = reader.flag() }
                        "i" -> { finishRun(); italic = reader.flag() }
                        "u" -> { finishRun(); underline = reader.getAttributeValue(null, "val") != "none" }
                        "strike" -> { finishRun(); strike = reader.flag() }
                        "sz" -> { finishRun(); sizePt = reader.getAttributeValue(null, "val")?.toFloatOrNull()?.div(2f) }
                        "color" -> {
                            finishRun()
                            color = reader.getAttributeValue(null, "val")?.takeIf { it != "auto" }
                        }
                        "rFonts" -> {
                            val font = reader.getAttributeValue(null, "ascii").orEmpty()
                            mono = font.contains("Mono", true) || font.contains("Courier", true) ||
                                font.contains("Consol", true)
                        }
                        "rPr" -> {
                            // Новое оформление куска: прежние пометки сбрасываем
                            finishRun()
                            bold = false; italic = false; underline = false; strike = false
                            sizePt = null; color = null; mono = false
                        }
                        "pStyle" -> {
                            val style = reader.getAttributeValue(null, "val").orEmpty()
                            heading = headingLevel(style)
                        }
                        "numPr" -> { listLevel = 0; numbered = false }
                        "ilvl" -> listLevel = reader.getAttributeValue(null, "val")?.toIntOrNull() ?: 0
                        "numFmt" -> numbered = reader.getAttributeValue(null, "val") != "bullet"
                        "jc" -> align = when (reader.getAttributeValue(null, "val")) {
                            "center" -> DocAlign.CENTER
                            "right", "end" -> DocAlign.RIGHT
                            "both", "distribute" -> DocAlign.JUSTIFY
                            else -> DocAlign.LEFT
                        }
                        "tbl" -> tableRows = ArrayList()
                        "tr" -> if (tableRows != null) tableRow = ArrayList()
                        "tc" -> if (tableRow != null) tableCell = ArrayList()
                        "blip" -> {
                            val id = (0 until reader.attributeCount)
                                .firstOrNull { reader.getAttributeLocalName(it) == "embed" }
                                ?.let { reader.getAttributeValue(it) }
                            val target = id?.let { rels[it] }
                            val data = target?.let { media[it] }
                            if (target != null && data != null) {
                                blocks += DocBlock.Picture(target.substringAfterLast('/'), data)
                            }
                        }
                    }
                    XMLStreamConstants.CHARACTERS -> if (inText) text.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "t" -> inText = false
                        "p" -> finishParagraph()
                        "tc" -> {
                            tableCell?.let { tableRow?.add(it) }
                            tableCell = null
                        }
                        "tr" -> {
                            tableRow?.let { tableRows?.add(it) }
                            tableRow = null
                        }
                        "tbl" -> {
                            tableRows?.let { rows ->
                                blocks += DocBlock.Table(rows.map { row -> row.map { it.toList() } })
                            }
                            tableRows = null
                        }
                    }
                }
            }
            reader.close()
            return blocks to paragraphIndex
        }

        private fun headingLevel(style: String): Int? {
            val lower = style.lowercase()
            val match = Regex("""(?:heading|заголовок)(\d)""").find(lower.replace(" ", ""))
            val level = match?.groupValues?.get(1)?.toIntOrNull()
            return level?.takeIf { it in 1..6 }
        }
    }

    /**
     * Правка текста абзацев: по номеру абзаца меняем содержимое.
     * Оформление абзаца остаётся, куски внутри склеиваются в один — иначе правка
     * пришлась бы на середину чужого начертания.
     */
    fun save(edits: Map<Int, String>, target: Path = path) {
        if (edits.isEmpty()) return
        val temp = File(target.toFile().parentFile, target.toFile().name + ".wex-tmp")
        ZipFile(path.toFile()).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    out.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "word/document.xml") {
                        out.write(applyEdits(bytes, edits))
                    } else {
                        out.write(bytes)
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
     * Правка с добавлением и удалением абзацев: [inserts] — новые абзацы после указанного номера,
     * [removed] — номера абзацев, которые убираем целиком.
     */
    fun saveWithStructure(
        edits: Map<Int, String>,
        inserts: Map<Int, List<String>>,
        removed: Set<Int>,
        target: Path = path
    ) {
        if (edits.isEmpty() && inserts.isEmpty() && removed.isEmpty()) return
        val temp = File(target.toFile().parentFile, target.toFile().name + ".wex-tmp")
        ZipFile(path.toFile()).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    out.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "word/document.xml") {
                        out.write(applyStructure(bytes, edits, inserts, removed))
                    } else {
                        out.write(bytes)
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

    internal fun applyStructure(
        documentXml: ByteArray,
        edits: Map<Int, String>,
        inserts: Map<Int, List<String>>,
        removed: Set<Int>
    ): ByteArray {
        val xml = documentXml.toString(Charsets.UTF_8)
        val sb = StringBuilder(xml.length + 512)
        var position = 0
        var index = 0
        val paragraph = Regex("""<w:p(?:\s[^>]*)?>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL)

        for (match in paragraph.findAll(xml)) {
            sb.append(xml, position, match.range.first)
            if (index !in removed) {
                val replacement = edits[index]
                sb.append(if (replacement == null) match.value else replaceParagraphText(match.value, replacement))
            }
            inserts[index]?.forEach { text -> sb.append(newParagraph(text)) }
            position = match.range.last + 1
            index++
        }
        sb.append(xml, position, xml.length)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun newParagraph(text: String): String =
        """<w:p><w:r><w:t xml:space="preserve">${escape(text)}</w:t></w:r></w:p>"""

    /** Замена текста в XML: абзацы считаются по порядку, как при чтении. */
    internal fun applyEdits(documentXml: ByteArray, edits: Map<Int, String>): ByteArray {
        val xml = documentXml.toString(Charsets.UTF_8)
        val sb = StringBuilder(xml.length + 256)
        var position = 0
        var index = 0
        val paragraph = Regex("""<w:p(?:\s[^>]*)?>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL)

        for (match in paragraph.findAll(xml)) {
            sb.append(xml, position, match.range.first)
            val body = match.value
            val replacement = edits[index]
            sb.append(if (replacement == null) body else replaceParagraphText(body, replacement))
            position = match.range.last + 1
            index++
        }
        sb.append(xml, position, xml.length)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Весь текст абзаца заменяется одним куском; оформление первого куска сохраняется. */
    private fun replaceParagraphText(paragraphXml: String, text: String): String {
        val runPattern = Regex("""<w:r(?:\s[^>]*)?>.*?</w:r>""", RegexOption.DOT_MATCHES_ALL)
        val runs = runPattern.findAll(paragraphXml).toList()
        val escaped = escape(text)
        if (runs.isEmpty()) {
            // Пустой абзац — добавляем кусок перед закрытием
            val close = paragraphXml.lastIndexOf("</w:p>")
            if (close < 0) return paragraphXml
            val run = """<w:r><w:t xml:space="preserve">$escaped</w:t></w:r>"""
            return paragraphXml.substring(0, close) + run + paragraphXml.substring(close)
        }
        val first = runs.first()
        val props = Regex("""<w:rPr>.*?</w:rPr>""", RegexOption.DOT_MATCHES_ALL)
            .find(first.value)?.value.orEmpty()
        val newRun = """<w:r>$props<w:t xml:space="preserve">$escaped</w:t></w:r>"""

        val sb = StringBuilder(paragraphXml.length)
        var position = 0
        for ((i, run) in runs.withIndex()) {
            sb.append(paragraphXml, position, run.range.first)
            if (i == 0) sb.append(newRun)
            position = run.range.last + 1
        }
        sb.append(paragraphXml, position, paragraphXml.length)
        return sb.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun javax.xml.stream.XMLStreamReader.flag(): Boolean {
    val value = getAttributeValue(null, "val") ?: return true
    return value != "0" && value != "false" && value != "none"
}
