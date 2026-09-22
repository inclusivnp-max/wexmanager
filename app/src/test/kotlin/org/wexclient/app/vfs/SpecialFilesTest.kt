package org.wexclient.app.vfs

import org.wexclient.app.vfs.archive.ArchiveFormats
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SpecialFilesTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_special")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    /** Зависание — провал теста, а не вечный прогон (поток-демон останется ждать канал). */
    private fun <T> within(ms: Long, block: () -> T): Result<T> {
        var result: Result<T>? = null
        val t = Thread { result = runCatching(block) }.apply {
            isDaemon = true
            start()
        }
        t.join(ms)
        return result ?: fail("зависло дольше $ms мс")
    }

    private fun mkfifo(name: String): Path {
        val p = dir.resolve(name)
        val proc = ProcessBuilder("mkfifo", p.toString()).start()
        assertEquals(0, proc.waitFor())
        return p
    }

    @Test
    fun `named pipes are listed as special and never read`() {
        if (!ArchiveFormats.toolAvailable("mkfifo")) return
        val pipe = mkfifo("console.in")
        mkfifo("trap.png")
        mkfifo("trap.zip")
        val regular = dir.resolve("server.properties")
        Files.writeString(regular, "motd=hi")

        val entries = within(5000) { vfs.listEntries(dir) }.getOrThrow().associateBy { it.name }
        for (name in listOf("console.in", "trap.png", "trap.zip")) {
            val e = entries.getValue(name)
            assertEquals(VfsItemType.SPECIAL, e.itemType, name)
            assertFalse(e.isArchive || e.isExtractableArchive || e.isImage || e.isMedia, name)
            assertEquals(0L, e.size)
        }
        assertEquals(VfsItemType.PROPERTIES_CONF, entries.getValue("server.properties").itemType)

        assertEquals("именованный канал (FIFO)", vfs.specialFileKind(pipe))
        assertNull(vfs.specialFileKind(regular))
        assertNull(vfs.specialFileKind(dir))
        assertEquals("символьное устройство", vfs.specialFileKind(Path.of("/dev/null")))
        // Ссылка на канал — тоже канал
        val link = Files.createSymbolicLink(dir.resolve("link.in"), pipe)
        assertEquals("именованный канал (FIFO)", vfs.specialFileKind(link))

        // Чтение — сразу ошибка, а не ожидание писателя
        for (read in listOf<() -> Any>(
            { vfs.readBytes(pipe) },
            { vfs.readBytes(pipe) { _, _ -> } },
            { vfs.readHead(pipe, 16) },
            { vfs.readText(link) },
            { vfs.readBytes(Path.of("/dev/zero")) }
        )) {
            val error = within(3000, read).exceptionOrNull()
            assertTrue(error is IOException && "не читается" in error.message.orEmpty(), error.toString())
        }

        // Копирование канала — снова канал, без чтения; папка с каналом — тоже
        val target = Files.createDirectory(dir.resolve("copy"))
        within(5000) { vfs.copyEntry(pipe, target) }.getOrThrow()
        assertEquals("именованный канал (FIFO)", vfs.specialFileKind(target.resolve("console.in")))
        val server = Files.createDirectory(dir.resolve("server"))
        Files.writeString(server.resolve("eula.txt"), "eula=true")
        assertEquals(0, ProcessBuilder("mkfifo", server.resolve("console.in").toString()).start().waitFor())
        within(5000) { vfs.copyEntry(server, target) }.getOrThrow()
        assertEquals("eula=true", Files.readString(target.resolve("server/eula.txt")))
        assertEquals("именованный канал (FIFO)", vfs.specialFileKind(target.resolve("server/console.in")))

        // Архив папки с каналом: zip без канала, не зависая
        val zip = dir.resolve("backup.zip")
        within(5000) { vfs.compressEntries(listOf(server), zip, "zip") }.getOrThrow()
        val names = java.util.zip.ZipFile(zip.toFile()).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("server/", "server/eula.txt"), names.sorted())
    }
}
