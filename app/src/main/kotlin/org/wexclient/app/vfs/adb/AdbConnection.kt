package org.wexclient.app.vfs.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.wexclient.app.vfs.remote.RemoteConnection
import org.wexclient.app.vfs.remote.RemoteEntry
import org.wexclient.app.vfs.remote.RemoteProcess
import org.wexclient.app.vfs.remote.RemoteResult

/** Ошибка на стороне телефона с понятным текстом. */
class AdbException(message: String) : IOException(message)

/**
 * Работа с телефоном через системный adb: каждая операция — команда `adb shell` для toybox
 * на телефоне, файлы идут потоком через cat, без временных копий. Постоянного соединения нет —
 * его держит сервер adb. Список папки строит find -printf: он есть в toybox с Android 10.
 */
class AdbConnection(
    /** Серийный номер из adb devices: USB, эмулятор или адрес Wi‑Fi. */
    val serial: String,
    private val adb: String
) : RemoteConnection {

    /** Проверка телефона. Возвращает папку, с которой начинать: общую память, если она есть. */
    fun connect(): String {
        val probe = exec("find / -maxdepth 0 -printf ${quote("%M")}", timeoutMs = 20_000)
        if (probe.code != 0 || !probe.output.startsWith("d")) {
            val text = (probe.error + "\n" + probe.output).trim()
            throw AdbException(
                if ("printf" in text) "На телефоне слишком старый Android: для списка файлов нужен Android 10 или новее"
                else explain(text).ifBlank { "Телефон не ответил" }
            )
        }
        return if (exec("cd /sdcard", timeoutMs = 10_000).code == 0) "/sdcard" else "/"
    }

    override fun list(dir: String): List<RemoteEntry> {
        val result = exec("cd -- ${quote(dir)} && " + find(".", "-mindepth 1 -maxdepth 1"), timeoutMs = 60_000)
        if (result.code != 0) throw AdbException(explain(result.error).ifBlank { "Не открылась папка $dir" })
        return parseListing(result.output)
    }

    override fun stat(path: String): RemoteEntry? {
        if (path == "/") return RemoteEntry("/", 'd', 'd', 0, 0, 0x1ED, "root", "root")
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val result = exec("cd -- ${quote(parent)} && " + find(quote("./$name"), "-maxdepth 0"), timeoutMs = 30_000)
        if (result.code != 0) return null
        return parseListing(result.output).firstOrNull()
    }

    override fun mkdir(path: String) = check(exec("mkdir -- ${quote(path)}"), "Не создалась папка")

    override fun delete(path: String, recursive: Boolean) =
        check(exec(if (recursive) "rm -rf -- ${quote(path)}" else "rm -f -- ${quote(path)} 2>/dev/null || rmdir -- ${quote(path)}"), "Не удалилось")

    // -T: существующая папка на месте цели — не «положить внутрь», а ошибка, как у Files.move/copy на диске
    override fun rename(from: String, to: String) = check(exec("mv -T -- ${quote(from)} ${quote(to)}"), "Не переименовалось")

    override fun copyInside(from: String, to: String) = check(exec("cp -a -T -- ${quote(from)} ${quote(to)}"), "Не скопировалось")

    override fun readLink(path: String): String {
        val result = exec("readlink -- ${quote(path)}", timeoutMs = 30_000)
        check(result, "Не прочиталась ссылка")
        return result.output.removeSuffix("\n")
    }

    override fun symlink(path: String, target: String) =
        check(exec("ln -s -- ${quote(target)} ${quote(path)}"), "Не создалась ссылка")

    override fun setModified(path: String, millis: Long) =
        check(exec("touch -h -d @${millis / 1000} -- ${quote(path)}"), "Не поменялось время файла")

    override fun diskSpace(path: String): Pair<Long, Long>? {
        // toybox df не знает -B1: размеры в килобайтах
        val result = exec("df -k -- ${quote(path)} | tail -n 1", timeoutMs = 20_000)
        if (result.code != 0) return null
        val parts = result.output.trim().split(Regex("\\s+"))
        val total = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val free = parts.getOrNull(3)?.toLongOrNull() ?: return null
        return free * 1024 to total * 1024
    }

    override fun openRead(path: String): InputStream = RemoteProcess.readStream(
        start("cat -- ${quote(path)}"),
        stalled = { AdbException("Телефон не закончил отдачу файла: $path") },
        failed = { error -> AdbException(explain(error).ifBlank { "Не прочиталось: $path" }) }
    )

    override fun openWrite(path: String): OutputStream = RemoteProcess.writeStream(
        start("cat > ${quote(path)}"),
        stalled = { AdbException("Запись на телефон не закончилась вовремя") },
        failed = { error -> AdbException(explain(error).ifBlank { "Не записалось: $path" }) }
    )

    /** Команда для консоли: оболочка телефона сразу в папке [dir]. */
    override fun shellCommand(dir: String): List<String> =
        listOf(adb, "-s", serial, "shell", "-t", "cd -- ${quote(dir)}; exec sh")

    /** Закрывать нечего: телефон остаётся подключённым к adb, панель просто перестаёт его показывать. */
    override fun close() = Unit

    // ------------------------------------------------------------------ внутреннее

    /** -T — без псевдотерминала: данные файла идут байт в байт, ошибки отдельно, код выхода настоящий. */
    private fun start(command: String): Process =
        ProcessBuilder(adb, "-s", serial, "shell", "-T", command).start()

    /** Команда на телефоне; [timeoutMs] — сколько она может молчать. */
    private fun exec(command: String, timeoutMs: Long = 60_000): RemoteResult =
        RemoteProcess.exec(start(command), timeoutMs) { AdbException("Телефон не ответил вовремя") }

    private fun check(result: RemoteResult, what: String) {
        if (result.code != 0) throw AdbException(explain(result.error).ifBlank { what })
    }

    /** Текст ошибки adb по-человечески: самые частые причины — кабель, запрет отладки и закрытые папки. */
    private fun explain(error: String): String {
        val text = error.trim()
        return when {
            "unauthorized" in text -> "Телефон не разрешил отладку: подтвердите запрос на его экране"
            "not found" in text && "device" in text -> "Телефон отключён — подключите его и откройте снова"
            "offline" in text -> "Телефон не отвечает — переподключите кабель"
            "Permission denied" in text -> "Нет доступа: Android не пускает сюда без root"
            "Read-only file system" in text -> "Этот раздел телефона только для чтения"
            else -> text.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        }
    }

    companion object {
        /** Записи без раскрытия ссылок: вид и права как в ls -l, размер, время, владелец, имя. */
        private const val ENTRY_FORMAT = "E\\t%M\\t%s\\t%T@\\t%m\\t%u\\t%g\\t%f\\0"

        /** Те же записи с раскрытием (-L): у ссылки здесь вид того, на что она указывает. */
        private const val FOLLOW_FORMAT = "L\\t%M\\t%f\\0"

        /**
         * Две команды find по [target]: сведения о записях и, для ссылок, вид цели. Ошибки второй —
         * битые или закрытые ссылки — не ошибка списка: такая ссылка просто остаётся ссылкой.
         */
        private fun find(target: String, depth: String): String =
            "find $target $depth -printf ${quote(ENTRY_FORMAT)} && " +
                "{ find -L $target $depth -printf ${quote(FOLLOW_FORMAT)} 2>/dev/null; true; }"

        /** Слово для оболочки телефона — имя не выполнится как команда. */
        fun quote(text: String): String = org.wexclient.app.terminal.ScriptRunner.quote(text)

        /** Вид записи по первой букве ls -l: «-» — файл, остальное (d, l, c, b, p, s) — как есть. */
        private fun kindOf(mode: String): Char = when (val c = mode.firstOrNull()) {
            '-' -> 'f'
            null -> '?'
            else -> c
        }

        /** Разбор ответа двух find: записи «E…» и «L…», поля через табуляцию, записи через NUL. */
        fun parseListing(raw: String): List<RemoteEntry> {
            val records = raw.split('\u0000')
            val followed = HashMap<String, Char>()
            for (record in records) {
                if (!record.startsWith("L\t")) continue
                val parts = record.split('\t', limit = 3)
                if (parts.size == 3) followed[parts[2]] = kindOf(parts[1])
            }
            return records.mapNotNull { record ->
                if (!record.startsWith("E\t")) return@mapNotNull null
                val parts = record.split('\t', limit = 8)
                if (parts.size < 8) return@mapNotNull null
                val name = parts[7]
                if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
                val type = kindOf(parts[1])
                RemoteEntry(
                    name = name,
                    type = type,
                    // Цель ссылки не раскрылась (битая, закрытая) — при раскрытии это по-прежнему ссылка, то есть «нет такого»
                    targetType = if (type == 'l') followed[name] ?: 'l' else type,
                    size = parts[2].toLongOrNull() ?: 0L,
                    modifiedMillis = ((parts[3].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                    mode = parts[4].toIntOrNull(8) ?: 0,
                    owner = parts[5],
                    group = parts[6]
                )
            }
        }
    }
}
