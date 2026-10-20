package org.wexclient.app.vfs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import org.wexclient.app.util.startHelper

/** Отмена летящего поиска: флаг + убийство fd (иначе readLine висит до конца обхода). */
class SearchToken {
    @Volatile
    var isCancelled = false
        private set

    @Volatile
    private var process: Process? = null

    internal fun attach(p: Process) {
        process = p
        if (isCancelled) p.destroyForcibly()
    }

    fun cancel() {
        isCancelled = true
        process?.destroyForcibly()
    }
}

object FastSearchEngine {

    /** Первые находки — не раньше чем через 30 мс (быстрый поиск обойдётся без мигания), дальше раз в 80 мс. */
    private const val FIRST_PROGRESS_MS = 30L
    private const val PROGRESS_INTERVAL_MS = 80L

    val isFdAvailable: Boolean by lazy {
        try {
            val process = ProcessBuilder("fd", "--version")
                .redirectOutput(org.wexclient.app.util.DiscardOutput)
                .redirectError(org.wexclient.app.util.DiscardOutput)
                .start()
            val ok = process.waitFor(1, TimeUnit.SECONDS)
            // Не ответил за секунду — убираем за собой
            if (!ok) process.destroyForcibly()
            ok && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Поиск по имени (без учёта регистра).
     * @param includeHidden false — не заходить в скрытые папки и не показывать скрытые файлы
     * @param onProgress промежуточные результаты (вызывается из потока поиска)
     */
    fun search(
        currentPath: Path,
        query: String,
        recursive: Boolean,
        vfsManager: VfsManager,
        maxResults: Int = 500,
        includeHidden: Boolean = true,
        token: SearchToken = SearchToken(),
        onProgress: ((List<VfsEntry>) -> Unit)? = null
    ): List<VfsEntry> {
        if (query.isBlank()) return emptyList()

        val isRealFs = currentPath.fileSystem == FileSystems.getDefault()

        return if (recursive && isRealFs && isFdAvailable) {
            searchWithFd(currentPath, query, maxResults, includeHidden, token, onProgress)
        } else {
            searchWithNio(currentPath, query, recursive, vfsManager, maxResults, includeHidden, token)
        }
    }

    internal fun fdCommand(root: Path, query: String, maxResults: Int, includeHidden: Boolean): List<String> {
        val cmd = mutableListOf("fd")
        if (includeHidden) cmd.add("-H") // скрытые файлы и папки
        cmd.addAll(listOf(
            "-I", // и то, что в .gitignore (build/ с APK и т.п.)
            "-a", // абсолютные пути
            "-i", // без учёта регистра
            "-F", // подстрока, не регулярка
            // Пути через NUL: имя с переводом строки не должно превращаться в «другой» путь
            "-0",
            "--max-results", maxResults.toString()
        ))
        // От корня псевдо-ФС не нужны: там тысячи узлов и ошибки доступа
        if (root.parent == null) {
            for (dir in listOf("/proc", "/sys", "/dev", "/run")) cmd.addAll(listOf("-E", dir))
        }
        // "--": запрос вида "-foo" не должен стать опцией
        cmd.addAll(listOf("--", query, root.toString()))
        return cmd
    }

    private fun searchWithFd(
        rootPath: Path,
        query: String,
        maxResults: Int,
        includeHidden: Boolean,
        token: SearchToken,
        onProgress: ((List<VfsEntry>) -> Unit)?
    ): List<VfsEntry> {
        val results = ArrayList<VfsEntry>()
        var process: Process? = null
        try {
            val rootAbs = rootPath.toAbsolutePath().normalize()
            process = ProcessBuilder(fdCommand(rootAbs, query, maxResults, includeHidden))
                .redirectError(org.wexclient.app.util.DiscardOutput)
                .startHelper()
            token.attach(process)

            val started = System.nanoTime()
            var lastPublished = 0L
            var publishedCount = 0
            java.io.BufferedInputStream(process.inputStream, 1 shl 16).use { input ->
                val name = java.io.ByteArrayOutputStream(256)
                while (!token.isCancelled && results.size < maxResults) {
                    // Одна запись до NUL; пробелы по краям — часть имени, не обрезаем
                    name.reset()
                    var b = input.read()
                    while (b > 0) {
                        name.write(b)
                        b = input.read()
                    }
                    if (b < 0 && name.size() == 0) break
                    if (name.size() == 0) continue
                    val path = try {
                        Paths.get(name.toString("UTF-8"))
                    } catch (_: java.nio.file.InvalidPathException) {
                        continue
                    }
                    if (path == rootAbs) continue
                    entryOf(path)?.let { results.add(it) }

                    if (onProgress != null && results.size != publishedCount) {
                        val now = System.nanoTime()
                        val due = if (publishedCount == 0) {
                            now - started >= TimeUnit.MILLISECONDS.toNanos(FIRST_PROGRESS_MS)
                        } else {
                            now - lastPublished >= TimeUnit.MILLISECONDS.toNanos(PROGRESS_INTERVAL_MS)
                        }
                        if (due) {
                            onProgress(ArrayList(results))
                            publishedCount = results.size
                            lastPublished = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!token.isCancelled) e.printStackTrace()
        } finally {
            process?.let { p ->
                // Досрочный выход (лимит/отмена) — fd больше не нужен
                if (p.isAlive) p.destroyForcibly()
                p.waitFor(1, TimeUnit.SECONDS)
            }
        }
        return results
    }

    /** Один системный вызов на файл (вместо exists/isDirectory/size/mtime по отдельности). */
    private fun entryOf(path: Path): VfsEntry? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (_: Exception) {
            return null // удалён между обходом и чтением
        }
        val isDir = attrs.isDirectory
        val name = path.fileName?.toString() ?: path.toString()
        val ext = name.substringAfterLast('.', "")
        return VfsEntry(
            name = name,
            path = path,
            isDirectory = isDir,
            isArchive = !isDir && org.wexclient.app.vfs.archive.ArchiveFormats.isBrowsable(name),
            size = if (isDir) 0L else attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis(),
            itemType = VfsEntry.detectType(name, isDir),
            extension = ext
        )
    }

    private fun searchWithNio(
        rootPath: Path,
        query: String,
        recursive: Boolean,
        vfsManager: VfsManager,
        maxResults: Int,
        includeHidden: Boolean,
        token: SearchToken
    ): List<VfsEntry> {
        val q = query.lowercase()
        val results = mutableListOf<VfsEntry>()

        if (!recursive) {
            val raw = vfsManager.listEntries(rootPath)
            return raw.filter { it.name.lowercase().contains(q) }
        }

        try {
            Files.walk(rootPath, 8).use { stream ->
                stream.filter { path ->
                    if (token.isCancelled) throw CancelledSearch()
                    val name = path.fileName?.toString() ?: ""
                    path != rootPath &&
                        name.lowercase().contains(q) &&
                        (includeHidden || rootPath.relativize(path).none { it.toString().startsWith(".") })
                }.limit(maxResults.toLong()).forEach { path ->
                    entryOf(path)?.let { results.add(it) }
                }
            }
        } catch (_: CancelledSearch) {
            // новый запрос уже ищется
        } catch (e: Exception) {
            val raw = vfsManager.listEntries(rootPath)
            results.addAll(raw.filter { it.name.lowercase().contains(q) })
        }
        return results
    }

    private class CancelledSearch : RuntimeException()
}
