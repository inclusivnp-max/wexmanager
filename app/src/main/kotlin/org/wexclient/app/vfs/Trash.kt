package org.wexclient.app.vfs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Файл в корзине: имя в files/ и сведения из info/<имя>.trashinfo. */
data class TrashItem(
    /** …/Trash или $topdir/.Trash-$uid */
    val trashDir: Path,
    val name: String,
    val originalPath: Path,
    val deletedAt: LocalDateTime?
) {
    val file: Path get() = trashDir.resolve("files").resolve(name)
    val info: Path get() = trashDir.resolve("info").resolve("$name.trashinfo")
    val isDirectory: Boolean get() = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
}

/**
 * Корзина по спецификации freedesktop.org (её же используют Dolphin, Nautilus, gio):
 * домашняя $XDG_DATA_HOME/Trash и $topdir/.Trash-$uid на других разделах.
 */
object Trash {
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /** Растёт при каждом изменении корзины — счётчик на панели перечитывается. */
    var version by mutableStateOf(0)
        private set

    private fun changed() {
        version++
    }
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** В тестах user.home подменён — тогда XDG_DATA_HOME настоящего пользователя не трогаем. */
    fun homeTrash(): Path {
        val userHome = System.getProperty("user.home")
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && it.startsWith("/") }
        return if (xdg != null && System.getenv("HOME") == userHome) Paths.get(xdg, "Trash")
        else Paths.get(userHome, ".local/share/Trash")
    }

    private val uid: Int by lazy {
        runCatching { (Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Number).toInt() }
            .getOrElse { (Files.getAttribute(Paths.get(System.getProperty("user.home")), "unix:uid") as Number).toInt() }
    }

    private fun device(p: Path): Long? =
        runCatching { (Files.getAttribute(p, "unix:dev", NOFOLLOW) as Number).toLong() }.getOrNull()

    private fun existingAncestor(p: Path): Path {
        var cur: Path? = p
        while (cur != null && !Files.exists(cur, NOFOLLOW)) cur = cur.parent
        return cur ?: p.root
    }

    /** Корень раздела: поднимаемся, пока номер устройства тот же. */
    private fun mountTop(start: Path, dev: Long): Path {
        var cur = start
        while (true) {
            val up = cur.parent ?: return cur
            if (device(up) != dev) return cur
            cur = up
        }
    }

    fun supports(path: Path): Boolean = path.fileSystem === FileSystems.getDefault()

    /** Корзина для файла: домашняя, если он на том же разделе, иначе корзина раздела (создаётся с правами 700). */
    fun trashDirFor(path: Path): Path? {
        if (!supports(path)) return null
        val abs = path.toAbsolutePath().normalize()
        val parent = abs.parent ?: return null
        val fileDev = device(parent) ?: return null
        val home = homeTrash()
        if (device(existingAncestor(home)) == fileDev) return home
        val top = mountTop(parent, fileDev)
        // Общая $topdir/.Trash с «липким» битом — подкаталог пользователя
        val shared = top.resolve(".Trash")
        val sticky = runCatching {
            Files.isDirectory(shared, NOFOLLOW) && ((Files.getAttribute(shared, "unix:mode", NOFOLLOW) as Int) and 0x200) != 0
        }.getOrDefault(false)
        val candidates = if (sticky) listOf(shared.resolve(uid.toString()), top.resolve(".Trash-$uid")) else listOf(top.resolve(".Trash-$uid"))
        return candidates.firstOrNull { ensurePrivateDir(it) }
    }

    private fun ensurePrivateDir(dir: Path): Boolean = runCatching {
        if (!Files.isDirectory(dir, NOFOLLOW)) {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        Files.isWritable(dir) && !Files.isSymbolicLink(dir)
    }.getOrDefault(false)

    /**
     * Корзина раздела — настоящие папки текущего пользователя: сама она, files и info (а общая .Trash —
     * настоящая папка). Ссылку вместо папки не принимаем: чужая флешка с .Trash-1000/files → /home/<user>
     * превратила бы «Очистить корзину» в удаление домашних папок, а «Восстановить» — в перенос их на флешку.
     */
    internal fun trusted(dir: Path): Boolean {
        if (topOf(dir) == null) return true // домашняя корзина — своя
        return runCatching {
            val shared = dir.parent?.takeIf { it.fileName?.toString() == ".Trash" }
            if (shared != null && !Files.isDirectory(shared, NOFOLLOW)) return false
            listOf(dir, dir.resolve("files"), dir.resolve("info")).all { d ->
                Files.isDirectory(d, NOFOLLOW) && (Files.getAttribute(d, "unix:uid", NOFOLLOW) as Number).toInt() == uid
            }
        }.getOrDefault(false)
    }

    private fun requireTrusted(dir: Path) {
        if (!trusted(dir)) throw IOException("Корзина $dir ненадёжна: вместо её папок — ссылки или чужие папки")
    }

    /** Корень раздела, к которому относятся относительные пути в .trashinfo. */
    private fun topOf(trashDir: Path): Path? = when {
        trashDir == homeTrash() -> null
        trashDir.fileName?.toString()?.startsWith(".Trash-") == true -> trashDir.parent
        trashDir.parent?.fileName?.toString() == ".Trash" -> trashDir.parent?.parent
        else -> null
    }

    /** Переместить в корзину. Имя в корзине уникально: «файл», «файл (2)», … */
    fun trash(path: Path): TrashItem {
        val given = path.toAbsolutePath().normalize()
        if (!Files.exists(given, NOFOLLOW)) throw NoSuchFileException(given.toString())
        // Папка могла быть открыта через ссылку (~/Downloads → /mnt/data/Downloads): раздел — по настоящему
        // пути, иначе выбиралась домашняя корзина и перенос в неё падал
        val real = runCatching { given.parent?.toRealPath()?.resolve(given.fileName.toString()) }.getOrNull() ?: given
        val dir = trashDirFor(real) ?: throw IOException("Корзина недоступна для «${given.fileName}»")
        val files = dir.resolve("files")
        val info = dir.resolve("info")
        Files.createDirectories(files)
        Files.createDirectories(info)
        requireTrusted(dir)
        val top = topOf(dir)
        // В корзине раздела путь — относительно его корня, то есть настоящий
        val abs = if (top != null) real else given
        val stored = if (top != null && abs.startsWith(top)) top.relativize(abs).toString() else abs.toString()
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val content = "[Trash Info]\nPath=${encodePath(stored)}\nDeletionDate=${now.format(dateFormat)}\n"

        val base = abs.fileName.toString()
        var n = 1
        var name = trashName(base, n)
        var infoFile: Path
        // Имя «занимаем» созданием .trashinfo: так два одновременных удаления не столкнутся
        while (true) {
            infoFile = info.resolve("$name.trashinfo")
            if (!Files.exists(files.resolve(name), NOFOLLOW)) {
                try {
                    Files.writeString(infoFile, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    break
                } catch (_: FileAlreadyExistsException) {
                    // занято — следующее имя
                }
            }
            n++
            name = trashName(base, n)
        }
        try {
            Files.move(abs, files.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(infoFile)
            throw IOException("Не удалось переместить «${abs.fileName}» в корзину: ${e.message ?: e.javaClass.simpleName}", e)
        }
        changed()
        return TrashItem(dir, name, abs, now)
    }

    private const val MAX_TRASH_NAME = 255 - ".trashinfo".length

    /**
     * Имя в корзине: «файл», «файл (2)»… Слишком длинное укорачивается с «…»: иначе «имя.trashinfo»
     * не помещается в 255 байт и файл в корзину не уходит. Настоящее имя остаётся в .trashinfo.
     */
    internal fun trashName(base: String, n: Int): String {
        val full = if (n == 1) base else numbered(base, n)
        if (full.toByteArray().size <= MAX_TRASH_NAME) return full
        val dot = base.lastIndexOf('.').takeIf { it > 0 && base.length - it <= 16 }
        val stem = if (dot != null) base.substring(0, dot) else base
        val tail = (if (n == 1) "" else " ($n)") + (if (dot != null) base.substring(dot) else "")
        var cut = stem
        while (cut.isNotEmpty() && (cut + "…" + tail).toByteArray().size > MAX_TRASH_NAME) {
            cut = cut.dropLast(if (cut.length >= 2 && cut[cut.length - 1].isLowSurrogate()) 2 else 1)
        }
        return cut + "…" + tail
    }

    /** «отчёт.pdf» → «отчёт (2).pdf», «.bashrc» → «.bashrc (2)» */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    /** Корзины: домашняя и корзины смонтированных разделов. */
    fun trashDirs(): List<Path> {
        val out = LinkedHashSet<Path>()
        out.add(homeTrash())
        // Тесты подменяют домашнюю папку, а корзины разделов — настоящие: «очистка» в тестах их не трогает
        if (System.getProperty("wex.isolated") == "true") return out.toList()
        runCatching {
            Files.readAllLines(Paths.get("/proc/self/mounts")).mapNotNull { line ->
                line.split(' ').getOrNull(1)?.replace("\\040", " ")?.let { Paths.get(it) }
            }
        }.getOrDefault(emptyList()).forEach { mount ->
            for (d in listOf(mount.resolve(".Trash-$uid"), mount.resolve(".Trash").resolve(uid.toString()))) {
                if (runCatching { Files.isDirectory(d.resolve("info"), NOFOLLOW) }.getOrDefault(false) && trusted(d)) out.add(d)
            }
        }
        return out.toList()
    }

    fun list(): List<TrashItem> {
        val items = ArrayList<TrashItem>()
        for (dir in trashDirs()) {
            val info = dir.resolve("info")
            if (!Files.isDirectory(info)) continue
            val top = topOf(dir)
            Files.newDirectoryStream(info, "*.trashinfo").use { stream ->
                for (f in stream) {
                    val name = f.fileName.toString().removeSuffix(".trashinfo")
                    if (!Files.exists(dir.resolve("files").resolve(name), NOFOLLOW)) continue
                    val text = runCatching { Files.readString(f) }.getOrNull() ?: continue
                    val parsed = parseInfo(text) ?: continue
                    val stored = runCatching { Paths.get(parsed.first) }.getOrNull() ?: continue
                    items.add(TrashItem(dir, name, originalPathOf(stored, top, name) ?: continue, parsed.second))
                }
            }
        }
        return items.sortedWith(compareByDescending<TrashItem> { it.deletedAt }.thenBy { it.name })
    }

    /**
     * Куда восстанавливать. В корзине раздела (spec freedesktop) путь относительный, без «..»;
     * иначе .trashinfo с чужой флешки мог бы вернуть файл, например, в ~/.config/autostart.
     * Такие записи восстанавливаются в корень раздела под своим именем.
     */
    internal fun originalPathOf(stored: Path, top: Path?, name: String): Path? {
        val hasParentRefs = stored.any { it.toString() == ".." }
        return when {
            top == null -> if (stored.isAbsolute) stored.normalize() else null
            !stored.isAbsolute && !hasParentRefs && stored.nameCount > 0 -> top.resolve(stored).normalize()
            // Старые версии KDE писали абсолютный путь и для раздела — внутри раздела его принимаем
            stored.isAbsolute && !hasParentRefs && stored.normalize().startsWith(top) -> stored.normalize()
            else -> top.resolve(name)
        }
    }

    fun count(): Int = runCatching {
        trashDirs().sumOf { dir ->
            val info = dir.resolve("info")
            if (!Files.isDirectory(info)) 0
            else Files.newDirectoryStream(info, "*.trashinfo").use { s -> s.count() }
        }
    }.getOrDefault(0)

    internal fun parseInfo(text: String): Pair<String, LocalDateTime?>? {
        var inSection = false
        var path: String? = null
        var date: LocalDateTime? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("[") -> inSection = line == "[Trash Info]"
                !inSection -> Unit
                line.startsWith("Path=") && path == null -> path = decodePath(line.substring(5))
                line.startsWith("DeletionDate=") && date == null ->
                    date = runCatching { LocalDateTime.parse(line.substring(13).take(19), dateFormat) }.getOrNull()
            }
        }
        return path?.let { it to date }
    }

    /** Вернуть на место (или в [target]); занятое место — ошибка, чтобы ничего не затереть. */
    fun restore(item: TrashItem, target: Path = item.originalPath): Path {
        requireTrusted(item.trashDir)
        if (Files.exists(target, NOFOLLOW)) throw FileAlreadyExistsException(target.toString(), null, "уже существует")
        topOf(item.trashDir)?.let { top -> ensureInsideTop(target, top) }
        target.parent?.let { if (!Files.isDirectory(it)) Files.createDirectories(it) }
        try {
            Files.move(item.file, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Исходная папка на другом разделе (её пересоздали) — переносим копированием
            moveAcross(item.file, target)
        }
        Files.deleteIfExists(item.info)
        changed()
        return target
    }

    /**
     * Корзина чужого раздела (флешка): путь внутри раздела ещё не значит, что файл туда и попадёт —
     * папка на пути может быть ссылкой, например на ~/.config/autostart. Проверяем настоящий путь
     * ближайшей существующей папки.
     */
    internal fun ensureInsideTop(target: Path, top: Path) {
        val realTop = top.toRealPath()
        var existing: Path? = target.parent
        while (existing != null && !Files.exists(existing, NOFOLLOW)) existing = existing.parent
        val real = existing?.toRealPath() ?: throw IOException("Нет папки для «${target.fileName}»")
        if (!real.startsWith(realTop)) {
            throw IOException("Место восстановления «$target» ведёт за пределы раздела корзины")
        }
    }

    /** Свободное имя рядом с исходным: «файл (восстановлено)», «файл (восстановлено 2)» */
    fun freeRestoreTarget(item: TrashItem): Path {
        val parent = item.originalPath.parent ?: return item.originalPath
        val name = item.originalPath.fileName.toString()
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val suffix = if (i == 1) " (восстановлено)" else " (восстановлено $i)"
            val candidate = parent.resolve("$stem$suffix$ext")
            if (!Files.exists(candidate, NOFOLLOW)) return candidate
            i++
        }
    }

    private fun moveAcross(src: Path, target: Path) {
        try {
            Files.walk(src).use { stream ->
                stream.forEach { s ->
                    val dest = target.resolve(src.relativize(s).toString())
                    when {
                        Files.isSymbolicLink(s) -> Files.createSymbolicLink(dest, Files.readSymbolicLink(s))
                        Files.isDirectory(s, NOFOLLOW) -> Files.createDirectories(dest)
                        Files.isRegularFile(s, NOFOLLOW) -> Files.copy(s, dest, StandardCopyOption.COPY_ATTRIBUTES)
                        // Канал или сокет: чтение канала ждало бы писателя вечно, данных в них нет
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            // Недокопированное не должно мешать следующей попытке: цели до нас не было
            org.wexclient.app.util.FileTree.delete(target)
            throw e
        }
        deleteTree(src)
    }

    fun delete(item: TrashItem) {
        requireTrusted(item.trashDir)
        deleteTree(item.file)
        Files.deleteIfExists(item.info)
        changed()
    }

    fun empty(): Int {
        val items = list()
        items.forEach { runCatching { delete(it) } }
        return items.size
    }

    private fun deleteTree(p: Path) {
        if (!Files.exists(p, NOFOLLOW)) return
        if (Files.isDirectory(p, NOFOLLOW)) {
            Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } else {
            Files.deleteIfExists(p)
        }
    }

    /** Размер (папки — суммой, ссылки не раскрываются). */
    fun sizeOf(item: TrashItem): Long = runCatching {
        if (!item.isDirectory) Files.size(item.file)
        else Files.walk(item.file).use { s ->
            s.filter { Files.isRegularFile(it, NOFOLLOW) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }.getOrDefault(0L)

    // ---------- Кодирование путей (RFC 2396, UTF-8) ----------

    private const val SAFE = "-_.!~*'()/"

    fun encodePath(path: String): String {
        val sb = StringBuilder()
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in SAFE)) sb.append(ch)
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun decodePath(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8)
    }
}
