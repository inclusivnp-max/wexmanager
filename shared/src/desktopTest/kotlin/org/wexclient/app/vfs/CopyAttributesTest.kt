package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopyAttributesTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_copy_attrs")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        // Папки «только чтение» иначе не удалить
        Files.walk(dir).use { s -> s.filter { Files.isDirectory(it) }.forEach { runCatching { it.toFile().setWritable(true) } } }
        dir.toFile().deleteRecursively()
    }

    private fun perms(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    @Test
    fun `private folders stay private and keep their dates`() {
        val src = Files.createDirectories(dir.resolve("src/.ssh"))
        val key = Files.writeString(src.resolve("id_ed25519"), "secret")
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
        val readOnly = Files.createDirectories(dir.resolve("src/docs"))
        Files.writeString(readOnly.resolve("a.txt"), "a")
        val old = FileTime.fromMillis(1_600_000_000_000)
        Files.setLastModifiedTime(key, old)
        Files.setLastModifiedTime(readOnly.resolve("a.txt"), old)
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
        Files.setPosixFilePermissions(src, PosixFilePermissions.fromString("rwx------"))
        Files.setLastModifiedTime(src, old)
        Files.setLastModifiedTime(readOnly, old)

        val out = vfs.copyTo(dir.resolve("src"), dir.resolve("copy"))
        assertEquals("rwx------", perms(out.resolve(".ssh")))
        assertEquals("rw-------", perms(out.resolve(".ssh/id_ed25519")))
        assertEquals("r-xr-xr-x", perms(out.resolve("docs")))
        assertEquals(old, Files.getLastModifiedTime(out.resolve(".ssh")))
        assertEquals(old, Files.getLastModifiedTime(out.resolve(".ssh/id_ed25519")))
        assertEquals(old, Files.getLastModifiedTime(out.resolve("docs")))
        assertEquals(old, Files.getLastModifiedTime(out.resolve("docs/a.txt")))
        assertEquals("a", Files.readString(out.resolve("docs/a.txt")))
    }

    @Test
    fun `copy into tar keeps folder permissions and merge leaves existing folders alone`() {
        val src = Files.createDirectories(dir.resolve("pkg/bin"))
        Files.writeString(src.resolve("tool"), "#!/bin/sh\n")
        Files.setPosixFilePermissions(src.resolve("tool"), PosixFilePermissions.fromString("rwxr-x---"))
        Files.setPosixFilePermissions(src, PosixFilePermissions.fromString("rwxr-x---"))
        val tar = dir.resolve("out.tar")
        val code = ProcessBuilder("tar", "-cf", tar.toString(), "--files-from", "/dev/null").start().waitFor()
        assertEquals(0, code)
        val root = vfs.openArchive(tar)
        vfs.copyTo(dir.resolve("pkg"), root.resolve("pkg"))
        val reopened = vfs.live(root)
        assertEquals("rwxr-x---", perms(reopened.resolve("pkg/bin")))
        assertEquals("rwxr-x---", perms(reopened.resolve("pkg/bin/tool")))

        // Слияние в существующую папку на диске: её права не меняются
        val existing = Files.createDirectories(dir.resolve("merge/pkg/bin"))
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxrwxr-x"))
        vfs.copyTo(dir.resolve("pkg"), dir.resolve("merge/pkg"), overwrite = true)
        assertEquals("rwxrwxr-x", perms(existing))
        assertTrue(Files.exists(existing.resolve("tool")))
    }
}
