package org.wexclient.app.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал необработанных ошибок: ~/.cache/wexdesktop/errors.log. Окна WEX запускаются без консоли
 * (вывод уходит в /dev/null), и сбой иначе пропадал бесследно. Файл только для владельца и не растёт
 * бесконечно: больше 1 МБ — прежний уходит в errors.log.1.
 */
object ErrorLog {
    private const val LIMIT = 1L shl 20
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val file: Path get() = Paths.get(System.getProperty("user.home"), ".cache", "wexdesktop", "errors.log")

    /** Перехватывать необработанные ошибки всех потоков (окна, фоновые задачи, корутины). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("Необработанная ошибка в потоке «${thread.name}»", error)
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
    }

    /** Записать ошибку, которую поймали, но показать пользователю толком не смогли. */
    @Synchronized
    fun record(what: String, error: Throwable) {
        runCatching {
            val f = file
            Files.createDirectories(f.parent)
            if (Files.exists(f) && Files.size(f) > LIMIT) {
                Files.move(f, f.resolveSibling("errors.log.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            // На NTFS прав POSIX нет — там файл создаёт сама запись (CREATE)
            if (!Files.exists(f) && "posix" in f.fileSystem.supportedFileAttributeViews()) {
                Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            val text = LocalDateTime.now().format(TIME) + "  " + what + "\n" + error.stackTraceToString() + "\n"
            Files.writeString(f, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** Сколько записей в журнале (для настроек); 0 — журнала нет. */
    fun count(): Int = runCatching {
        if (!Files.exists(file)) 0
        else Files.newBufferedReader(file).useLines { lines -> lines.count { it.length > 21 && it[4] == '-' && it[13] == ':' && it.startsWith("20") } }
    }.getOrDefault(0)
}
