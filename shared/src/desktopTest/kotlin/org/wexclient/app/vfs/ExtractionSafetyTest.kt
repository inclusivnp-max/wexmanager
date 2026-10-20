package org.wexclient.app.vfs

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractionSafetyTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_extract_safety").toRealPath()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        org.wexclient.app.util.FileTree.delete(dir)
    }

    /** Простой USTAR: файлы ('0'), папки ('5'), ссылки ('2'). */
    private class Tar {
        val out = ByteArrayOutputStream()

        private fun header(name: String, type: Char, size: Int, link: String, mode: Int): ByteArray {
            val h = ByteArray(512)
            fun put(off: Int, len: Int, s: String) {
                val b = s.toByteArray()
                System.arraycopy(b, 0, h, off, minOf(b.size, len))
            }
            fun oct(off: Int, len: Int, v: Long) = put(off, len - 1, v.toString(8).padStart(len - 1, '0'))
            put(0, 100, name)
            oct(100, 8, mode.toLong())
            oct(108, 8, 1000)
            oct(116, 8, 1000)
            oct(124, 12, size.toLong())
            oct(136, 12, 1_700_000_000)
            h[156] = type.code.toByte()
            put(157, 100, link)
            put(257, 6, "ustar")
            put(263, 2, "00")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, 6, sum.toString(8).padStart(6, '0'))
            h[154] = 0
            h[155] = ' '.code.toByte()
            return h
        }

        fun file(name: String, text: String) {
            val data = text.toByteArray()
            out.write(header(name, '0', data.size, "", 420))
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }

        fun dir(name: String) = out.write(header(name, '5', 0, "", 493))

        fun link(name: String, target: String) = out.write(header(name, '2', 0, target, 511))

        fun save(path: Path): Path {
            out.write(ByteArray(1024))
            Files.write(path, out.toByteArray())
            return path
        }
    }

    @Test
    fun `tar links cannot redirect writes outside the destination`() {
        val outside = Files.createDirectory(dir.resolve("outside"))
        val dest = dir.resolve("dest")
        val tar = Tar().apply {
            dir("lib/")
            file("lib/libfoo.so.1", "lib")
            link("lib/libfoo.so", "libfoo.so.1")
            dir("bin/")
            link("bin/tool", "../lib/libfoo.so.1")
            link("abs", "/etc/passwd")
            link("escape", "../outside")
            // Цепочка «a → .», «a/b → ..» и файл под ними: TarFileSystem делает a и a/b папками
            link("a", ".")
            link("a/b", "..")
            file("a/b/outside/evil.txt", "evil")
            file("c/ok.txt", "ok")
        }.save(dir.resolve("evil.tar"))

        runCatching { vfs.extractViaTarFs(tar, dest, null) }

        assertFalse(Files.exists(outside.resolve("evil.txt")), "запись ушла за папку назначения")
        assertEquals(0, Files.list(outside).use { it.count() })
        assertEquals("lib", Files.readString(dest.resolve("lib/libfoo.so.1")))
        assertTrue(Files.isSymbolicLink(dest.resolve("lib/libfoo.so")))
        assertEquals("lib", Files.readString(dest.resolve("bin/tool")))
        assertFalse(Files.exists(dest.resolve("abs"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(dest.resolve("escape"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("ok", Files.readString(dest.resolve("c/ok.txt")))
        // Что бы ни осталось от цепочки, наружу она не ведёт
        val a = dest.resolve("a")
        if (Files.exists(a, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertTrue(a.toRealPath().startsWith(dest.toRealPath()))
        }
    }

    @Test
    fun `existing link in the destination does not redirect extraction`() {
        val outside = Files.createDirectory(dir.resolve("outside"))
        val dest = Files.createDirectory(dir.resolve("dest"))
        Files.createSymbolicLink(dest.resolve("out"), outside)

        val tar = Tar().apply { file("out/evil.txt", "evil") }.save(dir.resolve("a.tar"))
        assertFailsWith<SecurityException> { vfs.extractViaTarFs(tar, dest, null) }
        assertFalse(Files.exists(outside.resolve("evil.txt")))

        val zip = dir.resolve("a.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("out/evil.txt"))
            z.write("evil".toByteArray())
            z.closeEntry()
        }
        assertFailsWith<SecurityException> { vfs.extractArchive(zip, dest) }
        assertFalse(Files.exists(outside.resolve("evil.txt")))

        val slip = dir.resolve("slip.zip")
        ZipOutputStream(Files.newOutputStream(slip)).use { z ->
            z.putNextEntry(ZipEntry("../outside/slip.txt"))
            z.write("slip".toByteArray())
            z.closeEntry()
        }
        assertFailsWith<SecurityException> { vfs.extractArchive(slip, dest) }
        assertFalse(Files.exists(outside.resolve("slip.txt")))

        // Обычный архив распаковывается как раньше
        val good = dir.resolve("good.zip")
        ZipOutputStream(Files.newOutputStream(good)).use { z ->
            z.putNextEntry(ZipEntry("docs/readme.txt"))
            z.write("hi".toByteArray())
            z.closeEntry()
        }
        vfs.extractArchive(good, dest)
        assertEquals("hi", Files.readString(dest.resolve("docs/readme.txt")))
        assertTrue(Paths.get(dest.resolve("docs").toString()).toRealPath().startsWith(dest))
    }
}
