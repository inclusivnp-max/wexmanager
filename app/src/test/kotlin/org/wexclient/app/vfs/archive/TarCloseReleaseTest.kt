package org.wexclient.app.vfs.archive

import java.nio.file.ClosedFileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Закрытый tar не держит дерево в памяти и на обращения отвечает ClosedFileSystemException. */
class TarCloseReleaseTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_tar_close")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `closed tar drops its tree and fails cleanly`() {
        val tree = dir.resolve("tree")
        Files.createDirectories(tree.resolve("sub"))
        Files.writeString(tree.resolve("sub/a.txt"), "a")
        Files.writeString(tree.resolve("b.txt"), "b")
        val tar = dir.resolve("x.tar")
        val p = ProcessBuilder("tar", "-cf", tar.toString(), "-C", tree.toString(), ".").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        assertEquals(0, p.waitFor())

        val fs = TarFileSystemProvider.INSTANCE.newFileSystem(tar, emptyMap<String, Any>()) as TarFileSystem
        val a = fs.getPath("/sub/a.txt")
        assertEquals("a", Files.readString(a))
        assertTrue(fs.root.children!!.isNotEmpty())

        fs.close()
        assertFalse(fs.isOpen)
        assertTrue(fs.root.children!!.isEmpty())

        assertFailsWith<ClosedFileSystemException> { Files.readString(a) }
        assertFailsWith<ClosedFileSystemException> { Files.readAttributes(a, BasicFileAttributes::class.java) }
        assertFailsWith<ClosedFileSystemException> { Files.newDirectoryStream(fs.getPath("/")).close() }
        assertFailsWith<ClosedFileSystemException> { Files.writeString(fs.getPath("/c.txt"), "c") }
        assertFailsWith<ClosedFileSystemException> { Files.delete(a) }
        // Повторное закрытие — без ошибок
        fs.close()
    }
}
