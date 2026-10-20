package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveSafetyTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory(Path.of(System.getProperty("user.home")).also { Files.createDirectories(it) }, "wex_arch_safety")
        Trash.empty()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Trash.empty()
        dir.toFile().walkTopDown().forEach { it.setWritable(true) }
        dir.toFile().deleteRecursively()
    }

    private fun zip(path: Path, vararg entries: Pair<String, String?>): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (text != null) z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return path
    }

    private fun names(zip: Path): Set<String> = ZipFile(zip.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun `moving into an archive that cannot be saved keeps the originals`() {
        val archDir = Files.createDirectory(dir.resolve("arch"))
        val archive = zip(archDir.resolve("box.zip"), "old.txt" to "old")
        val src = Files.createDirectory(dir.resolve("src"))
        val a = Files.writeString(src.resolve("a.txt"), "a")
        val b = Files.writeString(src.resolve("b.txt"), "b")
        val root = vfs.openArchive(archive)
        // Папка архива только для чтения: zipfs не сможет записать архив при сохранении
        Files.setPosixFilePermissions(archDir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val r = FileTransfers.transfer(vfs, listOf(a, b), root, move = true)
            assertNotNull(r.error)
            assertTrue(Files.exists(a) && Files.exists(b), "исходники удалены, хотя архив не записан")
            assertTrue(r.done.isEmpty(), "ничего не перемещено: ${r.done}")
        } finally {
            Files.setPosixFilePermissions(archDir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        // А когда запись возможна — перенос доходит до конца
        val ok = FileTransfers.transfer(vfs, listOf(a, b), vfs.live(root), move = true)
        assertEquals(null, ok.error)
        assertFalse(Files.exists(a) || Files.exists(b))
        vfs.closeAll()
        assertEquals(setOf("old.txt", "a.txt", "b.txt"), names(archive))
    }

    @Test
    fun `folder cannot go into an archive inside it`() {
        val proj = Files.createDirectory(dir.resolve("proj"))
        Files.writeString(proj.resolve("main.kt"), "fun main() {}")
        val inner = zip(proj.resolve("old.zip"), "x.txt" to "x")
        val root = vfs.openArchive(inner)
        val r = FileTransfers.transfer(vfs, listOf(proj), root, move = true)
        assertNotNull(r.error)
        assertTrue(r.error!!.message!!.contains("внутри"), r.error!!.message)
        assertTrue(Files.exists(proj.resolve("main.kt")))
        assertTrue(Files.exists(inner))
        // Сам архив — в себя
        val self = FileTransfers.transfer(vfs, listOf(inner), vfs.live(root), move = true)
        assertNotNull(self.error)
        assertTrue(Files.exists(inner))
        assertFailsWith<IllegalArgumentException> { vfs.copyTo(proj, vfs.live(root).resolve("proj")) }
    }

    @Test
    fun `renaming and moving folders inside a zip keeps their contents`() {
        val archive = zip(
            dir.resolve("app.zip"),
            "src/" to null, "src/Main.kt" to "main", "src/util/" to null, "src/util/U.kt" to "u", "docs/" to null
        )
        val root = vfs.openArchive(archive)
        vfs.renameEntry(root.resolve("src"), "source")
        var live = vfs.live(root)
        assertEquals("main", Files.readString(live.resolve("source/Main.kt")))
        assertEquals("u", Files.readString(live.resolve("source/util/U.kt")))
        assertFalse(Files.exists(live.resolve("src")))
        FileTransfers.transfer(vfs, listOf(live.resolve("source")), live.resolve("docs"), move = true).error?.let { throw it }
        live = vfs.live(root)
        assertEquals("u", Files.readString(live.resolve("docs/source/util/U.kt")))
        assertFalse(Files.exists(live.resolve("source")))
        vfs.closeAll()
        val entries = names(archive)
        assertTrue("docs/source/Main.kt" in entries, entries.toString())
        assertFalse(entries.any { it.startsWith("src/") || it.startsWith("source/") }, entries.toString())
    }

    @Test
    fun `compressing never overwrites the sources and keeps the old archive in trash`() {
        val data = Files.createDirectory(dir.resolve("data"))
        Files.writeString(data.resolve("f.txt"), "f")
        val existing = Files.writeString(dir.resolve("backup.zip"), "not really a zip")
        assertFailsWith<IllegalArgumentException> { vfs.compressToNewFile(listOf(existing), existing, "zip") }
        assertEquals("not really a zip", Files.readString(existing))
        assertFailsWith<IllegalArgumentException> { vfs.compressToNewFile(listOf(data), data.resolve("inner.zip"), "zip") }

        val replaced = ArrayList<TrashItem>()
        vfs.compressToNewFile(listOf(data), existing, "zip") { old -> replaced.add(Trash.trash(old)) }
        assertEquals(setOf("data/", "data/f.txt"), names(existing))
        assertEquals("not really a zip", Files.readString(replaced.single().file))
        assertEquals(emptyList(), Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith(".wex-part") }.toList() })

        // Источники из разных папок в tar.gz
        val other = Files.createDirectory(dir.resolve("other"))
        Files.writeString(other.resolve("f.txt"), "other f")
        val tgz = dir.resolve("mix.tar.gz")
        vfs.compressToNewFile(listOf(data.resolve("f.txt"), other), tgz, "tar_gz")
        val listing = ProcessBuilder("tar", "-tzf", tgz.toString()).start().inputStream.readAllBytes().decodeToString().lines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("f.txt", "other/", "other/f.txt"), listing)
    }

    @Test
    fun `replace never loses the target when the source is gone and works inside zip`() {
        val src = Files.createDirectory(dir.resolve("src"))
        val dst = Files.createDirectory(dir.resolve("dst"))
        Files.writeString(dst.resolve("a.txt"), "keep me")
        val gone = src.resolve("a.txt") // файла нет: вырезали и уже вставили раньше
        val r = FileTransfers.transfer(vfs, listOf(gone), dst, move = true, policy = ConflictPolicy.REPLACE)
        assertNotNull(r.error)
        assertEquals("keep me", Files.readString(dst.resolve("a.txt")))
        assertEquals(0, Trash.count())

        // Тот же файл «поверх себя» — пропуск
        val same = FileTransfers.transfer(vfs, listOf(dst.resolve("a.txt")), dst.resolve("."), move = false, policy = ConflictPolicy.REPLACE)
        assertEquals("keep me", Files.readString(dst.resolve("a.txt")))
        assertEquals(null, same.error)

        // Замена внутри zip: новое на месте старого, временных имён не остаётся
        val archive = zip(dir.resolve("r.zip"), "a.txt" to "old in zip", "b.txt" to "b")
        val root = vfs.openArchive(archive)
        Files.writeString(src.resolve("a.txt"), "new")
        val rz = FileTransfers.transfer(vfs, listOf(src.resolve("a.txt")), root, move = false, policy = ConflictPolicy.REPLACE)
        assertEquals(null, rz.error)
        vfs.closeAll()
        assertEquals(setOf("a.txt", "b.txt"), names(archive))
        java.util.zip.ZipFile(archive.toFile()).use { z ->
            assertEquals("new", z.getInputStream(z.getEntry("a.txt")).readAllBytes().decodeToString())
        }
    }
}

