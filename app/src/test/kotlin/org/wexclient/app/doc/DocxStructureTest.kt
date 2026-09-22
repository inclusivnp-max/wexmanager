package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Добавление и удаление абзацев в документе Word. */
class DocxStructureTest {

    private fun document(bodyXml: String): Path {
        val path = Files.createTempFile("wex-docx", ".docx")
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(ZipEntry("word/document.xml"))
            out.write(
                """<?xml version="1.0"?><w:document xmlns:w="http://w"><w:body>$bodyXml</w:body></w:document>"""
                    .toByteArray()
            )
            out.closeEntry()
        }
        return path
    }

    private fun texts(path: Path): List<String> =
        DocxDocument.open(path).blocks.filterIsInstance<DocBlock.Paragraph>().map { it.text }

    @Test
    fun `абзац добавляется после указанного`() {
        val path = document(
            """<w:p><w:r><w:t>Первый</w:t></w:r></w:p><w:p><w:r><w:t>Второй</w:t></w:r></w:p>"""
        )
        DocxDocument.open(path).saveWithStructure(
            edits = emptyMap(),
            inserts = mapOf(0 to listOf("Вставленный")),
            removed = emptySet()
        )
        assertEquals(listOf("Первый", "Вставленный", "Второй"), texts(path))
    }

    @Test
    fun `абзац удаляется целиком`() {
        val path = document(
            """<w:p><w:r><w:t>А</w:t></w:r></w:p><w:p><w:r><w:t>Б</w:t></w:r></w:p><w:p><w:r><w:t>В</w:t></w:r></w:p>"""
        )
        DocxDocument.open(path).saveWithStructure(emptyMap(), emptyMap(), setOf(1))
        assertEquals(listOf("А", "В"), texts(path))
    }

    @Test
    fun `правка, вставка и удаление работают вместе`() {
        val path = document(
            """<w:p><w:r><w:t>Один</w:t></w:r></w:p><w:p><w:r><w:t>Два</w:t></w:r></w:p><w:p><w:r><w:t>Три</w:t></w:r></w:p>"""
        )
        DocxDocument.open(path).saveWithStructure(
            edits = mapOf(0 to "Первый"),
            inserts = mapOf(2 to listOf("Четыре")),
            removed = setOf(1)
        )
        assertEquals(listOf("Первый", "Три", "Четыре"), texts(path))
    }

    @Test
    fun `особые символы в новом абзаце не ломают файл`() {
        val path = document("""<w:p><w:r><w:t>Текст</w:t></w:r></w:p>""")
        DocxDocument.open(path).saveWithStructure(emptyMap(), mapOf(0 to listOf("a < b & c")), emptySet())
        assertEquals(listOf("Текст", "a < b & c"), texts(path))
    }
}
