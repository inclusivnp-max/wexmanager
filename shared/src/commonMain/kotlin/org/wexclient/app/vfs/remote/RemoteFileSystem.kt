package org.wexclient.app.vfs.remote

import org.wexclient.app.util.skipExactly

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.ProviderMismatchException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap

/** Запись каталога на той стороне: на сервере по ssh или на телефоне по adb. */
data class RemoteEntry(
    val name: String,
    /** f — файл, d — папка, l — ссылка, прочее — особые файлы. */
    val type: Char,
    /** Тип цели ссылки: у ссылки на папку это d. */
    val targetType: Char,
    val size: Long,
    val modifiedMillis: Long,
    val mode: Int,
    val owner: String,
    val group: String
) {
    val isDirectory: Boolean get() = type == 'd' || (type == 'l' && targetType == 'd')
    val isLink: Boolean get() = type == 'l'
    val isRegular: Boolean get() = type == 'f' || (type == 'l' && targetType == 'f')
}

/**
 * Команды той стороны, на которых держится файловая система панели: ssh на сервер или adb на телефон.
 * Пути — полные, через «/». Ошибки — IOException с понятным текстом.
 */
interface RemoteConnection {
    /** Содержимое папки. */
    fun list(dir: String): List<RemoteEntry>

    /** Сведения об одном пути; null — такого нет. */
    fun stat(path: String): RemoteEntry?

    fun mkdir(path: String)

    fun delete(path: String, recursive: Boolean)

    /** Перенос: существующая папка на месте цели — ошибка, а не «положить внутрь», как у Files.move на диске. */
    fun rename(from: String, to: String)

    fun copyInside(from: String, to: String)

    /** Куда указывает ссылка — как записано в ней, без раскрытия. */
    fun readLink(path: String): String

    fun symlink(path: String, target: String)

    /** Время изменения файла — чтобы копия не выглядела «только что созданной». */
    fun setModified(path: String, millis: Long)

    /** Свободное место на разделе с этой папкой: (свободно, всего) в байтах. */
    fun diskSpace(path: String): Pair<Long, Long>?

    /** Чтение потоком; ошибка той стороны всплывает в конце чтения, а не выглядит обрезанным файлом. */
    fun openRead(path: String): InputStream

    /** Запись потоком; ошибка той стороны всплывает при закрытии. */
    fun openWrite(path: String): OutputStream

    /** Команда для консоли: оболочка той стороны сразу в папке [dir]. */
    fun shellCommand(dir: String): List<String>

    fun close()
}

/**
 * Папки на той стороне как обычная файловая система: панель ходит по ним, копирует F5
 * в обе стороны, удаляет и переименовывает тем же кодом, что и на диске.
 * Каждая операция — команда через [connection].
 */
abstract class RemoteFileSystem(private val provider: RemoteFileSystemProvider) : FileSystem() {

    abstract val connection: RemoteConnection

    /** Откуда начинаем: домашняя папка на сервере, общая память на телефоне. */
    abstract val home: String

    /** Короткое имя для панели и консоли: user@host, «Pixel 9 Pro». */
    abstract val label: String

    /** Подпись в строке пути: «SSH user@host», «ADB Pixel 9 Pro». */
    abstract val title: String

    /** Постоянное имя папки для скачанных копий файлов. */
    abstract val cacheKey: String

    /** «На сервере», «На телефоне» — начало фраз в сообщениях. */
    abstract val whereText: String

    /** Вид связи: ssh, adb. */
    abstract val kind: String

    /** Хранит ли та сторона символические ссылки: копия ссылки — ссылкой, а не тем, на что она указывает. */
    open val keepsLinks: Boolean get() = true

    /** Адрес пути в схеме своего поставщика. */
    internal abstract fun uriOf(path: String): URI

    @Volatile
    private var open = true

    internal val rootPath = RemotePath(this, "/")

    /** Кэш сведений после чтения папки: панель спрашивает про каждый файл, а та сторона — далеко. */
    private val cache = ConcurrentHashMap<String, Pair<Long, RemoteEntry>>()

    /** Когда папку читали целиком: имени нет в свежем списке — его нет и там. */
    private val listed = ConcurrentHashMap<String, Long>()

    fun homePath(): Path = RemotePath(this, home)

    internal fun list(dir: RemotePath): List<RemoteEntry> {
        val entries = connection.list(dir.raw)
        val now = System.currentTimeMillis()
        val prefix = if (dir.raw == "/") "/" else dir.raw + "/"
        // Прежние сведения о содержимом папки — вон (удалённое там не «существует» по кэшу),
        // просроченное — тоже: так кэш не растёт весь сеанс
        cache.entries.removeIf { (key, value) ->
            now - value.first >= CACHE_MS || (key.startsWith(prefix) && key.indexOf('/', prefix.length) < 0)
        }
        for (e in entries) cache[join(dir.raw, e.name)] = now to e
        listed.entries.removeIf { now - it.value >= CACHE_MS }
        listed[dir.raw] = now
        return entries
    }

    internal fun entry(path: RemotePath): RemoteEntry? {
        val now = System.currentTimeMillis()
        cache[path.raw]?.let { (time, e) ->
            if (now - time < CACHE_MS) return e
        }
        // Проверки имени при наборе и конфликты при копировании — без отдельной команды на каждое имя
        if (path.names.isNotEmpty()) {
            val parent = path.raw.substringBeforeLast('/', "").ifEmpty { "/" }
            listed[parent]?.let { if (now - it < CACHE_MS) return null }
        }
        val e = connection.stat(path.raw) ?: return null
        cache[path.raw] = now to e
        return e
    }

    /**
     * Для операций (запись, чтение, перенос): «нет» по кэшу не принимаем, спрашиваем ту сторону. Иначе файл,
     * появившийся там за последние секунды, молча перезаписался бы копией или «не нашёлся».
     */
    internal fun entryFresh(path: RemotePath): RemoteEntry? {
        cache[path.raw]?.let { (time, e) ->
            if (System.currentTimeMillis() - time < CACHE_MS) return e
        }
        return connection.stat(path.raw)?.also { cache[path.raw] = System.currentTimeMillis() to it }
    }

    /** Есть ли путь там сейчас (сама ссылка — тоже «есть»); «нет» по кэшу перепроверяется. */
    fun existsNow(path: Path): Boolean = entryFresh(path.toAbsolutePath() as RemotePath) != null

    /** Удалить файл или папку целиком одной командой: rm -rf по ссылкам не ходит. */
    fun deleteTree(path: Path) {
        val p = path.toAbsolutePath() as RemotePath
        if (p.names.isEmpty()) throw java.io.IOException("Корень удалять нельзя")
        connection.delete(p.raw, recursive = true)
        forget(p)
    }

    /** После правок сведения о папке и её содержимом устарели. */
    internal fun forget(path: RemotePath) {
        val prefix = path.raw.trimEnd('/') + "/"
        cache.keys.removeIf { it == path.raw || it.startsWith(prefix) }
        listed.keys.removeIf { it == path.raw || it.startsWith(prefix) }
        path.parent?.let {
            val raw = (it as RemotePath).raw
            cache.remove(raw)
            // Список папки после правки неполон: «нет в списке» больше не значит «нет там»
            listed.remove(raw)
        }
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        if (!open) return
        open = false
        connection.close()
        provider.forget(this)
    }

    override fun isOpen(): Boolean = open

    override fun isReadOnly(): Boolean = false

    override fun getSeparator(): String = "/"

    override fun getRootDirectories(): MutableIterable<Path> = mutableListOf(rootPath)

    override fun getFileStores(): MutableIterable<FileStore> = mutableListOf(RemoteFileStore(this, "/"))

    override fun supportedFileAttributeViews(): MutableSet<String> = mutableSetOf("basic", "posix")

    override fun getPath(first: String, vararg more: String): Path {
        val joined = (listOf(first) + more).filter { it.isNotEmpty() }.joinToString("/")
        return RemotePath(this, joined)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        java.nio.file.FileSystems.getDefault().getPathMatcher(syntaxAndPattern).let { matcher ->
            PathMatcher { path -> matcher.matches(java.nio.file.Path.of(path.toString())) }
        }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw UnsupportedOperationException()

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    override fun toString(): String = "$kind://$label"

    companion object {
        private const val CACHE_MS = 15_000L

        fun join(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
    }
}

/** Путь на той стороне. Всегда разделитель «/», корень — «/». */
class RemotePath internal constructor(internal val fs: RemoteFileSystem, path: String) : Path {
    // В Java 8 (так видит их Android) эти методы абстрактные, в JDK 11+ — по умолчанию; тела — как в JDK
    override fun startsWith(other: String): Boolean = startsWith(fileSystem.getPath(other))
    override fun endsWith(other: String): Boolean = endsWith(fileSystem.getPath(other))
    override fun resolveSibling(other: Path): Path = parent?.resolve(other) ?: other
    override fun resolveSibling(other: String): Path = resolveSibling(fileSystem.getPath(other))
    override fun toFile(): java.io.File = throw UnsupportedOperationException("Путь не на диске: $this")
    override fun register(watcher: java.nio.file.WatchService, vararg events: java.nio.file.WatchEvent.Kind<*>): java.nio.file.WatchKey =
        register(watcher, events)
    override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
        private var i = 0
        override fun hasNext() = i < nameCount
        override fun next(): Path = if (i < nameCount) getName(i++) else throw NoSuchElementException()
        override fun remove() = throw UnsupportedOperationException()
    }

    internal val raw: String = normalizeSlashes(path)
    internal val names: List<String> = raw.split('/').filter { it.isNotEmpty() }

    private fun normalizeSlashes(p: String): String {
        if (p.isEmpty()) return ""
        val sb = StringBuilder(p.length)
        var prevSlash = false
        for (c in p) {
            if (c == '/') {
                if (!prevSlash) sb.append(c)
                prevSlash = true
            } else {
                sb.append(c)
                prevSlash = false
            }
        }
        if (sb.length > 1 && sb.endsWith("/")) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun of(s: String) = RemotePath(fs, s)

    private fun cast(other: Path): RemotePath = other as? RemotePath ?: throw ProviderMismatchException()

    override fun getFileSystem(): FileSystem = fs
    override fun isAbsolute(): Boolean = raw.startsWith("/")
    override fun getRoot(): Path? = if (isAbsolute) fs.rootPath else null

    override fun getFileName(): Path? = when {
        raw.isEmpty() -> this
        names.isEmpty() -> null
        names.size == 1 && !isAbsolute -> this
        else -> of(names.last())
    }

    override fun getParent(): Path? = when {
        names.isEmpty() -> null
        names.size == 1 -> if (isAbsolute) fs.rootPath else null
        else -> of((if (isAbsolute) "/" else "") + names.dropLast(1).joinToString("/"))
    }

    override fun getNameCount(): Int = if (raw.isEmpty()) 1 else names.size

    override fun getName(index: Int): Path {
        if (raw.isEmpty() && index == 0) return this
        if (index < 0 || index >= names.size) throw IllegalArgumentException()
        return of(names[index])
    }

    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        if (beginIndex < 0 || endIndex > names.size || beginIndex >= endIndex) throw IllegalArgumentException()
        return of(names.subList(beginIndex, endIndex).joinToString("/"))
    }

    override fun startsWith(other: Path): Boolean {
        val o = other as? RemotePath ?: return false
        if (o.fs !== fs || o.isAbsolute != isAbsolute) return false
        if (o.raw.isEmpty()) return raw.isEmpty()
        if (o.names.size > names.size) return false
        return names.subList(0, o.names.size) == o.names
    }

    override fun endsWith(other: Path): Boolean {
        val o = other as? RemotePath ?: return false
        if (o.fs !== fs) return false
        if (o.isAbsolute) return o.raw == raw
        if (o.raw.isEmpty()) return raw.isEmpty()
        if (o.names.size > names.size) return false
        return names.subList(names.size - o.names.size, names.size) == o.names
    }

    override fun normalize(): Path {
        val stack = ArrayList<String>()
        for (n in names) {
            when (n) {
                "." -> Unit
                ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeAt(stack.size - 1) else if (!isAbsolute) stack.add("..")
                else -> stack.add(n)
            }
        }
        val s = stack.joinToString("/")
        return of(if (isAbsolute) "/$s" else s)
    }

    override fun resolve(other: Path): Path {
        // Имена из другой ФС (например, локальной при копировании) переносим как текст
        val o = other as? RemotePath ?: return resolve(other.toString())
        if (o.isAbsolute) return o
        if (o.raw.isEmpty()) return this
        if (raw.isEmpty()) return o
        return of(if (raw == "/") "/" + o.raw else raw + "/" + o.raw)
    }

    override fun resolve(other: String): Path = resolve(of(other))

    override fun relativize(other: Path): Path {
        val o = cast(other)
        if (o.isAbsolute != isAbsolute) throw IllegalArgumentException("Пути разного вида: $this и $o")
        if (o.raw == raw) return of("")
        var i = 0
        while (i < names.size && i < o.names.size && names[i] == o.names[i]) i++
        val parts = List(names.size - i) { ".." } + o.names.subList(i, o.names.size)
        return of(parts.joinToString("/"))
    }

    override fun toUri(): URI = fs.uriOf((toAbsolutePath() as RemotePath).raw)

    override fun toAbsolutePath(): Path = if (isAbsolute) this else of(RemoteFileSystem.join(fs.home, raw))

    override fun toRealPath(vararg options: LinkOption): Path = toAbsolutePath().normalize()

    override fun register(watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier): WatchKey =
        throw UnsupportedOperationException()

    override fun compareTo(other: Path): Int = raw.compareTo(cast(other).raw)
    override fun equals(other: Any?): Boolean = other is RemotePath && other.fs === fs && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = raw
}

/**
 * Сведения о файле в виде, понятном NIO. [follow] — как в NIO: по умолчанию о том, на что указывает ссылка,
 * с NOFOLLOW_LINKS — о самой ссылке. Обход дерева (удаление, копирование) спрашивает без
 * раскрытия и в ссылку на папку не заходит — иначе удаление стирало бы то, на что она указывает.
 */
internal class RemoteAttributes(private val e: RemoteEntry, private val key: String, private val follow: Boolean = true) : PosixFileAttributes {
    private val time = FileTime.fromMillis(e.modifiedMillis)
    private val kind: Char = if (follow && e.isLink) e.targetType else e.type
    override fun lastModifiedTime(): FileTime = time
    override fun lastAccessTime(): FileTime = time
    override fun creationTime(): FileTime = time
    override fun isRegularFile(): Boolean = kind == 'f'
    override fun isDirectory(): Boolean = kind == 'd'
    override fun isSymbolicLink(): Boolean = kind == 'l'
    override fun isOther(): Boolean = kind != 'f' && kind != 'd' && kind != 'l'
    override fun size(): Long = e.size
    override fun fileKey(): Any = key
    override fun owner(): UserPrincipal = Principal(e.owner)
    override fun group(): GroupPrincipal = Principal(e.group)
    override fun permissions(): MutableSet<PosixFilePermission> {
        val result = HashSet<PosixFilePermission>()
        val bits = listOf(
            0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE, 0x40 to PosixFilePermission.OWNER_EXECUTE,
            0x20 to PosixFilePermission.GROUP_READ, 0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
            0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE, 0x1 to PosixFilePermission.OTHERS_EXECUTE
        )
        for ((bit, permission) in bits) if (e.mode and bit != 0) result += permission
        return result
    }
}

private class Principal(private val principalName: String) : UserPrincipal, GroupPrincipal {
    override fun getName(): String = principalName
    override fun toString(): String = principalName
}

/** Раздел на той стороне: свободное место берётся из df. */
internal class RemoteFileStore(private val fs: RemoteFileSystem, private val path: String) : FileStore() {
    private val space by lazy { runCatching { fs.connection.diskSpace(path) }.getOrNull() }
    override fun name(): String = fs.label
    override fun type(): String = fs.kind
    override fun isReadOnly(): Boolean = false
    override fun getTotalSpace(): Long = space?.second ?: 0L
    override fun getUsableSpace(): Long = space?.first ?: 0L
    override fun getUnallocatedSpace(): Long = space?.first ?: 0L
    override fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type == BasicFileAttributeView::class.java
    override fun supportsFileAttributeView(name: String): Boolean = name == "basic" || name == "posix"
    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>): V? = null
    override fun getAttribute(attribute: String): Any? = null
}

/** Канал поверх потока: панель и копирование читают и пишут файлы последовательно. */
private class StreamChannel(
    private val input: InputStream?,
    private val output: OutputStream?,
    private val length: Long
) : SeekableByteChannel {
    private val reader = input?.let { Channels.newChannel(it) }
    private val writer = output?.let { Channels.newChannel(it) }
    private var pos = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        val r = reader ?: throw NonReadableChannelException()
        val n = r.read(dst)
        if (n > 0) pos += n
        return n
    }

    override fun write(src: ByteBuffer): Int {
        val w = writer ?: throw NonWritableChannelException()
        val n = w.write(src)
        pos += n
        return n
    }

    override fun position(): Long = pos

    override fun position(newPosition: Long): SeekableByteChannel {
        // Вперёд можно — пропускаем байты; назад поток не умеет
        if (newPosition < pos || reader == null) throw UnsupportedOperationException("Удалённый файл читается только вперёд")
        input!!.skipExactly(newPosition - pos)
        pos = newPosition
        return this
    }

    override fun size(): Long = if (writer != null) pos else length
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    override fun isOpen(): Boolean = open
    override fun close() {
        if (!open) return
        open = false
        input?.close()
        output?.close()
    }
}

/**
 * Операции NIO над [RemoteFileSystem] — общие для сервера и телефона. Наследник знает, как
 * подключаться и как найти открытую файловую систему по адресу.
 */
abstract class RemoteFileSystemProvider : FileSystemProvider() {

    /** Файловую систему закрыли: больше не выдавать её как открытую. */
    internal abstract fun forget(fs: RemoteFileSystem)

    private fun cast(path: Path): RemotePath = path as? RemotePath ?: throw ProviderMismatchException()

    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        val p = cast(path).toAbsolutePath() as RemotePath
        val write = StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options
        return if (write) {
            if (StandardOpenOption.APPEND in options) throw UnsupportedOperationException("Дописывать в конец удалённого файла нельзя")
            p.fs.forget(p)
            StreamChannel(null, p.fs.connection.openWrite(p.raw), 0)
        } else {
            val e = p.fs.entryFresh(p) ?: throw NoSuchFileException(p.raw)
            StreamChannel(p.fs.connection.openRead(p.raw), null, e.size)
        }
    }

    override fun newInputStream(path: Path, vararg options: OpenOption): InputStream {
        val p = cast(path).toAbsolutePath() as RemotePath
        if (p.fs.entryFresh(p) == null) throw NoSuchFileException(p.raw)
        return p.fs.connection.openRead(p.raw)
    }

    override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream {
        val p = cast(path).toAbsolutePath() as RemotePath
        if (StandardOpenOption.CREATE_NEW in options && p.fs.entryFresh(p) != null) throw FileAlreadyExistsException(p.raw)
        p.fs.forget(p)
        return p.fs.connection.openWrite(p.raw)
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val p = cast(dir).toAbsolutePath() as RemotePath
        val names = p.fs.list(p).map { it.name }
        return object : DirectoryStream<Path> {
            private var used = false
            override fun iterator(): MutableIterator<Path> {
                check(!used) { "Итератор уже получен" }
                used = true
                return names.map { p.resolve(it) }.filter { filter.accept(it) }.toMutableList().iterator()
            }
            override fun close() = Unit
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        val p = cast(dir).toAbsolutePath() as RemotePath
        if (p.fs.entryFresh(p) != null) throw FileAlreadyExistsException(p.raw)
        p.fs.connection.mkdir(p.raw)
        p.fs.forget(p)
    }

    override fun delete(path: Path) {
        val p = cast(path).toAbsolutePath() as RemotePath
        val e = p.fs.entryFresh(p) ?: throw NoSuchFileException(p.raw)
        // Как и в NIO: непустую папку delete не удаляет — это делает обход сверху
        if (e.isDirectory && !e.isLink && p.fs.list(p).isNotEmpty()) {
            throw java.nio.file.DirectoryNotEmptyException(p.raw)
        }
        p.fs.connection.delete(p.raw, recursive = false)
        p.fs.forget(p)
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        val s = cast(source).toAbsolutePath() as RemotePath
        val t = cast(target).toAbsolutePath() as RemotePath
        if (StandardCopyOption.REPLACE_EXISTING !in options && t.fs.entryFresh(t) != null) throw FileAlreadyExistsException(t.raw)
        if (s.fs === t.fs) {
            s.fs.connection.copyInside(s.raw, t.raw)
        } else {
            // Между двумя серверами (телефонами): данные идут через компьютер потоком, без временного файла.
            // Как Files.copy на диске: ссылка — ссылкой, папка — пустой папкой (содержимое копирует обход)
            val e = s.fs.entryFresh(s) ?: throw NoSuchFileException(s.raw)
            when {
                e.isLink -> t.fs.connection.symlink(t.raw, s.fs.connection.readLink(s.raw))
                e.type == 'd' -> t.fs.connection.mkdir(t.raw)
                else -> s.fs.connection.openRead(s.raw).use { input ->
                    t.fs.connection.openWrite(t.raw).use { output -> input.copyTo(output, 256 * 1024) }
                }
            }
        }
        t.fs.forget(t)
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        val s = cast(source).toAbsolutePath() as RemotePath
        val t = cast(target).toAbsolutePath() as RemotePath
        if (StandardCopyOption.REPLACE_EXISTING !in options && t.fs.entryFresh(t) != null) throw FileAlreadyExistsException(t.raw)
        if (s.fs === t.fs) {
            s.fs.connection.rename(s.raw, t.raw)
        } else {
            // Разные стороны: копия, затем удаление источника — только после успеха.
            // Непустую папку одним шагом не перенести (copy создаёт пустую) — как NIO на разных дисках:
            // DirectoryNotEmptyException, и вызывающий переносит дерево обходом
            val e = s.fs.entryFresh(s) ?: throw NoSuchFileException(s.raw)
            if (e.type == 'd' && s.fs.list(s).isNotEmpty()) throw java.nio.file.DirectoryNotEmptyException(s.raw)
            copy(source, target, *options)
            s.fs.connection.delete(s.raw, recursive = e.type == 'd')
        }
        s.fs.forget(s)
        t.fs.forget(t)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean = path.fileName?.toString()?.startsWith(".") == true

    override fun getFileStore(path: Path): FileStore {
        val p = cast(path).toAbsolutePath() as RemotePath
        return RemoteFileStore(p.fs, p.raw)
    }

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        val p = cast(path).toAbsolutePath() as RemotePath
        // Проверка всегда идёт по ссылке: битая ссылка — «нет такого», как на диске
        followed(p, p.fs.entry(p) ?: throw NoSuchFileException(p.raw), follow = true)
    }

    /** Битая ссылка при раскрытии — «нет такого файла», как у NIO на диске. */
    private fun followed(p: RemotePath, e: RemoteEntry, follow: Boolean): RemoteEntry {
        if (follow && e.isLink && e.targetType !in "fdspcbD") throw NoSuchFileException(p.raw, null, "битая ссылка")
        return e
    }

    override fun readSymbolicLink(link: Path): Path {
        val p = cast(link).toAbsolutePath() as RemotePath
        val e = p.fs.entryFresh(p) ?: throw NoSuchFileException(p.raw)
        if (!e.isLink) throw java.nio.file.NotLinkException(p.raw)
        return RemotePath(p.fs, p.fs.connection.readLink(p.raw))
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) {
        val p = cast(link).toAbsolutePath() as RemotePath
        if (p.fs.entryFresh(p) != null) throw FileAlreadyExistsException(p.raw)
        p.fs.connection.symlink(p.raw, target.toString())
        p.fs.forget(p)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView?> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        if (type != BasicFileAttributeView::class.java) return null
        val p = cast(path).toAbsolutePath() as RemotePath
        return object : BasicFileAttributeView {
            override fun name(): String = "basic"
            override fun readAttributes(): BasicFileAttributes {
                val follow = LinkOption.NOFOLLOW_LINKS !in options
                val e = followed(p, p.fs.entry(p) ?: throw NoSuchFileException(p.raw), follow)
                return RemoteAttributes(e, p.raw, follow)
            }
            override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, createTime: FileTime?) {
                val time = lastModifiedTime ?: return
                runCatching { p.fs.connection.setModified(p.raw, time.toMillis()) }
                p.fs.forget(p)
            }
        } as V
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes?> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        val p = cast(path).toAbsolutePath() as RemotePath
        val follow = LinkOption.NOFOLLOW_LINKS !in options
        val e = followed(p, p.fs.entry(p) ?: throw NoSuchFileException(p.raw), follow)
        if (type == BasicFileAttributes::class.java || type == PosixFileAttributes::class.java) {
            return RemoteAttributes(e, p.raw, follow) as A
        }
        throw UnsupportedOperationException("Нет таких сведений: ${type.simpleName}")
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        val a = readAttributes(path, BasicFileAttributes::class.java, *options)
        return mutableMapOf(
            "size" to a.size(),
            "lastModifiedTime" to a.lastModifiedTime(),
            "isDirectory" to a.isDirectory,
            "isRegularFile" to a.isRegularFile,
            "isSymbolicLink" to a.isSymbolicLink
        )
    }

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        if ((attribute == "lastModifiedTime" || attribute == "basic:lastModifiedTime") && value is FileTime) {
            val p = cast(path).toAbsolutePath() as RemotePath
            runCatching { p.fs.connection.setModified(p.raw, value.toMillis()) }
            p.fs.forget(p)
        }
    }
}
