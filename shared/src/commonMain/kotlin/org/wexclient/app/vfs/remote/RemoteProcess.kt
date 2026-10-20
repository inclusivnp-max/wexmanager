package org.wexclient.app.vfs.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Итог команды на той стороне. */
class RemoteResult(val code: Int, val output: String, val error: String)

/** Процессы ssh и adb, через которые идут команды и файлы: общее для сервера и телефона. */
object RemoteProcess {

    /**
     * Команда целиком. [timeoutMs] — сколько можно молчать: пока данные идут (список большой папки
     * по медленной связи), команда не обрывается, а зависшее соединение обрывается вовремя.
     */
    fun exec(process: Process, timeoutMs: Long, silent: () -> IOException): RemoteResult {
        process.outputStream.close()
        val lastData = AtomicLong(System.nanoTime())
        // Вывод и ошибки читаем в своих потоках: заполненный канал повесил бы процесс,
        // а чтение здесь же не дало бы сработать таймауту
        fun pump(from: InputStream, to: ByteArrayOutputStream) = Thread {
            runCatching {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    lastData.set(System.nanoTime())
                    to.write(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; start() }
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val readers = listOf(pump(process.inputStream, output), pump(process.errorStream, error))
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() - lastData.get() > timeoutMs * 1_000_000) {
                process.destroyForcibly()
                throw silent()
            }
        }
        // Фоновое соединение (ControlPersist) у старых ssh держит канал ошибок открытым — не ждём его долго
        readers.forEach { it.join(2_000) }
        return RemoteResult(process.exitValue(), output.toString("UTF-8"), error.toString("UTF-8"))
    }

    /**
     * Чтение файла потоком: данные идут прямо из процесса, в память целиком не собираются.
     * Конец данных — ещё не успех: cat мог упасть (нет прав, оборвалась связь), и тогда чтение
     * заканчивается ошибкой [failed] с текстом ошибок процесса. Иначе перенос принял бы пустой
     * или обрезанный файл за целый и удалил бы источник.
     */
    fun readStream(process: Process, stalled: () -> IOException, failed: (String) -> IOException): InputStream {
        process.outputStream.close()
        val error = StringBuffer()
        val errorReader = Thread {
            runCatching { error.append(process.errorStream.readBytes().decodeToString()) }
        }.apply { isDaemon = true; start() }
        val input = process.inputStream
        return object : InputStream() {
            private var finished = false

            private fun atEnd() {
                if (finished) return
                finished = true
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw stalled()
                }
                errorReader.join(2_000)
                if (process.exitValue() != 0) throw failed(error.toString())
            }

            override fun read(): Int = input.read().also { if (it < 0) atEnd() }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                return input.read(b, off, len).also { if (it < 0) atEnd() }
            }

            override fun available(): Int = input.available()

            override fun close() {
                input.close()
                // Бросили читать раньше конца — cat завершится сам, ошибка тут уже не важна
                if (!finished && !process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    /** Запись файла потоком; ошибка той стороны ([failed] с текстом ошибок процесса) всплывает при закрытии. */
    fun writeStream(process: Process, stalled: () -> IOException, failed: (String) -> IOException): OutputStream =
        object : java.io.FilterOutputStream(process.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                val finished = process.waitFor(10, TimeUnit.MINUTES)
                val error = process.errorStream.readBytes().decodeToString()
                if (!finished) {
                    process.destroyForcibly()
                    throw stalled()
                }
                if (process.exitValue() != 0) throw failed(error)
            }
        }
}
