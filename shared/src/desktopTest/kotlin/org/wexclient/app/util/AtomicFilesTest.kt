package org.wexclient.app.util

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicFilesTest {
    private val root = Files.createTempDirectory("wex-atomic-files")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `successful write replaces the whole file`() {
        val target = root.resolve("settings.txt").toFile().apply { writeText("old") }
        AtomicFiles.write(target) { it.write("new".toByteArray()) }
        assertEquals("new", target.readText())
        assertEquals(listOf("settings.txt"), root.toFile().list()!!.toList())
    }

    @Test
    fun `failed write preserves the original and removes temporary file`() {
        val target = root.resolve("settings.txt").toFile().apply { writeText("old") }
        assertFailsWith<IOException> {
            AtomicFiles.write(target) {
                it.write("partial".toByteArray())
                throw IOException("write failed")
            }
        }
        assertEquals("old", target.readText())
        assertEquals(listOf("settings.txt"), root.toFile().list()!!.toList())
    }

    @Test
    fun `parent directories are created`() {
        val target = root.resolve("nested/settings.txt").toFile()
        AtomicFiles.write(target) { it.write("text".toByteArray()) }
        assertEquals("text", target.readText())
    }

    @Test
    fun `new settings file is private on POSIX filesystems`() {
        if ("posix" !in root.fileSystem.supportedFileAttributeViews()) return
        val target = root.resolve("settings.txt").toFile()
        AtomicFiles.write(target) { it.write("text".toByteArray()) }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
    }
}
