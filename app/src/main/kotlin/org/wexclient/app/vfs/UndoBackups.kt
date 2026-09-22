package org.wexclient.app.vfs

import org.wexclient.app.util.FileTree
import org.wexclient.app.util.ProcessScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Заменённое внутри архива: корзины там нет, поэтому копия лежит на диске, а отмена кладёт её обратно. */
class ArchiveBackup(val original: Path, val copy: Path)

/**
 * Копии заменённого в архивах — в ~/.cache/wexdesktop/undo/<процесс>, только для владельца.
 * Живут, пока работает программа (история отмены тоже); папки завершившихся процессов убираются.
 */
internal object UndoBackups {
    private val scratch = ProcessScratch { Paths.get(System.getProperty("user.home"), ".cache", "wexdesktop", "undo") }

    /** Папка процесса и уборка оставшихся от завершившихся — заранее, при запуске. */
    fun prepare() {
        scratch.dir
    }

    /** Копия [entry] (файл или папка из архива) на диске. */
    fun save(vfs: VfsManager, entry: Path): ArchiveBackup {
        val slot = scratch.newDir("r")
        val copy = slot.resolve(entry.fileName?.toString()?.trimEnd('/')?.ifEmpty { null } ?: "entry")
        try {
            vfs.copyTo(entry, copy)
        } catch (e: Exception) {
            FileTree.delete(slot)
            throw e
        }
        return ArchiveBackup(entry, copy)
    }

    fun discard(b: ArchiveBackup) {
        FileTree.delete(b.copy.parent)
    }
}
