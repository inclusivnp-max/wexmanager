package org.wexclient.app.vfs.remote

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Копии файлов с сервера и телефона, скачанные для просмотра: ~/.cache/wexdesktop/remote/<откуда>/<путь>.
 * Раньше они не удалялись никогда, и папка росла от запуска к запуску. Раз за процесс (при первом окне
 * и перед первым скачиванием) убираются копии прошлых запусков старше [MAX_AGE_MS], а если всё вместе
 * больше [MAX_TOTAL_BYTES] — ещё и самые давние из них. Копии этого запуска не трогаем: их может показывать
 * просмотрщик или внешняя программа, которой их отдали; файлы, открытые сейчас каким-либо процессом, — тоже.
 */
object RemoteCopies {
    private const val MAX_AGE_MS = 7L * 24 * 3600_000
    private const val MAX_TOTAL_BYTES = 2L shl 30

    private val root: Path get() = org.wexclient.app.util.AppDirs.cache.resolve("remote")

    // Что скачано до этого момента — копии прошлых запусков
    private val startedAt: Long =
        org.wexclient.app.util.processStartMillis()

    @Volatile
    private var pruned = false

    /** Куда скачать [path] с [remote] для просмотра (имя сохраняется — по нему выбирается просмотрщик). */
    fun target(remote: RemoteFileSystem, path: Path): Path {
        pruneOnce()
        val base = root.resolve(remote.cacheKey)
        val target = base.resolve(path.toAbsolutePath().toString().removePrefix("/")).normalize()
        // «..» в пути не должно увести копию за пределы папки кэша
        if (!target.startsWith(base)) throw java.io.IOException("Недопустимый путь: $path")
        return target
    }

    /**
     * Уборка — один раз за процесс; звать не из потока окна. Скачивание ([target]) ждёт идущую уборку:
     * иначе она могла бы убрать только что созданную для копии пустую папку.
     */
    fun pruneOnce() {
        if (pruned) return
        synchronized(this) {
            if (pruned) return
            runCatching { prune(root, System.currentTimeMillis(), startedAt) }
            pruned = true
        }
    }

    internal fun prune(dir: Path, now: Long, since: Long, maxTotal: Long = MAX_TOTAL_BYTES) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        // Файл и время последнего обращения к нему (скачали или открыли)
        val files = ArrayList<Triple<Path, Long, Long>>()
        Files.walk(dir).use { stream ->
            stream.forEach { p ->
                val a = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                if (a != null && a.isRegularFile) {
                    files.add(Triple(p, maxOf(a.lastModifiedTime().toMillis(), a.lastAccessTime().toMillis()), a.size()))
                }
            }
        }
        val old = files.filter { it.second < since }
        if (old.isEmpty()) return
        var total = files.sumOf { it.third }
        val open by lazy { openFiles(dir) }
        fun remove(f: Triple<Path, Long, Long>): Boolean {
            if (realOf(f.first) in open) return false
            if (!runCatching { Files.deleteIfExists(f.first) }.getOrDefault(false)) return false
            total -= f.third
            return true
        }
        // Сначала давние, потом — пока не уложимся в предел — самые старые из прошлых запусков
        val rest = ArrayList<Triple<Path, Long, Long>>()
        for (f in old) if (now - f.second <= MAX_AGE_MS || !remove(f)) rest.add(f)
        for (f in rest.sortedBy { it.second }) {
            if (total <= maxTotal) break
            remove(f)
        }
        removeEmptyDirs(dir)
    }

    /**
     * Файлы в [dir], открытые сейчас каким-либо процессом этого пользователя (Linux: /proc/<pid>/fd).
     * Где /proc нет, удаление открытого файла и так не пройдёт (Windows) — там пусто.
     */
    private fun openFiles(dir: Path): Set<Path> {
        val out = HashSet<Path>()
        val proc = Paths.get("/proc")
        if (!Files.isDirectory(proc)) return out
        // /proc показывает настоящие пути (домашняя папка бывает ссылкой)
        val base = realOf(dir)
        runCatching {
            Files.newDirectoryStream(proc) { it.fileName.toString().all(Char::isDigit) }.use { pids ->
                for (pid in pids) {
                    // Чужие процессы не читаются — их и не нужно: копии лежат в нашей домашней папке
                    runCatching {
                        Files.newDirectoryStream(pid.resolve("fd")).use { fds ->
                            for (fd in fds) {
                                val target = runCatching { Files.readSymbolicLink(fd) }.getOrNull() ?: continue
                                if (target.startsWith(base)) out.add(target.normalize())
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun realOf(p: Path): Path = runCatching { p.toRealPath() }.getOrElse { p.toAbsolutePath().normalize() }

    /** Пустые папки после уборки — прочь (саму папку кэша оставляем). */
    private fun removeEmptyDirs(dir: Path) {
        runCatching {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .filter { it != dir && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { d -> runCatching { Files.newDirectoryStream(d).use { s -> if (!s.iterator().hasNext()) Files.delete(d) } } }
            }
        }
    }
}
