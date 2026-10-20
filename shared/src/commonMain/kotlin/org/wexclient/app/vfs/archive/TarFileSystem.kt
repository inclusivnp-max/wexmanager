package org.wexclient.app.vfs.archive

import org.wexclient.app.util.readUpTo

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.ClosedFileSystemException
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.NotLinkException
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
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.IdentityHashMap
import java.util.regex.Pattern

internal enum class NodeKind { DIR, FILE, SYMLINK, SPECIAL }

/** Откуда читать содержимое файла. Источники неизменяемы: запись создаёт новый временный файл. */
internal sealed class Content {
    abstract val length: Long

    /** Кусок распакованного tar. */
    class Slice(val offset: Long, override val length: Long) : Content()

    /** Изменённый или новый файл. */
    class Temp(val file: Path, override val length: Long) : Content()

    object Empty : Content() {
        override val length = 0L
    }
}

internal class Node(var name: String, val kind: NodeKind) {
    var parent: Node? = null
    val children: LinkedHashMap<String, Node>? = if (kind == NodeKind.DIR) LinkedHashMap() else null
    var content: Content = Content.Empty
    var mtime: Long = System.currentTimeMillis()
    var mode: Int = when (kind) {
        NodeKind.DIR -> 493 // 0755
        NodeKind.SYMLINK -> 511 // 0777
        else -> 420 // 0644
    }
    var uid = 0L
    var gid = 0L
    var uname = ""
    var gname = ""
    var link: String? = null
    /** Была жёсткой ссылкой в архиве — при записи снова станет ссылкой, если цель записана раньше. */
    var hardLink = false
    var typeFlag = '0'
    var devMajor = 0
    var devMinor = 0
    var unsupported: String? = null
    /** Нераспознанные PAX-атрибуты (xattr, ACL, atime…) — записываются обратно. */
    var extraPax: Map<String, String> = emptyMap()

    val size: Long
        get() = when (kind) {
            NodeKind.FILE -> content.length
            NodeKind.SYMLINK -> (link ?: "").toByteArray().size.toLong()
            else -> 0L
        }

    fun put(child: Node) {
        children!!.remove(child.name)?.parent = null
        child.parent = this
        children[child.name] = child
    }

    fun detach() {
        parent?.children?.let { if (it[name] === this) it.remove(name) }
        parent = null
    }

    fun isAttached(root: Node): Boolean {
        var n: Node? = this
        while (n != null) {
            if (n === root) return true
            n = n.parent
        }
        return false
    }

    fun path(): String {
        val parts = ArrayList<String>()
        var n: Node? = this
        while (n?.parent != null) {
            parts.add(n.name)
            n = n.parent
        }
        return parts.asReversed().joinToString("/")
    }

    fun copyMetaFrom(o: Node) {
        mtime = o.mtime
        mode = o.mode
        uid = o.uid
        gid = o.gid
        uname = o.uname
        gname = o.gname
        extraPax = o.extraPax
    }

    /** PAX для записи: точное время из архива — только если его не меняли. */
    fun paxToWrite(): Map<String, String> {
        val exact = extraPax["mtime"] ?: return extraPax
        return if (TarCodec.paxTimeMillis(exact) == mtime) extraPax else extraPax - "mtime"
    }
}

/**
 * Архив tar (сжатый или нет) либо одиночный сжатый файл как файловая система NIO.
 * Сжатый архив распаковывается во временный tar; правки хранятся во временных файлах
 * и записываются в архив при close() — так же, как это делает zipfs.
 */
class TarFileSystem internal constructor(
    private val provider: TarFileSystemProvider,
    /** Файл архива (на диске или внутри другого архива). */
    val source: Path
) : FileSystem() {
    private val lock = Any()

    @Volatile
    private var open = true
    @Volatile
    private var dirty = false

    // close() заменяет пустым: закрытую ФС ещё держат старые Path из панелей, а дерево — ~250 Б на запись
    internal var root = Node("", NodeKind.DIR)
        private set
    internal val rootPath = TarPath(this, "/")
    private val store = TarFileStore(this)

    private var baseChannel: FileChannel? = null

    // Распакованный tar и временные файлы правок. Удаляются в close(), а у ФС, брошенной незакрытой, — когда её
    // заберёт сборщик мусора: VfsManager давно не нужный архив не закрывает (путь в него мог остаться у окна
    // или потока), а только перестаёт держать
    private val leftovers = Leftovers()
    private val cleanable = ArchiveCache.cleaner.register(this, leftovers)

    /** Есть правки, ещё не записанные в архив (такую ФС отпускать нельзя — они пропали бы). */
    internal val hasUnsavedChanges: Boolean get() = open && dirty

    /** Сжатие исходника (по содержимому). */
    var compression: Compression = Compression.NONE
        private set

    /** Не tar, а одиночный сжатый файл: внутри ровно один файл. */
    var singleFile = false
        private set

    /** Первая запись архива начиналась с «./» — сохраняем тот же вид путей. */
    private var dotSlash = false
    private var hasRootEntry = false

    private var readOnlyReason: String? = null

    /** Первое имя не в UTF-8: при перезаписи его байты изменились бы — такой архив только читаем. */
    private var nonUtf8Name: String? = null

    /** Подпись для панели: TAR.GZ, GZ… */
    val label: String
        get() = when {
            singleFile -> compression.label
            compression == Compression.NONE -> "TAR"
            else -> "TAR." + compression.label
        }

    internal fun load(progress: ((Long) -> Unit)?, cancelled: (() -> Boolean)? = null) {
        val head = Files.newInputStream(source).use { it.readUpTo(16) }
        val byName = ArchiveFormats.kindOf(source.fileName?.toString() ?: "")
        compression = Compression.detect(head)
            ?: if (byName?.compression == Compression.LZMA) Compression.LZMA else Compression.NONE
        val onDisk = source.fileSystem === FileSystems.getDefault()
        val file = if (compression == Compression.NONE && onDisk) {
            source
        } else {
            val tmp = ArchiveCache.newTempFile(".tar")
            leftovers.files.add(tmp)
            try {
                ArchiveFormats.decompress(compression, source, tmp, progress, cancelled)
            } catch (e: Exception) {
                Files.deleteIfExists(tmp)
                throw e
            }
            tmp
        }
        val ch = FileChannel.open(file, StandardOpenOption.READ)
        baseChannel = ch
        leftovers.channel = ch
        val sourceMtime = runCatching { Files.getLastModifiedTime(source).toMillis() }.getOrDefault(System.currentTimeMillis())
        root.mtime = sourceMtime
        val isTar = TarCodec.looksLikeTar(ch) || (ch.size() == 0L && compression == Compression.NONE)
        if (!isTar) {
            if (compression == Compression.NONE) throw IOException("Файл не является tar-архивом")
            singleFile = true
            val node = Node(ArchiveFormats.innerName(source.fileName?.toString() ?: "file"), NodeKind.FILE)
            node.content = if (ch.size() == 0L) Content.Empty else Content.Slice(0, ch.size())
            node.mtime = sourceMtime
            root.put(node)
        } else {
            var first = true
            var records = 0L
            // Каждая запись — узел в памяти: миллионы пустых файлов сжимаются в килобайты
            val maxRecords = maxOf(1_000_000L, Runtime.getRuntime().maxMemory() / 512)
            TarCodec.read(ch) { rec ->
                if (first) {
                    dotSlash = rec.rawPath.startsWith("./")
                    first = false
                }
                if (++records and 0xFFF == 0L && cancelled?.invoke() == true) {
                    throw ArchiveFormats.Aborted("Открытие отменено")
                }
                if (records > maxRecords) throw IOException("В архиве слишком много записей (больше $maxRecords)")
                addRecord(rec)
            }
        }
        val writable = when {
            onDisk -> Files.isWritable(source) && runCatching { Files.isWritable(source.toAbsolutePath().parent) }.getOrDefault(false)
            else -> !source.fileSystem.isReadOnly
        }
        val unsupported = firstUnsupported(root)
        readOnlyReason = when {
            !writable -> "нет прав на запись"
            unsupported != null -> unsupported
            nonUtf8Name != null -> "«$nonUtf8Name»: имя не в кодировке UTF-8"
            compression.tool != null && !ArchiveFormats.toolAvailable(compression.tool!!) -> "нет утилиты ${compression.tool}"
            else -> null
        }
    }

    private fun firstUnsupported(node: Node): String? {
        node.children?.values?.forEach { c ->
            c.unsupported?.let { return "«${c.path()}»: $it" }
            firstUnsupported(c)?.let { return it }
        }
        return null
    }

    private fun addRecord(rec: TarRecord) {
        if (rec.nameNotUtf8 && nonUtf8Name == null) nonUtf8Name = rec.rawPath
        val parts = normalizeParts(rec.rawPath)
        if (parts.isEmpty()) {
            if (rec.kind == TarEntryKind.DIR) {
                hasRootEntry = true
                applyRecord(root, rec)
            }
            return
        }
        var dir = root
        for (i in 0 until parts.size - 1) {
            val existing = dir.children!![parts[i]]
            dir = if (existing != null && existing.kind == NodeKind.DIR) existing else {
                Node(parts[i], NodeKind.DIR).also {
                    it.mtime = rec.mtime
                    dir.put(it)
                }
            }
        }
        val name = parts.last()
        when (rec.kind) {
            TarEntryKind.DIR -> {
                val existing = dir.children!![name]
                val node = if (existing != null && existing.kind == NodeKind.DIR) existing else Node(name, NodeKind.DIR).also { dir.put(it) }
                applyRecord(node, rec)
            }
            TarEntryKind.FILE -> {
                val node = Node(name, NodeKind.FILE)
                node.content = if (rec.size == 0L) Content.Empty else Content.Slice(rec.dataOffset, rec.size)
                node.unsupported = rec.unsupported
                applyRecord(node, rec)
                dir.put(node)
            }
            TarEntryKind.HARDLINK -> {
                val node = Node(name, NodeKind.FILE)
                val target = rec.link?.let { lookupPlain(normalizeParts(it)) }
                if (target != null && target.kind == NodeKind.FILE) {
                    node.content = target.content
                    node.unsupported = target.unsupported
                } else {
                    node.unsupported = "жёсткая ссылка на отсутствующий файл ${rec.link}"
                }
                node.hardLink = true
                applyRecord(node, rec)
                dir.put(node)
            }
            TarEntryKind.SYMLINK -> {
                val node = Node(name, NodeKind.SYMLINK)
                node.link = rec.link ?: ""
                applyRecord(node, rec)
                dir.put(node)
            }
            TarEntryKind.SPECIAL -> {
                val node = Node(name, NodeKind.SPECIAL)
                node.typeFlag = rec.typeFlag
                node.devMajor = rec.devMajor
                node.devMinor = rec.devMinor
                applyRecord(node, rec)
                dir.put(node)
            }
        }
    }

    private fun applyRecord(node: Node, rec: TarRecord) {
        node.extraPax = rec.extraPax
        node.mtime = rec.mtime
        node.mode = rec.mode
        node.uid = rec.uid
        node.gid = rec.gid
        node.uname = rec.uname
        node.gname = rec.gname
    }

    private fun lookupPlain(parts: List<String>): Node? {
        var cur = root
        for (p in parts) {
            cur = cur.children?.get(p) ?: return null
        }
        return cur
    }

    // ---- состояние ----

    private fun ensureOpen() {
        if (!open) throw ClosedFileSystemException()
    }

    internal fun checkWritable(path: Path) {
        readOnlyReason?.let { throw AccessDeniedException(path.toString(), null, "архив только для чтения: $it") }
    }

    // В одиночном сжатом файле можно менять только содержимое
    private fun checkStructure(path: Path) {
        if (singleFile) throw FileSystemException(path.toString(), null, "в ${compression.label} может быть только один файл")
    }

    /** Узел по пути; [followLast] — раскрывать символическую ссылку в последнем элементе. */
    internal fun resolve(path: TarPath, followLast: Boolean): Node? {
        val queue = ArrayDeque((path.toAbsolutePath() as TarPath).names)
        var cur = root
        var hops = 0
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name == ".") continue
            if (name == "..") {
                cur = cur.parent ?: root
                continue
            }
            val child = cur.children?.get(name) ?: return null
            val last = queue.isEmpty()
            if (child.kind == NodeKind.SYMLINK && (!last || followLast)) {
                if (++hops > 40) throw FileSystemException(path.toString(), null, "слишком много символических ссылок")
                val target = child.link ?: return null
                if (target.startsWith("/")) cur = root
                target.split('/').filter { it.isNotEmpty() }.asReversed().forEach { queue.addFirst(it) }
                continue
            }
            cur = child
        }
        return cur
    }

    private fun parentOf(path: TarPath): Pair<Node, String> {
        val abs = path.toAbsolutePath().normalize() as TarPath
        val name = abs.names.lastOrNull() ?: throw FileSystemException(path.toString(), null, "корень архива")
        val parentPath = abs.parent as TarPath
        val parent = resolve(parentPath, true) ?: throw NoSuchFileException(parentPath.toString())
        if (parent.kind != NodeKind.DIR) throw NotDirectoryException(parentPath.toString())
        return parent to name
    }

    private fun markDirty() {
        dirty = true
    }

    // ---- операции (вызываются провайдером) ----

    internal fun newByteChannel(path: TarPath, options: Set<OpenOption>): SeekableByteChannel = synchronized(lock) {
        ensureOpen()
        val write = StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options
        if (!write) {
            val node = resolve(path, LinkOption.NOFOLLOW_LINKS !in options) ?: throw NoSuchFileException(path.toString())
            return openRead(path, node)
        }
        checkWritable(path)
        val (parent, name) = parentOf(path)
        var node = parent.children!![name]
        if (node != null && node.kind == NodeKind.SYMLINK) {
            node = resolve(path, true) ?: throw FileSystemException(path.toString(), null, "запись через битую символическую ссылку")
        }
        if (node != null && StandardOpenOption.CREATE_NEW in options) throw FileAlreadyExistsException(path.toString())
        if (node == null && StandardOpenOption.CREATE !in options && StandardOpenOption.CREATE_NEW !in options) {
            throw NoSuchFileException(path.toString())
        }
        if (node != null && node.kind != NodeKind.FILE) throw FileSystemException(path.toString(), null, "это не файл")
        if (node == null) checkStructure(path)
        node?.unsupported?.let { throw FileSystemException(path.toString(), null, it) }
        val temp = ArchiveCache.newTempFile(".part")
        leftovers.files.add(temp)
        if (node != null && StandardOpenOption.TRUNCATE_EXISTING !in options) {
            Files.newOutputStream(temp).use { out -> copyContent(node.content, out) }
        }
        val ch = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE)
        if (StandardOpenOption.APPEND in options) ch.position(ch.size())
        val target = node
        return WriteChannel(ch) { size -> commitWrite(parent, name, target, temp, size) }
    }

    private fun commitWrite(parent: Node, name: String, existing: Node?, temp: Path, size: Long) {
        synchronized(lock) {
            if (!open) return
            val content = if (size == 0L) Content.Empty else Content.Temp(temp, size)
            if (existing != null && existing.isAttached(root)) {
                existing.content = content
                existing.mtime = System.currentTimeMillis()
                existing.hardLink = false
            } else if (parent.isAttached(root)) {
                val node = Node(name, NodeKind.FILE)
                node.content = content
                inheritOwner(node, parent)
                parent.put(node)
            }
            markDirty()
        }
    }

    private fun inheritOwner(node: Node, parent: Node) {
        node.uid = parent.uid
        node.gid = parent.gid
        node.uname = parent.uname
        node.gname = parent.gname
    }

    private fun openRead(path: TarPath, node: Node): SeekableByteChannel {
        if (node.kind == NodeKind.DIR) throw FileSystemException(path.toString(), null, "это папка")
        node.unsupported?.let { throw FileSystemException(path.toString(), null, it) }
        if (node.kind != NodeKind.FILE) return SliceChannel(null, 0, 0)
        return when (val c = node.content) {
            is Content.Slice -> SliceChannel(baseChannel ?: throw ClosedChannelException(), c.offset, c.length)
            is Content.Temp -> ReadOnlyChannel(FileChannel.open(c.file, StandardOpenOption.READ))
            Content.Empty -> SliceChannel(null, 0, 0)
        }
    }

    private fun copyContent(c: Content, out: OutputStream) {
        when (c) {
            is Content.Slice -> {
                val ch = baseChannel ?: throw ClosedChannelException()
                val buf = ByteBuffer.allocate(1 shl 16)
                var pos = c.offset
                val end = c.offset + c.length
                while (pos < end) {
                    buf.clear()
                    if (end - pos < buf.capacity()) buf.limit((end - pos).toInt())
                    val n = ch.read(buf, pos)
                    if (n <= 0) throw IOException("Архив обрезан")
                    out.write(buf.array(), 0, n)
                    pos += n
                }
            }
            is Content.Temp -> Files.copy(c.file, out)
            Content.Empty -> Unit
        }
    }

    internal fun newDirectoryStream(dir: TarPath, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = synchronized(lock) {
        ensureOpen()
        val node = resolve(dir, true) ?: throw NoSuchFileException(dir.toString())
        if (node.kind != NodeKind.DIR) throw NotDirectoryException(dir.toString())
        val names = node.children!!.keys.toList()
        return TarDirectoryStream(dir, names, filter)
    }

    internal fun createDirectory(dir: TarPath) {
        synchronized(lock) {
            ensureOpen()
            // Корень существует всегда: так Files.createDirectories("/") не падает с «Unable to determine…»
            if ((dir.toAbsolutePath() as TarPath).names.isEmpty()) throw FileAlreadyExistsException(dir.toString())
            checkWritable(dir)
            val (parent, name) = parentOf(dir)
            if (parent.children!!.containsKey(name)) throw FileAlreadyExistsException(dir.toString())
            checkStructure(dir)
            val node = Node(name, NodeKind.DIR)
            inheritOwner(node, parent)
            parent.put(node)
            markDirty()
        }
    }

    internal fun createSymbolicLink(link: TarPath, target: String) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(link)
            val (parent, name) = parentOf(link)
            if (parent.children!!.containsKey(name)) throw FileAlreadyExistsException(link.toString())
            checkStructure(link)
            val node = Node(name, NodeKind.SYMLINK)
            node.link = target
            inheritOwner(node, parent)
            parent.put(node)
            markDirty()
        }
    }

    internal fun delete(path: TarPath) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(path)
            val node = resolve(path, false) ?: throw NoSuchFileException(path.toString())
            if (node === root) throw FileSystemException(path.toString(), null, "нельзя удалить корень архива")
            if (node.kind == NodeKind.DIR && node.children!!.isNotEmpty()) throw DirectoryNotEmptyException(path.toString())
            checkStructure(path)
            node.detach()
            markDirty()
        }
    }

    internal fun readSymbolicLink(link: TarPath): Path = synchronized(lock) {
        ensureOpen()
        val node = resolve(link, false) ?: throw NoSuchFileException(link.toString())
        if (node.kind != NodeKind.SYMLINK) throw NotLinkException(link.toString())
        return TarPath(this, node.link ?: "")
    }

    internal fun copyWithin(src: TarPath, dst: TarPath, options: Set<CopyOption>) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(dst)
            val follow = LinkOption.NOFOLLOW_LINKS !in options
            val node = resolve(src, follow) ?: throw NoSuchFileException(src.toString())
            node.unsupported?.let { throw FileSystemException(src.toString(), null, it) }
            val (parent, name) = parentOf(dst)
            checkStructure(dst)
            prepareTarget(parent, name, dst, options, node)
            val copy = Node(name, node.kind)
            when (node.kind) {
                NodeKind.FILE -> copy.content = node.content
                NodeKind.SYMLINK -> copy.link = node.link
                NodeKind.SPECIAL -> {
                    copy.typeFlag = node.typeFlag
                    copy.devMajor = node.devMajor
                    copy.devMinor = node.devMinor
                }
                NodeKind.DIR -> Unit // Files.copy копирует папку без содержимого
            }
            copy.copyMetaFrom(node)
            if (StandardCopyOption.COPY_ATTRIBUTES !in options) copy.mtime = System.currentTimeMillis()
            parent.put(copy)
            markDirty()
        }
    }

    internal fun moveWithin(src: TarPath, dst: TarPath, options: Set<CopyOption>) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(src)
            val node = resolve(src, false) ?: throw NoSuchFileException(src.toString())
            if (node === root) throw FileSystemException(src.toString(), null, "нельзя переместить корень архива")
            val (parent, name) = parentOf(dst)
            if (parent.children!![name] === node) return
            var p: Node? = parent
            while (p != null) {
                if (p === node) throw FileSystemException(src.toString(), dst.toString(), "нельзя переместить папку внутрь неё самой")
                p = p.parent
            }
            checkStructure(dst)
            prepareTarget(parent, name, dst, options, node)
            node.detach()
            node.name = name
            parent.put(node)
            markDirty()
        }
    }

    private fun prepareTarget(parent: Node, name: String, dst: TarPath, options: Set<CopyOption>, source: Node) {
        val existing = parent.children!![name] ?: return
        if (existing === source) throw FileSystemException(dst.toString(), null, "источник и назначение совпадают")
        if (StandardCopyOption.REPLACE_EXISTING !in options) throw FileAlreadyExistsException(dst.toString())
        if (existing.kind == NodeKind.DIR && existing.children!!.isNotEmpty()) throw DirectoryNotEmptyException(dst.toString())
        existing.detach()
    }

    internal fun attributes(path: TarPath, follow: Boolean): TarAttributes = synchronized(lock) {
        ensureOpen()
        val node = resolve(path, follow) ?: throw NoSuchFileException(path.toString())
        return TarAttributes(node, this)
    }

    internal fun setTimes(path: TarPath, follow: Boolean, lastModified: FileTime?) {
        synchronized(lock) {
            ensureOpen()
            if (lastModified == null) return
            checkWritable(path)
            val node = resolve(path, follow) ?: throw NoSuchFileException(path.toString())
            if (node.mtime != lastModified.toMillis()) {
                node.mtime = lastModified.toMillis()
                markDirty()
            }
        }
    }

    internal fun setMode(path: TarPath, follow: Boolean, mode: Int) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(path)
            val node = resolve(path, follow) ?: throw NoSuchFileException(path.toString())
            val merged = (node.mode and 0xE00) or (mode and 0x1FF)
            if (node.mode != merged) {
                node.mode = merged
                markDirty()
            }
        }
    }

    internal fun setOwner(path: TarPath, follow: Boolean, user: String?, group: String?) {
        synchronized(lock) {
            ensureOpen()
            checkWritable(path)
            val node = resolve(path, follow) ?: throw NoSuchFileException(path.toString())
            user?.let { node.uname = it }
            group?.let { node.gname = it }
            markDirty()
        }
    }

    internal fun checkAccess(path: TarPath, modes: Array<out AccessMode>) {
        synchronized(lock) {
            ensureOpen()
            val node = resolve(path, true) ?: throw NoSuchFileException(path.toString())
            if (AccessMode.WRITE in modes) checkWritable(path)
            if (AccessMode.EXECUTE in modes && node.kind == NodeKind.FILE && (node.mode and 73) == 0) {
                throw AccessDeniedException(path.toString())
            }
        }
    }

    internal fun sameNode(a: TarPath, b: TarPath): Boolean = synchronized(lock) {
        ensureOpen()
        val na = resolve(a, true) ?: throw NoSuchFileException(a.toString())
        val nb = resolve(b, true) ?: throw NoSuchFileException(b.toString())
        return na === nb
    }

    internal fun realPath(path: TarPath, follow: Boolean): TarPath = synchronized(lock) {
        ensureOpen()
        val node = resolve(path, follow) ?: throw NoSuchFileException(path.toString())
        // Без раскрытия ссылок — тот же (нормализованный) путь, только проверка существования
        return if (follow) TarPath(this, "/" + node.path()) else path
    }

    internal val sourceSize: Long get() = runCatching { Files.size(source) }.getOrDefault(0L)

    // ---- запись архива ----

    private fun save() {
        val onDisk = source.fileSystem === FileSystems.getDefault()
        val out = if (onDisk) {
            Files.createTempFile(source.toAbsolutePath().parent, ".wex-", ".tmp")
        } else {
            ArchiveCache.newTempFile(".out")
        }
        try {
            if (singleFile) {
                val node = root.children!!.values.firstOrNull { it.kind == NodeKind.FILE }
                    ?: throw IOException("в ${compression.label} нет файла")
                val plain = ArchiveCache.newTempFile(".raw")
                try {
                    Files.newOutputStream(plain).use { copyContent(node.content, it) }
                    ArchiveFormats.compress(compression, plain, out)
                } finally {
                    Files.deleteIfExists(plain)
                }
            } else if (compression == Compression.NONE) {
                Files.newOutputStream(out).use { writeTar(it) }
            } else {
                val tar = ArchiveCache.newTempFile(".tar")
                try {
                    Files.newOutputStream(tar).use { writeTar(it) }
                    ArchiveFormats.compress(compression, tar, out)
                } finally {
                    Files.deleteIfExists(tar)
                }
            }
            if (onDisk) {
                runCatching { Files.setPosixFilePermissions(out, Files.getPosixFilePermissions(source)) }
                try {
                    Files.move(out, source, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(out, source, StandardCopyOption.REPLACE_EXISTING)
                }
            } else {
                // Архив внутри другого архива: внешний допишется при его закрытии
                Files.copy(out, source, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(out)
        }
    }

    private fun writeTar(out: OutputStream) {
        val writer = TarCodec.Writer(out)
        val prefix = if (dotSlash) "./" else ""
        val written = IdentityHashMap<Content, String>()
        if (hasRootEntry || dotSlash) {
            writer.entry("./", '5', 0, root.mtime, root.mode, root.uid, root.gid, root.uname, root.gname, extraPax = root.paxToWrite())
        }
        fun visit(dir: Node, path: String) {
            for (node in dir.children!!.values.toList()) {
                val p = if (path.isEmpty()) prefix + node.name else "$path/${node.name}"
                when (node.kind) {
                    NodeKind.DIR -> {
                        writer.entry("$p/", '5', 0, node.mtime, node.mode, node.uid, node.gid, node.uname, node.gname, extraPax = node.paxToWrite())
                        visit(node, p)
                    }
                    NodeKind.FILE -> {
                        node.unsupported?.let { throw IOException("«$p»: $it") }
                        val c = node.content
                        val linkTo = if (node.hardLink && c !is Content.Empty) written[c] else null
                        if (linkTo != null) {
                            writer.entry(
                                p, '1', 0, node.mtime, node.mode, node.uid, node.gid, node.uname, node.gname,
                                link = linkTo, extraPax = node.paxToWrite()
                            )
                        } else {
                            writer.entry(
                                p, '0', c.length, node.mtime, node.mode, node.uid, node.gid, node.uname, node.gname,
                                extraPax = node.paxToWrite()
                            ) { copyContent(c, it) }
                            if (c !is Content.Empty) written.putIfAbsent(c, p)
                        }
                    }
                    NodeKind.SYMLINK -> writer.entry(
                        p, '2', 0, node.mtime, node.mode, node.uid, node.gid, node.uname, node.gname,
                        link = node.link ?: "", extraPax = node.paxToWrite()
                    )
                    NodeKind.SPECIAL -> writer.entry(
                        p, node.typeFlag, 0, node.mtime, node.mode, node.uid, node.gid, node.uname, node.gname,
                        devMajor = node.devMajor, devMinor = node.devMinor, extraPax = node.paxToWrite()
                    )
                }
            }
        }
        visit(root, "")
        writer.finish()
        writer.close()
    }

    // ---- FileSystem ----

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!open) return
            open = false
            try {
                if (dirty) save()
            } finally {
                runCatching { baseChannel?.close() }
                // Распакованный tar и временные файлы правок
                cleanable.clean()
                // Дерево больше не нужно: все операции сначала проверяют ensureOpen()
                root = Node("", NodeKind.DIR)
            }
        }
    }

    /** Закрыть без записи правок (архив не удалось открыть целиком). */
    internal fun discard() {
        dirty = false
        close()
    }

    override fun isOpen(): Boolean = open

    override fun isReadOnly(): Boolean = readOnlyReason != null

    override fun getSeparator(): String = "/"

    override fun getRootDirectories(): MutableIterable<Path> = mutableListOf(rootPath)

    override fun getFileStores(): MutableIterable<FileStore> = mutableListOf(store)

    override fun supportedFileAttributeViews(): MutableSet<String> = mutableSetOf("basic", "posix", "owner")

    override fun getPath(first: String, vararg more: String): Path {
        if (more.isEmpty()) return TarPath(this, first)
        val sb = StringBuilder(first)
        for (m in more) {
            if (m.isEmpty()) continue
            if (sb.isNotEmpty() && !sb.endsWith("/")) sb.append('/')
            sb.append(m)
        }
        return TarPath(this, sb.toString())
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        val colon = syntaxAndPattern.indexOf(':')
        require(colon > 0) { "Нужен формат «синтаксис:шаблон»" }
        val syntax = syntaxAndPattern.substring(0, colon)
        val pattern = syntaxAndPattern.substring(colon + 1)
        val regex = when (syntax.lowercase()) {
            "regex" -> pattern
            "glob" -> globToRegex(pattern)
            else -> throw UnsupportedOperationException("Синтаксис «$syntax» не поддерживается")
        }
        val compiled = Pattern.compile(regex)
        return PathMatcher { p -> compiled.matcher(p.toString()).matches() }
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw UnsupportedOperationException()

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    override fun toString(): String = "$label:$source"

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder("^")
        var i = 0
        var inGroup = false
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> if (i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i++
                } else {
                    sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '{' -> {
                    sb.append("(?:")
                    inGroup = true
                }
                '}' -> {
                    sb.append(')')
                    inGroup = false
                }
                ',' -> sb.append(if (inGroup) "|" else ",")
                '[' -> sb.append('[')
                ']' -> sb.append(']')
                '\\' -> {
                    i++
                    if (i < glob.length) sb.append(Pattern.quote(glob[i].toString()))
                }
                else -> sb.append(if (c.isLetterOrDigit() || c == '/') c.toString() else Pattern.quote(c.toString()))
            }
            i++
        }
        return sb.append('$').toString()
    }
}

/**
 * Что убрать за [TarFileSystem]: канал и временные файлы. Отдельный класс, а не лямбда: действие Cleaner
 * не должно ссылаться на саму ФС, иначе сборщик мусора её никогда не заберёт.
 */
private class Leftovers : Runnable {
    val files: MutableList<Path> = java.util.Collections.synchronizedList(ArrayList())

    @Volatile
    var channel: java.nio.channels.Channel? = null

    override fun run() {
        // Сначала канал: на Windows открытый файл не удаляется
        runCatching { channel?.close() }
        val all = synchronized(files) { files.toList().also { files.clear() } }
        all.forEach { runCatching { Files.deleteIfExists(it) } }
    }
}

/** Путь внутри архива; разделитель — «/». */
class TarPath internal constructor(private val fs: TarFileSystem, path: String) : Path {
    // В Java 8 (так видит их Android) эти методы абстрактные, в JDK 11+ — по умолчанию; тела — как в JDK
    override fun startsWith(other: String): Boolean = startsWith(fileSystem.getPath(other))
    override fun endsWith(other: String): Boolean = endsWith(fileSystem.getPath(other))
    override fun resolve(other: String): Path = resolve(fileSystem.getPath(other))
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

    private fun of(s: String) = TarPath(fs, s)

    private fun cast(other: Path): TarPath {
        if (other !is TarPath) throw ProviderMismatchException()
        return other
    }

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
        val o = other as? TarPath ?: return false
        if (o.fs !== fs || o.isAbsolute != isAbsolute) return false
        if (o.raw.isEmpty()) return raw.isEmpty()
        if (o.names.size > names.size) return false
        return names.subList(0, o.names.size) == o.names
    }

    override fun endsWith(other: Path): Boolean {
        val o = other as? TarPath ?: return false
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
        val o = cast(other)
        if (o.isAbsolute) return o
        if (o.raw.isEmpty()) return this
        if (raw.isEmpty()) return o
        return of(if (raw == "/") "/" + o.raw else raw + "/" + o.raw)
    }

    override fun relativize(other: Path): Path {
        val o = cast(other)
        if (o.isAbsolute != isAbsolute) throw IllegalArgumentException("Пути разного вида: $this и $o")
        if (o.raw == raw) return of("")
        var i = 0
        while (i < names.size && i < o.names.size && names[i] == o.names[i]) i++
        val parts = List(names.size - i) { ".." } + o.names.subList(i, o.names.size)
        return of(parts.joinToString("/"))
    }

    override fun toUri(): URI {
        val archive = runCatching { fs.source.toAbsolutePath().toUri().toString() }.getOrDefault(fs.source.toString())
        return URI(TarFileSystemProvider.SCHEME, "$archive!${(toAbsolutePath() as TarPath).raw}", null)
    }

    override fun toAbsolutePath(): Path = if (isAbsolute) this else of("/$raw")

    override fun toRealPath(vararg options: LinkOption): Path =
        fs.realPath(toAbsolutePath().normalize() as TarPath, LinkOption.NOFOLLOW_LINKS !in options)

    override fun register(watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier): WatchKey =
        throw UnsupportedOperationException()

    override fun compareTo(other: Path): Int = raw.compareTo(cast(other).raw)

    override fun equals(other: Any?): Boolean = other is TarPath && other.fs === fs && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw
}

private class TarDirectoryStream(
    private val dir: TarPath,
    private val names: List<String>,
    private val filter: DirectoryStream.Filter<in Path>
) : DirectoryStream<Path> {
    private var used = false
    private var closed = false

    override fun iterator(): MutableIterator<Path> {
        if (closed) throw IllegalStateException("Поток закрыт")
        if (used) throw IllegalStateException("Итератор уже получен")
        used = true
        val paths = names.map { dir.resolve(it) }.filter { filter.accept(it) }
        return paths.toMutableList().iterator()
    }

    override fun close() {
        closed = true
    }
}

/** Чтение куска распакованного tar (позиционное чтение потокобезопасно). */
private class SliceChannel(private val base: FileChannel?, private val offset: Long, private val length: Long) : SeekableByteChannel {
    private var pos = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (pos >= length) return -1
        val want = minOf(dst.remaining().toLong(), length - pos).toInt()
        if (want == 0) return 0
        val ch = base ?: return -1
        val saved = dst.limit()
        dst.limit(dst.position() + want)
        try {
            val n = ch.read(dst, offset + pos)
            if (n > 0) pos += n
            return if (n < 0) -1 else n
        } finally {
            dst.limit(saved)
        }
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        pos = newPosition
        return this
    }
    override fun size(): Long = length
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
    }
}

private class ReadOnlyChannel(private val inner: FileChannel) : SeekableByteChannel by inner {
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun position(newPosition: Long): SeekableByteChannel {
        inner.position(newPosition)
        return this
    }
}

/** Запись во временный файл; при закрытии содержимое становится файлом архива. */
private class WriteChannel(private val inner: FileChannel, private val onClose: (Long) -> Unit) : SeekableByteChannel by inner {
    private var closed = false

    override fun position(newPosition: Long): SeekableByteChannel {
        inner.position(newPosition)
        return this
    }

    override fun truncate(size: Long): SeekableByteChannel {
        inner.truncate(size)
        return this
    }

    override fun close() {
        if (closed) return
        closed = true
        val size = inner.size()
        inner.close()
        onClose(size)
    }
}

internal class TarAttributes(node: Node, fs: TarFileSystem) : PosixFileAttributes {
    private val mtime = FileTime.fromMillis(node.mtime)
    private val kind = node.kind
    private val size = node.size
    private val mode = node.mode
    private val owner = node.uname.ifEmpty { node.uid.toString() }
    private val group = node.gname.ifEmpty { node.gid.toString() }
    private val key = "${System.identityHashCode(fs)}:${System.identityHashCode(node)}"

    override fun lastModifiedTime(): FileTime = mtime
    override fun lastAccessTime(): FileTime = mtime
    override fun creationTime(): FileTime = mtime
    override fun isRegularFile(): Boolean = kind == NodeKind.FILE
    override fun isDirectory(): Boolean = kind == NodeKind.DIR
    override fun isSymbolicLink(): Boolean = kind == NodeKind.SYMLINK
    override fun isOther(): Boolean = kind == NodeKind.SPECIAL
    override fun size(): Long = size
    override fun fileKey(): Any = key
    override fun owner(): UserPrincipal = NamedPrincipal(owner)
    override fun group(): GroupPrincipal = NamedPrincipal(group)
    override fun permissions(): MutableSet<PosixFilePermission> = modeToPermissions(mode)

    fun toMap(view: String, names: List<String>): MutableMap<String, Any> {
        val all = linkedMapOf<String, Any>(
            "lastModifiedTime" to mtime,
            "lastAccessTime" to mtime,
            "creationTime" to mtime,
            "size" to size,
            "isRegularFile" to isRegularFile,
            "isDirectory" to isDirectory,
            "isSymbolicLink" to isSymbolicLink,
            "isOther" to isOther,
            "fileKey" to key
        )
        if (view == "posix" || view == "owner") all["owner"] = owner()
        if (view == "posix") {
            all["group"] = group()
            all["permissions"] = permissions()
        }
        if (names.contains("*")) return all
        return names.filter { all.containsKey(it) }.associateWithTo(LinkedHashMap()) { all.getValue(it) }
    }
}

private class NamedPrincipal(private val principalName: String) : GroupPrincipal {
    override fun getName(): String = principalName
    override fun toString(): String = principalName
    override fun equals(other: Any?): Boolean = other is NamedPrincipal && other.principalName == principalName
    override fun hashCode(): Int = principalName.hashCode()
}

private fun modeToPermissions(mode: Int): MutableSet<PosixFilePermission> {
    val result = LinkedHashSet<PosixFilePermission>()
    val order = listOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
    )
    order.forEachIndexed { i, p -> if ((mode and (1 shl (8 - i))) != 0) result.add(p) }
    return result
}

private fun permissionsToMode(perms: Set<PosixFilePermission>): Int {
    val order = listOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
    )
    var mode = 0
    order.forEachIndexed { i, p -> if (p in perms) mode = mode or (1 shl (8 - i)) }
    return mode
}

private class TarAttributeView(private val path: TarPath, private val follow: Boolean, private val viewName: String) :
    PosixFileAttributeView {
    private val fs get() = path.fileSystem as TarFileSystem

    override fun name(): String = viewName
    override fun readAttributes(): PosixFileAttributes = fs.attributes(path, follow)
    override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, createTime: FileTime?) =
        fs.setTimes(path, follow, lastModifiedTime)
    override fun setPermissions(perms: MutableSet<PosixFilePermission>) = fs.setMode(path, follow, permissionsToMode(perms))
    override fun setGroup(group: GroupPrincipal) = fs.setOwner(path, follow, null, group.name)
    override fun getOwner(): UserPrincipal = readAttributes().owner()
    override fun setOwner(owner: UserPrincipal) = fs.setOwner(path, follow, owner.name, null)
}

private class TarFileStore(private val fs: TarFileSystem) : FileStore() {
    override fun name(): String = fs.source.fileName?.toString() ?: fs.source.toString()
    override fun type(): String = "tar"
    override fun isReadOnly(): Boolean = fs.isReadOnly
    override fun getTotalSpace(): Long = fs.sourceSize
    override fun getUsableSpace(): Long = 0
    override fun getUnallocatedSpace(): Long = 0
    override fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type == BasicFileAttributeView::class.java || type == PosixFileAttributeView::class.java || type == FileOwnerAttributeView::class.java
    override fun supportsFileAttributeView(name: String): Boolean = name in setOf("basic", "posix", "owner")
    override fun <V : FileStoreAttributeView> getFileStoreAttributeView(type: Class<V>): V? = null
    override fun getAttribute(attribute: String): Any = when (attribute) {
        "totalSpace" -> totalSpace
        "usableSpace" -> usableSpace
        "unallocatedSpace" -> unallocatedSpace
        else -> throw UnsupportedOperationException(attribute)
    }
}

/** Провайдер tar-архивов. Не регистрируется в системе: ФС создаётся явно через [newFileSystem]. */
class TarFileSystemProvider : FileSystemProvider() {
    companion object {
        const val SCHEME = "wextar"
        val INSTANCE = TarFileSystemProvider()
    }

    private fun tar(path: Path): TarPath {
        if (path !is TarPath) throw ProviderMismatchException()
        return path
    }

    private fun fsOf(path: TarPath) = path.fileSystem as TarFileSystem

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException("Открывайте архив через newFileSystem(Path)")

    /**
     * env["progress"]: (Long) -> Unit — сколько сжатых байт прочитано при распаковке;
     * env["cancelled"]: () -> Boolean — прервать открытие.
     */
    override fun newFileSystem(path: Path, env: Map<String, *>): FileSystem {
        if (!Files.isRegularFile(path)) throw NoSuchFileException(path.toString())
        @Suppress("UNCHECKED_CAST")
        val progress = env["progress"] as? (Long) -> Unit
        @Suppress("UNCHECKED_CAST")
        val cancelled = env["cancelled"] as? () -> Boolean
        val fs = TarFileSystem(this, path)
        try {
            fs.load(progress, cancelled)
        } catch (e: Throwable) {
            runCatching { fs.discard() }
            throw e
        }
        return fs
    }

    override fun getFileSystem(uri: URI): FileSystem = throw FileSystemNotFoundException()

    override fun getPath(uri: URI): Path = throw FileSystemNotFoundException()

    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        val p = tar(path)
        return fsOf(p).newByteChannel(p, options.toSet())
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val p = tar(dir)
        return fsOf(p).newDirectoryStream(p, filter)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        val p = tar(dir)
        fsOf(p).createDirectory(p)
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) {
        val p = tar(link)
        fsOf(p).createSymbolicLink(p, target.toString())
    }

    override fun readSymbolicLink(link: Path): Path {
        val p = tar(link)
        return fsOf(p).readSymbolicLink(p)
    }

    override fun delete(path: Path) {
        val p = tar(path)
        fsOf(p).delete(p)
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        val s = tar(source)
        val t = tar(target)
        if (s.fileSystem === t.fileSystem) {
            fsOf(s).copyWithin(s, t, options.toSet())
        } else {
            copyAcross(s, t, options.toSet())
        }
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        val s = tar(source)
        val t = tar(target)
        if (s.fileSystem === t.fileSystem) {
            fsOf(s).moveWithin(s, t, options.toSet())
        } else {
            copyAcross(s, t, options.toSet() + StandardCopyOption.COPY_ATTRIBUTES)
            delete(s)
        }
    }

    // Между двумя разными архивами — через потоки
    private fun copyAcross(s: TarPath, t: TarPath, options: Set<CopyOption>) {
        val attrs = readAttributes(s, BasicFileAttributes::class.java)
        if (attrs.isSymbolicLink) throw IOException("Копирование символических ссылок между архивами не поддерживается")
        if (StandardCopyOption.REPLACE_EXISTING in options) {
            deleteIfExists(t)
        } else if (runCatching { checkAccess(t) }.isSuccess) {
            throw FileAlreadyExistsException(t.toString())
        }
        if (attrs.isDirectory) {
            createDirectory(t)
        } else {
            newInputStream(s).use { input ->
                newOutputStream(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { input.copyTo(it, 1 shl 16) }
            }
        }
        if (StandardCopyOption.COPY_ATTRIBUTES in options) {
            fsOf(t).setTimes(t, true, attrs.lastModifiedTime())
        }
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        if (path == path2) return true
        if (path !is TarPath || path2 !is TarPath || path.fileSystem !== path2.fileSystem) return false
        return fsOf(path).sameNode(path, path2)
    }

    override fun isHidden(path: Path): Boolean = tar(path).fileName?.toString()?.startsWith(".") == true

    override fun getFileStore(path: Path): FileStore = fsOf(tar(path)).fileStores.first()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        val p = tar(path)
        fsOf(p).checkAccess(p, modes)
    }

    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        val p = tar(path)
        val follow = LinkOption.NOFOLLOW_LINKS !in options
        val view = when (type) {
            BasicFileAttributeView::class.java -> TarAttributeView(p, follow, "basic")
            PosixFileAttributeView::class.java -> TarAttributeView(p, follow, "posix")
            FileOwnerAttributeView::class.java -> TarAttributeView(p, follow, "owner")
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        return view as V
    }

    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        val p = tar(path)
        if (type != BasicFileAttributes::class.java && type != PosixFileAttributes::class.java) {
            throw UnsupportedOperationException("Атрибуты ${type.simpleName} не поддерживаются")
        }
        @Suppress("UNCHECKED_CAST")
        return fsOf(p).attributes(p, LinkOption.NOFOLLOW_LINKS !in options) as A
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        val p = tar(path)
        val colon = attributes.indexOf(':')
        val view = if (colon < 0) "basic" else attributes.substring(0, colon)
        if (view !in setOf("basic", "posix", "owner")) throw UnsupportedOperationException("Вид атрибутов «$view» не поддерживается")
        val names = (if (colon < 0) attributes else attributes.substring(colon + 1)).split(',').map { it.trim() }
        return fsOf(p).attributes(p, LinkOption.NOFOLLOW_LINKS !in options).toMap(view, names)
    }

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        val p = tar(path)
        val follow = LinkOption.NOFOLLOW_LINKS !in options
        val name = attribute.substringAfter(':')
        val fs = fsOf(p)
        when (name) {
            "lastModifiedTime" -> fs.setTimes(p, follow, value as FileTime)
            "lastAccessTime", "creationTime" -> Unit
            "permissions" -> {
                @Suppress("UNCHECKED_CAST")
                fs.setMode(p, follow, permissionsToMode(value as Set<PosixFilePermission>))
            }
            "owner" -> fs.setOwner(p, follow, (value as UserPrincipal).name, null)
            "group" -> fs.setOwner(p, follow, null, (value as GroupPrincipal).name)
            else -> throw UnsupportedOperationException("Атрибут «$attribute» не поддерживается")
        }
    }
}

/** Путь из tar без «./», «/» и выходов наружу через «..». */
internal fun normalizeParts(raw: String): List<String> {
    val out = ArrayList<String>()
    for (part in raw.replace('\\', '/').split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
    }
    return out
}
