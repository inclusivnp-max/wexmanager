package org.wexclient.app.vfs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.wexclient.app.util.FileTree

/**
 * Распаковка без молчаливой перезаписи: архив распаковывается во временную папку рядом,
 * совпадения показываются пользователю, и только потом содержимое переносится на место.
 */
object SafeExtract {
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    class Prepared(
        val temp: Path,
        val dest: Path,
        /** Пути (относительно [dest]), которые уже заняты */
        val conflicts: List<String>,
        val fileCount: Int,
        /** Верхний уровень содержимого архива */
        val topNames: List<String>,
        private val ownsTempParent: Path?
    ) {
        internal fun cleanup() {
            FileTree.delete(temp)
            ownsTempParent?.let { FileTree.delete(it) }
        }
    }

    class Result(
        /** Появившееся заново: целые новые папки и отдельные файлы в уже существовавших */
        val created: List<Path>,
        val replaced: List<TrashItem>,
        val skipped: Int,
        /** Заменённое внутри архива-назначения — копии на диске для отмены */
        val archiveReplaced: List<ArchiveBackup> = emptyList(),
        /** Распаковка прервалась: сделанное до ошибки — выше, его можно отменить */
        val error: Throwable? = null
    )

    fun prepare(vfs: VfsManager, archive: Path, destDir: Path, onProgress: ((Int, Int, String) -> Unit)? = null): Prepared =
        vfs.exclusive(archive, destDir) { prepareLocked(vfs, archive, destDir, onProgress) }

    private fun prepareLocked(vfs: VfsManager, archive: Path, destDir: Path, onProgress: ((Int, Int, String) -> Unit)?): Prepared {
        val dest = vfs.live(destDir)
        Files.createDirectories(dest)
        // На том же диске — чтобы переносить, а не копировать ещё раз
        val local = dest.fileSystem === FileSystems.getDefault()
        val parent = if (local) null else org.wexclient.app.util.ProcessScratch.work.newDir("extract-")
        val temp = if (local) dest.resolve(".wex-extract-${System.nanoTime()}") else parent!!.resolve("content")
        try {
            vfs.extractArchive(archive, temp, onProgress)
            if (!local) dropEscapingLinks(temp)
            val conflicts = ArrayList<String>()
            var files = 0
            Files.walk(temp).use { stream ->
                stream.filter { it != temp }.forEach { p ->
                    val rel = temp.relativize(p).joinToString("/")
                    val target = dest.resolve(rel)
                    val isDir = Files.isDirectory(p, NOFOLLOW)
                    if (!isDir) files++
                    if (!Files.exists(target, NOFOLLOW)) return@forEach
                    // Папка на месте папки — не конфликт, содержимое сольётся
                    if (isDir && Files.isDirectory(target, NOFOLLOW)) return@forEach
                    // Конфликт внутри уже конфликтующей папки не повторяем
                    if (conflicts.none { rel.startsWith("$it/") }) conflicts.add(rel)
                }
            }
            val top = Files.list(temp).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            return Prepared(temp, dest, conflicts, files, top, parent)
        } catch (e: Exception) {
            FileTree.delete(temp)
            parent?.let { FileTree.delete(it) }
            throw e
        }
    }

    /**
     * tar и 7z создают ссылки из архива как есть, в том числе абсолютные. При переносе в архив
     * копируется то, на что ссылка указывает, — присланный tar со ссылкой на ~/.ssh унёс бы ключи
     * внутрь APK или на сервер. Оставляем только ссылки, которые и по настоящему пути ведут внутрь распакованного.
     */
    internal fun dropEscapingLinks(root: Path) {
        val realRoot = root.toRealPath()
        val links = Files.walk(root).use { s -> s.filter { Files.isSymbolicLink(it) }.toList() }
        for (link in links) {
            val inside = runCatching { link.toRealPath().startsWith(realRoot) }.getOrDefault(false)
            if (!inside) Files.deleteIfExists(link)
        }
    }

    fun discard(p: Prepared) = p.cleanup()

    fun finish(vfs: VfsManager, p: Prepared, policy: ConflictPolicy): Result =
        vfs.exclusive(p.dest) { finishLocked(vfs, p, policy) }

    private fun finishLocked(vfs: VfsManager, p: Prepared, policy: ConflictPolicy): Result {
        val created = ArrayList<Path>()
        val replaced = ArrayList<TrashItem>()
        val archiveReplaced = ArrayList<ArchiveBackup>()
        var skipped = 0
        // Пока решали, архив-назначение мог переоткрыться
        val dest = vfs.live(p.dest)
        val sameFs = p.temp.fileSystem === dest.fileSystem
        val destLocal = dest.fileSystem === FileSystems.getDefault()

        fun place(from: Path, to: Path) {
            if (sameFs) Files.move(from, to) else vfs.copyTo(from, to)
            created.add(to)
        }

        fun merge(src: Path, dst: Path) {
            val children = Files.list(src).use { it.toList() }
            for (c in children) {
                val name = c.fileName.toString()
                val t = dst.resolve(name)
                when {
                    !Files.exists(t, NOFOLLOW) -> place(c, t)
                    Files.isDirectory(c, NOFOLLOW) && Files.isDirectory(t, NOFOLLOW) -> merge(c, t)
                    else -> when (policy) {
                        ConflictPolicy.SKIP -> skipped++
                        ConflictPolicy.KEEP_BOTH -> place(c, FileTransfers.freeCopyTarget(dst, name))
                        ConflictPolicy.REPLACE -> {
                            if (destLocal) {
                                replaced.add(Trash.trash(t))
                            } else {
                                archiveReplaced.add(UndoBackups.save(vfs, t))
                                vfs.deleteEntry(t)
                            }
                            place(c, t)
                        }
                    }
                }
            }
        }

        // Ошибка посреди работы (оборвалась связь, файл без прав) — не повод терять сведения для отмены:
        // заменённое к этому моменту уже убрано, и вернуть его можно только через Ctrl+Z
        var error: Throwable? = null
        try {
            vfs.batch { merge(p.temp, dest) }
        } catch (e: Exception) {
            error = e
        } finally {
            p.cleanup()
        }
        return Result(created, replaced, skipped, archiveReplaced, error)
    }

    /** Свободная папка для распаковки: «name», если занято — «name (2)». */
    fun freeFolder(parent: Path, name: String): Path = FileTransfers.freeName(parent, name)
}
