package org.wexclient.app.util

/**
 * Отметки времени запуска: от старта JVM до первого кадра и до готового списка файлов.
 * Включается переменной окружения WEX_STARTUP_LOG=1 — обычный запуск ничего не печатает.
 */
object Startup {
    private val on: Boolean = System.getenv("WEX_STARTUP_LOG") != null

    /** Чтобы не собирать строку отметки там, где её всё равно никто не напечатает. */
    val enabled: Boolean get() = on
    private val runtime by lazy { java.lang.management.ManagementFactory.getRuntimeMXBean() }
    private var last = 0L

    fun mark(what: String) {
        if (!on) return
        val uptime = runtime.uptime
        val delta = uptime - last
        last = uptime
        println(String.format("[старт] %-34s %6d мс от старта JVM (+%d)", what, uptime, delta))
    }
}
