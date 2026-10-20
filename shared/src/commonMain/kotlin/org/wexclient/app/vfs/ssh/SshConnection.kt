package org.wexclient.app.vfs.ssh

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.wexclient.app.vfs.remote.RemoteConnection
import org.wexclient.app.vfs.remote.RemoteEntry
import org.wexclient.app.vfs.remote.RemoteProcess
import org.wexclient.app.vfs.remote.RemoteResult

/** Куда подключаться: `user@host:port` или имя из ~/.ssh/config. */
data class SshTarget(val user: String?, val host: String, val port: Int = 22) {
    /** Как это пишется в ssh: user@host. */
    val destination: String get() = (user?.let { "$it@" } ?: "") + host

    /** Подпись для панели и истории: user@host или user@host:2222. */
    val label: String get() = destination + if (port != 22) ":$port" else ""

    companion object {
        /**
         * Разбор строки подключения: `host`, `user@host`, `user@host:2222`, `ssh://user@host:22`.
         * null — строка не похожа на адрес сервера.
         */
        fun parse(text: String): SshTarget? {
            var rest = text.trim().removePrefix("ssh://").removePrefix("sftp://").trimEnd('/')
            if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
            val user = rest.substringBefore('@', "").takeIf { '@' in rest && it.isNotEmpty() }
            // Адрес с «-» в начале ssh принял бы за свой параметр (-oProxyCommand=… запустил бы команду)
            if (user != null && user.startsWith("-")) return null
            if (user != null) rest = rest.substringAfter('@')
            // Порт после двоеточия; адрес IPv6 в квадратных скобках не трогаем
            val port = if (!rest.startsWith("[") && rest.count { it == ':' } == 1) {
                rest.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
            } else null
            val host = if (port != null) rest.substringBefore(':') else rest
            if (host.isEmpty() || host.startsWith("-")) return null
            return SshTarget(user, host, port ?: 22)
        }
    }
}

/** Ошибка на стороне сервера с понятным текстом. */
class SshException(message: String) : IOException(message)

/**
 * Системный ssh с общим соединением ControlMaster на сервер.
 * Первая команда открывает соединение, последующие используют его повторно.
 * Пароль не спрашиваем (BatchMode) — вход по ключу или через агент, как у самого ssh.
 */
class SshConnection(val target: SshTarget) : RemoteConnection {

    /**
     * Сокеты соединений. Путь к сокету Unix не длиннее 108 байт, поэтому не в ~/.cache,
     * а в XDG_RUNTIME_DIR — это и короче, и правильное место для приватных сокетов.
     */
    private val controlDir: File by lazy {
        (
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { File(it).isDirectory }?.let { File(it, "wex-ssh") }
                ?: File("/tmp", "wex-ssh-" + System.getProperty("user.name"))
            ).apply {
                mkdirs()
                // Только для владельца: через сокет можно выполнять команды на сервере
                setReadable(false, false); setReadable(true, true)
                setWritable(false, false); setWritable(true, true)
                setExecutable(false, false); setExecutable(true, true)
            }
    }

    /** Мультиплексирование. В OpenSSH для Windows его нет — там каждая команда идёт своим соединением. */
    private val controlArgs: List<String>
        get() = if (isWindows) emptyList() else listOf(
            "-o", "ControlMaster=auto",
            "-o", "ControlPath=${controlDir.absolutePath}/%C",
            "-o", "ControlPersist=600"
        )

    /**
     * Свой файл настроек ssh, если задан WEX_SSH_CONFIG: удобно держать отдельные ключи
     * для программы, не трогая ~/.ssh/config.
     */
    private val configArgs: List<String> =
        System.getenv("WEX_SSH_CONFIG")?.takeIf { File(it).isFile }?.let { listOf("-F", it) } ?: emptyList()

    /** Общие параметры ssh: мультиплексирование, без вопросов, с разумными таймаутами. */
    private fun sshArgs(): List<String> = listOf("ssh") + configArgs + listOf(
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=10",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=3"
    ) + controlArgs + portArgs + listOf("--", target.destination)

    /**
     * Порт передаём, только если он не обычный: иначе «-p 22» перебил бы порт,
     * заданный для этого имени в ~/.ssh/config.
     */
    private val portArgs: List<String> get() = if (target.port != 22) listOf("-p", target.port.toString()) else emptyList()

    /** Проверка подключения. Возвращает домашнюю папку пользователя на сервере. */
    fun connect(): String {
        val result = exec("cd && pwd", timeoutMs = 20_000)
        if (result.code != 0) throw SshException(explain(result.error))
        return result.output.trim().ifEmpty { "/" }
    }

    /** Содержимое папки. Нужен GNU find — он есть на любом обычном Linux-сервере. */
    override fun list(dir: String): List<RemoteEntry> {
        val command = "cd -- ${quote(dir)} && find . -mindepth 1 -maxdepth 1 -printf " +
            quote("%y\\t%Y\\t%s\\t%T@\\t%m\\t%u\\t%g\\t%f\\0")
        val result = exec(command, timeoutMs = 60_000)
        if (result.code != 0) throw SshException(explain(result.error).ifBlank { "Не открылась папка $dir" })
        return parseListing(result.output)
    }

    /** Сведения об одном пути; null — такого нет. */
    override fun stat(path: String): RemoteEntry? {
        if (path == "/") return RemoteEntry("/", 'd', 'd', 0, 0, 0x1ED, "root", "root")
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val command = "cd -- ${quote(parent)} && find ${quote("./$name")} -maxdepth 0 -printf " +
            quote("%y\\t%Y\\t%s\\t%T@\\t%m\\t%u\\t%g\\t%f\\0")
        val result = exec(command, timeoutMs = 30_000)
        if (result.code != 0) return null
        return parseListing(result.output).firstOrNull()
    }

    override fun mkdir(path: String) = check(exec("mkdir -- ${quote(path)}"), "Не создалась папка")

    override fun delete(path: String, recursive: Boolean) =
        check(exec(if (recursive) "rm -rf -- ${quote(path)}" else "rm -f -- ${quote(path)} 2>/dev/null || rmdir -- ${quote(path)}"), "Не удалилось")

    // -T: существующая папка на месте цели — не «положить внутрь», а ошибка, как у Files.move/copy на диске
    override fun rename(from: String, to: String) = check(exec("mv -T -- ${quote(from)} ${quote(to)}"), "Не переименовалось")

    override fun copyInside(from: String, to: String) = check(exec("cp -a -T -- ${quote(from)} ${quote(to)}"), "Не скопировалось")

    /** Куда указывает ссылка — как записано в ней, без раскрытия. */
    override fun readLink(path: String): String {
        val result = exec("readlink -- ${quote(path)}", timeoutMs = 30_000)
        check(result, "Не прочиталась ссылка")
        return result.output.removeSuffix("\n")
    }

    override fun symlink(path: String, target: String) =
        check(exec("ln -s -- ${quote(target)} ${quote(path)}"), "Не создалась ссылка")

    fun touch(path: String) = check(exec(": > ${quote(path)}"), "Не создался файл")

    /** Время изменения файла — чтобы копия на сервере не выглядела «только что созданной». */
    override fun setModified(path: String, millis: Long) =
        check(exec("touch -h -d @${millis / 1000} -- ${quote(path)}"), "Не поменялось время файла")

    /** Свободное место на разделе с этой папкой: (свободно, всего) в байтах. */
    override fun diskSpace(path: String): Pair<Long, Long>? {
        val result = exec("df -PB1 -- ${quote(path)} | tail -n 1", timeoutMs = 20_000)
        if (result.code != 0) return null
        val parts = result.output.trim().split(Regex("\\s+"))
        val total = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val free = parts.getOrNull(3)?.toLongOrNull() ?: return null
        return free to total
    }

    /** Чтение файла потоком прямо из ssh; ошибка cat — ошибка чтения, а не обрезанный файл. */
    override fun openRead(path: String): InputStream = RemoteProcess.readStream(
        start("cat -- ${quote(path)}"),
        stalled = { SshException("Сервер не закончил отдачу файла: $path") },
        failed = { error -> SshException(explain(error).ifBlank { "Не прочиталось: $path" }) }
    )

    /** Запись файла потоком; ошибка сервера всплывает при закрытии. */
    override fun openWrite(path: String): OutputStream = RemoteProcess.writeStream(
        start("cat > ${quote(path)}"),
        stalled = { SshException("Запись на сервер не закончилась вовремя") },
        failed = { error -> SshException(explain(error).ifBlank { "Не записалось: $path" }) }
    )

    /**
     * Команда для консоли: вход на сервер сразу в папку [dir]. Идёт по уже открытому
     * соединению панели — ключ заново не спрашивается. Синтаксис годится и для bash, и для fish.
     */
    override fun shellCommand(dir: String): List<String> = listOf("ssh") + configArgs + listOf(
        "-t",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=3"
    ) + controlArgs + portArgs + listOf("--", target.destination, "cd -- ${quote(dir)}; exec \$SHELL -l")

    /** Закрыть фоновое соединение. */
    override fun close() {
        if (isWindows) return // фонового соединения нет
        runCatching {
            ProcessBuilder(
                listOf("ssh") + configArgs + listOf(
                "-o", "ControlPath=${controlDir.absolutePath}/%C") + portArgs + listOf(
                "-O", "exit",
                "--", target.destination)
            ).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ внутреннее

    private fun start(command: String): Process =
        ProcessBuilder(sshArgs() + command).start()

    /** Команда на сервере; [timeoutMs] — сколько она может молчать. */
    private fun exec(command: String, timeoutMs: Long = 60_000): RemoteResult =
        RemoteProcess.exec(start(command), timeoutMs) { SshException("Сервер не ответил вовремя") }

    private fun check(result: RemoteResult, what: String) {
        if (result.code != 0) throw SshException(explain(result.error).ifBlank { what })
    }

    /** Текст ошибки ssh по-человечески: самые частые причины — вход и сеть. */
    private fun explain(error: String): String {
        val text = error.trim()
        return when {
            "Permission denied" in text -> "Сервер не пустил: нужен вход по ключу (ssh-copy-id ${target.destination})"
            "Could not resolve hostname" in text -> "Не найден адрес ${target.host}"
            "Connection refused" in text -> "Сервер отказал в соединении на порту ${target.port}"
            "timed out" in text -> "Сервер не отвечает"
            "Host key verification failed" in text -> "Ключ сервера не проверен: подключитесь один раз из консоли, чтобы принять его"
            "No such file or directory" in text -> text.lines().lastOrNull { it.isNotBlank() }.orEmpty()
            else -> text.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        }
    }

    companion object {
        private val isWindows = File.separatorChar == '\\'

        /**
         * Слово для оболочки сервера — имя не выполнится как команда. Оболочкой входа бывает и fish:
         * там внутри одинарных кавычек действуют \\ и \', и прежнее '…'\''…' с «\» в имени выводило
         * остаток строки из кавычек. Эти два символа ставим вне кавычек — годится и для sh/bash/zsh, и для fish.
         */
        fun quote(text: String): String = org.wexclient.app.terminal.ScriptRunner.quote(text)

        /** Разбор вывода find -printf: поля через табуляцию, записи через NUL. */
        fun parseListing(raw: String): List<RemoteEntry> = raw.split('\u0000').mapNotNull { record ->
            if (record.isBlank()) return@mapNotNull null
            val parts = record.split('\t', limit = 8)
            if (parts.size < 8) return@mapNotNull null
            val name = parts[7].removePrefix("./")
            if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
            RemoteEntry(
                name = name,
                type = parts[0].firstOrNull() ?: 'f',
                targetType = parts[1].firstOrNull() ?: 'f',
                size = parts[2].toLongOrNull() ?: 0L,
                modifiedMillis = ((parts[3].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                mode = parts[4].toIntOrNull(8) ?: 0,
                owner = parts[5],
                group = parts[6]
            )
        }
    }
}
