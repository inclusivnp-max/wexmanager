package org.wexclient.app.vfs

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymlinkCopyTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_symlink_copy")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    private fun tarHeader(name: String, type: Char, size: Int, link: String): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, len: Int, s: String) = s.toByteArray().let { System.arraycopy(it, 0, h, off, minOf(it.size, len)) }
        fun oct(off: Int, len: Int, v: Long) = put(off, len - 1, v.toString(8).padStart(len - 1, '0'))
        put(0, 100, name)
        oct(100, 8, if (type == '5') 493 else 420)
        oct(108, 8, 1000)
        oct(116, 8, 1000)
        oct(124, 12, size.toLong())
        oct(136, 12, 1_700_000_000)
        h[156] = type.code.toByte()
        put(157, 100, link)
        put(257, 6, "ustar")
        put(263, 2, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        put(148, 6, h.sumOf { it.toInt() and 0xff }.toString(8).padStart(6, '0'))
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    @Test
    fun `links stay links on disk and become content in archives`() {
        val music = Files.createDirectories(dir.resolve("data/Music"))
        Files.writeString(music.resolve("song.mp3"), "la")
        val link = Files.createSymbolicLink(dir.resolve("Music"), Paths.get("data/Music"))
        val usb = Files.createDirectory(dir.resolve("usb"))

        // На диск — ссылкой, а не пустой папкой
        val copied = vfs.copyTo(link, usb.resolve("Music"))
        assertTrue(Files.isSymbolicLink(copied))
        assertEquals(Paths.get("data/Music"), Files.readSymbolicLink(copied))

        // В zip — содержимым
        val zip = dir.resolve("box.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { }
        val root = vfs.openArchive(zip)
        FileTransfers.transfer(vfs, listOf(link), root, move = false).error?.let { throw it }
        assertEquals("la", Files.readString(vfs.live(root).resolve("Music/song.mp3")))

        // Папка со ссылкой внутри — в zip тоже содержимым
        val proj = Files.createDirectories(dir.resolve("proj"))
        Files.createSymbolicLink(proj.resolve("assets"), music)
        FileTransfers.transfer(vfs, listOf(proj), vfs.live(root), move = false).error?.let { throw it }
        assertEquals("la", Files.readString(vfs.live(root).resolve("proj/assets/song.mp3")))

        // Ссылки из tar — на диск ссылками
        val out = ByteArrayOutputStream()
        out.write(tarHeader("pkg/", '5', 0, ""))
        val data = "lib".toByteArray()
        out.write(tarHeader("pkg/libfoo.so.1", '0', data.size, ""))
        out.write(data)
        out.write(ByteArray(512 - data.size))
        out.write(tarHeader("pkg/libfoo.so", '2', 0, "libfoo.so.1"))
        out.write(ByteArray(1024))
        val tar = Files.write(dir.resolve("pkg.tar"), out.toByteArray())
        val troot = vfs.openArchive(tar)
        val dest = Files.createDirectory(dir.resolve("out"))
        FileTransfers.transfer(vfs, listOf(troot.resolve("pkg")), dest, move = false).error?.let { throw it }
        assertTrue(Files.isSymbolicLink(dest.resolve("pkg/libfoo.so")))
        assertEquals("lib", Files.readString(dest.resolve("pkg/libfoo.so")))
        assertFalse(Files.isSymbolicLink(dest.resolve("pkg/libfoo.so.1")))
    }
}
