package org.wexclient.app.vfs

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue

/**
 * Узел дерева «что сколько занимает»: папка со всем содержимым или файл.
 * Размер — сумма размеров файлов (как у `du --apparent-size`).
 */
class DuNode internal constructor(
    val name: String,
    parent: DuNode?,
    val isDir: Boolean
) {
    var parent: DuNode? = parent
        internal set

    /** У папки — всё, что внутри. */
    var size = 0L
        internal set

    /** Файлов внутри; у файла — 1. */
    var files = 0L
        internal set

    /** Подпапок внутри на всех уровнях. */
    var dirs = 0L
        internal set

    /** Самое позднее изменение внутри. */
    var modified = 0L
        internal set

    /** Прочитать целиком не удалось (нет прав) — настоящий размер больше. */
    var error: String? = null
        internal set

    /** Другой раздел (точка монтирования) — внутрь не заходили. */
    var otherDevice = false
        internal set

    internal var kids: ArrayList<DuNode>? = if (isDir) ArrayList(4) else null

    /** Содержимое папки по убыванию размера. */
    val children: List<DuNode> get() = kids ?: emptyList()

    val category: DuCategory get() = if (isDir) DuCategory.FOLDER else DuCategory.of(name)

    /** Путь узла: [root] — папка, с которой начинался анализ. */
    fun pathFrom(root: Path): Path {
        val names = ArrayList<String>()
        var n: DuNode? = this
        while (n?.parent != null) {
            names.add(n.name)
            n = n.parent
        }
        var p = root
        for (i in names.indices.reversed()) p = p.resolve(names[i])
        return p
    }

    /** Цепочка от корня анализа до этого узла (включительно). */
    fun lineage(): List<DuNode> {
        val chain = ArrayList<DuNode>()
        var n: DuNode? = this
        while (n != null) {
            chain.add(n)
            n = n.parent
        }
        chain.reverse()
        return chain
    }

    /** Лежит ли узел внутри [other] (или это он сам). */
    fun isWithin(other: DuNode): Boolean {
        var n: DuNode? = this
        while (n != null) {
            if (n === other) return true
            n = n.parent
        }
        return false
    }

    override fun toString(): String = "$name ($size)"
}

/** Виды файлов для раскраски и сводки «по типам». */
enum class DuCategory(val title: String) {
    FOLDER("Папки"),
    VIDEO("Видео"),
    IMAGE("Изображения"),
    AUDIO("Музыка и звук"),
    ARCHIVE("Архивы и APK"),
    DOCUMENT("Документы"),
    CODE("Код и текст"),
    BINARY("Программы и библиотеки"),
    OTHER("Прочее");

    companion object {
        private val byExt: Map<String, DuCategory> = buildMap {
            fun put(cat: DuCategory, vararg exts: String) = exts.forEach { put(it, cat) }
            put(VIDEO, "mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv", "flv", "mpg", "mpeg", "ts", "3gp", "vob")
            put(IMAGE, "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff", "heic", "avif", "psd", "xcf", "raw", "cr2", "nef", "kra")
            put(AUDIO, "mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "wma", "mid", "midi", "ape")
            put(
                ARCHIVE, "zip", "apk", "apks", "xapk", "aab", "jar", "aar", "7z", "rar", "tar", "gz", "tgz", "xz", "txz",
                "zst", "bz2", "tbz2", "lz4", "lzma", "iso", "img", "dmg", "deb", "rpm", "pkg", "cab", "obb", "squashfs"
            )
            put(DOCUMENT, "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "djvu", "fb2", "mobi")
            put(
                CODE, "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "rs", "go", "py", "js", "mjs", "ts", "tsx", "jsx",
                "json", "xml", "html", "css", "scss", "md", "txt", "log", "yaml", "yml", "toml", "ini", "conf", "cfg",
                "sh", "fish", "bash", "zsh", "gradle", "properties", "sql", "lua", "rb", "php", "swift", "dart", "smali", "cs"
            )
            put(BINARY, "so", "dll", "exe", "appimage", "bin", "dex", "class", "o", "a", "ko", "elf", "wasm", "node", "pyc", "vdex", "odex", "oat")
        }

        fun of(name: String): DuCategory {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return OTHER
            return byExt[name.substring(dot + 1).lowercase()] ?: OTHER
        }
    }
}

/** Ход подсчёта. */
data class DuProgress(val files: Long, val dirs: Long, val bytes: Long, val current: String)

/** Итог: дерево размеров. [complete] = false — подсчёт остановили раньше. */
class DuResult(
    val root: Path,
    val top: DuNode,
    val complete: Boolean,
    val errors: Int,
    val millis: Long
)

object DiskUsage {

    /**
     * Виртуальные разделы ядра (/proc, /sys…) — файлы там не занимают места,
     * а /proc/kcore «весит» больше всего диска. Их не считаем никогда.
     */
    private val pseudoFs = setOf(
        "proc", "sysfs", "devtmpfs", "devpts", "cgroup", "cgroup2", "debugfs", "tracefs", "securityfs",
        "pstore", "bpf", "configfs", "fusectl", "mqueue", "hugetlbfs", "autofs", "binfmt_misc", "efivarfs", "rpc_pipefs"
    )

    /**
     * Подсчёт папки [root]. [oneFileSystem] — не заходить в другие разделы (как `du -x`):
     * при анализе / не потянутся внешние диски. Вызывать в фоне.
     */
    fun scan(
        root: Path,
        oneFileSystem: Boolean = true,
        cancelled: () -> Boolean = { false },
        onProgress: (DuProgress) -> Unit = {}
    ): DuResult {
        val started = System.nanoTime()
        // Корень-ссылка на папку (~/Downloads → /mnt/data/Downloads): обход без раскрытия ссылок принял бы
        // его за файл и показал пустоту. Идём по настоящему пути; имена внутри те же
        val walkRoot = if (Files.isSymbolicLink(root)) runCatching { root.toRealPath() }.getOrDefault(root) else root
        val rootDev = device(walkRoot)
        val top = DuNode(root.fileName?.toString() ?: root.toString(), null, true)
        val stack = ArrayDeque<DuNode>()
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var errors = 0
        var stopped = false
        var lastReport = 0L

        fun report(current: Path) {
            val now = System.nanoTime()
            if (now - lastReport < 80_000_000L) return
            lastReport = now
            onProgress(DuProgress(files, dirs, bytes, current.toString()))
        }

        Files.walkFileTree(walkRoot, emptySet(), Int.MAX_VALUE, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelled()) {
                    stopped = true
                    return FileVisitResult.TERMINATE
                }
                val parent = stack.lastOrNull()
                val node = if (parent == null) top else DuNode(dir.fileName.toString(), parent, true)
                node.modified = attrs.lastModifiedTime().toMillis()
                if (parent != null) {
                    parent.kids!!.add(node)
                    // Граница раздела: внутрь не идём, если просили или если это /proc и подобное
                    val dev = device(dir)
                    if (dev != null && rootDev != null && dev != rootDev && (oneFileSystem || isPseudo(dir))) {
                        node.otherDevice = true
                        return FileVisitResult.SKIP_SUBTREE
                    }
                }
                stack.addLast(node)
                dirs++
                report(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val parent = stack.lastOrNull() ?: return FileVisitResult.CONTINUE
                val size = if (attrs.isRegularFile) attrs.size() else 0L
                val node = DuNode(file.fileName.toString(), parent, false)
                node.size = size
                node.files = 1
                node.modified = attrs.lastModifiedTime().toMillis()
                parent.kids!!.add(node)
                files++
                bytes += size
                report(file)
                if (cancelled()) {
                    stopped = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Сам корень не открылся — это ошибка анализа, а не строка в дереве
                val parent = stack.lastOrNull() ?: throw exc
                errors++
                val isDir = runCatching { Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
                val node = DuNode(file.fileName?.toString() ?: file.toString(), parent, isDir)
                node.error = describe(exc)
                if (!isDir) node.files = 1
                parent.kids!!.add(node)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val node = stack.removeLastOrNull() ?: return FileVisitResult.CONTINUE
                if (exc != null) {
                    node.error = describe(exc)
                    errors++
                }
                finish(node)
                return FileVisitResult.CONTINUE
            }
        })
        // Остановили на середине — досчитываем открытые папки, чтобы итоги сошлись
        while (stack.isNotEmpty()) finish(stack.removeLast())
        return DuResult(root, top, complete = !stopped, errors = errors, millis = (System.nanoTime() - started) / 1_000_000)
    }

    /** Итоги папки из её содержимого; содержимое — по убыванию размера. */
    private fun finish(node: DuNode) {
        val kids = node.kids ?: return
        var size = 0L
        var files = 0L
        var dirs = 0L
        var modified = node.modified
        for (c in kids) {
            size += c.size
            files += c.files
            dirs += c.dirs + if (c.isDir) 1 else 0
            if (c.modified > modified) modified = c.modified
        }
        node.size = size
        node.files = files
        node.dirs = dirs
        node.modified = modified
        kids.sortWith(BY_SIZE)
        kids.trimToSize()
    }

    /** По убыванию размера, одинаковые — по имени, как в панели (файл2 раньше файл10). */
    private val BY_SIZE = Comparator<DuNode> { a, b ->
        val c = b.size.compareTo(a.size)
        if (c != 0) c else org.wexclient.app.util.NaturalOrder.compare(a.name, b.name)
    }

    /** Убрать узел из дерева (удалён или в корзине): итоги всех папок выше уменьшаются. */
    fun detach(node: DuNode) {
        val parent = node.parent ?: return
        parent.kids?.remove(node)
        var a: DuNode? = parent
        while (a != null) {
            a.size -= node.size
            a.files -= node.files
            a.dirs -= node.dirs + if (node.isDir) 1 else 0
            a = a.parent
        }
        node.parent = null
    }

    /**
     * Пересчитать папку [node] заново (после правок снаружи) и поставить новый узел
     * на её место. Возвращает новый узел.
     */
    fun rescan(
        root: Path,
        node: DuNode,
        oneFileSystem: Boolean = true,
        cancelled: () -> Boolean = { false },
        onProgress: (DuProgress) -> Unit = {}
    ): DuNode = replace(node, scan(node.pathFrom(root), oneFileSystem, cancelled, onProgress).top)

    /**
     * Поставить свежепосчитанную папку [fresh] на место [node]; итоги выше меняются на разницу.
     * Подсчёт идёт в фоне, а подстановка — там, где дерево читают (в потоке окна).
     */
    fun replace(node: DuNode, fresh: DuNode): DuNode {
        val parent = node.parent ?: return fresh
        val kids = parent.kids!!
        val idx = kids.indexOf(node)
        if (idx < 0) return fresh
        val renamed = DuNode(node.name, parent, true).also {
            it.size = fresh.size
            it.files = fresh.files
            it.dirs = fresh.dirs
            it.modified = fresh.modified
            it.error = fresh.error
            it.kids = fresh.kids
            fresh.kids?.forEach { c -> c.parent = it }
        }
        kids[idx] = renamed
        val dSize = renamed.size - node.size
        val dFiles = renamed.files - node.files
        val dDirs = renamed.dirs - node.dirs
        var a: DuNode? = parent
        while (a != null) {
            a.size += dSize
            a.files += dFiles
            a.dirs += dDirs
            a.kids?.sortWith(BY_SIZE)
            a = a.parent
        }
        // Родителя у старого узла не обнуляем: его ещё держат окно и открытые вопросы, и путь
        // (pathFrom) у них должен остаться верным, а не укоротиться до корня
        return renamed
    }

    /**
     * Тот же узел в пересчитанном дереве: [n] лежит внутри заменённого [from] — ищем его по именам в [to].
     * Пропавшее при пересчёте — ближайшая уцелевшая папка. Узел вне [from] возвращается как есть.
     */
    fun remap(n: DuNode?, from: DuNode, to: DuNode): DuNode? {
        if (n == null) return null
        val names = ArrayList<String>()
        var cur: DuNode? = n
        while (cur != null && cur !== from) {
            names.add(cur.name)
            cur = cur.parent
        }
        if (cur == null) return n
        var m = to
        for (name in names.asReversed()) m = m.children.firstOrNull { it.name == name } ?: return m
        return m
    }

    /** Самые большие файлы внутри [node] — по убыванию. */
    fun largestFiles(node: DuNode, limit: Int = 100): List<DuNode> {
        val heap = PriorityQueue<DuNode>(limit + 1, compareBy { it.size })
        val stack = ArrayDeque<DuNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!n.isDir) {
                if (heap.size < limit) heap.add(n)
                else if (n.size > heap.peek().size) {
                    heap.poll()
                    heap.add(n)
                }
                continue
            }
            n.kids?.let { stack.addAll(it) }
        }
        return heap.sortedWith(BY_SIZE)
    }

    /** Сводка по видам файлов внутри [node]: вид → (байт, файлов), по убыванию байт. */
    fun byCategory(node: DuNode): List<Triple<DuCategory, Long, Long>> {
        val bytes = LongArray(DuCategory.entries.size)
        val counts = LongArray(DuCategory.entries.size)
        val stack = ArrayDeque<DuNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isDir) {
                n.kids?.let { stack.addAll(it) }
                continue
            }
            val c = DuCategory.of(n.name).ordinal
            bytes[c] += n.size
            counts[c]++
        }
        return DuCategory.entries
            .filter { counts[it.ordinal] > 0 }
            .map { Triple(it, bytes[it.ordinal], counts[it.ordinal]) }
            .sortedByDescending { it.second }
    }

    private fun device(p: Path): Long? =
        runCatching { (Files.getAttribute(p, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong() }.getOrNull()

    private fun isPseudo(dir: Path): Boolean =
        runCatching { Files.getFileStore(dir).type() in pseudoFs }.getOrDefault(false)

    private fun describe(e: IOException): String = when (e) {
        is AccessDeniedException -> "нет доступа"
        is NoSuchFileException -> "исчез во время подсчёта"
        else -> e.message ?: e.javaClass.simpleName
    }
}
