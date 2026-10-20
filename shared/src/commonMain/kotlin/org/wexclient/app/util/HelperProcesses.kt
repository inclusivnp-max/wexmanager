package org.wexclient.app.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Учитывает служебные процессы и завершает их при выходе из WEX.
 * Пользовательские программы, терминал и внешние просмотрщики сюда не входят.
 */
object HelperProcesses {
    private val live = ConcurrentHashMap.newKeySet<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ stopAll(900) }, "wex-helpers-stop"))
    }

    fun <P : Process> track(process: P): P {
        live.add(process)
        process.whenExited { live.remove(process) }
        return process
    }

    /** Сколько служебных процессов работает сейчас (для тестов). */
    internal fun running(): Int = live.count { it.isAlive }

    internal fun stopAll(graceMs: Long) {
        val all = live.filter { it.isAlive }
        if (all.isEmpty()) return
        // Дети тоже: tar запускает xz/zstd
        all.forEach { it.destroyTree(force = false) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        while (all.any { it.treeAlive() } && System.nanoTime() < deadline) Thread.sleep(20)
        all.filter { it.treeAlive() }.forEach { it.destroyTree(force = true) }
    }
}

/** Запуск служебного процесса: при выходе из WEX он будет остановлен. */
fun ProcessBuilder.startHelper(): Process = HelperProcesses.track(start())
