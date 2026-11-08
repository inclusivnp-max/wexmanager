package org.wexclient.app.doc

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** An embedded font makes the rendering fixture independent of system font substitution. */
internal fun pdfReaderFixture(dir: Path): Path {
    Files.createDirectories(dir)
    val bytes = checkNotNull(PdfBookSessionTest::class.java.getResourceAsStream("/pdf-reader-book.pdf")).use { it.readBytes() }
    return Files.write(dir.resolve("Книга с пробелами.pdf"), bytes)
}

class PdfBookSessionTest {
    private fun available() { assumeTrue(PdfTools.available && PdfTools.textAvailable, "Poppler не установлен") }
    @Test fun `real pdf metadata text and search keep page numbers and UTF-8 paths`() {
        available(); val dir = Files.createTempDirectory("reading-session"); val pdf = pdfReaderFixture(dir)
        assertEquals(3, assertNotNull(PdfTools.info(pdf)).pages)
        PdfBookSession(pdf, dir.resolve("cache")).use { book ->
            assertTrue(book.text(2).contains("NEEDLE")); assertFalse(book.text(1).contains("NEEDLE"))
            val hits = book.search("needle", 3); assertEquals(listOf(2), hits.map { it.page }); assertEquals(2, hits.single().occurrences)
            assertEquals(listOf(1, 3), book.search("reader", 3).map { it.page })
            assertTrue(book.search("missing", 3).isEmpty()); assertTrue(book.search("  ", 3).isEmpty())
        }
    }
    @Test fun `real pages render at bounded size including landscape pages`() {
        available(); val dir = Files.createTempDirectory("reading-render"); val pdf = pdfReaderFixture(dir)
        PdfBookSession(pdf, dir.resolve("cache")).use { book ->
            val portrait = ImageIO.read(book.render(1, 320).inputStream())
            val landscape = ImageIO.read(book.render(2, 320).inputStream())
            assertEquals(320, portrait.height); assertEquals(320, landscape.width)
            assertTrue(portrait.height > portrait.width); assertTrue(landscape.width > landscape.height)
            assertContentEquals(book.render(1, 320), book.render(1, 320))
        }
    }
    @Test fun `closing a session removes only its own cache`() {
        available(); val dir = Files.createTempDirectory("reading-cache"); val pdf = pdfReaderFixture(dir); val root = dir.resolve("cache")
        val first = PdfBookSession(pdf, root); val second = PdfBookSession(pdf, root)
        first.render(1, 128); second.render(2, 128); first.close()
        assertEquals(1L, Files.list(root).use { it.count() }); assertTrue(second.render(2, 128).isNotEmpty())
        assertFailsWith<IllegalStateException> { first.render(1, 128) }
        second.close(); assertEquals(0L, Files.list(root).use { it.count() })
    }
    @Test fun `broken pages report errors instead of remaining in a loading state`() {
        available(); val dir = Files.createTempDirectory("reading-broken"); val pdf = Files.writeString(dir.resolve("broken.pdf"), "not a pdf")
        assertNull(PdfTools.info(pdf))
        PdfBookSession(pdf, dir.resolve("cache")).use { book -> assertFailsWith<java.io.IOException> { book.render(1, 128) } }
    }
    @Test fun `book bytes never change after reading searching and rendering`() {
        available(); val dir = Files.createTempDirectory("reading-original"); val pdf = pdfReaderFixture(dir); val before = Files.readAllBytes(pdf)
        PdfBookSession(pdf, dir.resolve("cache")).use { it.render(1, 128); it.text(2); it.search("reader", 3) }
        assertContentEquals(before, Files.readAllBytes(pdf))
    }
    @Test fun `encoded page cache evicts old sizes instead of growing with every zoom step`() {
        available(); val dir = Files.createTempDirectory("reading-budget"); val pdf = pdfReaderFixture(dir); val root = dir.resolve("cache")
        PdfBookSession(pdf, root).use { book ->
            repeat(36) { book.render(1, 128 + it * 8) }
            val files = Files.walk(root).use { stream -> stream.filter(Files::isRegularFile).toList() }
            assertTrue(files.size <= 32)
            assertTrue(files.sumOf(Files::size) <= 96L * 1024 * 1024)
        }
    }
    @Test fun `cancelled searches clean up their output and do not return stale matches`() {
        available(); val dir = Files.createTempDirectory("reading-cancel"); val pdf = pdfReaderFixture(dir); val root = dir.resolve("cache")
        PdfBookSession(pdf, root).use { book ->
            assertFailsWith<java.util.concurrent.CancellationException> { book.search("reader", 3) { true } }
            assertEquals(0L, Files.walk(root).use { it.filter(Files::isRegularFile).count() })
            assertEquals(listOf(2), book.search("needle", 3).map { it.page })
        }
    }

}
