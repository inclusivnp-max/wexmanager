package org.wexclient.app.doc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.min

internal enum class PdfPaper { LIGHT, SEPIA, DARK }
internal enum class PdfFit { WIDTH, PAGE, CUSTOM }
internal enum class PdfReadingMode { SCROLL, PAGE, TEXT }

internal data class PdfReadingState(
    val page: Int = 1,
    val offset: Int = 0,
    val zoom: Float = 1f,
    val fit: PdfFit = PdfFit.WIDTH,
    val paper: PdfPaper = PdfPaper.SEPIA,
    val mode: PdfReadingMode = PdfReadingMode.SCROLL,
    val rotation: Int = 0,
    val textSize: Int = 20,
    val bookmarks: Set<Int> = emptySet()
) {
    fun bounded(pages: Int) = copy(
        page = page.coerceIn(1, pages.coerceAtLeast(1)),
        offset = offset.coerceIn(0, 100_000),
        zoom = if (zoom.isFinite()) zoom.coerceIn(.25f, 4f) else 1f,
        rotation = Math.floorMod(rotation / 90, 4) * 90,
        textSize = textSize.coerceIn(14, 36),
        bookmarks = bookmarks.filter { it in 1..pages }.take(2000).toSortedSet()
    )

    fun toggleBookmark() = copy(bookmarks = if (page in bookmarks) bookmarks - page else bookmarks + page)
}

/** A separate atomic file per book keeps simultaneous reader windows independent. */
internal class PdfReadingStore(private val directory: Path = Path.of(System.getProperty("user.home"), ".config/wexdesktop/pdf-reader")) {
    private fun file(book: Path): Path {
        val name = MessageDigest.getInstance("SHA-256").digest(book.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$name.properties")
    }

    fun load(book: Path, pages: Int): PdfReadingState = runCatching {
        val p = Properties()
        val source = file(book)
        if (!Files.isRegularFile(source) || Files.size(source) > 128 * 1024) return PdfReadingState()
        Files.newInputStream(source).use(p::load)
        fun int(key: String, fallback: Int) = p.getProperty(key)?.toIntOrNull() ?: fallback
        fun <T : Enum<T>> enum(key: String, values: Array<T>, fallback: T) = values.firstOrNull { it.name == p.getProperty(key) } ?: fallback
        PdfReadingState(
            page = int("page", 1), offset = int("offset", 0), zoom = p.getProperty("zoom")?.toFloatOrNull() ?: 1f,
            fit = enum("fit", PdfFit.entries.toTypedArray(), PdfFit.WIDTH),
            paper = enum("paper", PdfPaper.entries.toTypedArray(), PdfPaper.SEPIA),
            mode = enum("mode", PdfReadingMode.entries.toTypedArray(), PdfReadingMode.SCROLL),
            rotation = int("rotation", 0), textSize = int("textSize", 20),
            bookmarks = p.getProperty("bookmarks", "").split(',').mapNotNull(String::toIntOrNull).toSet()
        ).bounded(pages)
    }.getOrDefault(PdfReadingState())

    fun save(book: Path, state: PdfReadingState) {
        Files.createDirectories(directory)
        val p = Properties().apply {
            setProperty("page", state.page.toString()); setProperty("offset", state.offset.toString())
            setProperty("zoom", state.zoom.toString()); setProperty("fit", state.fit.name)
            setProperty("paper", state.paper.name); setProperty("mode", state.mode.name)
            setProperty("rotation", state.rotation.toString()); setProperty("textSize", state.textSize.toString())
            setProperty("bookmarks", state.bookmarks.sorted().joinToString(","))
        }
        val tmp = Files.createTempFile(directory, "reading-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { p.store(it, "WEX PDF reader") }
            try { Files.move(tmp, file(book), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, file(book), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
}

internal data class PdfPageSize(val width: Float, val height: Float, val renderEdge: Int)

internal fun pdfPageSize(widthPt: Double, heightPt: Double, viewportWidth: Float, viewportHeight: Float, state: PdfReadingState, density: Float = 1f): PdfPageSize {
    val w = widthPt.takeIf { it.isFinite() && it > 0 }?.coerceIn(1.0, 14400.0)?.toFloat() ?: 595f
    val h = heightPt.takeIf { it.isFinite() && it > 0 }?.coerceIn(1.0, 14400.0)?.toFloat() ?: 842f
    val rotated = state.rotation % 180 != 0
    val width = if (rotated) h else w
    val height = if (rotated) w else h
    val availableW = (viewportWidth - 48f).coerceAtLeast(120f)
    val availableH = (viewportHeight - 48f).coerceAtLeast(120f)
    val fitted = when (state.fit) {
        PdfFit.WIDTH -> availableW / width
        PdfFit.PAGE -> min(availableW / width, availableH / height)
        PdfFit.CUSTOM -> state.bounded(Int.MAX_VALUE).zoom * 96f / 72f
    }
    val scale = min(fitted, 10000f / maxOf(width, height))
    val outW = width * scale
    val outH = height * scale
    // The bitmap budget does not grow with a poster-sized page or a 4x display scale.
    val ratio = min(width, height) / maxOf(width, height)
    val edge = min(4096.0, min(maxOf(outW, outH) * density.coerceIn(1f, 4f).toDouble(), kotlin.math.sqrt(8_000_000.0 / ratio))).toInt().coerceAtLeast(128)
    return PdfPageSize(outW, outH, edge)
}

internal data class PdfSearchHit(val page: Int, val snippet: String, val occurrences: Int)

internal fun pdfSearchHit(page: Int, text: String, query: String): PdfSearchHit? {
    val needle = query.trim().replace(Regex("\\s+"), " ")
    if (needle.isEmpty()) return null
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val first = normalized.indexOf(needle, ignoreCase = true)
    if (first < 0) return null
    var count = 0
    var cursor = first
    while (cursor >= 0 && count < 10_000) {
        count++
        cursor = normalized.indexOf(needle, cursor + needle.length, ignoreCase = true)
    }
    val from = (first - 55).coerceAtLeast(0)
    val to = (first + needle.length + 100).coerceAtMost(normalized.length)
    return PdfSearchHit(page, (if (from > 0) "…" else "") + normalized.substring(from, to) + if (to < normalized.length) "…" else "", count)
}

internal fun pdfMatchIndex(hits: List<PdfSearchHit>, page: Int, backwards: Boolean): Int {
    if (hits.isEmpty()) return -1
    return if (backwards) hits.indexOfLast { it.page < page }.takeIf { it >= 0 } ?: hits.lastIndex
    else hits.indexOfFirst { it.page > page }.takeIf { it >= 0 } ?: 0
}
