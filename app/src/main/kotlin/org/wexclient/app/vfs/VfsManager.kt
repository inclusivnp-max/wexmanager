package org.wexclient.app.vfs

import java.nio.file.FileSystem
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.wexclient.app.vfs.archive.ArchiveFormats
import org.wexclient.app.vfs.archive.TarFileSystem
import org.wexclient.app.vfs.archive.TarFileSystemProvider
import org.wexclient.app.util.startHelper

class VfsManager {
    companion object {
        /** Ошибка «чтение прервали» — результат выбрасывают, показывать её не нужно. */
        const val CANCELLED = "\u0000cancelled"
    }

    // Открытые ZipFileSystem. Ключ — ФС, где лежит файл архива, + путь в ней:
    // у вложенных архивов (base.apk внутри разных XAPK) одни и те же пути.
    private data class ArchiveKey(val hostFs: FileSystem, val path: String)

    private val lock = Any()
    private val openFileSystems = HashMap<ArchiveKey, FileSystem>()

    // Файл архива (на диске или внутри внешнего архива) для каждой открытой ФС
    private val archiveSourceMap = IdentityHashMap<FileSystem, Path>()

    // Та же карта для чтения без блокировки (UI спрашивает родителя, пока архив сохраняется).
    // ФС не переопределяют equals — ключи сравниваются по ссылке.
    private val sourceOf = ConcurrentHashMap<FileSystem, Path>()

    // mtime/размер файла на момент открытия: APK пересобрали снаружи — переоткрываем
    private val archiveStamps = IdentityHashMap<FileSystem, Pair<Long, Long>>()

    // Закрытая ФС -> переоткрытая. Старые Path из UI продолжают работать через live().
    // Своя синхронизация, не общая блокировка: live() зовут и из потока окна
    private val successors: MutableMap<FileSystem, FileSystem> = java.util.Collections.synchronizedMap(WeakHashMap())

    private val reopenListeners = CopyOnWriteArrayList<(FileSystem, FileSystem) -> Unit>()

    // Архив на диске, в который только что записали правки (для предложения подписать APK)
    private val modifiedListeners = CopyOnWriteArrayList<(Path) -> Unit>()

    // zipfs держит правки до close(), поэтому после записи архив закрывается
    // и переоткрывается. Внутри batch{} — один раз в конце.
    private val batchDepth = ThreadLocal.withInitial { 0 }
    private val dirtyArchives = ThreadLocal.withInitial { LinkedHashSet<FileSystem>() }

    fun getHomeDirectory(): Path = Paths.get(System.getProperty("user.home"))

    fun getRootDirectory(): Path = Paths.get("/")

    /** Слушатель переоткрытия архива (old -> new); вызывается из потока операции. */
    fun addArchiveReopenListener(listener: (FileSystem, FileSystem) -> Unit) {
        reopenListeners.add(listener)
    }

    fun removeArchiveReopenListener(listener: (FileSystem, FileSystem) -> Unit) {
        reopenListeners.remove(listener)
    }

    /** Вызывается из потока записи после того, как правки архива сохранены на диск. */
    fun addArchiveModifiedListener(listener: (Path) -> Unit) {
        modifiedListeners.add(listener)
    }

    fun removeArchiveModifiedListener(listener: (Path) -> Unit) {
        modifiedListeners.remove(listener)
    }

    /** Файл архива на диске, внутри которого лежит path (для вложенных — самый внешний), иначе null. */
    fun topArchiveFile(path: Path): Path? {
        val fs = live(path).fileSystem
        if (fs === FileSystems.getDefault()) return null
        synchronized(lock) {
            val top = topArchiveLocked(fs) ?: return null
            return archiveSourceMap[top]?.takeIf { it.fileSystem === FileSystems.getDefault() }
        }
    }

    /** Файлы архивов, внутри которых лежит путь: от ближнего к самому внешнему. */
    fun archiveChain(path: Path): List<Path> {
        val out = ArrayList<Path>()
        var fs = live(path).fileSystem
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<FileSystem, Boolean>())
        while (fs !== FileSystems.getDefault() && seen.add(fs)) {
            val src = sourceOf[fs]?.let { live(it) } ?: break
            out.add(src)
            fs = src.fileSystem
        }
        return out
    }

    /**
     * Почему [source] нельзя положить в [targetDir]: цель — внутри архива, который лежит в самом
     * источнике (или это он сам). Иначе перенос удалил бы источник вместе с архивом-назначением.
     */
    fun transferProblem(source: Path, targetDir: Path, move: Boolean): String? {
        val src = live(source)
        val srcKey = src.toAbsolutePath().normalize()
        val action = if (move) "переместить" else "скопировать"
        for (archive in archiveChain(targetDir)) {
            if (archive.fileSystem === src.fileSystem && archive.toAbsolutePath().normalize().startsWith(srcKey)) {
                return "Нельзя $action «${src.fileName}» в архив «${archive.fileName}», который лежит внутри него"
            }
        }
        return null
    }

    /** Путь в актуальной ФС: после сохранения архив переоткрывается, старые Path ведут в закрытую ФС. */
    fun live(path: Path): Path {
        val fs = path.fileSystem
        if (fs === FileSystems.getDefault() || fs.isOpen) return path
        // Без общей блокировки: её держит запись архива (секунды, у tar.xz — минуты), а live() зовут
        // и из потока окна (родитель папки, «вверх») — окно стояло всё время записи. Пока архив
        // пишется, преемника ещё нет — остаётся прежний путь; запись и чтение ждут её сами
        var target = successors[fs]
        var guard = 0
        while (target != null && !target.isOpen && guard++ < 64) {
            target = successors[target]
        }
        return if (target != null && target.isOpen) target.getPath(path.toString()) else path
    }

    /**
     * Открывает архив как папку. [progress] — прочитано сжатых байт при распаковке tar.* (из потока операции).
     * Новый архив открывается без общей блокировки: распаковка большого tar.xz не должна держать остальные панели.
     */
    fun openArchive(archivePath: Path, cancelled: (() -> Boolean)? = null, progress: ((Long) -> Unit)? = null): Path {
        val source = live(archivePath).toAbsolutePath().normalize()
        val key = ArchiveKey(source.fileSystem, source.toString())
        val events = ArrayList<Pair<FileSystem, FileSystem>>()
        try {
            synchronized(lock) {
                val cached = openFileSystems[key]
                if (cached != null && cached.isOpen && !isStaleLocked(cached)) {
                    return cached.getPath("/")
                }
                if (cached != null) {
                    reopenTreeLocked(cached, events, flush = false)
                    openFileSystems[key]?.let { return it.getPath("/") }
                }
            }
        } finally {
            fireReopened(events)
        }
        val fresh = newArchiveFs(source, progress, cancelled)
        synchronized(lock) {
            val raced = openFileSystems[key]
            if (raced != null && raced.isOpen) {
                runCatching { fresh.close() }
                return raced.getPath("/")
            }
            registerLocked(fresh, source)
            return fresh.getPath("/")
        }
    }

    private fun newArchiveFs(source: Path, progress: ((Long) -> Unit)? = null, cancelled: (() -> Boolean)? = null): FileSystem {
        val name = source.fileName?.toString() ?: ""
        if (ArchiveFormats.isTarFamily(name)) {
            val env = HashMap<String, Any>()
            progress?.let { env["progress"] = it }
            cancelled?.let { env["cancelled"] = it }
            return TarFileSystemProvider.INSTANCE.newFileSystem(source, env)
        }
        // newFileSystem(Path) не регистрирует ФС в глобальном реестре jar:-URI
        // и работает и для файла на диске, и для архива внутри архива.
        return FileSystems.newFileSystem(source, mapOf("create" to "false", "encoding" to "UTF-8"))
    }

    /** Подпись архива, в котором лежит path: TAR.GZ, APK, ZIP… (null — путь на диске). */
    fun archiveLabel(path: Path): String? {
        val fs = live(path).fileSystem
        if (fs === FileSystems.getDefault()) return null
        // Папка на сервере или телефоне: в строке пути видно, где именно
        if (fs is org.wexclient.app.vfs.remote.RemoteFileSystem) return fs.title
        if (fs is TarFileSystem) return fs.label
        val name = sourceOf[fs]?.fileName?.toString() ?: return "ZIP"
        return ArchiveFormats.label(name)
    }

    /** Архив открыт только для чтения (нет прав, нет утилиты сжатия и т.п.). */
    fun isReadOnlyArchive(path: Path): Boolean {
        val fs = live(path).fileSystem
        return fs !== FileSystems.getDefault() && fs.isReadOnly
    }

    private fun registerLocked(fs: FileSystem, source: Path) {
        openFileSystems[ArchiveKey(source.fileSystem, source.toString())] = fs
        archiveSourceMap[fs] = source
        sourceOf[fs] = source
        stampOf(source)?.let { archiveStamps[fs] = it }
    }

    private fun unregisterLocked(fs: FileSystem) {
        openFileSystems.entries.removeIf { it.value === fs }
        archiveSourceMap.remove(fs)
        sourceOf.remove(fs)
        archiveStamps.remove(fs)
    }

    private fun stampOf(source: Path): Pair<Long, Long>? {
        if (source.fileSystem !== FileSystems.getDefault()) return null
        return try {
            Files.getLastModifiedTime(source).toMillis() to Files.size(source)
        } catch (_: Exception) {
            null
        }
    }

    private fun isStaleLocked(fs: FileSystem): Boolean {
        val source = archiveSourceMap[fs] ?: return false
        val stamp = archiveStamps[fs] ?: return false
        return stampOf(source) != stamp
    }

    /**
     * Закрывает архив вместе со всеми вложенными и открывает заново.
     * flush=true — после записи: close() у zipfs и пишет архив
     * (вложенный — во внешний, поэтому начинаем с самого внешнего).
     */
    private fun reopenTreeLocked(
        fs: FileSystem,
        events: MutableList<Pair<FileSystem, FileSystem>>,
        flush: Boolean
    ) {
        val top = if (flush) topArchiveLocked(fs) ?: fs else fs
        val closed = ArrayList<Pair<FileSystem, Path>>()
        val errors = ArrayList<Exception>()
        closeTreeLocked(top, closed, errors)

        // Снаружи внутрь: источник вложенного берётся из уже переоткрытого внешнего
        for ((old, src) in closed) {
            val liveSrc = live(src)
            try {
                val fresh = newArchiveFs(liveSrc)
                registerLocked(fresh, liveSrc)
                // Ссылавшиеся на old — сразу на fresh: цепочка old→…→fresh держала бы все прежние версии
                successors.replaceAll { _, next -> if (next === old) fresh else next }
                successors[old] = fresh
                events.add(old to fresh)
            } catch (e: Exception) {
                errors.add(e)
            }
        }
        errors.firstOrNull()?.let { first ->
            errors.drop(1).forEach { first.addSuppressed(it) }
            throw java.io.IOException("Не удалось сохранить архив: ${first.message}", first)
        }
    }

    /** Самый внешний из наших архивов, в который вложен fs (null — fs не наш). */
    private fun topArchiveLocked(fs: FileSystem): FileSystem? {
        if (!archiveSourceMap.containsKey(fs)) return null
        var top = fs
        var guard = 0
        while (guard++ < 64) {
            val host = archiveSourceMap[top]?.fileSystem ?: break
            if (host === FileSystems.getDefault() || !archiveSourceMap.containsKey(host)) break
            top = host
        }
        return top
    }

    private fun closeTreeLocked(
        fs: FileSystem,
        closed: MutableList<Pair<FileSystem, Path>>,
        errors: MutableList<Exception>
    ) {
        val source = archiveSourceMap[fs] ?: return
        closed.add(fs to source)
        // Вложенные закрываем первыми: их правки пишутся в этот архив
        val children = archiveSourceMap.entries.filter { it.value.fileSystem === fs }.map { it.key }
        for (child in children) closeTreeLocked(child, closed, errors)
        unregisterLocked(fs)
        try {
            if (fs.isOpen) fs.close()
        } catch (e: Exception) {
            errors.add(e)
        }
    }

    private fun fireReopened(events: List<Pair<FileSystem, FileSystem>>) {
        for ((old, fresh) in events) {
            for (l in reopenListeners) {
                try {
                    l(old, fresh)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /** Записывает правки архива, в котором лежит path, на диск (внутри batch — в конце batch). */
    fun commitArchive(path: Path) {
        val fs = live(path).fileSystem
        if (fs === FileSystems.getDefault()) return
        if (batchDepth.get() > 0) {
            dirtyArchives.get().add(fs)
            return
        }
        flushArchive(fs)
    }

    private fun flushArchive(fs: FileSystem) {
        val events = ArrayList<Pair<FileSystem, FileSystem>>()
        var written: Path? = null
        try {
            synchronized(lock) {
                if (fs.isOpen && archiveSourceMap.containsKey(fs)) {
                    written = archiveSourceMap[topArchiveLocked(fs) ?: fs]
                    reopenTreeLocked(fs, events, flush = true)
                }
            }
        } finally {
            fireReopened(events)
        }
        // Только после успешной записи (при ошибке сюда не доходим)
        val src = written ?: return
        if (src.fileSystem !== FileSystems.getDefault()) return
        for (l in modifiedListeners) {
            try {
                l(src)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Запись в архивы — по одной группе операций за раз: сохранение одной операции закрывало
     * архив, в который ещё писала другая, и файл молча пропадал. Держится до конца группы,
     * включая запись архива на диск.
     */
    @PublishedApi
    internal val archiveWriteLock = java.util.concurrent.locks.ReentrantLock()
    private val batchHoldsLock = ThreadLocal.withInitial { false }

    @PublishedApi
    internal fun inArchive(p: Path) = p.fileSystem !== FileSystems.getDefault()

    /**
     * Операция целиком под блокировкой записи в архивы (если касается архива). Пути внутри
     * вычисляются заново через [live] уже после ожидания — архив за это время мог переоткрыться.
     */
    inline fun <T> exclusive(vararg paths: Path, block: () -> T): T {
        val need = paths.any { inArchive(it) }
        if (need) archiveWriteLock.lock()
        try {
            return block()
        } finally {
            if (need) archiveWriteLock.unlock()
        }
    }

    /** Группа операций: изменённые архивы записываются один раз, в конце (и при ошибке тоже). */
    fun <T> batch(block: () -> T): T {
        batchDepth.set(batchDepth.get() + 1)
        var failure: Throwable? = null
        try {
            return block()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            val depth = batchDepth.get() - 1
            batchDepth.set(depth)
            if (depth == 0) {
                try {
                    val dirty = dirtyArchives.get().toList()
                    dirtyArchives.get().clear()
                    var flushError: Exception? = null
                    for (fs in dirty) {
                        try {
                            flushArchive(fs)
                        } catch (e: Exception) {
                            if (flushError == null) flushError = e
                        }
                    }
                    if (flushError != null) {
                        if (failure != null) failure.addSuppressed(flushError) else throw flushError
                    }
                } finally {
                    if (batchHoldsLock.get()) {
                        batchHoldsLock.set(false)
                        archiveWriteLock.unlock()
                    }
                }
            }
        }
    }

    private fun <T> writing(vararg touched: Path, block: () -> T): T = batch {
        if (!batchHoldsLock.get() && touched.any { inArchive(it) }) {
            archiveWriteLock.lock()
            batchHoldsLock.set(true)
        }
        ensureNotStale(touched)
        try {
            block()
        } finally {
            touched.forEach { commitArchive(it) }
        }
    }

    /**
     * Архив пересобрали или переписали снаружи после открытия (сборка в консоли, apksigner): запись
     * в открытую копию при сохранении вернула бы его прежнее содержимое поверх нового. Отказываем —
     * панель перечитает архив.
     */
    private fun ensureNotStale(touched: Array<out Path>) {
        for (p in touched) {
            val fs = p.fileSystem
            if (fs === FileSystems.getDefault() || fs is org.wexclient.app.vfs.remote.RemoteFileSystem) continue
            val staleSource = synchronized(lock) {
                val top = topArchiveLocked(fs) ?: return@synchronized null
                if (isStaleLocked(top)) archiveSourceMap[top] else null
            } ?: continue
            throw java.io.IOException(
                "«${staleSource.fileName}» изменён другой программой после открытия. " +
                    "Обновите панель (Ctrl+R) и повторите — иначе запись вернула бы его прежнее содержимое"
            )
        }
    }

    /** Идёт ли сейчас запись в архив (другим потоком). */
    fun archiveBusy(): Boolean = archiveWriteLock.isLocked && !archiveWriteLock.isHeldByCurrentThread

    /** Архив, в который можно войти как в папку: zip/apk/jar…, tar.* и одиночные .gz/.xz/.zst. */
    fun isArchiveFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (!ArchiveFormats.isBrowsable(name)) return false
        return !Files.isDirectory(path)
    }

    fun isInsideArchive(path: Path): Boolean {
        return path.fileSystem != FileSystems.getDefault()
    }

    fun getParent(currentPath: Path): Path? {
        val current = live(currentPath)
        val parent = current.parent
        if (parent != null) return parent

        // If we are at the root of a Zip archive, navigating up should return to the folder containing the archive
        if (isInsideArchive(current)) {
            val archiveSource = sourceOf[current.fileSystem]
            if (archiveSource != null) {
                return live(archiveSource).parent ?: getRootDirectory()
            }
        }
        return null
    }

    fun listEntries(dir: Path): List<VfsEntry> = listEntriesChecked(dir, sorted = true).first

    /**
     * Содержимое папки и ошибка чтения (нет прав, папку удалили) — чтобы панель не показывала
     * недоступную папку пустой. [sorted] = false — порядок наведёт вызывающий.
     * [cancelled] спрашивается каждые 256 элементов: из папки на 50 000 файлов ушли — её stat бросаем,
     * а не держим ими очередь чтения следующей папки. Прерванное чтение возвращает ошибку [CANCELLED].
     */
    fun listEntriesChecked(dir: Path, sorted: Boolean = false, cancelled: () -> Boolean = { false }): Pair<List<VfsEntry>, String?> {
        var error: String? = null
        val entries = mutableListOf<VfsEntry>()
        var path = live(dir)
        if (isInsideArchive(path)) {
            // Устаревание видно только у архива на диске — у вложенного смотрим самый внешний
            val staleTop = synchronized(lock) {
                val top = topArchiveLocked(path.fileSystem)
                if (top != null && isStaleLocked(top)) top else null
            }
            // Пока в архив пишет другая операция, не переоткрываем его у неё из-под рук
            if (staleTop != null && archiveWriteLock.tryLock()) {
                val events = ArrayList<Pair<FileSystem, FileSystem>>()
                try {
                    synchronized(lock) { reopenTreeLocked(staleTop, events, flush = false) }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    archiveWriteLock.unlock()
                    fireReopened(events)
                }
            }
            // Если архив тем временем сохранялся, блокировка выше дождалась записи — путь уже в новой ФС
            path = live(path)
        }

        try {
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return emptyList<VfsEntry>() to "Папки нет: $path"
            }

            Files.newDirectoryStream(path).use { stream ->
                var seen = 0
                for (item in stream) {
                    if ((++seen and 255) == 0 && cancelled()) return entries to CANCELLED
                    // Один stat на элемент; битая ссылка — атрибуты самой ссылки
                    val attrs = try {
                        Files.readAttributes(item, BasicFileAttributes::class.java)
                    } catch (e: Exception) {
                        try {
                            Files.readAttributes(item, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val isDir = attrs?.isDirectory == true
                    // Канал, сокет, устройство: по имени не архив и не картинка — читать их нельзя
                    val special = attrs?.isOther == true
                    val name = item.fileName?.toString() ?: ""
                    // Без второго обращения к диску: атрибуты уже есть
                    // Архив на сервере или телефоне внутрь не открываем: zip требует произвольного доступа к файлу,
                    // поэтому он открывается из скачанной копии, как и прочие файлы
                    val remote = path.fileSystem is org.wexclient.app.vfs.remote.RemoteFileSystem
                    val isArch = !special && !isDir && !remote && ArchiveFormats.isBrowsable(name)
                    val size = if (attrs != null && !isDir && !special) attrs.size() else 0L
                    val lastMod = attrs?.lastModifiedTime()?.toMillis() ?: 0L
                    val ext = name.substringAfterLast('.', "")
                    val type = if (special) VfsItemType.SPECIAL else VfsEntry.detectType(name, isDir)

                    entries.add(
                        VfsEntry(
                            name = name,
                            path = item,
                            isDirectory = isDir,
                            isArchive = isArch,
                            size = size,
                            lastModified = lastMod,
                            itemType = type,
                            extension = ext
                        )
                    )
                }
            }
        } catch (e: java.nio.file.AccessDeniedException) {
            error = "Нет доступа к папке"
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        if (!sorted) return entries to error

        val hasAndroidPackageFiles = isInsideArchive(path) || entries.any {
            val n = it.name
            n.equals("androidmanifest.xml", ignoreCase = true) || n.endsWith(".dex", ignoreCase = true) ||
                n.equals("resources.arsc", ignoreCase = true)
        }

        // Ключ имени — один раз на элемент, а не lowercase() в каждом сравнении (2·n·log n строк)
        val lower = HashMap<VfsEntry, String>(entries.size * 2)
        val byName = Comparator<VfsEntry> { x, y ->
            lower.getOrPut(x) { x.name.lowercase() }.compareTo(lower.getOrPut(y) { y.name.lowercase() })
        }
        // Sort: In APK/Android archives: Folders -> AndroidManifest.xml -> DEX -> resources.arsc -> others
        val ordered = if (hasAndroidPackageFiles) {
            entries.sortedWith(
                Comparator<VfsEntry> { x, y -> x.apkItemPriority.compareTo(y.apkItemPriority) }
                    .then { x, y -> x.dexNaturalIndex.compareTo(y.dexNaturalIndex) }
                    .then(byName)
            )
        } else {
            entries.sortedWith(compareBy<VfsEntry> { !it.isDirectory && !it.isArchive }.then(byName))
        }
        return ordered to error
    }

    fun exists(path: Path): Boolean = Files.exists(live(path))

    fun size(path: Path): Long = Files.size(live(path))

    /**
     * Именованный канал, сокет или устройство — null для обычных файлов и папок.
     * Чтение канала ждёт писателя, /dev/zero не кончается: такие файлы не читаем.
     */
    fun specialFileKind(path: Path): String? {
        val p = live(path)
        if (p.fileSystem !== FileSystems.getDefault()) return null
        val mode = try {
            Files.getAttribute(p, "unix:mode") as Int
        } catch (_: Exception) {
            return null
        }
        return when (mode and 0xF000) {
            0x1000 -> "именованный канал (FIFO)"
            0xC000 -> "сокет"
            0x2000 -> "символьное устройство"
            0x6000 -> "блочное устройство"
            else -> null
        }
    }

    private fun readable(path: Path): Path {
        val p = live(path)
        specialFileKind(p)?.let { kind ->
            throw java.io.IOException("«${p.fileName}» — $kind: содержимое не читается")
        }
        return p
    }

    /** Поток для чтения (каналы и устройства не открываются). */
    fun openInputStream(path: Path): java.io.InputStream = Files.newInputStream(readable(path))

    fun readText(path: Path): String {
        return Files.readString(readable(path))
    }

    fun readBytes(path: Path): ByteArray {
        return Files.readAllBytes(readable(path))
    }

    /**
     * Целиком, но не больше [limit] байт — для разбора файлов из чужих рук (манифест из APK,
     * .class): заявленный размер записи в архиве может быть гигантским. Больше — IOException.
     */
    fun readBytesLimited(path: Path, limit: Int): ByteArray {
        val p = readable(path)
        fun tooBig(): Nothing = throw java.io.IOException("«${p.fileName}» слишком большой (больше ${limit shr 20} МБ)")
        runCatching { Files.size(p) }.getOrNull()?.let { if (it > limit) tooBig() }
        Files.newInputStream(p).use { input ->
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) tooBig()
            return bytes
        }
    }

    /** Целиком, с прогрессом [onProgress] (прочитано, всего) — для индикатора открытия. */
    fun readBytes(path: Path, onProgress: (Long, Long) -> Unit): ByteArray {
        val p = readable(path)
        val total = try { Files.size(p) } catch (_: Exception) { -1L }
        if (total !in 0..(Int.MAX_VALUE - 8).toLong()) return Files.readAllBytes(p)
        Files.newInputStream(p).use { input ->
            val out = ByteArray(total.toInt())
            var done = 0
            while (done < out.size) {
                val n = input.read(out, done, minOf(256 * 1024, out.size - done))
                if (n < 0) break
                done += n
                onProgress(done.toLong(), total)
            }
            // Файл поменялся во время чтения: отдаём то, что есть на самом деле
            if (done < out.size) return out.copyOf(done)
            val rest = input.readAllBytes()
            return if (rest.isEmpty()) out else out + rest
        }
    }

    /** Первые maxBytes байт — чтобы определить тип файла, не читая его целиком. */
    fun readHead(path: Path, maxBytes: Int): ByteArray {
        return Files.newInputStream(readable(path)).use { it.readNBytes(maxBytes) }
    }

    fun writeText(path: Path, content: String) = writeBytes(path, content.toByteArray(Charsets.UTF_8))

    fun writeBytes(path: Path, content: ByteArray) {
        exclusive(path) {
            val p = live(path)
            writing(p) { saveFile(p, content) }
        }
    }

    /**
     * Сохранение без риска остаться с пустым файлом: новое содержимое пишется рядом, сбрасывается
     * на диск и одним переименованием встаёт на место (права сохраняются, ссылка остаётся ссылкой).
     * Чужой файл или файл с жёсткими ссылками переписывается на месте — после проверки свободного места.
     */
    private fun saveFile(p: Path, content: ByteArray) {
        if (p.fileSystem !== FileSystems.getDefault()) {
            Files.write(p, content)
            return
        }
        val target = if (Files.isSymbolicLink(p)) runCatching { p.toRealPath() }.getOrDefault(p) else p
        val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        val dir = target.toAbsolutePath().parent
        val ownFile = !exists || runCatching {
            val owner = Files.getAttribute(target, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
            val links = Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
            owner == currentUid && links <= 1
        }.getOrDefault(false)
        if (ownFile && dir != null && Files.isWritable(dir)) {
            val perms = if (exists) runCatching { Files.getPosixFilePermissions(target) }.getOrNull() else null
            val tmp = Files.createTempFile(dir, ".${target.fileName}.", ".wex-save")
            try {
                java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE).use { ch ->
                    val buf = java.nio.ByteBuffer.wrap(content)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
                // createTempFile создаёт с правами 600 — возвращаем как было (новый файл — по umask)
                if (perms != null) Files.setPosixFilePermissions(tmp, perms)
                else runCatching { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")) }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
            return
        }
        // На месте: сначала убеждаемся, что новое содержимое поместится
        val old = if (exists) runCatching { Files.size(target) }.getOrDefault(0L) else 0L
        val free = runCatching { Files.getFileStore(dir ?: target).usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (content.size - old > free) {
            throw java.io.IOException("Недостаточно места на диске для сохранения «${target.fileName}»")
        }
        java.nio.channels.FileChannel.open(
            target,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        ).use { ch ->
            val buf = java.nio.ByteBuffer.wrap(content)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }

    private val currentUid: Int by lazy {
        runCatching { (Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Number).toInt() }.getOrDefault(-1)
    }

    fun createDirectory(parent: Path, dirName: String): Path {
        return exclusive(parent) {
            val target = live(parent).resolve(dirName)
            return writing(target) { Files.createDirectories(target) }
        }
    }

    /** Пустой файл; существующий не перезаписывается. */
    fun createFile(parent: Path, fileName: String): Path {
        return exclusive(parent) {
            val target = live(parent).resolve(fileName)
            return writing(target) { Files.createFile(target) }
        }
    }

    /**
     * Папка внутри zip: JDK zipfs при Files.move создаёт пустую папку и молча оставляет содержимое
     * на месте — такую папку копируем и удаляем (в одном архиве это сохраняется вместе).
     */
    private fun isZipDirectory(p: Path): Boolean =
        p.fileSystem !== FileSystems.getDefault() && p.fileSystem.provider().scheme == "jar" && Files.isDirectory(p)

    private fun moveZipDirectory(src: Path, target: Path): Path {
        if (Files.exists(target)) throw java.nio.file.FileAlreadyExistsException(target.toString())
        copyTree(src, target)
        deleteEntry(src)
        return target
    }

    /** Переименование без перезаписи существующего файла. */
    fun renameEntry(entry: Path, newName: String): Path {
        return exclusive(entry) {
            val src = live(entry)
            val parent = src.parent ?: throw IllegalArgumentException("Нельзя переименовать корень")
            val target = parent.resolve(newName)
            return writing(src, target) {
                val oldName = src.fileName?.toString()?.trimEnd('/')
                if (oldName != null && oldName != newName && oldName.equals(newName, ignoreCase = true)) {
                    // Только регистр: на FAT/exFAT цель «уже существует» — через временное имя
                    val temp = parent.resolve(".wex-case-${System.nanoTime()}")
                    val moved = if (isZipDirectory(src)) moveZipDirectory(src, temp) else Files.move(src, temp)
                    try {
                        if (isZipDirectory(moved)) moveZipDirectory(moved, target) else Files.move(moved, target)
                    } catch (e: Exception) {
                        // На ext4 «A.txt» может быть другим файлом: не оставляем исходный под временным именем
                        runCatching { if (isZipDirectory(moved)) moveZipDirectory(moved, src) else Files.move(moved, src) }
                            .exceptionOrNull()?.let(e::addSuppressed)
                        throw e
                    }
                } else if (isZipDirectory(src)) {
                    moveZipDirectory(src, target)
                } else {
                    Files.move(src, target)
                }
            }
        }
    }

    fun deleteEntry(entry: Path) {
        exclusive(entry) {
            val p = live(entry)
            writing(p) {
                val remote = p.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem
                when {
                    // На сервере и телефоне — одной командой: rm -rf в ссылки не заходит, и нет отдельной команды на каждый файл
                    remote != null -> remote.deleteTree(p)
                    // Windows: junction и «только чтение» — см. FileTree.deleteOrThrow
                    java.io.File.separatorChar == '\\' && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ->
                        org.wexclient.app.util.FileTree.deleteOrThrow(p)
                    // Ссылка на папку — удаляется сама ссылка, без захода внутрь
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> Files.walk(p).use { stream ->
                        stream.sorted(Comparator.reverseOrder())
                            .forEach { Files.deleteIfExists(it) }
                    }
                    else -> Files.deleteIfExists(p)
                }
            }
        }
    }

    private fun canonicalDir(dir: Path): Path {
        val abs = dir.toAbsolutePath().normalize()
        return try {
            abs.toRealPath()
        } catch (_: Exception) {
            abs
        }
    }

    // Родители раскрываются, сам элемент — нет: копирование симлинка не должно считаться копированием цели
    private fun canonicalEntry(p: Path): Path {
        val abs = p.toAbsolutePath().normalize()
        val parent = abs.parent ?: return abs
        val name = abs.fileName?.toString() ?: return abs
        return canonicalDir(parent).resolve(name)
    }

    /** Копирование/перемещение в себя или в собственную подпапку уходит в бесконечную вложенность. */
    private fun ensureNotInside(source: Path, targetDir: Path, action: String) {
        if (source.fileSystem !== targetDir.fileSystem) return
        val name = source.fileName?.toString() ?: return
        val src = canonicalEntry(source)
        val dst = canonicalDir(targetDir).resolve(name)
        if (dst == src) {
            throw IllegalArgumentException("«$name»: источник и назначение совпадают")
        }
        if (dst.startsWith(src)) {
            throw IllegalArgumentException("Нельзя $action папку «$name» внутрь неё самой")
        }
    }

    fun copyEntry(source: Path, targetDir: Path) {
        val src = live(source)
        val dir = live(targetDir)
        ensureNotInside(src, dir, "скопировать")
        val fileName = src.fileName?.toString()?.trimEnd('/') ?: "copied_file"
        copyTo(src, dir.resolve(fileName), overwrite = true)
    }

    /** Источник совпадает с назначением или назначение внутри него — такое копирование бесконечно. */
    private fun checkTarget(src: Path, dst: Path, action: String) {
        if (src.fileSystem !== dst.fileSystem) {
            dst.parent?.let { dir -> transferProblem(src, dir, action == "переместить")?.let { throw IllegalArgumentException(it) } }
            return
        }
        val name = dst.fileName?.toString() ?: return
        val from = canonicalEntry(src)
        val to = (dst.parent?.let { canonicalDir(it) } ?: dst).resolve(name)
        if (to == from) throw IllegalArgumentException("«$name»: источник и назначение совпадают")
        if (to.startsWith(from)) throw IllegalArgumentException("Нельзя $action папку «${src.fileName}» внутрь неё самой")
    }

    /** Есть ли источник на самом деле: на сервере и телефоне «нет» из кэша перепроверяется там. */
    private fun sourceExists(p: Path): Boolean {
        (p.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem)?.let { return it.existsNow(p) }
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS)
    }

    /** Копия [source] ровно в [target] (имя может отличаться); без [overwrite] существующее не трогаем. */
    fun copyTo(source: Path, target: Path, overwrite: Boolean = false): Path {
        return exclusive(source, target) {
            val src = live(source)
            val dst = live(target)
            checkTarget(src, dst, "скопировать")
            // Источник убрали другой программой, а панель ещё не обновилась — это ошибка, а не «Скопировано»
            if (!sourceExists(src)) throw java.nio.file.NoSuchFileException(src.toString(), null, "источника уже нет")
            if (!overwrite && Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(dst.toString())
            writing(dst) { copyTree(src, dst) }
            return dst
        }
    }

    private fun copyTree(src: Path, target: Path) {
        val localTarget = target.fileSystem === FileSystems.getDefault()
        // На диск и на сервер ссылка копируется ссылкой (как cp -P, rsync -l, Dolphin, Nautilus); путь —
        // в ФС назначения (ссылка из tar иначе падала с ProviderMismatchException). В архив и в память
        // телефона, где ссылок не бывает, — то, на что указывает.
        val linksAsLinks = localTarget || (target.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem)?.keepsLinks == true
        fun linkLike(s: Path, dest: Path) {
            Files.deleteIfExists(dest)
            Files.createSymbolicLink(dest, dest.fileSystem.getPath(Files.readSymbolicLink(s).toString()))
        }
        if (linksAsLinks && Files.isSymbolicLink(src)) {
            linkLike(src, target)
            return
        }
        // Папки, созданные этим копированием: точные права и время — когда содержимое уже внутри
        val madeDirs = ArrayList<Pair<Path, Path>>()
        fun makeDir(s: Path, dest: Path) {
            // Слияние с существующей папкой — её права и время не трогаем
            if (Files.isDirectory(dest)) return
            dest.parent?.let { if (!Files.isDirectory(it)) Files.createDirectories(it) }
            val perms = posixPermissionsOf(s)
            // NTFS прав POSIX не знает: папка из tar, с сервера или телефона создаётся без них
            if (perms != null && localTarget && "posix" in dest.fileSystem.supportedFileAttributeViews()) {
                // Закрытая папка (0700) не становится открытой даже на время копирования
                Files.createDirectory(dest, PosixFilePermissions.asFileAttribute(perms + OWNER_RWX))
            } else {
                Files.createDirectories(dest)
            }
            madeDirs.add(s to dest)
        }
        fun copyOne(s: Path, dest: Path) {
            when {
                linksAsLinks && Files.isSymbolicLink(s) -> linkLike(s, dest)
                // В архив ссылок не кладём — то, на что они указывают
                Files.isDirectory(s) -> makeDir(s, dest)
                specialFileKind(s) != null -> copySpecial(s, dest)
                Files.exists(s) -> {
                    Files.copy(s, dest, StandardCopyOption.REPLACE_EXISTING)
                    keepPermissions(s, dest)
                    keepModifiedTime(s, dest)
                }
                else -> Unit // битая ссылка при копировании в архив
            }
        }
        if (Files.isDirectory(src)) {
            // В архив — с переходом по ссылкам (кольца JDK обнаруживает сам)
            val options = if (linksAsLinks) emptyArray() else arrayOf(java.nio.file.FileVisitOption.FOLLOW_LINKS)
            try {
                Files.walk(src, Int.MAX_VALUE, *options).use { stream ->
                    stream.forEach { s ->
                        // Имена по одному: разделители у ФС источника и назначения могут отличаться
                        val rel = src.relativize(s).joinToString("/") { it.toString().trimEnd('/') }
                        copyOne(s, if (rel.isEmpty()) target else target.resolve(rel))
                    }
                }
            } finally {
                // Изнутри наружу: запись в папку меняет её время, а права «только чтение» мешали бы писать
                for ((s, d) in madeDirs.asReversed()) {
                    if ("posix" in d.fileSystem.supportedFileAttributeViews()) {
                        posixPermissionsOf(s)?.let { runCatching { Files.setPosixFilePermissions(d, it) } }
                    }
                    keepModifiedTime(s, d)
                }
            }
        } else {
            copyOne(src, target)
        }
    }

    private val OWNER_RWX = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)

    private fun posixPermissionsOf(p: Path): Set<PosixFilePermission>? {
        if ("posix" !in p.fileSystem.supportedFileAttributeViews()) return null
        return runCatching { Files.getPosixFilePermissions(p) }.getOrNull()
    }

    // Как в Dolphin и Nautilus: у копии — время изменения оригинала (Files.copy его не переносит)
    private fun keepModifiedTime(src: Path, target: Path) {
        runCatching { Files.setLastModifiedTime(target, Files.getLastModifiedTime(src)) }
    }

    /**
     * Files.copy открывает канал на чтение и ждёт писателя — копирование висело бы вечно.
     * Канал создаём заново (mkfifo), сокеты и устройства не копируются.
     */
    private fun copySpecial(src: Path, target: Path) {
        if (specialFileKind(src)?.startsWith("именованный") != true) return
        if (target.fileSystem !== FileSystems.getDefault()) return
        Files.deleteIfExists(target)
        val mode = try {
            Integer.toOctalString((Files.getAttribute(src, "unix:mode") as Int) and 0x1FF)
        } catch (_: Exception) {
            "644"
        }
        val process = ProcessBuilder("mkfifo", "-m", mode, target.toString()).redirectErrorStream(true).start()
        val out = process.inputStream.readAllBytes().decodeToString().trim()
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw java.io.IOException("mkfifo не ответил")
        }
        if (process.exitValue() != 0) throw java.io.IOException("Не удалось создать канал «${target.fileName}»: $out")
    }

    // Между разными ФС Files.copy права не переносит — а для tar и диска бит исполнения важен
    private fun keepPermissions(src: Path, target: Path) {
        if (src.fileSystem === target.fileSystem) return
        if ("posix" !in src.fileSystem.supportedFileAttributeViews()) return
        if ("posix" !in target.fileSystem.supportedFileAttributeViews()) return
        runCatching { Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(src)) }
    }

    fun moveEntry(source: Path, targetDir: Path) {
        val src = live(source)
        val dir = live(targetDir)
        ensureNotInside(src, dir, "переместить")
        val fileName = src.fileName?.toString()?.trimEnd('/') ?: "moved_file"
        moveTo(src, dir.resolve(fileName), overwrite = true)
    }

    /** Перенос [source] ровно в [target]; между файловыми системами — копия и удаление. */
    fun moveTo(source: Path, target: Path, overwrite: Boolean = false): Path {
        return exclusive(source, target) {
            val src = live(source)
            val dst = live(target)
            checkTarget(src, dst, "переместить")
            if (!sourceExists(src)) throw java.nio.file.NoSuchFileException(src.toString(), null, "источника уже нет")
            if (!overwrite && Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(dst.toString())
            writing(src, dst) {
                if (isZipDirectory(src) && src.fileSystem === dst.fileSystem) {
                    if (overwrite && Files.exists(dst)) {
                        // Слияние, как при копировании с заменой; существующую папку не удаляем
                        copyTree(src, dst)
                        deleteEntry(src)
                    } else {
                        moveZipDirectory(src, dst)
                    }
                    return@writing
                }
                try {
                    if (overwrite) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING) else Files.move(src, dst)
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    throw e
                } catch (e: java.nio.file.AccessDeniedException) {
                    // Копия тут не поможет — только оставит дубликат
                    throw e
                } catch (e: Exception) {
                    // Другая файловая система (или непустая папка между ними): копия, затем удаление
                    copyTree(src, dst)
                    deleteEntry(src)
                }
            }
            return dst
        }
    }

    /** Архив [destination] — это сжимаемый файл или лежит внутри сжимаемой папки. */
    fun archiveInsideSources(sources: List<Path>, destination: Path): Boolean {
        val dest = live(destination)
        return sources.map { live(it) }.any { src ->
            src.fileSystem === dest.fileSystem &&
                dest.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())
        }
    }

    /**
     * Сжатие без риска для старых данных: архив пишется во временный файл рядом и переносится
     * на место одним движением. Существующий [destination] должен убрать вызывающий (в корзину).
     */
    fun compressToNewFile(
        sources: List<Path>,
        destination: Path,
        format: String,
        /** Архив готов, а на месте назначения что-то есть — последний шанс убрать это в корзину */
        beforeReplace: (existing: Path) -> Unit = {}
    ) {
        if (archiveInsideSources(sources, destination)) {
            throw IllegalArgumentException("Архив «${destination.fileName}» не может лежать в том, что сжимается")
        }
        exclusive(destination, *sources.toTypedArray()) { compressLocked(sources, destination, format, beforeReplace) }
    }

    private fun compressLocked(sources: List<Path>, destination: Path, format: String, beforeReplace: (existing: Path) -> Unit) {
        val dest = live(destination)
        val dir = dest.parent ?: throw IllegalArgumentException("Нет папки для архива")
        val temp = dir.resolve(".wex-part-${System.nanoTime()}-${dest.fileName}")
        try {
            compressEntries(sources, temp, format)
            if (!Files.exists(temp)) throw java.io.IOException("Архив не создан")
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) beforeReplace(dest)
            writing(dest) {
                try {
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(live(temp)) }
        }
    }

    fun compressEntries(sources: List<Path>, targetArchive: Path, format: String = "zip") {
        val liveSources = sources.map { live(it) }
        val archive = live(targetArchive)
        val fmt = format.lowercase().replace(".", "")
        val targetStr = archive.toString().lowercase()
        val isLocal = archive.fileSystem === FileSystems.getDefault() &&
                liveSources.all { it.fileSystem === FileSystems.getDefault() }

        // tar и сжатые tar: системный tar подбирает сжатие по расширению (-a)
        val tarKind = ArchiveFormats.kindOf(archive.fileName?.toString() ?: "")
            ?: if (fmt == "targz" || fmt == "tar_gz") ArchiveFormats.kindOf("x.tar.gz") else null
        if (tarKind != null && tarKind.tar) {
            if (isLocal) {
                try {
                    // Своя папка у каждого источника (результаты поиска бывают из разных папок)
                    val cmd = mutableListOf("tar", "-caf", archive.toAbsolutePath().toString())
                    for (s in liveSources) {
                        val abs = s.toAbsolutePath()
                        cmd.add("-C")
                        cmd.add((abs.parent ?: abs).toString())
                        cmd.add("--")
                        cmd.add(abs.fileName?.toString() ?: continue)
                    }
                    val process = ProcessBuilder(cmd).redirectErrorStream(true).startHelper()
                    process.inputStream.readAllBytes()
                    if (process.waitFor() == 0) return
                } catch (_: Exception) {
                    // ниже — своя запись
                }
            }
            createTarArchive(liveSources, archive)
            return
        }

        // 7Z format support
        if (isLocal && (fmt == "7z" || targetStr.endsWith(".7z"))) {
            try {
                val cmd = mutableListOf("7z", "a", "-bd", archive.toAbsolutePath().toString(), "--")
                for (s in liveSources) {
                    cmd.add(s.toAbsolutePath().toString())
                }
                // Вывод не читаем — не даём ему заполнить канал и остановить 7z
                val process = ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).startHelper()
                val exit = process.waitFor()
                if (exit == 0) return
            } catch (e: Exception) {
                // fallback to zip if 7z process fails
            }
        }

        // Standard ZIP and APK compression
        writing(archive) {
            val archiveKey = archive.toAbsolutePath().normalize()
            val fos = Files.newOutputStream(archive)
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(fos)).use { zos ->
                for (source in liveSources) {
                    if (Files.isDirectory(source)) {
                        val root = source.parent ?: source
                        Files.walk(source).use { stream ->
                            stream.forEach { file ->
                                // Архив может создаваться внутри сжимаемой папки — себя не пакуем
                                if (file.toAbsolutePath().normalize() == archiveKey) return@forEach
                                val rel = root.relativize(file).joinToString("/")
                                if (Files.isDirectory(file)) {
                                    val entryName = if (rel.endsWith("/")) rel else "$rel/"
                                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                    zos.closeEntry()
                                } else if (specialFileKind(file) == null) {
                                    // Каналы, сокеты и устройства в zip не кладём: чтение канала повисло бы
                                    zos.putNextEntry(java.util.zip.ZipEntry(rel))
                                    Files.copy(file, zos)
                                    zos.closeEntry()
                                }
                            }
                        }
                    } else if (specialFileKind(source) == null) {
                        val name = source.fileName?.toString() ?: "file"
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        Files.copy(source, zos)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    /** Новый tar.* без системного tar: пустой архив, затем файлы через TarFileSystem (сжатие — как у имени). */
    private fun createTarArchive(sources: List<Path>, archive: Path) {
        val kind = ArchiveFormats.kindOf(archive.fileName?.toString() ?: "") ?: ArchiveFormats.kindOf("x.tar.gz")!!
        writing(archive) {
            val emptyTar = org.wexclient.app.util.ProcessScratch.work.newFile("empty-", ".tar")
            try {
                Files.write(emptyTar, ByteArray(10240))
                val packed = org.wexclient.app.util.ProcessScratch.work.newFile("empty-", ".pack")
                try {
                    ArchiveFormats.compress(kind.compression, emptyTar, packed)
                    Files.copy(packed, archive, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(packed)
                }
            } finally {
                Files.deleteIfExists(emptyTar)
            }
            val fs = TarFileSystemProvider.INSTANCE.newFileSystem(archive, emptyMap<String, Any>())
            try {
                val root = fs.getPath("/")
                for (source in sources) {
                    val name = source.fileName?.toString() ?: continue
                    Files.walk(source).use { stream ->
                        stream.forEach { file ->
                            val rel = source.relativize(file).joinToString("/")
                            val dest = if (rel.isEmpty()) root.resolve(name) else root.resolve("$name/$rel")
                            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            when {
                                attrs.isDirectory -> Files.createDirectories(dest)
                                attrs.isSymbolicLink -> runCatching { Files.createSymbolicLink(dest, Files.readSymbolicLink(file)) }
                                attrs.isRegularFile -> Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                            }
                            if (!attrs.isSymbolicLink) {
                                runCatching { Files.setLastModifiedTime(dest, attrs.lastModifiedTime()) }
                                runCatching {
                                    val perms = Files.readAttributes(file, PosixFileAttributes::class.java).permissions()
                                    Files.setPosixFilePermissions(dest, perms)
                                }
                            }
                        }
                    }
                }
            } finally {
                fs.close()
            }
        }
    }

    fun extractArchive(
        archiveFile: Path,
        destinationDir: Path,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ) {
        val archive = live(archiveFile)
        val destination = live(destinationDir)
        writing(destination) {
            extractArchiveImpl(archive, destination, onProgress)
        }
    }

    /**
     * Внешняя утилита распаковки под присмотром: архив-бомба не должна занять весь диск.
     * Код выхода; нет утилиты — -1. Нехватку места сообщает исключением.
     */
    private fun runExtractTool(cmd: List<String>, guard: ArchiveFormats.DiskGuard): Int {
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).startHelper()
        } catch (_: java.io.IOException) {
            return -1
        }
        process.outputStream.close()
        try {
            while (!process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) guard.check()
        } catch (e: java.io.IOException) {
            process.destroyForcibly()
            process.waitFor()
            throw e
        }
        return process.exitValue()
    }

    /** Как Files.copy с заменой: существующая ссылка заменяется, а не открывается (запись не уйдёт по ней наружу). */
    private fun writeExtracted(input: java.io.InputStream, target: Path, guard: ArchiveFormats.DiskGuard?) {
        Files.deleteIfExists(target)
        Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { out ->
            ArchiveFormats.copy(input, out, null, guard)
        }
    }

    private fun extractArchiveImpl(
        archiveFile: Path,
        destinationDir: Path,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)?
    ) {
        Files.createDirectories(destinationDir)
        val destNormalized = destinationDir.toAbsolutePath().normalize()
        // Порог свободного места — от места на старте
        val guard = ArchiveFormats.DiskGuard(destNormalized.resolve(".wex-space-check"), null)
        val nameLower = archiveFile.fileName?.toString()?.lowercase() ?: ""
        // Внешние утилиты понимают только пути на диске
        val isLocal = archiveFile.fileSystem === FileSystems.getDefault() &&
                destNormalized.fileSystem === FileSystems.getDefault()

        // 1. tar и сжатые tar: на диске — системным tar (права, ссылки, sparse), иначе — через TarFileSystem
        val tarKind = ArchiveFormats.kindOf(nameLower)
        if (tarKind != null) {
            if (isLocal && tarKind.tar) {
                // Не вышло системным tar — ниже своя распаковка
                if (runExtractTool(listOf("tar", "-xf", archiveFile.toAbsolutePath().toString(), "-C", destNormalized.toString()), guard) == 0) return
            }
            extractViaTarFs(archiveFile, destNormalized, onProgress, guard)
            return
        }

        // 2. 7Z / RAR
        if (isLocal && (nameLower.endsWith(".7z") || nameLower.endsWith(".rar"))) {
            val sevenZip = listOf("7z", "x", "-bd", archiveFile.toAbsolutePath().toString(), "-o$destNormalized", "-y")
            if (runExtractTool(sevenZip, guard) == 0) return
            if (nameLower.endsWith(".rar")) {
                val unrar = listOf("unrar", "x", "-o+", archiveFile.toAbsolutePath().toString(), "$destNormalized/")
                if (runExtractTool(unrar, guard) == 0) return
            }
        }

        // 4. Standard ZIP / APK / JAR / XAPK / APKS
        val zipFile = try {
            java.util.zip.ZipFile(archiveFile.toFile(), java.nio.charset.StandardCharsets.UTF_8)
        } catch (_: Exception) {
            try {
                java.util.zip.ZipFile(archiveFile.toFile(), java.nio.charset.Charset.forName("Cp437"))
            } catch (_: Exception) {
                java.util.zip.ZipFile(archiveFile.toFile())
            }
        }

        val realDest = if (destNormalized.fileSystem === FileSystems.getDefault()) destNormalized.toRealPath() else null
        zipFile.use { zip ->
            val total = zip.size()
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                onProgress?.invoke(count, total, entry.name)

                val entryName = entry.name.replace('\\', '/')
                val targetPath = destNormalized.resolve(entryName).normalize()
                if (!targetPath.startsWith(destNormalized)) {
                    throw SecurityException("Обнаружена уязвимость Zip Slip в пути: ${entry.name}")
                }
                // Ссылка, уже лежащая в папке назначения, не должна увести запись наружу
                realDest?.let { ensureInside(targetPath, it) }

                if (entry.isDirectory || entryName.endsWith('/')) {
                    Files.createDirectories(targetPath)
                } else {
                    targetPath.parent?.let { Files.createDirectories(it) }
                    zip.getInputStream(entry).use { input -> writeExtracted(input, targetPath, guard) }
                    try {
                        if (entry.lastModifiedTime != null) {
                            Files.setLastModifiedTime(targetPath, entry.lastModifiedTime)
                        }
                    } catch (_: Exception) {
                        // ignore permission issue
                    }
                }
            }
        }
    }

    /**
     * Настоящий путь ближайшей существующей папки над [target] — внутри [realDest]:
     * символическая ссылка по пути (из архива или уже лежащая в папке) не уведёт запись наружу.
     */
    private fun ensureInside(target: Path, realDest: Path) {
        var p: Path? = target.parent
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p = p.parent
        val existing = p ?: return
        val real = try {
            existing.toRealPath()
        } catch (e: java.io.IOException) {
            throw SecurityException("Путь ведёт через битую ссылку: $target")
        }
        if (!real.startsWith(realDest)) throw SecurityException("Путь выходит за папку назначения: $target")
    }

    /**
     * tar через TarFileSystem. Сначала папки и файлы, ссылки — в самом конце: запись не должна
     * пройти через ссылку — ни уже лежащую в папке назначения, ни созданную из архива
     * (на разделах без учёта регистра ссылка «X → ..» совпала бы с папкой «x»).
     */
    internal fun extractViaTarFs(
        archiveFile: Path,
        dest: Path,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)?,
        guard: ArchiveFormats.DiskGuard? = null
    ) {
        val fs = TarFileSystemProvider.INSTANCE.newFileSystem(archiveFile, emptyMap<String, Any>())
        try {
            val root = fs.getPath("/")
            val all = Files.walk(root).use { stream -> stream.filter { it != root }.toList() }
            val onDisk = dest.fileSystem === FileSystems.getDefault()
            Files.createDirectories(dest)
            val realDest = if (onDisk) dest.toRealPath() else null
            val links = ArrayList<Pair<Path, Path>>()
            all.forEachIndexed { index, p ->
                val rel = root.relativize(p).joinToString("/")
                onProgress?.invoke(index + 1, all.size, rel)
                val target = dest.resolve(rel).normalize()
                if (!target.startsWith(dest)) {
                    throw SecurityException("Путь выходит за папку назначения: $rel")
                }
                val attrs = Files.readAttributes(p, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                when {
                    attrs.isSymbolicLink -> if (onDisk) links.add(p to target)
                    attrs.isDirectory -> {
                        realDest?.let { ensureInside(target, it) }
                        Files.createDirectories(target)
                    }
                    attrs.isRegularFile -> {
                        realDest?.let { ensureInside(target, it) }
                        target.parent?.let { Files.createDirectories(it) }
                        Files.newInputStream(p).use { input -> writeExtracted(input, target, guard) }
                        runCatching { Files.setLastModifiedTime(target, attrs.lastModifiedTime()) }
                        if (onDisk) runCatching { Files.setPosixFilePermissions(target, attrs.permissions()) }
                    }
                    else -> Unit // устройства и каналы не создаём
                }
            }
            // Только относительные ссылки, которые и по настоящему пути остаются внутри папки
            for ((p, target) in links) {
                val real = realDest ?: break
                val link = Files.readSymbolicLink(p).toString()
                if (link.isEmpty() || link.startsWith("/")) continue
                val ok = runCatching {
                    ensureInside(target, real)
                    target.parent?.let { Files.createDirectories(it) }
                    val realParent = (target.parent ?: dest).toRealPath()
                    realParent.resolve(link).normalize().startsWith(real)
                }.getOrDefault(false)
                if (!ok) continue
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) continue
                Files.deleteIfExists(target)
                Files.createSymbolicLink(target, Paths.get(link))
            }
        } finally {
            fs.close()
        }
    }

    fun closeAll() {
        synchronized(lock) {
            // Сначала вложенные: их close() пишет во внешний архив
            val order = ArrayList<Pair<FileSystem, Path>>()
            val roots = archiveSourceMap.entries
                .filter { it.value.fileSystem === FileSystems.getDefault() }
                .map { it.key }
            val errors = ArrayList<Exception>()
            for (fs in roots) closeTreeLocked(fs, order, errors)
            for (fs in archiveSourceMap.keys.toList()) {
                unregisterLocked(fs)
                try {
                    if (fs.isOpen) fs.close()
                } catch (_: Exception) {
                }
            }
            openFileSystems.clear()
            errors.forEach { it.printStackTrace() }
        }
    }
}
