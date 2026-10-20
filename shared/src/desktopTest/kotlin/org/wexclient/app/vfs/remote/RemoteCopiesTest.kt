package org.wexclient.app.vfs.remote

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Копии файлов с серверов и телефонов не копятся без конца, но и не пропадают из-под просмотра. */
class RemoteCopiesTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_remote_copies")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val now = System.currentTimeMillis()

    // Процесс запущен час назад: всё скачанное раньше — копии прошлых запусков
    private val since = now - 3600_000L

    private fun copy(rel: String, ageMs: Long, size: Int = 10): Path {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, ByteArray(size))
        val t = FileTime.fromMillis(now - ageMs)
        Files.getFileAttributeView(p, BasicFileAttributeView::class.java).setTimes(t, t, null)
        return p
    }

    @Test
    fun `old copies go, this session's and open ones stay`() {
        val day = 24 * 3600_000L
        val ancient = copy("host/a/old.pdf", 8 * day)
        val openAncient = copy("host/b/open.pdf", 8 * day)
        val previous = copy("host/c/prev.mp4", 2 * 3600_000L)
        val current = copy("phone/d/new.jpg", 60_000L)
        val canSeeOpen = Files.isDirectory(Paths.get("/proc/self/fd"))
        Files.newInputStream(openAncient).use {
            RemoteCopies.prune(dir, now, since)
        }
        assertFalse(Files.exists(ancient), "копия старше недели осталась")
        assertFalse(Files.exists(dir.resolve("host/a")), "пустая папка осталась")
        if (canSeeOpen) assertTrue(Files.exists(openAncient), "удалён открытый файл")
        assertTrue(Files.exists(previous), "свежая копия прошлого запуска удалена без нужды")
        assertTrue(Files.exists(current), "копия этого запуска удалена")
    }

    @Test
    fun `over the size limit the oldest copies of past sessions go first`() {
        val older = copy("host/a/1.bin", 3 * 3600_000L, size = 100)
        val newer = copy("host/a/2.bin", 2 * 3600_000L, size = 100)
        val current = copy("host/a/3.bin", 60_000L, size = 100)
        RemoteCopies.prune(dir, now, since, maxTotal = 250)
        assertFalse(Files.exists(older))
        assertTrue(Files.exists(newer))
        assertTrue(Files.exists(current))
        // Даже если копии этого запуска сами больше предела — их не трогаем
        RemoteCopies.prune(dir, now, since, maxTotal = 10)
        assertFalse(Files.exists(newer))
        assertTrue(Files.exists(current))
    }
}
