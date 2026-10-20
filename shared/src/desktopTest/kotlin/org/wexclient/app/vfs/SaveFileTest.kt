package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveFileTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_save")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    private fun leftovers() = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".wex-save") }.toList() }

    @Test
    fun `save keeps permissions, symlinks and hard links`() {
        val script = Files.writeString(dir.resolve("run.sh"), "echo old\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"))
        vfs.writeBytes(script, "echo new\n".toByteArray())
        assertEquals("echo new\n", Files.readString(script))
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))

        // Ссылка остаётся ссылкой, меняется файл, на который она указывает
        val real = Files.writeString(dir.resolve("real.conf"), "a=1")
        val link = Files.createSymbolicLink(dir.resolve("link.conf"), real.fileName)
        vfs.writeText(link, "a=2")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("a=2", Files.readString(real))

        // Жёсткая ссылка: переписываем на месте, чтобы обе видели новое
        val hard = Files.createLink(dir.resolve("hard.conf"), real)
        vfs.writeText(real, "a=3")
        assertEquals("a=3", Files.readString(hard))

        // Новый файл
        val fresh = dir.resolve("new.txt")
        vfs.writeText(fresh, "привет")
        assertEquals("привет", Files.readString(fresh))
        assertEquals(emptyList(), leftovers())
    }
}
