package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirWatcherTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_watch")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `changes are reported once per burst and stop after close`() {
        val calls = AtomicInteger()
        val before = DirWatcher.watchedCount()
        val handle = DirWatcher.watch(dir) { calls.incrementAndGet() }
        assertEquals(before + 1, DirWatcher.watchedCount())

        Files.writeString(dir.resolve("a.txt"), "a")
        assertTrue(waitFor { calls.get() == 1 })

        // Пачка изменений — одно-два уведомления, а не сотня
        calls.set(0)
        repeat(100) { Files.writeString(dir.resolve("f$it.txt"), "x") }
        assertTrue(waitFor { calls.get() >= 1 })
        Thread.sleep(DirWatcher.QUIET_MS * 3)
        assertTrue(calls.get() in 1..3, "уведомлений: ${calls.get()}")

        // Второй подписчик на ту же папку и снятие первого
        val second = AtomicInteger()
        val handle2 = DirWatcher.watch(dir) { second.incrementAndGet() }
        assertEquals(before + 1, DirWatcher.watchedCount())
        handle.close()
        calls.set(0)
        Files.delete(dir.resolve("a.txt"))
        assertTrue(waitFor { second.get() == 1 })
        assertEquals(0, calls.get())

        handle2.close()
        assertEquals(before, DirWatcher.watchedCount())
        Files.writeString(dir.resolve("b.txt"), "b")
        Thread.sleep(DirWatcher.QUIET_MS * 3)
        assertEquals(1, second.get())
    }

    @Test
    fun `removed folder is reported and forgotten`() {
        val sub = Files.createDirectory(dir.resolve("sub"))
        val calls = AtomicInteger()
        val before = DirWatcher.watchedCount()
        DirWatcher.watch(sub) { calls.incrementAndGet() }
        Files.delete(sub)
        assertTrue(waitFor { calls.get() >= 1 })
        assertTrue(waitFor { DirWatcher.watchedCount() == before })
    }
}
