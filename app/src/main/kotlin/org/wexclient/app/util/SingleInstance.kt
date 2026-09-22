package org.wexclient.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Открытие файла из другой программы («Открыть с помощью», xdg-open) не плодит окна: если WEX уже
 * запущен, путь уходит ему по сокету, а новый процесс сразу закрывается. Запуск без пути
 * по-прежнему открывает отдельное окно — два окна рядом никто не отменял.
 */
object SingleInstance {

    /** Просьба открыть путь; null-путь — «просто открой окно». */
    class Request(val id: Long, val path: Path?)

    /** Что попросил другой запуск WEX; читает [org.wexclient.app.launchWex]. */
    var requested by mutableStateOf<Request?>(null)
        private set

    private var counter = 0L

    /**
     * Путь к сокету Unix не длиннее 108 байт, поэтому сначала XDG_RUNTIME_DIR (обычно /run/user/1000),
     * а если он длинный или его нет — короткое имя в /tmp.
     */
    private val socketFile: Path by lazy {
        val user = System.getProperty("user.name")
        val runtime = System.getenv("XDG_RUNTIME_DIR")
            ?.takeIf { Files.isDirectory(Paths.get(it)) }
            ?.let { Paths.get(it, "wexdesktop.sock") }
        if (runtime != null && runtime.toString().toByteArray().size < 100) runtime
        else Paths.get("/tmp", "wexdesktop-$user.sock")
    }

    /**
     * Отдать путь уже запущенному окну. true — отдали, этому процессу делать нечего.
     * false — никто не слушает (первый запуск или прошлое окно закрыто).
     */
    fun handOver(path: Path?): Boolean = send((path?.toAbsolutePath()?.toString() ?: "") + "\n")

    /**
     * Проверка «WEX уже работает?» — без просьбы открыть окно. Нужна фоновому запуску: он должен
     * молча уйти, если процесс уже есть, а не выкидывать на экран лишнее окно.
     */
    fun alive(): Boolean = send("$PING\n")

    private fun send(line: String): Boolean = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketFile))
            channel.write(ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8)))
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Слово вместо пути: настоящие пути всегда абсолютные, так что перепутать нельзя. */
    private const val PING = "ping"

    /** Слушать просьбы открыть файл. Занято другим окном — просто не слушаем. */
    fun listen(onRequest: (Path?) -> Unit = {}) {
        val server = try {
            bind()
        } catch (_: Exception) {
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { server.close() }
            runCatching { Files.deleteIfExists(socketFile) }
        })
        Thread({
            while (server.isOpen) {
                try {
                    server.accept().use { channel ->
                        val buffer = ByteBuffer.allocate(8192)
                        while (channel.read(buffer) > 0 && buffer.hasRemaining()) Unit
                        buffer.flip()
                        val text = StandardCharsets.UTF_8.decode(buffer).toString().trim()
                        // Проверка живости: отвечать нечем — важно лишь, что соединение удалось
                        if (text == PING) return@use
                        val path = text.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { Paths.get(it) }.getOrNull() }
                            ?.takeIf { Files.exists(it) }
                        counter++
                        requested = Request(counter, path)
                        onRequest(path)
                    }
                } catch (_: IOException) {
                    if (!server.isOpen) return@Thread
                }
            }
        }, "wex-single-instance").apply { isDaemon = true }.start()
    }

    /** Файл сокета от закрытого окна мешает: если на него никто не отвечает, убираем и слушаем сами. */
    private fun bind(): ServerSocketChannel {
        val address = UnixDomainSocketAddress.of(socketFile)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        return try {
            server.bind(address)
            server
        } catch (e: Exception) {
            runCatching { server.close() }
            if (alive()) throw e
            Files.deleteIfExists(socketFile)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(address) }
        }
    }

    /** Просьбу выполнили — ждём следующую. */
    fun done(request: Request) {
        if (requested === request) requested = null
    }
}
