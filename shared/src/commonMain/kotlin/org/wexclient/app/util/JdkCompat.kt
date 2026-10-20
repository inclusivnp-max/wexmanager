package org.wexclient.app.util

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/*
 * Замены API JDK 9–17, которых нет в Android до 13–14 (minSdk 26). Ведут себя так же, как оригиналы;
 * D8 подменяет часть таких вызовов сам, а эти — нет.
 */

/** Как InputStream.readNBytes(n) (Java 11): до [n] байт; меньше — только если поток кончился. */
fun InputStream.readUpTo(n: Int): ByteArray {
    require(n >= 0) { "n < 0" }
    var buf = ByteArray(minOf(n, 8192))
    var total = 0
    while (total < n) {
        if (total == buf.size) buf = buf.copyOf(minOf(n, maxOf(buf.size * 2, 8192)))
        val r = read(buf, total, minOf(buf.size, n) - total)
        if (r < 0) break
        total += r
    }
    return if (total == buf.size) buf else buf.copyOf(total)
}

/** Как InputStream.readNBytes(b, off, len) (Java 9): читает, пока не наберёт [len] или не кончится поток. */
fun InputStream.readInto(b: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val r = read(b, off + total, len - total)
        if (r < 0) break
        total += r
    }
    return total
}

/** Как InputStream.skipNBytes(n) (Java 12): пропустить ровно [n] байт, иначе EOFException. */
fun InputStream.skipExactly(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s > 0) {
            left -= s
            continue
        }
        if (read() < 0) throw EOFException()
        left--
    }
}

/** Как OutputStream.nullOutputStream() (Java 11). */
object NullOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

/** Как Arrays.mismatch(a, aFrom, aTo, b, bFrom, bTo) (Java 9): первый различающийся индекс или -1. */
fun mismatch(a: ByteArray, aFrom: Int, aTo: Int, b: ByteArray, bFrom: Int, bTo: Int): Int {
    val len = minOf(aTo - aFrom, bTo - bFrom)
    for (i in 0 until len) if (a[aFrom + i] != b[bFrom + i]) return i
    return if (aTo - aFrom == bTo - bFrom) -1 else len
}

/**
 * Действие после того, как объект собран сборщиком мусора, — как java.lang.ref.Cleaner (в Android он только
 * с 13-й версии). Одна фоновая нить на очередь; [Cleanable.clean] выполняет действие сразу и один раз.
 */
class RefCleaner(threadName: String) {
    fun interface Cleanable {
        fun clean()
    }

    private val queue = ReferenceQueue<Any>()
    private val live = ConcurrentHashMap.newKeySet<Ref>()

    private inner class Ref(obj: Any, private val action: Runnable) : PhantomReference<Any>(obj, queue), Cleanable {
        override fun clean() {
            if (live.remove(this)) {
                clear()
                runCatching { action.run() }
            }
        }
    }

    init {
        Thread({
            while (true) {
                val ref = runCatching { queue.remove() }.getOrNull() ?: continue
                (ref as? Cleanable)?.clean()
            }
        }, threadName).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun register(obj: Any, action: Runnable): Cleanable = Ref(obj, action).also { live += it }
}
