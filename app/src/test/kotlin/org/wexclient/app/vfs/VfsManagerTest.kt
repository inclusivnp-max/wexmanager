package org.wexclient.app.vfs

import java.io.ByteArrayOutputStream
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VfsManagerTest {

    private lateinit var tmp: Path
    private lateinit var vfs: VfsManager

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("wex_vfs_test")
        vfs = VfsManager()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun makeZip(path: Path, entries: Map<String, String>): Path {
        Files.write(path, zipBytes(entries.mapValues { it.value.toByteArray() }))
        return path
    }

    private fun readZipEntry(zip: Path, name: String): String? =
        ZipFile(zip.toFile()).use { z -> z.getEntry(name)?.let { String(z.getInputStream(it).readBytes()) } }

    @Test
    fun `copy or move into own subfolder is rejected`() {
        val proj = Files.createDirectories(tmp.resolve("proj/sub"))
        Files.writeString(proj.resolve("f.txt"), "x")
        val parent = tmp.resolve("proj")

        assertFailsWith<IllegalArgumentException> { vfs.copyEntry(parent, parent) }
        assertFailsWith<IllegalArgumentException> { vfs.copyEntry(parent, proj) }
        assertFailsWith<IllegalArgumentException> { vfs.moveEntry(parent, proj) }
        assertFalse(Files.exists(parent.resolve("proj")))
        assertFalse(Files.exists(proj.resolve("proj")))

        // Через симлинк на подпапку — тоже
        val link = Files.createSymbolicLink(tmp.resolve("link"), proj)
        assertFailsWith<IllegalArgumentException> { vfs.copyEntry(parent, link) }
    }

    @Test
    fun `copy or move onto itself is rejected`() {
        val f = Files.writeString(tmp.resolve("a.txt"), "data")
        assertFailsWith<IllegalArgumentException> { vfs.copyEntry(f, tmp) }
        assertFailsWith<IllegalArgumentException> { vfs.moveEntry(f, tmp) }
        assertEquals("data", Files.readString(f))
    }

    @Test
    fun `copy to sibling with similar name still works`() {
        val src = Files.createDirectories(tmp.resolve("proj/inner"))
        Files.writeString(src.resolve("f.txt"), "x")
        val dest = Files.createDirectories(tmp.resolve("projX"))
        vfs.copyEntry(tmp.resolve("proj"), dest)
        assertEquals("x", Files.readString(dest.resolve("proj/inner/f.txt")))
    }

    @Test
    fun `write inside archive reaches disk immediately`() {
        val zip = makeZip(tmp.resolve("app.zip"), mapOf("resources.arsc" to "OLD", "keep.txt" to "keep"))
        val root = vfs.openArchive(zip)
        val events = mutableListOf<Pair<FileSystem, FileSystem>>()
        vfs.addArchiveReopenListener { old, new -> events.add(old to new) }

        vfs.writeBytes(root.resolve("resources.arsc"), "NEW".toByteArray())

        assertEquals("NEW", readZipEntry(zip, "resources.arsc"))
        assertEquals("keep", readZipEntry(zip, "keep.txt"))
        assertEquals(1, events.size)
        assertNotSame(events[0].first, events[0].second)

        // Старый путь (из UI) продолжает работать через новую ФС
        assertEquals("NEW", String(vfs.readBytes(root.resolve("resources.arsc"))))
        vfs.writeText(root.resolve("keep.txt"), "changed")
        assertEquals("changed", readZipEntry(zip, "keep.txt"))
    }

    @Test
    fun `delete rename and mkdir inside archive are persisted`() {
        val zip = makeZip(tmp.resolve("a.zip"), mapOf("a.txt" to "1", "b.txt" to "2"))
        val root = vfs.openArchive(zip)
        vfs.deleteEntry(root.resolve("a.txt"))
        vfs.renameEntry(root.resolve("b.txt"), "c.txt")
        vfs.createDirectory(root, "dir")
        ZipFile(zip.toFile()).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("c.txt", "dir/"), names)
        }
    }

    @Test
    fun `rename does not overwrite existing file`() {
        val a = Files.writeString(tmp.resolve("a.txt"), "A")
        Files.writeString(tmp.resolve("b.txt"), "B")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { vfs.renameEntry(a, "b.txt") }
        assertEquals("B", Files.readString(tmp.resolve("b.txt")))
    }

    @Test
    fun `batch flushes archive once`() {
        val zip = makeZip(tmp.resolve("b.zip"), mapOf("x" to "0"))
        val root = vfs.openArchive(zip)
        var reopened = 0
        vfs.addArchiveReopenListener { _, _ -> reopened++ }
        val src = Files.createDirectories(tmp.resolve("src"))
        repeat(5) { Files.writeString(src.resolve("f$it.txt"), "v$it") }

        vfs.batch {
            Files.list(src).use { s -> s.toList() }.forEach { vfs.copyEntry(it, root) }
        }

        assertEquals(1, reopened)
        assertEquals("v3", readZipEntry(zip, "f3.txt"))
    }

    @Test
    fun `nested archives with same inner path are not mixed up`() {
        val innerA = zipBytes(mapOf("who.txt" to "A".toByteArray()))
        val innerB = zipBytes(mapOf("who.txt" to "B".toByteArray()))
        val outerA = tmp.resolve("a.xapk").also { Files.write(it, zipBytes(mapOf("base.apk" to innerA))) }
        val outerB = tmp.resolve("b.xapk").also { Files.write(it, zipBytes(mapOf("base.apk" to innerB))) }

        val rootA = vfs.openArchive(vfs.openArchive(outerA).resolve("base.apk"))
        val rootB = vfs.openArchive(vfs.openArchive(outerB).resolve("base.apk"))
        assertEquals("A", String(vfs.readBytes(rootA.resolve("who.txt"))))
        assertEquals("B", String(vfs.readBytes(rootB.resolve("who.txt"))))

        // Правка во вложенном архиве доходит до файла на диске
        vfs.writeText(rootB.resolve("who.txt"), "B2")
        val innerOnDisk = ZipFile(outerB.toFile()).use { z -> z.getInputStream(z.getEntry("base.apk")).readBytes() }
        val tmpInner = Files.write(tmp.resolve("inner_check.zip"), innerOnDisk)
        assertEquals("B2", readZipEntry(tmpInner, "who.txt"))
        assertEquals("A", String(vfs.readBytes(rootA.resolve("who.txt"))))

        // Подъём из вложенного архива ведёт во внешний (актуальный) архив
        val up = vfs.getParent(rootB)!!
        assertEquals("/", up.toString())
        assertTrue(vfs.listEntries(up).any { it.name == "base.apk" })
    }

    @Test
    fun `archive rebuilt on disk is reopened`() {
        val zip = makeZip(tmp.resolve("r.zip"), mapOf("old.txt" to "1"))
        val root = vfs.openArchive(zip)
        assertEquals(listOf("old.txt"), vfs.listEntries(root).map { it.name })

        makeZip(zip, mapOf("new.txt" to "22", "other.txt" to "333"))
        Files.setLastModifiedTime(zip, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))

        assertEquals(listOf("new.txt", "other.txt"), vfs.listEntries(root).map { it.name })
        assertEquals(listOf("new.txt", "other.txt"), vfs.listEntries(vfs.openArchive(zip)).map { it.name })
    }

    @Test
    fun `zip is not packed into itself`() {
        val dir = Files.createDirectories(tmp.resolve("data"))
        Files.writeString(dir.resolve("f.txt"), "x")
        val target = dir.resolve("data.zip")
        vfs.compressEntries(listOf(dir), target, "zip")
        ZipFile(target.toFile()).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("data/", "data/f.txt"), names)
        }
    }

    @Test
    fun `head read does not need whole file`() {
        val f = tmp.resolve("big.bin")
        Files.write(f, ByteArray(100_000) { (it % 251).toByte() })
        val head = vfs.readHead(f, 16)
        assertEquals(16, head.size)
        assertEquals(15.toByte(), head[15])
        assertEquals(100_000L, vfs.size(f))
    }

    @Test
    fun `limited reads refuse oversized files and archive entries`() {
        val small = Files.write(tmp.resolve("small.bin"), ByteArray(100))
        assertEquals(100, vfs.readBytesLimited(small, 1000).size)
        val big = Files.write(tmp.resolve("big.bin"), ByteArray(3000))
        kotlin.test.assertFailsWith<java.io.IOException> { vfs.readBytesLimited(big, 1000) }

        // 20 МБ нулей в zip — килобайты на диске, но читать их целиком не нужно
        val zip = tmp.resolve("bomb.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            val chunk = ByteArray(1 shl 20)
            repeat(20) { z.write(chunk) }
            z.closeEntry()
        }
        val root = vfs.openArchive(zip)
        val e = kotlin.test.assertFailsWith<java.io.IOException> { vfs.readBytesLimited(root.resolve("AndroidManifest.xml"), 16 shl 20) }
        kotlin.test.assertTrue(e.message!!.contains("слишком большой"))
    }

    @Test
    fun `deleting a link removes only the link`() {
        val victim = Files.createDirectories(tmp.resolve("victim/sub"))
        Files.writeString(victim.resolve("keep.txt"), "keep")
        val link = Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("victim"))
        vfs.deleteEntry(link)
        kotlin.test.assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(victim.resolve("keep.txt")))
        // Папка со ссылкой внутри — удаляется сама папка, цель ссылки цела
        val dirWithLink = Files.createDirectories(tmp.resolve("holder"))
        Files.createSymbolicLink(dirWithLink.resolve("inner"), tmp.resolve("victim"))
        vfs.deleteEntry(dirWithLink)
        kotlin.test.assertFalse(Files.exists(dirWithLink))
        assertEquals("keep", Files.readString(victim.resolve("keep.txt")))
        // В корзину (домашнюю, тестовую) — тоже только ссылка
        val home = java.nio.file.Paths.get(System.getProperty("user.home")).also { Files.createDirectories(it) }
        val inHome = Files.createTempDirectory(home, "wex_link_trash")
        try {
            val link2 = Files.createSymbolicLink(inHome.resolve("link2"), tmp.resolve("victim"))
            val item = Trash.trash(link2)
            Trash.delete(item)
            assertEquals("keep", Files.readString(victim.resolve("keep.txt")))
        } finally {
            org.wexclient.app.util.FileTree.delete(inHome)
        }
    }
}
