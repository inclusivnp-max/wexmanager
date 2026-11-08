package org.wexclient.app.doc

import org.wexclient.app.util.ProcessRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The session owns its raster cache; closing one window cannot invalidate another window's pages. */
internal class PdfBookSession(val path: Path, cacheRoot: Path = Path.of(System.getProperty("user.home"), ".cache/wexdesktop/pdf-reader")) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    private val cache: File

    init {
        Files.createDirectories(cacheRoot)
        cache = Files.createTempDirectory(cacheRoot, "book-").toFile()
    }

    private fun run(args: List<String>, cancelled: () -> Boolean, timeout: Long = 60_000): ProcessRunner.Result {
        if (closed.get() || cancelled()) throw java.util.concurrent.CancellationException()
        val result = ProcessRunner.run(args, timeoutMs = timeout, env = mapOf("LC_ALL" to "C"), isCancelled = { closed.get() || cancelled() })
        if (!result.ok) throw IOException(when {
            result.cancelled -> "Операция отменена"
            result.timedOut -> "PDF обрабатывается слишком долго"
            else -> result.output.takeLast(700).trim().ifEmpty { "Ошибка ${args.first()} (${result.exitCode})" }
        })
        return result
    }

    private inline fun <T> operation(block: () -> T): T {
        active.incrementAndGet()
        try {
            check(!closed.get()) { "Книга уже закрыта" }
            return block()
        } finally { if (active.decrementAndGet() == 0 && closed.get()) cache.deleteRecursively() }
    }

    @Synchronized
    fun render(page: Int, edge: Int, cancelled: () -> Boolean = { false }): ByteArray = operation {
        require(page > 0)
        val bounded = edge.coerceIn(128, 4096)
        val target = File(cache, "page-$page-$bounded.png")
        if (!target.isFile) {
            val prefix = File(cache, "drawing")
            val output = File(cache, "drawing.png")
            try {
                run(listOf("pdftoppm", "-png", "-scale-to", bounded.toString(), "-f", page.toString(), "-l", page.toString(), "-singlefile", path.toAbsolutePath().toString(), prefix.path), cancelled)
                if (!output.isFile || output.length() == 0L) throw IOException("Страница $page не отрисовалась")
                Files.move(output.toPath(), target.toPath())
            } finally { output.delete() }
        }
        target.setLastModified(System.currentTimeMillis())
        val bytes = target.readBytes()
        // Only encoded pages are cached, not native bitmaps. Bound both file count and total disk usage.
        val files = cache.listFiles { f -> f.name.startsWith("page-") }?.sortedByDescending(File::lastModified).orEmpty()
        var size = 0L
        files.forEachIndexed { i, f -> size += f.length(); if (i >= 32 || size > 96L * 1024 * 1024) f.delete() }
        bytes
    }

    fun text(page: Int, cancelled: () -> Boolean = { false }): String = operation {
        require(page > 0)
        run(listOf("pdftotext", "-enc", "UTF-8", "-f", page.toString(), "-l", page.toString(), path.toAbsolutePath().toString(), "-"), cancelled).output.replace("\u000c", "").trim()
    }

    fun search(query: String, pages: Int, cancelled: () -> Boolean = { false }): List<PdfSearchHit> = operation {
        if (query.isBlank()) return emptyList()
        val output = Files.createTempFile(cache.toPath(), "search-", ".txt")
        try {
            run(listOf("pdftotext", "-enc", "UTF-8", path.toAbsolutePath().toString(), output.toString()), cancelled, 120_000)
            if (Files.size(output) > 128L * 1024 * 1024) throw IOException("Текстовый слой слишком большой для поиска (больше 128 МБ)")
            val hits = ArrayList<PdfSearchHit>()
            Files.newBufferedReader(output, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val pageText = StringBuilder()
                var page = 1
                while (page <= pages) {
                    if (closed.get() || cancelled()) throw java.util.concurrent.CancellationException()
                    val n = reader.read(buffer)
                    if (n < 0) { pdfSearchHit(page, pageText.toString(), query)?.let(hits::add); break }
                    for (i in 0 until n) {
                        if (buffer[i] == '\u000c') {
                            pdfSearchHit(page, pageText.toString(), query)?.let(hits::add)
                            pageText.setLength(0); page++
                        } else {
                            if (pageText.length >= 2_000_000) throw IOException("Слишком большой текстовый слой на странице $page")
                            pageText.append(buffer[i])
                        }
                    }
                }
            }
            hits
        } finally { Files.deleteIfExists(output) }
    }

    override fun close() {
        closed.set(true)
        if (active.get() == 0) cache.deleteRecursively()
    }
}
