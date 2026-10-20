package com.tim.csmali.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Параллельная обработка независимых элементов (только API Java 8, работает и на Android). */
internal object Parallel {

    /** [action] для каждого индекса `0 until count`; первое исключение пробрасывается вызывающему. */
    fun forEach(count: Int, action: (Int) -> Unit) {
        val workers = minOf(Runtime.getRuntime().availableProcessors(), count, 32)
        if (workers <= 1) {
            for (i in 0 until count) action(i)
            return
        }
        val next = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val task = Runnable {
            try {
                while (failure.get() == null) {
                    val i = next.getAndIncrement()
                    if (i >= count) break
                    action(i)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        // Стек как у -Xss16m: разбор вложенных значений аннотаций рекурсивен.
        val threads = Array(workers - 1) { Thread(null, task, "csmali-$it", 16L shl 20).apply { isDaemon = true; start() } }
        task.run()
        for (t in threads) t.join()
        failure.get()?.let { throw it }
    }

    fun <T, R> map(items: List<T>, transform: (T) -> R): List<R> {
        val out = arrayOfNulls<Any?>(items.size)
        forEach(items.size) { out[it] = transform(items[it]) }
        @Suppress("UNCHECKED_CAST")
        return (out as Array<R>).asList()
    }
}
