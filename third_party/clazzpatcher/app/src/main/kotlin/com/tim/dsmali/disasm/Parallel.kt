package com.tim.dsmali.disasm

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Параллельная обработка независимых элементов с сохранением порядка результатов.
 * Только Java 8 API (работает и на Android). Первое исключение пробрасывается вызывающему,
 * остальные потоки прекращают работу.
 */
object Parallel {
    /** Число потоков по умолчанию. */
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 32)

    /** [transform] для каждого индекса `0 until count`; результат — в порядке индексов. */
    fun <R> map(count: Int, transform: (Int) -> R): List<R> {
        val out = arrayOfNulls<Any?>(count)
        forEach(count) { i -> out[i] = transform(i) }
        @Suppress("UNCHECKED_CAST")
        return (out as Array<R>).asList()
    }

    fun forEach(count: Int, action: (Int) -> Unit) {
        val workers = minOf(threads, count)
        if (workers <= 1) {
            for (i in 0 until count) action(i)
            return
        }
        // Небольшие порции: классы сильно различаются по размеру, так нагрузка ровнее.
        val chunk = maxOf(1, minOf(64, count / (workers * 16)))
        val next = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val task = Runnable {
            try {
                while (failure.get() == null) {
                    val start = next.getAndAdd(chunk)
                    if (start >= count) break
                    val end = minOf(count, start + chunk)
                    for (i in start until end) action(i)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val pool = Array(workers - 1) { n ->
            Thread(null, task, "clazzpatcher-worker-$n", STACK_SIZE).apply { isDaemon = true; start() }
        }
        task.run()
        for (t in pool) {
            while (true) {
                try {
                    t.join()
                    break
                } catch (e: InterruptedException) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        failure.get()?.let { throw it }
    }

    // Как -Xss16m у приложения: разбор вложенных конструкций может быть рекурсивным.
    private const val STACK_SIZE = 16L * 1024 * 1024
}
