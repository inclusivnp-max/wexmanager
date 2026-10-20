package org.wexclient.app.vfs

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.wexclient.app.util.withUserEnv
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import org.wexclient.app.util.startHelper

/** Что искать внутри файлов. */
data class ContentQuery(
    val text: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val includeHidden: Boolean = false,
    /** Пропускать то, что в .gitignore (как ripgrep по умолчанию) */
    val respectIgnore: Boolean = true,
    /** Маски имён: «*.kt», «!*.min.js» */
    val globs: List<String> = emptyList()
) {
    companion object {
        /** «*.kt, *.java !build» → [*.kt, *.java, !build] */
        fun parseGlobs(text: String): List<String> =
            text.split(',', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Найденная строка. [preview] — строка (длинная — обрезана вокруг совпадения),
 * [ranges] — совпадения в ней; [column] и [length] — в исходной строке, для перехода.
 */
class ContentMatch(
    val path: Path,
    val line: Int,
    val column: Int,
    val length: Int,
    val preview: String,
    val ranges: List<IntRange>,
    val clippedStart: Boolean,
    val clippedEnd: Boolean
)

class ContentSearchResult(
    val matches: Int,
    val files: Int,
    /** Упёрлись в предел — показаны не все совпадения */
    val truncated: Boolean,
    val error: String?,
    /** «ripgrep» или «встроенный поиск» */
    val engine: String
)

/** Поиск текста в файлах папки: ripgrep, а без него (или внутри архива) — обход на Kotlin. */
object ContentSearch {
    const val MAX_MATCHES = 5000
    private const val PREVIEW_CHARS = 240
    private const val MAX_FILE_BYTES = 20L shl 20
    private const val BATCH = 64
    private const val MAX_SUBMATCHES = 256

    // Не lazy: ripgrep, поставленный при работающем процессе, находится без перезапуска
    private val rgAvailable: Boolean get() = org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("rg")

    fun usesRipgrep(dir: Path): Boolean = rgAvailable && dir.fileSystem === FileSystems.getDefault()

    /**
     * Ищет в [dir] и подпапках; находки приходят пачками в [onBatch] (из фонового потока).
     * Отмена — через [token].
     */
    fun search(
        dir: Path,
        query: ContentQuery,
        token: SearchToken,
        forceBuiltIn: Boolean = false,
        onBatch: (List<ContentMatch>) -> Unit
    ): ContentSearchResult {
        if (query.text.isEmpty()) return ContentSearchResult(0, 0, false, null, "")
        return if (!forceBuiltIn && usesRipgrep(dir)) searchWithRipgrep(dir, query, token, onBatch)
        else searchBuiltIn(dir, query, token, onBatch)
    }

    // ---------- ripgrep ----------

    internal fun ripgrepCommand(dir: Path, query: ContentQuery): List<String> = buildList {
        add("rg")
        add("--json")
        add("--no-config")
        add("--no-messages")
        add("--max-filesize")
        add("20M")
        add(if (query.caseSensitive) "--case-sensitive" else "--ignore-case")
        if (!query.regex) add("--fixed-strings")
        if (query.wholeWord) add("--word-regexp")
        if (query.includeHidden) {
            add("--hidden")
            add("--glob")
            add("!.git")
        }
        if (!query.respectIgnore) add("--no-ignore")
        for (g in query.globs) {
            add("--iglob")
            add(g)
        }
        add("--regexp")
        add(query.text)
        add("--")
        add(dir.toAbsolutePath().toString())
    }

    private fun searchWithRipgrep(
        dir: Path,
        query: ContentQuery,
        token: SearchToken,
        onBatch: (List<ContentMatch>) -> Unit
    ): ContentSearchResult {
        val pb = ProcessBuilder(ripgrepCommand(dir, query)).directory(dir.toFile()).withUserEnv()
        pb.environment().remove("RIPGREP_CONFIG_PATH")
        val process = try {
            pb.startHelper()
        } catch (e: IOException) {
            return searchBuiltIn(dir, query, token, onBatch)
        }
        token.attach(process)
        process.outputStream.close()
        val stderr = ByteArrayOutputStream()
        val errReader = Thread {
            runCatching {
                process.errorStream.use { s ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (stderr.size() < 16_000) stderr.write(buf, 0, n)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        var matches = 0
        var files = 0
        var truncated = false
        val batch = ArrayList<ContentMatch>()
        var lastFlush = System.nanoTime()
        fun flush() {
            if (batch.isEmpty()) return
            onBatch(batch.toList())
            batch.clear()
            lastFlush = System.nanoTime()
        }
        try {
            // Потоком, а не строками: строка минифицированного файла — это JSON в десятки мегабайт
            // с сотнями тысяч совпадений (--max-columns в режиме --json не действует)
            val reader = JsonReader(process.inputStream.bufferedReader(Charsets.UTF_8))
            @Suppress("DEPRECATION")
            reader.isLenient = true
            while (!token.isCancelled && reader.peek() != JsonToken.END_DOCUMENT) {
                when (val msg = readRipgrepMessage(reader)) {
                    RgMessage.Begin -> files++
                    is RgMessage.Found -> {
                        batch.add(msg.match)
                        matches++
                        if (matches >= MAX_MATCHES) {
                            truncated = true
                            break
                        }
                        if (batch.size >= BATCH || System.nanoTime() - lastFlush > 120_000_000L) flush()
                    }
                    null -> Unit
                }
            }
        } catch (_: IOException) {
            // процесс убит отменой (или поток оборван)
        } catch (_: IllegalStateException) {
            // неожиданная структура вывода — что успели, то показываем
        } finally {
            if (truncated || token.isCancelled) process.destroyForcibly()
        }
        flush()
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        errReader.join(1000)
        val err = stderr.toString("UTF-8").trim()
        // 1 — ничего не нашлось; 2 — ошибка (например, в регулярном выражении)
        val error = if (code == 2 && err.isNotEmpty() && !token.isCancelled) cleanRipgrepError(err) else null
        return ContentSearchResult(matches, files, truncated, error, "ripgrep")
    }

    private fun cleanRipgrepError(err: String): String =
        err.lines().filter { it.isNotBlank() }.joinToString("\n") { it.removePrefix("rg: ") }.take(600)

    private sealed interface RgMessage {
        data object Begin : RgMessage
        class Found(val match: ContentMatch) : RgMessage
    }

    /** Одно сообщение rg --json; «type» у rg идёт раньше «data». */
    private fun readRipgrepMessage(r: JsonReader): RgMessage? {
        var type: String? = null
        var result: RgMessage? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "data" -> if (type == "match") result = readRipgrepMatch(r)?.let { RgMessage.Found(it) } else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (type == "begin") RgMessage.Begin else result
    }

    /** {"text": …} или {"bytes": base64} */
    private fun readTextOrBytes(r: JsonReader): String? {
        var out: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "text" -> out = r.nextString()
                "bytes" -> out = runCatching { String(Base64.getDecoder().decode(r.nextString()), Charsets.UTF_8) }.getOrNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return out
    }

    private fun readRipgrepMatch(r: JsonReader): ContentMatch? {
        var pathText: String? = null
        var lineText: String? = null
        var lineNumber: Int? = null
        val subs = ArrayList<IntArray>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "path" -> pathText = readTextOrBytes(r)
                "lines" -> lineText = readTextOrBytes(r)
                "line_number" -> if (r.peek() == JsonToken.NULL) r.nextNull() else lineNumber = r.nextInt()
                "submatches" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        // Превью — 240 символов: сотни тысяч совпадений строки не нужны
                        if (subs.size >= MAX_SUBMATCHES) {
                            r.skipValue()
                            continue
                        }
                        var start = -1
                        var end = -1
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "start" -> start = r.nextInt()
                                "end" -> end = r.nextInt()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (start >= 0 && end >= start) subs.add(intArrayOf(start, end))
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val path = pathText?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        return matchFrom(path, lineText ?: return null, lineNumber ?: return null, subs)
    }

    /** Совпадения строки по байтовым смещениям rg: смещения пересчитываются в символы за один проход. */
    internal fun matchFrom(path: Path, lineText: String, lineNumber: Int, subs: List<IntArray>): ContentMatch {
        // Несколько строк бывает только у многострочных выражений — показываем первую
        val first = lineText.substringBefore('\n').removeSuffix("\r")
        val ranges = ArrayList<IntRange>()
        val cursor = Utf8Cursor(lineText)
        for (sm in subs) {
            val start = cursor.charIndex(sm[0])
            if (start >= first.length) break
            val end = cursor.charIndex(sm[1])
            ranges.add(start until minOf(end, first.length).coerceAtLeast(start + 1))
            // Дальше превью подсветку не видно
            if (start > ranges[0].first + PREVIEW_CHARS) break
        }
        return makeMatch(path, lineNumber, first, ranges)
    }

    /** Байтовые смещения UTF-8 (по возрастанию) → индексы символов строки. */
    private class Utf8Cursor(private val s: String) {
        private var bytes = 0
        private var i = 0

        fun charIndex(byteOffset: Int): Int {
            if (byteOffset < bytes) {
                bytes = 0
                i = 0
            }
            while (i < s.length && bytes < byteOffset) {
                val c = s[i]
                when {
                    c.code < 0x80 -> bytes += 1
                    c.code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        bytes += 4
                        i++
                    }
                    else -> bytes += 3
                }
                i++
            }
            return i
        }
    }

    /** Смещение в байтах UTF-8 → индекс символа строки. */
    internal fun charIndex(s: String, byteOffset: Int): Int = Utf8Cursor(s).charIndex(byteOffset)

    /** Строка для списка: длинная обрезается вокруг первого совпадения. */
    internal fun makeMatch(path: Path, line: Int, text: String, ranges: List<IntRange>): ContentMatch {
        val firstRange = ranges.firstOrNull()
        val column = (firstRange?.first ?: 0) + 1
        val length = firstRange?.let { it.last - it.first + 1 } ?: 0
        // Ведущие отступы в списке не нужны
        val indent = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
        var from = indent.coerceAtMost(firstRange?.first ?: indent)
        var to = text.length
        if (to - from > PREVIEW_CHARS) {
            val anchor = firstRange?.first ?: from
            from = maxOf(from, anchor - PREVIEW_CHARS / 4)
            to = minOf(text.length, from + PREVIEW_CHARS)
        }
        val preview = text.substring(from, to).replace('\t', ' ')
        val shifted = ranges.mapNotNull { r ->
            val s = r.first - from
            val e = minOf(r.last - from, preview.length - 1)
            if (s < 0 || s >= preview.length || e < s) null else s..e
        }
        return ContentMatch(path, line, column, length, preview, shifted, clippedStart = from > indent, clippedEnd = to < text.length)
    }

    // ---------- Встроенный поиск ----------

    internal fun buildRegex(query: ContentQuery): Regex {
        val body = if (query.regex) query.text else Regex.escape(query.text)
        val pattern = if (query.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        // Kotlin сам добавляет UNICODE_CASE к IGNORE_CASE — кириллица тоже без учёта регистра
        return if (query.caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private fun globMatchers(dir: Path, globs: List<String>): Pair<List<PathMatcher>, List<PathMatcher>> {
        val fs = dir.fileSystem
        fun matcher(g: String): PathMatcher? = runCatching { fs.getPathMatcher("glob:" + g.lowercase()) }.getOrNull()
        val include = globs.filter { !it.startsWith("!") }.mapNotNull(::matcher)
        val exclude = globs.filter { it.startsWith("!") }.mapNotNull { matcher(it.drop(1)) }
        return include to exclude
    }

    private fun searchBuiltIn(
        dir: Path,
        query: ContentQuery,
        token: SearchToken,
        onBatch: (List<ContentMatch>) -> Unit
    ): ContentSearchResult {
        val regex = try {
            buildRegex(query)
        } catch (e: Exception) {
            return ContentSearchResult(0, 0, false, "Ошибка в выражении: ${e.message ?: e.javaClass.simpleName}", "встроенный поиск")
        }
        val (include, exclude) = globMatchers(dir, query.globs)
        val fs = dir.fileSystem
        fun nameMatches(p: Path, list: List<PathMatcher>): Boolean {
            val name = p.fileName?.toString()?.trimEnd('/')?.lowercase() ?: return false
            val namePath = runCatching { fs.getPath(name) }.getOrNull() ?: return false
            return list.any { it.matches(namePath) }
        }
        var matches = 0
        var files = 0
        var truncated = false
        val batch = ArrayList<ContentMatch>()
        val decoder = { bytes: ByteArray ->
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }

        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (token.isCancelled || truncated) return FileVisitResult.TERMINATE
                if (d == dir) return FileVisitResult.CONTINUE
                val name = d.fileName?.toString()?.trimEnd('/') ?: return FileVisitResult.CONTINUE
                if (name == ".git" || (!query.includeHidden && name.startsWith("."))) return FileVisitResult.SKIP_SUBTREE
                if (exclude.isNotEmpty() && nameMatches(d, exclude)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (token.isCancelled || truncated) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile || attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE
                val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                if (!query.includeHidden && name.startsWith(".")) return FileVisitResult.CONTINUE
                if (include.isNotEmpty() && !nameMatches(file, include)) return FileVisitResult.CONTINUE
                if (exclude.isNotEmpty() && nameMatches(file, exclude)) return FileVisitResult.CONTINUE
                val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return FileVisitResult.CONTINUE
                // Двоичные файлы (ноль в начале) — мимо, как у ripgrep
                val head = minOf(bytes.size, 8192)
                for (i in 0 until head) if (bytes[i].toInt() == 0) return FileVisitResult.CONTINUE
                val text = decoder(bytes)
                var fileHasMatch = false
                var lineNo = 0
                var start = 0
                while (start <= text.length) {
                    val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                    lineNo++
                    val line = text.substring(start, nl).removeSuffix("\r")
                    val found = regex.findAll(line).take(50).map { it.range }.filter { !it.isEmpty() }.toList()
                    if (found.isNotEmpty()) {
                        if (!fileHasMatch) {
                            fileHasMatch = true
                            files++
                        }
                        batch.add(makeMatch(file, lineNo, line, found))
                        matches++
                        if (batch.size >= BATCH) {
                            onBatch(batch.toList())
                            batch.clear()
                        }
                        if (matches >= MAX_MATCHES) {
                            truncated = true
                            return FileVisitResult.TERMINATE
                        }
                    }
                    if (nl >= text.length) break
                    start = nl + 1
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return ContentSearchResult(matches, files, truncated, null, "встроенный поиск")
    }
}
