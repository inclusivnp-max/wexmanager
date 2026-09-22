package org.wexclient.app.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Служебные процессы WEX (ffmpeg и ffplay плеера, архиваторы, git, fd, rg, keytool…) завершаются
 * вместе с ним: иначе после выхода продолжали играть звук видео, а 7z — распаковывать после
 * «Прервать и выйти». Запущенное пользователем (AppImage, xdg-open, терминал, внешний плеер) сюда не входит.
 */
object HelperProcesses {
    private val live = ConcurrentHashMap.newKeySet<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ stopAll(900) }, "wex-helpers-stop"))
    }

    fun <P : Process> track(process: P): P {
        live.add(process)
        process.onExit().thenRun { live.remove(process) }
        return process
    }

    /** Сколько служебных процессов работает сейчас (для тестов). */
    internal fun running(): Int = live.count { it.isAlive }

    internal fun stopAll(graceMs: Long) {
        val all = live.filter { it.isAlive }
        if (all.isEmpty()) return
        // Дети тоже: tar запускает xz/zstd
        val handles = all.flatMap { p -> p.descendants().toList() + p.toHandle() }
        handles.forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        while (handles.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}

/** Запуск служебного процесса: при выходе из WEX он будет остановлен. */
fun ProcessBuilder.startHelper(): Process = HelperProcesses.track(start())
