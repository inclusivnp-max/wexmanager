package org.wexclient.app.util

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Запуск внешних команд с ограничением вывода, таймаутом и отменой. */
object ProcessRunner {

    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val cancelled: Boolean
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut && !cancelled
    }

    private val whichCache = ConcurrentHashMap<String, String>()

    // Отсутствие утилиты кэшируется на 30 секунд: её могут установить во время работы WEX.
    private val notFoundAt = ConcurrentHashMap<String, Long>()
    private const val NOT_FOUND_TTL_MS = 30_000L

    /** Полный путь к утилите из PATH или null. */
    fun which(name: String): String? {
        whichCache[name]?.let { return it }
        val missedAt = notFoundAt[name]
        if (missedAt != null && System.currentTimeMillis() - missedAt < NOT_FOUND_TTL_MS) return null
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
        val exts = if (File.separatorChar == '\\') listOf("", ".exe", ".bat", ".cmd") else listOf("")
        val found = dirs.asSequence()
            .filter { it.isNotEmpty() }
            .flatMap { dir -> exts.asSequence().map { File(dir, name + it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
        if (found == null) {
            notFoundAt[name] = System.currentTimeMillis()
            return null
        }
        notFoundAt.remove(name)
        return whichCache.putIfAbsent(name, found) ?: found
    }

    fun isAvailable(name: String): Boolean = which(name) != null

    /**
     * Запускает команду и ждёт завершения.
     * @param maxOutputChars сверх лимита вывод продолжает вычитываться, но не сохраняется
     * @param onLine вызывается из потока чтения для каждой строки вывода
     */
    @Throws(IOException::class, InterruptedException::class)
    fun run(
        command: List<String>,
        timeoutMs: Long = 60_000,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        maxOutputChars: Int = 1_000_000,
        isCancelled: () -> Boolean = { false },
        onLine: ((String) -> Unit)? = null
    ): Result {
        require(maxOutputChars >= 0) { "Лимит вывода должен быть неотрицательным" }
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (workDir != null) pb.directory(workDir)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        val process = pb.startHelper()

        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        synchronized(output) {
                            val remaining = maxOutputChars - output.length
                            if (remaining > 0) {
                                output.append(line, 0, minOf(line.length, remaining))
                                if (output.length < maxOutputChars) output.append('\n')
                            }
                        }
                        onLine?.invoke(line)
                    }
                }
            } catch (_: IOException) {
                // процесс убит — поток закрыт
            }
        }.apply {
            isDaemon = true
            name = "proc-out-${command.firstOrNull()}"
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // Запись в pipe тоже может блокироваться. Поток ожидания должен проверять отмену и таймаут.
        val writer = if (stdin != null) Thread({
            try {
                process.outputStream.use { it.write(stdin) }
            } catch (_: IOException) {
                // Процесс завершился или закрыл stdin.
            }
        }, "proc-in-${command.firstOrNull()}").apply {
            isDaemon = true
            start()
        } else {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // Процесс уже завершился.
            }
            null
        }

        var timedOut = false
        var cancelled = false
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true
                    break
                }
            }
        } finally {
            if (process.isAlive) kill(process)
            writer?.join(500)
            reader.join(2000)
        }

        val exit = if (process.isAlive) -1 else process.exitValue()
        val text = synchronized(output) { output.toString() }
        return Result(exit, text, timedOut, cancelled)
    }

    /** Как run(), но при ошибке бросает IOException с хвостом вывода. */
    @Throws(IOException::class, InterruptedException::class)
    fun runChecked(command: List<String>, timeoutMs: Long = 60_000, workDir: File? = null, isCancelled: () -> Boolean = { false }): String {
        val r = run(command, timeoutMs, workDir, isCancelled = isCancelled)
        if (!r.ok) {
            val why = when {
                r.cancelled -> "отменено"
                r.timedOut -> "таймаут ${timeoutMs / 1000} с"
                else -> "код ${r.exitCode}"
            }
            throw IOException("${command.first()}: $why\n${r.output.takeLast(2000).trim()}")
        }
        return r.output
    }

    private fun kill(process: Process) {
        process.destroyTree(force = false)
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyTree(force = true)
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
