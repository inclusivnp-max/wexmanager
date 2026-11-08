package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class PdfReadingStateTest {
    @Test fun `new books start in a warm continuous width-fitted view`() {
        val state = PdfReadingStore(Files.createTempDirectory("reading-store")).load(Path.of("new.pdf"), 200)
        assertEquals(1, state.page); assertEquals(PdfPaper.SEPIA, state.paper)
        assertEquals(PdfFit.WIDTH, state.fit); assertEquals(PdfReadingMode.SCROLL, state.mode)
    }
    @Test fun `position settings and bookmarks survive a new store instance`() {
        val dir = Files.createTempDirectory("reading-store"); val book = dir.resolve("Книга с пробелами.pdf")
        val state = PdfReadingState(63, 124, 1.7f, PdfFit.CUSTOM, PdfPaper.DARK, PdfReadingMode.TEXT, 270, 26, setOf(5, 19, 63))
        PdfReadingStore(dir).save(book, state)
        assertEquals(state, PdfReadingStore(dir).load(book, 300))
        assertEquals(1L, Files.list(dir).use { it.count() })
    }
    @Test fun `same filename in different folders does not share a position`() {
        val dir = Files.createTempDirectory("reading-store"); val store = PdfReadingStore(dir)
        store.save(Path.of("a/book.pdf"), PdfReadingState(page = 12))
        store.save(Path.of("b/book.pdf"), PdfReadingState(page = 28))
        assertEquals(12, store.load(Path.of("a/book.pdf"), 100).page)
        assertEquals(28, store.load(Path.of("b/book.pdf"), 100).page)
    }
    @Test fun `corrupt preferences use defaults instead of blocking the book`() {
        val dir = Files.createTempDirectory("reading-store"); val store = PdfReadingStore(dir); val book = Path.of("book.pdf")
        store.save(book, PdfReadingState())
        val file = Files.list(dir).use { it.findFirst().get() }
        Files.writeString(file, "page=abc\nzoom=NaN\nmode=GARBAGE\npaper=NONE\nrotation=-90\nbookmarks=0,2,2,999,bad\n")
        val loaded = store.load(book, 10)
        assertEquals(1, loaded.page); assertEquals(1f, loaded.zoom); assertEquals(PdfPaper.SEPIA, loaded.paper)
        assertEquals(270, loaded.rotation); assertEquals(setOf(2), loaded.bookmarks)
    }
    @Test fun `bounds clamp restored pages zoom offsets and text size`() {
        val s = PdfReadingState(page = 999, offset = -5, zoom = Float.POSITIVE_INFINITY, rotation = 450, textSize = 80, bookmarks = setOf(-1, 2, 30)).bounded(12)
        assertEquals(12, s.page); assertEquals(0, s.offset); assertEquals(1f, s.zoom)
        assertEquals(90, s.rotation); assertEquals(36, s.textSize); assertEquals(setOf(2), s.bookmarks)
    }
    @Test fun `bookmark toggle is reversible and leaves other pages alone`() {
        val s = PdfReadingState(page = 8, bookmarks = setOf(4))
        assertEquals(setOf(4, 8), s.toggleBookmark().bookmarks)
        assertEquals(s, s.toggleBookmark().toggleBookmark())
    }
    @Test fun `fit width preserves proportions and leaves room for margins`() {
        val size = pdfPageSize(600.0, 900.0, 900f, 700f, PdfReadingState())
        assertEquals(852f, size.width, .1f); assertEquals(1.5f, size.height / size.width, .001f)
    }
    @Test fun `fit page fits both dimensions even after rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val size = pdfPageSize(600.0, 900.0, 900f, 700f, PdfReadingState(fit = PdfFit.PAGE, rotation = rotation))
            assertTrue(size.width <= 852.1f); assertTrue(size.height <= 652.1f)
        }
    }
    @Test fun `custom zoom is independent of viewport dimensions`() {
        val s = PdfReadingState(fit = PdfFit.CUSTOM, zoom = 2f)
        assertEquals(pdfPageSize(600.0, 900.0, 900f, 700f, s), pdfPageSize(600.0, 900.0, 500f, 400f, s))
    }
    @Test fun `large and malformed page dimensions have a bounded raster budget`() {
        for ((w, h) in listOf(10000.0 to 10000.0, 1.0 to 10000.0, Double.NaN to Double.POSITIVE_INFINITY, 1e200 to 1e201)) {
            val size = pdfPageSize(w, h, 1600f, 1000f, PdfReadingState(fit = PdfFit.CUSTOM, zoom = 4f), 4f)
            assertTrue(size.width.isFinite() && size.height.isFinite())
            assertTrue(maxOf(size.width, size.height) <= 10000.1f)
            assertTrue(size.renderEdge in 128..4096)
        }
    }
    @Test fun `search ignores case and folds line wrapping`() {
        val hit = assertNotNull(pdfSearchHit(3, "A useful\n\n READER finds reader.", "useful reader"))
        assertEquals(3, hit.page); assertTrue(hit.snippet.contains("useful READER"))
        assertEquals(2, pdfSearchHit(1, "reader READER", "reader")?.occurrences)
        assertNull(pdfSearchHit(1, "text", " ")); assertNull(pdfSearchHit(1, "text", "missing"))
    }
    @Test fun `long search snippets stay bounded and show their omissions`() {
        val hit = assertNotNull(pdfSearchHit(42, "x".repeat(1000) + "needle" + "y".repeat(1000), "needle"))
        assertTrue(hit.snippet.length < 200); assertTrue(hit.snippet.startsWith("…") && hit.snippet.endsWith("…"))
    }
    @Test fun `next and previous results wrap in document order`() {
        val hits = listOf(2, 5, 8).map { PdfSearchHit(it, "", 1) }
        assertEquals(1, pdfMatchIndex(hits, 2, false)); assertEquals(0, pdfMatchIndex(hits, 8, false))
        assertEquals(2, pdfMatchIndex(hits, 2, true)); assertEquals(1, pdfMatchIndex(hits, 8, true))
        assertEquals(-1, pdfMatchIndex(emptyList(), 1, false))
    }
}
