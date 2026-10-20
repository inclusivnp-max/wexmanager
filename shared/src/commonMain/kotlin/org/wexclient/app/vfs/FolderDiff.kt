package org.wexclient.app.vfs

import org.wexclient.app.util.readInto

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Arrays

/** Чем запись в левой папке отличается от той же в правой. */
enum class FolderDiffKind {
    LEFT_ONLY,
    RIGHT_ONLY,
    LEFT_NEWER,
    RIGHT_NEWER,
    /** Размер разный, а время одинаковое (или содержимое разное при одинаковом размере). */
    DIFFERENT,
    SAME
}

/** Сведения о файле с одной стороны. */
data class FolderSide(val size: Long, val modified: Long, val directory: Boolean)

/** Строка сравнения: путь от корня сравниваемых папок и что с ним по обе стороны. */
data class FolderDiffEntry(
    val relative: String,
    val left: FolderSide?,
    val right: FolderSide?,
    val kind: FolderDiffKind
) {
    val directory: Boolean get() = (left ?: right)?.directory == true
}

/** Как сравнивать. */
data class FolderDiffOptions(
    val recursive: Boolean = true,
    /** Одинаковый размер — ещё не одинаковое содержимое: сверять хешем. Медленнее, но точно. */
    val byContent: Boolean = false,
    val includeHidden: Boolean = false,
    /** Разница во времени, которую не считаем изменением: FAT хранит время с шагом в две секунды. */
    val timeToleranceMs: Long = 2_000
)

/**
 * Сравнение двух папок: что есть только здесь, только там и какая копия новее.
 * Результат — плоский список путей, пригодный и для показа, и для синхронизации.
 */
object FolderDiff {

    fun compare(
        left: Path,
        right: Path,
        options: FolderDiffOptions = FolderDiffOptions(),
        cancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {}
    ): List<FolderDiffEntry> {
        val leftFiles = collect(left, options, cancelled, onProgress)
        val rightFiles = collect(right, options, cancelled, onProgress)
        val names = (leftFiles.keys + rightFiles.keys).toSortedSet()
        val result = ArrayList<FolderDiffEntry>(names.size)
        for (name in names) {
            if (cancelled()) break
            val l = leftFiles[name]
            val r = rightFiles[name]
            val kind = when {
                l == null -> FolderDiffKind.RIGHT_ONLY
                r == null -> FolderDiffKind.LEFT_ONLY
                l.directory || r.directory -> if (l.directory == r.directory) FolderDiffKind.SAME else FolderDiffKind.DIFFERENT
                else -> compareFiles(left.resolve(name), right.resolve(name), l, r, options, cancelled)
            }
            result += FolderDiffEntry(name, l, r, kind)
        }
        return result
    }

    private fun compareFiles(
        leftPath: Path,
        rightPath: Path,
        l: FolderSide,
        r: FolderSide,
        options: FolderDiffOptions,
        cancelled: () -> Boolean
    ): FolderDiffKind {
        val sameTime = kotlin.math.abs(l.modified - r.modified) <= options.timeToleranceMs
        if (l.size == r.size) {
            if (!options.byContent) {
                return if (sameTime) FolderDiffKind.SAME else if (l.modified > r.modified) FolderDiffKind.LEFT_NEWER else FolderDiffKind.RIGHT_NEWER
            }
            // Содержимое решает окончательно: время могло поменяться при копировании
            if (sameContent(leftPath, rightPath, cancelled)) return FolderDiffKind.SAME
        }
        return when {
            sameTime -> FolderDiffKind.DIFFERENT
            l.modified > r.modified -> FolderDiffKind.LEFT_NEWER
            else -> FolderDiffKind.RIGHT_NEWER
        }
    }

    /** Все файлы и папки под корнем: путь относительно корня → сведения. */
    private fun collect(
        root: Path,
        options: FolderDiffOptions,
        cancelled: () -> Boolean,
        onProgress: (String) -> Unit
    ): Map<String, FolderSide> {
        val result = HashMap<String, FolderSide>()
        if (!Files.isDirectory(root)) return result
        fun walk(dir: Path, prefix: String) {
            if (cancelled()) return
            onProgress(prefix.ifEmpty { root.fileName?.toString() ?: root.toString() })
            val children = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (child in children) {
                val name = child.fileName?.toString() ?: continue
                if (!options.includeHidden && name.startsWith(".")) continue
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                // Ссылки не разворачиваем: они могли бы увести за пределы папки или в петлю
                val isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                val size = if (isDir) 0L else runCatching { Files.size(child) }.getOrDefault(0L)
                val modified = runCatching { Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L)
                result[relative] = FolderSide(size, modified, isDir)
                if (isDir && options.recursive) walk(child, relative)
            }
        }
        walk(root, "")
        return result
    }

    /**
     * Одинаково ли содержимое: побайтово, порциями, с выходом на первом различии и по отмене. Раньше оба файла
     * каждой пары одного размера читались целиком ради двух SHA-256 — и остановить это было нельзя.
     */
    private fun sameContent(a: Path, b: Path, cancelled: () -> Boolean): Boolean = runCatching {
        Files.newInputStream(a).use { ia ->
            Files.newInputStream(b).use { ib ->
                val ba = ByteArray(64 * 1024)
                val bb = ByteArray(64 * 1024)
                while (!cancelled()) {
                    val n = ia.readInto(ba, 0, ba.size)
                    if (ib.readInto(bb, 0, bb.size) != n) return@runCatching false
                    if (n == 0) return@runCatching true
                    if (org.wexclient.app.util.mismatch(ba, 0, n, bb, 0, n) >= 0) return@runCatching false
                }
                false
            }
        }
    }.getOrDefault(false)

    /**
     * Что скопировать, чтобы сделать папки одинаковыми в выбранную сторону.
     * [toRight] — слева направо: недостающее справа и то, что слева новее.
     */
    fun plan(entries: List<FolderDiffEntry>, toRight: Boolean, includeOlder: Boolean = false): List<FolderDiffEntry> =
        entries.filter { entry ->
            if (entry.directory) {
                // Папку копируем целиком, только если её нет на другой стороне
                if (toRight) entry.kind == FolderDiffKind.LEFT_ONLY else entry.kind == FolderDiffKind.RIGHT_ONLY
            } else when (entry.kind) {
                FolderDiffKind.LEFT_ONLY -> toRight
                FolderDiffKind.RIGHT_ONLY -> !toRight
                FolderDiffKind.LEFT_NEWER -> toRight || includeOlder
                FolderDiffKind.RIGHT_NEWER -> !toRight || includeOlder
                FolderDiffKind.DIFFERENT -> includeOlder
                FolderDiffKind.SAME -> false
            }
        }.let { list ->
            // Файлы внутри папки, которую и так копируем целиком, повторно не нужны
            val copiedDirs = list.filter { it.directory }.map { it.relative + "/" }
            list.filter { entry -> copiedDirs.none { dir -> entry.relative.startsWith(dir) } }
        }
}
