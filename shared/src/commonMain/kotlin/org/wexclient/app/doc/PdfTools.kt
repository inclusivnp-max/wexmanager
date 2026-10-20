package org.wexclient.app.doc

import org.wexclient.app.util.ProcessRunner
import java.io.File
import java.nio.file.Path

/** Сведения о PDF из pdfinfo. */
data class PdfInfo(
    val pages: Int,
    val title: String?,
    val author: String?,
    val creator: String?,
    val created: String?,
    val pageWidth: Double,
    val pageHeight: Double,
    val encrypted: Boolean,
    val version: String?
) {
    /** «210 × 297 мм» — привычный размер страницы вместо пунктов. */
    val pageSizeMm: String
        get() {
            val w = (pageWidth * 25.4 / 72).toInt()
            val h = (pageHeight * 25.4 / 72).toInt()
            return "$w × $h мм"
        }
}

/**
 * Работа с PDF через poppler: разбор своего формата PDF — это отдельная библиотека на месяцы,
 * а pdftoppm с pdftotext стоят почти в каждой системе и делают это быстро и верно.
 */
object PdfTools {

    /** Есть ли чем показывать PDF. */
    val available: Boolean get() = ProcessRunner.isAvailable("pdftoppm")

    val textAvailable: Boolean get() = ProcessRunner.isAvailable("pdftotext")

    /** Название пакета, который нужно поставить, если утилит нет. */
    const val PACKAGE = "poppler"

    fun info(path: Path): PdfInfo? {
        val result = runCatching {
            ProcessRunner.run(listOf("pdfinfo", path.toAbsolutePath().toString()), timeoutMs = 15_000)
        }.getOrNull() ?: return null
        if (result.output.isBlank()) return null

        val fields = HashMap<String, String>()
        for (line in result.output.lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        val pages = fields["Pages"]?.toIntOrNull() ?: return null
        // «Page size: 595.276 x 841.89 pts (A4)»
        val size = fields["Page size"].orEmpty()
        val numbers = Regex("""([\d.]+)\s*x\s*([\d.]+)""").find(size)
        return PdfInfo(
            pages = pages,
            title = fields["Title"]?.takeIf { it.isNotBlank() },
            author = fields["Author"]?.takeIf { it.isNotBlank() },
            creator = fields["Creator"]?.takeIf { it.isNotBlank() },
            created = fields["CreationDate"]?.takeIf { it.isNotBlank() },
            pageWidth = numbers?.groupValues?.get(1)?.toDoubleOrNull() ?: 595.0,
            pageHeight = numbers?.groupValues?.get(2)?.toDoubleOrNull() ?: 842.0,
            encrypted = fields["Encrypted"]?.startsWith("yes") == true,
            version = fields["PDF version"]
        )
    }

    /**
     * Страница картинкой. Файл кладётся в [outDir] под именем с номером и плотностью,
     * так что повторный показ той же страницы ничего не пересчитывает.
     */
    fun renderPage(path: Path, page: Int, dpi: Int, outDir: File): File? {
        val ready = File(outDir, "p${page}_$dpi.png")
        if (ready.isFile && ready.length() > 0) return ready
        outDir.mkdirs()
        val prefix = File(outDir, "tmp_${page}_$dpi")
        val result = runCatching {
            ProcessRunner.run(
                listOf(
                    "pdftoppm", "-png", "-r", dpi.toString(),
                    "-f", page.toString(), "-l", page.toString(),
                    "-singlefile",
                    path.toAbsolutePath().toString(), prefix.absolutePath
                ),
                timeoutMs = 60_000
            )
        }.getOrNull() ?: return null
        if (!result.ok) return null
        val produced = File(prefix.absolutePath + ".png")
        if (!produced.isFile) return null
        return if (produced.renameTo(ready)) ready else produced
    }

    /** Текст страницы (или всего документа, если [page] не задан). Пустая строка — текста нет. */
    fun text(path: Path, page: Int? = null): String {
        if (!textAvailable) return ""
        val args = buildList {
            add("pdftotext")
            add("-layout")
            if (page != null) {
                add("-f"); add(page.toString())
                add("-l"); add(page.toString())
            }
            add(path.toAbsolutePath().toString())
            add("-") // вывод в stdout
        }
        val result = runCatching { ProcessRunner.run(args, timeoutMs = 60_000) }.getOrNull() ?: return ""
        return if (result.ok) result.output else ""
    }

    /** Свободное имя рядом: «имя (2).pdf», если такое уже есть. */
    fun freeName(dir: Path, base: String, ext: String): Path {
        var candidate = dir.resolve("$base.$ext")
        var n = 2
        while (java.nio.file.Files.exists(candidate)) {
            candidate = dir.resolve("$base ($n).$ext")
            n++
        }
        return candidate
    }

    /** Свободное имя папки рядом: «имя (2)», если такая уже есть. */
    fun freeDir(parent: Path, base: String): Path {
        var candidate = parent.resolve(base)
        var n = 2
        while (java.nio.file.Files.exists(candidate)) {
            candidate = parent.resolve("$base ($n)")
            n++
        }
        return candidate
    }

    /** Каждая страница — отдельным файлом в новой папке рядом. Возвращает эту папку. */
    fun split(path: Path): Path {
        val base = path.fileName.toString().substringBeforeLast('.')
        val dir = freeDir(path.toAbsolutePath().parent, "$base — страницы")
        java.nio.file.Files.createDirectories(dir)
        val result = ProcessRunner.run(
            listOf("pdfseparate", path.toAbsolutePath().toString(), dir.resolve("$base-%d.pdf").toString()),
            timeoutMs = 10 * 60_000
        )
        if (!result.ok) throw java.io.IOException(result.output.lines().lastOrNull { it.isNotBlank() } ?: "pdfseparate не справился")
        return dir
    }

    /** Страницы с [from] по [to] включительно — одним новым PDF рядом. */
    fun extract(path: Path, from: Int, to: Int): Path {
        val base = path.fileName.toString().substringBeforeLast('.')
        val target = freeName(path.toAbsolutePath().parent, "$base (стр. $from–$to)", "pdf")
        val temp = java.nio.file.Files.createTempDirectory("wex-pdf")
        try {
            val split = ProcessRunner.run(
                listOf(
                    "pdfseparate", "-f", from.toString(), "-l", to.toString(),
                    path.toAbsolutePath().toString(), temp.resolve("p-%d.pdf").toString()
                ),
                timeoutMs = 10 * 60_000
            )
            if (!split.ok) throw java.io.IOException("pdfseparate не справился")
            val pages = (from..to).map { temp.resolve("p-$it.pdf").toString() }
            val unite = ProcessRunner.run(listOf("pdfunite") + pages + target.toString(), timeoutMs = 10 * 60_000)
            if (!unite.ok) throw java.io.IOException("pdfunite не справился")
        } finally {
            runCatching { temp.toFile().deleteRecursively() }
        }
        return target
    }

    /** Несколько PDF — в один, по порядку. Результат кладётся рядом с первым. */
    fun merge(paths: List<Path>): Path {
        require(paths.size >= 2) { "Нужно хотя бы два PDF" }
        val first = paths.first()
        val target = freeName(first.toAbsolutePath().parent, "Объединённый", "pdf")
        val result = ProcessRunner.run(
            listOf("pdfunite") + paths.map { it.toAbsolutePath().toString() } + target.toString(),
            timeoutMs = 10 * 60_000
        )
        if (!result.ok) throw java.io.IOException(result.output.lines().lastOrNull { it.isNotBlank() } ?: "pdfunite не справился")
        return target
    }

    /** Все картинки документа — в новую папку рядом. Возвращает папку. */
    fun extractImages(path: Path): Path {
        val base = path.fileName.toString().substringBeforeLast('.')
        val dir = freeDir(path.toAbsolutePath().parent, "$base — картинки")
        java.nio.file.Files.createDirectories(dir)
        val result = ProcessRunner.run(
            listOf("pdfimages", "-png", path.toAbsolutePath().toString(), dir.resolve("img").toString()),
            timeoutMs = 10 * 60_000
        )
        if (!result.ok) throw java.io.IOException(result.output.lines().lastOrNull { it.isNotBlank() } ?: "pdfimages не справился")
        return dir
    }

    /** Страницы, где встречается искомое. Номера — от единицы. */
    fun findPages(path: Path, query: String, pages: Int, onProgress: ((Int) -> Unit)? = null): List<Int> {
        if (query.isBlank() || !textAvailable) return emptyList()
        val found = ArrayList<Int>()
        for (page in 1..pages) {
            onProgress?.invoke(page)
            if (text(path, page).contains(query, ignoreCase = true)) found += page
        }
        return found
    }
}
