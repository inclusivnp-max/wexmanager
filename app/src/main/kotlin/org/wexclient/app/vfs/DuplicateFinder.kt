package org.wexclient.app.vfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Файл-кандидат: где лежит, размер, когда изменён и от какой из папок поиска. */
data class DupFile(val path: Path, val size: Long, val modified: Long, val root: Path)

/** Группа одинаковых по содержимому файлов. */
class DupGroup(val size: Long, val files: List<DupFile>) {
    /** Сколько места займут копии, если оставить одну. */
    val wasted: Long get() = size * (files.size - 1)
}

data class DupOptions(
    /** Меньше этого размера не сравниваем: мелочь редко стоит внимания. */
    val minSize: Long = 1,
    /** Скрытые файлы и папки (с точки в начале имени). */
    val includeHidden: Boolean = false
)

/** Ход поиска: этап, найдено файлов и, на этапе сравнения, сколько байт прочитано. */
data class DupProgress(val stage: String, val files: Long, val bytesDone: Long, val bytesTotal: Long, val current: String = "")

class DupResult(
    val roots: List<Path>,
    val groups: List<DupGroup>,
    val scannedFiles: Long,
    val complete: Boolean,
    val errors: Int,
    val millis: Long
)

/**
 * Поиск одинаковых файлов: сначала по размеру, потом по началу и концу файла,
 * и только у совпавших — полное сравнение по SHA-256. Жёсткие ссылки на один и тот же
 * файл дубликатами не считаются: удаление одной не освобождает места.
 */
object DuplicateFinder {

    private const val EDGE = 16 * 1024
    private const val BUFFER = 1 shl 20

    private class Candidate(val file: DupFile, val key: Any?)

    fun find(
        roots: List<Path>,
        options: DupOptions = DupOptions(),
        cancelled: () -> Boolean = { false },
        onProgress: (DupProgress) -> Unit = {}
    ): DupResult {
        val started = System.nanoTime()
        // Папка внутри другой из списка уже просмотрена вместе с ней
        val normalized = roots.map { it.toAbsolutePath().normalize() }.distinct()
        val searchRoots = normalized.filter { r -> normalized.none { o -> o != r && r.startsWith(o) } }

        val bySize = HashMap<Long, MutableList<Candidate>>()
        var files = 0L
        var errors = 0
        var stopped = false
        var lastReport = 0L
        val minSize = options.minSize.coerceAtLeast(1)

        fun report(stage: String, done: Long = 0, total: Long = 0, current: String = "") {
            val now = System.nanoTime()
            if (now - lastReport < 80_000_000L) return
            lastReport = now
            onProgress(DupProgress(stage, files, done, total, current))
        }

        for (root in searchRoots) {
            if (stopped) break
            // Корень-ссылка на папку: без раскрытия обход принял бы его за файл. Идём по настоящему пути,
            // а пути файлов показываем от корня, как его видит пользователь
            val walkRoot = if (Files.isSymbolicLink(root)) runCatching { root.toRealPath() }.getOrDefault(root) else root
            fun shown(p: Path): Path = if (walkRoot === root) p else root.resolve(walkRoot.relativize(p).toString())
            Files.walkFileTree(walkRoot, emptySet(), Int.MAX_VALUE, object : FileVisitor<Path> {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) {
                        stopped = true
                        return FileVisitResult.TERMINATE
                    }
                    if (dir != walkRoot && !options.includeHidden && dir.fileName.toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (dir != walkRoot && isPseudo(dir)) return FileVisitResult.SKIP_SUBTREE
                    report("Сбор файлов", current = dir.toString())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    if (!options.includeHidden && file.fileName.toString().startsWith(".")) return FileVisitResult.CONTINUE
                    val size = attrs.size()
                    if (size < minSize) return FileVisitResult.CONTINUE
                    files++
                    bySize.getOrPut(size) { ArrayList(2) }
                        .add(Candidate(DupFile(shown(file), size, attrs.lastModifiedTime().toMillis(), root), attrs.fileKey()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (file == walkRoot) throw exc
                    errors++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) errors++
                    return FileVisitResult.CONTINUE
                }
            })
        }

        // Одинаковый размер — ещё не повод: оставляем группы, где файлов хотя бы два
        val sameSize = bySize.values.mapNotNull { list ->
            if (list.size < 2) return@mapNotNull null
            // Жёсткие ссылки на один файл — это не копии
            val seen = HashSet<Any>()
            val unique = list.filter { c -> c.key == null || seen.add(c.key) }.map { it.file }
            unique.takeIf { it.size >= 2 }
        }
        bySize.clear()

        val groups = ArrayList<DupGroup>()
        if (!stopped) {
            // Начало и конец файла: отсеивают почти всё за доли секунды
            val headTotal = sameSize.sumOf { g -> g.size.toLong() * minOf(g[0].size, 2L * EDGE) }
            var headDone = 0L
            val needFull = ArrayList<List<DupFile>>()
            loop@ for (group in sameSize) {
                val byEdge = HashMap<String, MutableList<DupFile>>()
                for (f in group) {
                    if (cancelled()) {
                        stopped = true
                        break@loop
                    }
                    val digest = runCatching { edgeHash(f.path, f.size) }.getOrNull()
                    headDone += minOf(f.size, 2L * EDGE)
                    report("Сравнение начала и конца", headDone, headTotal, f.path.toString())
                    if (digest == null) {
                        errors++
                        continue
                    }
                    byEdge.getOrPut(digest) { ArrayList(2) }.add(f)
                }
                for (same in byEdge.values) {
                    if (same.size < 2) continue
                    // Маленький файл прочитан целиком — совпадение уже окончательное
                    if (same[0].size <= 2L * EDGE) groups.add(DupGroup(same[0].size, same)) else needFull.add(same)
                }
            }

            // Полное сравнение только того, что совпало по краям
            val fullTotal = needFull.sumOf { g -> g.size.toLong() * g[0].size }
            var fullDone = 0L
            loop@ for (group in needFull) {
                val byHash = HashMap<String, MutableList<DupFile>>()
                for (f in group) {
                    if (stopped || cancelled()) {
                        stopped = true
                        break@loop
                    }
                    val base = fullDone
                    val digest = runCatching {
                        fullHash(f.path, cancelled) { read ->
                            report("Полное сравнение", base + read, fullTotal, f.path.toString())
                        }
                    }.getOrNull()
                    fullDone = base + f.size
                    if (digest == null) {
                        if (cancelled()) {
                            stopped = true
                            break@loop
                        }
                        errors++
                        continue
                    }
                    byHash.getOrPut(digest) { ArrayList(2) }.add(f)
                }
                for (same in byHash.values) if (same.size >= 2) groups.add(DupGroup(same[0].size, same))
            }
        }

        val sorted = groups
            .map { g -> DupGroup(g.size, g.files.sortedWith(compareBy(org.wexclient.app.util.NaturalOrder) { it.path.toString() })) }
            .sortedWith(compareByDescending<DupGroup> { it.wasted }.thenBy { it.files[0].path.fileName.toString() })
        return DupResult(
            roots = searchRoots,
            groups = sorted,
            scannedFiles = files,
            complete = !stopped,
            errors = errors,
            millis = (System.nanoTime() - started) / 1_000_000
        )
    }

    /** SHA-256 первых и последних 16 КБ (у маленького файла — всего содержимого). */
    internal fun edgeHash(path: Path, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            if (size <= 2L * EDGE) {
                val buf = ByteBuffer.allocate(size.toInt())
                while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
                buf.flip()
                md.update(buf)
            } else {
                val buf = ByteBuffer.allocate(EDGE)
                readAt(ch, buf, 0)
                md.update(buf)
                buf.clear()
                readAt(ch, buf, size - EDGE)
                md.update(buf)
            }
        }
        return md.digest().toHex()
    }

    private fun readAt(ch: FileChannel, buf: ByteBuffer, position: Long) {
        var pos = position
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        buf.flip()
    }

    /** SHA-256 всего файла; [cancelled] проверяется на каждом мегабайте. */
    internal fun fullHash(path: Path, cancelled: () -> Boolean = { false }, onRead: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var total = 0L
        Files.newInputStream(path).use { input ->
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
                onRead(total)
            }
        }
        return md.digest().toHex()
    }

    private val pseudoFs = setOf("proc", "sysfs", "devtmpfs", "devpts", "cgroup", "cgroup2", "debugfs", "tracefs", "securityfs", "pstore", "bpf", "configfs", "efivarfs")

    private fun isPseudo(dir: Path): Boolean {
        val name = dir.toString()
        if (name != "/proc" && name != "/sys" && name != "/dev" && name != "/run") return false
        return runCatching { Files.getFileStore(dir).type() in pseudoFs || name == "/dev" || name == "/run" }.getOrDefault(false)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Отметить в каждой группе всё, кроме одного файла, выбранного [keep]. */
    /**
     * «Оставить копии в [root]»: в группах, где есть копия внутри [root], отмечаются все копии вне его.
     * Копии внутри [root] не трогаются никогда; группы только вне его — тоже.
     */
    fun markOutside(groups: List<DupGroup>, root: Path): Set<Path> {
        val marked = HashSet<Path>()
        for (g in groups) {
            if (g.files.none { it.root == root }) continue
            for (f in g.files) if (f.root != root) marked.add(f.path)
        }
        return marked
    }

    fun markAllBut(groups: List<DupGroup>, keep: (DupGroup) -> DupFile): Set<Path> {
        val marked = HashSet<Path>()
        for (g in groups) {
            if (g.files.size < 2) continue
            val kept = keep(g)
            for (f in g.files) if (f.path != kept.path) marked.add(f.path)
        }
        return marked
    }
}
