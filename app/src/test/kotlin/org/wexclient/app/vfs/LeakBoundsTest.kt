package org.wexclient.app.vfs

import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Кэши и служебные файлы, которые раньше росли весь срок жизни процесса. */
class LeakBoundsTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_leaks")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `failed thumbnail keys stay bounded`() = runBlocking {
        val bad = Files.writeString(dir.resolve("broken.png"), "not an image")
        repeat(MediaThumbnailLoader.FAILED_ENTRIES + 50) { i ->
            assertNull(MediaThumbnailLoader.loadThumbnail(bad, isImage = true, isVideo = false, lastModified = i.toLong()))
        }
        assertEquals(MediaThumbnailLoader.FAILED_ENTRIES, MediaThumbnailLoader.failedCount())
    }

    @Test
    fun `failed apk icon keys stay bounded`() = runBlocking {
        val bad = Files.writeString(dir.resolve("broken.apk"), "not a zip")
        repeat(ApkIconLoader.FAILED_ENTRIES + 50) { i ->
            assertNull(ApkIconLoader.loadIcon(bad, i.toLong()))
        }
        assertEquals(ApkIconLoader.FAILED_ENTRIES, ApkIconLoader.failedCount())
    }

    @Test
    fun `thumbnail disk cache is pruned while the process runs`() = runBlocking {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val src = dir.resolve("p.png").also { ImageIO.write(img, "png", it.toFile()) }
        // Первая миниатюра: разовая уборка при запуске уже позади
        assertNotNull(MediaThumbnailLoader.loadThumbnail(src, isImage = true, isVideo = false, lastModified = -1L))
        val cache = File(System.getProperty("user.home"), ".cache/wexdesktop/thumbnails")
        val before = cache.list()!!.toSet()
        // Старые файлы сверх предела (5000)
        val filler = (0 until 6000).map { i ->
            File(cache, "filler-$i.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000_000L)
            }
        }
        try {
            repeat(500) { i ->
                assertNotNull(MediaThumbnailLoader.loadThumbnail(src, isImage = true, isVideo = false, lastModified = 1_000L + i))
            }
            // Уборка прошла на одной из 500 записей: не больше 5000 + 499
            assertTrue(MediaThumbnailLoader.diskCacheFiles() < 5500, "в кэше ${MediaThumbnailLoader.diskCacheFiles()} файлов")
        } finally {
            filler.forEach { it.delete() }
            cache.listFiles()?.filter { it.name !in before }?.forEach { it.delete() }
        }
    }

    private fun backup(name: String): ArchiveBackup {
        val slot = Files.createDirectories(dir.resolve("undo/$name"))
        val copy = Files.writeString(slot.resolve("f.txt"), name)
        return ArchiveBackup(dir.resolve("archive.zip/$name"), copy)
    }

    private fun waitGone(p: Path) {
        val until = System.currentTimeMillis() + 5000
        while (Files.exists(p) && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test
    fun `evicted undo steps discard their archive backups`() {
        val stack = FileUndoStack(limit = 2)
        val old = backup("old")
        val kept = backup("kept")
        stack.push(FileOp.Copied(listOf(dir.resolve("a")), archiveReplaced = listOf(old)))
        stack.push(FileOp.Moved(listOf(dir.resolve("b") to dir.resolve("c")), archiveReplaced = listOf(kept)))
        stack.push(FileOp.Created(dir.resolve("d")))
        assertEquals(2, stack.size)
        waitGone(old.copy.parent)
        assertFalse(Files.exists(old.copy.parent))
        assertTrue(Files.exists(kept.copy))

        stack.clear()
        assertEquals(0, stack.size)
        waitGone(kept.copy.parent)
        assertFalse(Files.exists(kept.copy.parent))
    }

    @Test
    fun `closed manager window leaves no pending path`() {
        val windows = org.wexclient.app.ui.WexWindows
        windows.openManager(null)
        val window = windows.managers.last()
        try {
            windows.openManager(dir)
            assertNotNull(windows.pendingFor(window.id))
        } finally {
            windows.close(window)
        }
        assertNull(windows.pendingFor(window.id))
    }

    private fun zipWith(name: String, text: String): Path {
        val zip = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("x.txt"))
            out.write(text.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    // Отдельной функцией: промежуточная ФС не остаётся в локальных переменных теста
    private fun weakFsOf(vfs: VfsManager, p: Path): WeakReference<FileSystem> = WeakReference(vfs.live(p).fileSystem)

    @Test
    fun `old archive path reaches the latest version without pinning the ones between`() {
        val vfs = VfsManager()
        try {
            val first = vfs.openArchive(zipWith("a.zip", "0")).resolve("x.txt")
            vfs.writeText(first, "1")
            val middle = weakFsOf(vfs, first)
            vfs.writeText(first, "2")
            vfs.writeText(first, "3")
            val now = vfs.live(first)
            assertTrue(now.fileSystem.isOpen)
            assertEquals("3", Files.readString(now))
            var tries = 0
            while (middle.get() != null && tries++ < 50) {
                System.gc()
                Thread.sleep(20)
            }
            assertNull(middle.get(), "промежуточная версия архива осталась в памяти")
            // Старый путь по-прежнему ведёт в текущую версию
            assertEquals("3", Files.readString(vfs.live(first)))
        } finally {
            vfs.closeAll()
        }
    }
}
