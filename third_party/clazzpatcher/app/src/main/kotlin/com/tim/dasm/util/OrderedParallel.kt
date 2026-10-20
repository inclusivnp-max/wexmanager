package com.tim.dasm.util

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Параллельная обработка с потреблением результатов строго по порядку: [run] вызывает `produce`
 * в рабочих потоках (не дальше окна элементов впереди потребителя — память ограничена), а `consume` —
 * в вызывающем потоке по порядку элементов. Исключение `produce` пробрасывается, когда потребитель
 * доходит до этого элемента, — как при последовательной обработке; остальная работа прекращается.
 */
object OrderedParallel {

    /** Как -Xss16m у приложения: разбор вложенных конструкций может быть рекурсивным. */
    private const val STACK_SIZE = 16L * 1024 * 1024

    /** Элементов в работе и готовых на один рабочий поток. */
    private const val WINDOW_PER_WORKER = 8

    private class Failure(val error: Throwable)

    /** [threads] — всего потоков вместе с вызывающим; `1` — всё в вызывающем потоке. */
    fun <T, R> run(items: List<T>, threads: Int, produce: (T) -> R, consume: (T, R) -> Unit) {
        val workers = minOf(threads - 1, items.size - 1)
        if (workers < 1) {
            for (item in items) consume(item, produce(item))
            return
        }
        val window = workers * WINDOW_PER_WORKER
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        val results = arrayOfNulls<Any>(items.size)
        val ready = BooleanArray(items.size)
        var claimed = 0
        var consumed = 0
        var stop = false

        val task = Runnable {
            while (true) {
                val i = lock.withLock {
                    while (!stop && claimed < items.size && claimed >= consumed + window) changed.awaitUninterruptibly()
                    if (stop || claimed >= items.size) return@Runnable
                    claimed++
                }
                val result: Any? = try {
                    produce(items[i])
                } catch (t: Throwable) {
                    Failure(t)
                }
                lock.withLock {
                    results[i] = result
                    ready[i] = true
                    changed.signalAll()
                }
            }
        }
        val pool = Array(workers) { n ->
            Thread(null, task, "clazzpatcher-build-$n", STACK_SIZE).apply { isDaemon = true; start() }
        }
        try {
            for (i in items.indices) {
                val result = lock.withLock {
                    while (!ready[i]) changed.awaitUninterruptibly()
                    val r = results[i]
                    results[i] = null
                    consumed = i + 1
                    changed.signalAll()
                    r
                }
                if (result is Failure) throw result.error
                @Suppress("UNCHECKED_CAST")
                consume(items[i], result as R)
            }
        } finally {
            lock.withLock {
                stop = true
                changed.signalAll()
            }
            var interrupted = false
            for (t in pool) {
                while (true) {
                    try {
                        t.join()
                        break
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
