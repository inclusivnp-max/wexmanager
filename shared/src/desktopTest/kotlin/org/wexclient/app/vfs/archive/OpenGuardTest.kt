package org.wexclient.app.vfs.archive

import org.wexclient.app.vfs.VfsManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenGuardTest {
    private lateinit var dir: Path
    private val cacheDir get() = ArchiveCache.dir

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_open_guard")
    }

    @AfterTest
    fun tearDown() {
        ArchiveFormats.floorForTests = null
        dir.toFile().deleteRecursively()
    }

    private fun cacheFiles(): Set<String> =
        if (Files.isDirectory(cacheDir)) Files.list(cacheDir).use { s -> s.map { it.fileName.toString() }.toList().toSet() } else emptySet()

    /** tar с одним файлом из нулей: сжимается в килобайты. */
    private fun zeroTar(size: Int): Path {
        val src = dir.resolve("src").also { Files.createDirectories(it) }
        Files.write(src.resolve("zeros.bin"), ByteArray(size))
        val tar = dir.resolve("plain.tar")
        val code = ProcessBuilder("tar", "-cf", tar.toString(), "-C", src.toString(), "zeros.bin")
            .redirectErrorStream(true).start().waitFor()
        assertEquals(0, code)
        return tar
    }

    @Test
    fun `cancelled opening leaves no temp files`() {
        val tar = zeroTar(8 shl 20)
        val xz = dir.resolve("data.tar.xz")
        assertEquals(0, ProcessBuilder("sh", "-c", "xz -c '$tar' > '$xz'").start().waitFor())
        val gz = dir.resolve("data.tar.gz")
        GZIPOutputStream(Files.newOutputStream(gz)).use { Files.copy(tar, it) }
        val before = cacheFiles()
        val vfs = VfsManager()
        for (archive in listOf(xz, gz)) {
            val e = assertFailsWith<java.io.InterruptedIOException> { vfs.openArchive(archive, cancelled = { true }) }
            assertEquals("Открытие отменено", e.message)
        }
        assertEquals(before, cacheFiles())
        // Без отмены открывается как обычно
        val root = vfs.openArchive(xz)
        assertEquals(8L shl 20, Files.size(root.resolve("zeros.bin")))
        vfs.closeAll()
        assertEquals(before, cacheFiles())
    }

    @Test
    fun `decompression stops when disk space runs out`() {
        val tar = zeroTar(16 shl 20)
        val gz = dir.resolve("bomb.tar.gz")
        GZIPOutputStream(Files.newOutputStream(gz)).use { Files.copy(tar, it) }
        val zst = dir.resolve("bomb.tar.zst")
        assertEquals(0, ProcessBuilder("sh", "-c", "zstd -q -c '$tar' > '$zst'").start().waitFor())
        val before = cacheFiles()
        // «Свободно меньше порога» — как если бы архив разворачивался в терабайты
        ArchiveFormats.floorForTests = Long.MAX_VALUE
        val vfs = VfsManager()
        for (archive in listOf(gz, zst)) {
            val e = assertFailsWith<java.io.IOException> { vfs.openArchive(archive) }
            assertTrue(e.message!!.startsWith("Недостаточно места на диске"), e.message)
        }
        assertEquals(before, cacheFiles())
    }
}
