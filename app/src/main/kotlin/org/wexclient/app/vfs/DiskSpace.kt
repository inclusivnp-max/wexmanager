package org.wexclient.app.vfs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Место на разделе, где лежит папка. */
class DiskSpace(val free: Long, val total: Long) {
    val usedFraction: Float get() = if (total <= 0) 0f else ((total - free).toDouble() / total).toFloat().coerceIn(0f, 1f)

    companion object {
        /** null — внутри архива или раздел не отвечает. */
        fun of(path: Path): DiskSpace? {
            // На сервере место спрашиваем у df — видно, сколько осталось на его диске
            val remote = path.fileSystem as? org.wexclient.app.vfs.ssh.SshFileSystem
            if (remote != null) {
                return runCatching { remote.connection.diskSpace(path.toAbsolutePath().toString()) }
                    .getOrNull()?.let { (free, total) -> DiskSpace(free, total) }?.takeIf { it.total > 0 }
            }
            if (path.fileSystem !== FileSystems.getDefault()) return null
            return runCatching {
                val store = Files.getFileStore(path)
                DiskSpace(store.usableSpace, store.totalSpace)
            }.getOrNull()?.takeIf { it.total > 0 }
        }
    }
}
