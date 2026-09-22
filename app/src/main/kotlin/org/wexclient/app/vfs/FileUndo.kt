package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Отменяемое действие с файлами. */
sealed interface FileOp {
    val title: String

    class Trashed(val items: List<TrashItem>) : FileOp {
        override val title get() = "удаление в корзину"
    }

    class Renamed(val from: Path, val to: Path) : FileOp {
        override val title get() = "переименование «${from.fileName}»"
    }

    /** Пакетное переименование: отменяется тоже в два шага (обмены «a ↔ b»). */
    class BatchRenamed(val renames: List<Pair<Path, Path>>) : FileOp {
        override val title get() = "переименование"
    }

    /** Было → стало; [replaced] — файлы, которые заменили (они в корзине), [archiveReplaced] — заменённое в архивах. */
    class Moved(
        val moves: List<Pair<Path, Path>>,
        val replaced: List<TrashItem> = emptyList(),
        val archiveReplaced: List<ArchiveBackup> = emptyList()
    ) : FileOp {
        override val title get() = "перемещение"
    }

    class Copied(
        val created: List<Path>,
        val replaced: List<TrashItem> = emptyList(),
        val archiveReplaced: List<ArchiveBackup> = emptyList()
    ) : FileOp {
        override val title get() = "копирование"
    }

    /** Созданные файлы, папки, ссылки — отмена убирает их в корзину. */
    class Created(val paths: List<Path>) : FileOp {
        constructor(path: Path) : this(listOf(path))

        override val title get() = if (paths.size == 1) "создание «${paths[0].fileName}»" else "создание"
    }
}

/** Последние действия; Ctrl+Z отменяет с конца. */
class FileUndoStack(private val limit: Int = 50) {
    private val ops = ArrayDeque<FileOp>()

    val size: Int get() = ops.size

    @Synchronized
    fun push(op: FileOp) {
        ops.addLast(op)
        while (ops.size > limit) ops.removeFirst()
    }

    @Synchronized
    fun pop(): FileOp? = ops.removeLastOrNull()

    @Synchronized
    fun peek(): FileOp? = ops.lastOrNull()

    /** Отменить последнее действие; сообщение для пользователя (или null, если отменять нечего). */
    fun undo(vfs: VfsManager): String? {
        val op = pop() ?: return null
        return FileUndo.revert(op, vfs)
    }
}

object FileUndo {
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    private fun exists(vfs: VfsManager, p: Path) = runCatching { Files.exists(vfs.live(p), NOFOLLOW) }.getOrDefault(false)

    private fun restoreAll(vfs: VfsManager, items: List<TrashItem>): Int {
        var n = 0
        for (item in items) {
            if (!Files.exists(item.file, NOFOLLOW)) continue // уже не в корзине
            val target = if (exists(vfs, item.originalPath)) Trash.freeRestoreTarget(item) else item.originalPath
            Trash.restore(item, target)
            n++
        }
        return n
    }

    /** Копии заменённого в архивах — обратно на места (занятое место не затираем). */
    private fun restoreArchived(vfs: VfsManager, items: List<ArchiveBackup>): Int {
        if (items.isEmpty()) return 0
        val restored = ArrayList<ArchiveBackup>()
        vfs.batch {
            for (b in items) {
                if (!Files.exists(b.copy, NOFOLLOW)) continue
                val target = vfs.live(b.original)
                if (Files.exists(target, NOFOLLOW)) continue
                target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                vfs.copyTo(b.copy, target)
                restored.add(b)
            }
        }
        // Копии — только после записи архива
        restored.forEach { UndoBackups.discard(it) }
        return restored.size
    }

    private fun remove(vfs: VfsManager, path: Path): Boolean {
        val p = vfs.live(path)
        if (!Files.exists(p, NOFOLLOW)) return false
        if (Trash.supports(p)) Trash.trash(p) else vfs.deleteEntry(p)
        return true
    }

    fun revert(op: FileOp, vfs: VfsManager): String = vfs.exclusive(*pathsOf(op).toTypedArray()) { revertLocked(op, vfs) }

    private fun pathsOf(op: FileOp): List<Path> = when (op) {
        is FileOp.Trashed -> emptyList()
        is FileOp.Renamed -> listOf(op.from, op.to)
        is FileOp.BatchRenamed -> op.renames.flatMap { listOf(it.first, it.second) }
        is FileOp.Moved -> op.moves.flatMap { listOf(it.first, it.second) } + op.archiveReplaced.map { it.original }
        is FileOp.Copied -> op.created + op.archiveReplaced.map { it.original }
        is FileOp.Created -> op.paths
    }

    private fun revertLocked(op: FileOp, vfs: VfsManager): String = when (op) {
        is FileOp.Trashed -> {
            val n = restoreAll(vfs, op.items)
            if (n == 1 && op.items.size == 1) "Восстановлено: «${op.items[0].originalPath.fileName}»" else "Восстановлено из корзины: $n"
        }
        is FileOp.Renamed -> {
            if (exists(vfs, op.from)) throw IllegalStateException("«${op.from.fileName}» уже существует")
            vfs.renameEntry(vfs.live(op.to), op.from.fileName.toString())
            "Имя снова «${op.from.fileName}»"
        }
        is FileOp.BatchRenamed -> {
            val back = op.renames.filter { exists(vfs, it.second) }.map { (from, to) -> vfs.live(to) to from.fileName.toString() }
            BatchRenamer.apply(vfs, back)
            "Имена возвращены: ${back.size}"
        }
        is FileOp.Moved -> {
            var n = 0
            vfs.batch {
                for ((from, to) in op.moves.asReversed()) {
                    if (!exists(vfs, to)) continue
                    if (exists(vfs, from)) throw IllegalStateException("«${from.fileName}» уже есть на старом месте")
                    vfs.live(from).parent?.let { if (!Files.isDirectory(it)) Files.createDirectories(it) }
                    vfs.moveTo(vfs.live(to), vfs.live(from))
                    n++
                }
            }
            restoreAll(vfs, op.replaced)
            restoreArchived(vfs, op.archiveReplaced)
            "Перемещение отменено: $n"
        }
        is FileOp.Copied -> {
            var n = 0
            vfs.batch { op.created.asReversed().forEach { if (remove(vfs, it)) n++ } }
            restoreAll(vfs, op.replaced)
            restoreArchived(vfs, op.archiveReplaced)
            "Копии убраны в корзину: $n"
        }
        is FileOp.Created -> {
            var n = 0
            vfs.batch { op.paths.asReversed().forEach { if (remove(vfs, it)) n++ } }
            if (n == 1 && op.paths.size == 1) "«${op.paths[0].fileName}» убран в корзину" else "Созданное убрано в корзину: $n"
        }
    }

    /** Что из действия ещё не отменено (после ошибки на середине) — чтобы повторить Ctrl+Z. */
    fun remaining(op: FileOp, vfs: VfsManager): FileOp? = when (op) {
        is FileOp.Trashed -> op.items.filter { Files.exists(it.file, NOFOLLOW) }.takeIf { it.isNotEmpty() }?.let { FileOp.Trashed(it) }
        is FileOp.Renamed -> op.takeIf { exists(vfs, it.to) }
        is FileOp.BatchRenamed -> op.renames.filter { exists(vfs, it.second) }.takeIf { it.isNotEmpty() }?.let { FileOp.BatchRenamed(it) }
        is FileOp.Moved -> {
            val moves = op.moves.filter { (_, to) -> exists(vfs, to) }
            val replaced = op.replaced.filter { Files.exists(it.file, NOFOLLOW) }
            val archived = op.archiveReplaced.filter { Files.exists(it.copy, NOFOLLOW) }
            if (moves.isEmpty() && replaced.isEmpty() && archived.isEmpty()) null else FileOp.Moved(moves, replaced, archived)
        }
        is FileOp.Copied -> {
            val created = op.created.filter { exists(vfs, it) }
            val replaced = op.replaced.filter { Files.exists(it.file, NOFOLLOW) }
            val archived = op.archiveReplaced.filter { Files.exists(it.copy, NOFOLLOW) }
            if (created.isEmpty() && replaced.isEmpty() && archived.isEmpty()) null else FileOp.Copied(created, replaced, archived)
        }
        is FileOp.Created -> op.paths.filter { exists(vfs, it) }.takeIf { it.isNotEmpty() }?.let { FileOp.Created(it) }
    }
}
