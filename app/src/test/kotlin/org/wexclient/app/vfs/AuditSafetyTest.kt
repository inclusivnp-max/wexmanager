package org.wexclient.app.vfs

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Находки аудита: удаление по ссылкам, корзина чужого раздела, распаковка, устаревший архив. */
class AuditSafetyTest {

    private lateinit var tmp: Path
    private lateinit var vfs: VfsManager

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("wex_audit_test")
        vfs = VfsManager()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun zip(path: Path, entries: Map<String, String>): Path {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data.toByteArray())
                zos.closeEntry()
            }
        }
        Files.write(path, bos.toByteArray())
        return path
    }

    @Test
    fun `удаление ссылки на папку не трогает то, на что она указывает`() {
        val target = Files.createDirectories(tmp.resolve("shared/storage"))
        Files.writeString(target.resolve("keep.txt"), "данные")
        val release = Files.createDirectories(tmp.resolve("releases/5"))
        Files.createSymbolicLink(release.resolve("storage"), target)
        val current = Files.createSymbolicLink(tmp.resolve("current"), release)

        vfs.deleteEntry(current)
        assertFalse(Files.exists(current, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(release))

        vfs.deleteEntry(release)
        assertFalse(Files.exists(release))
        assertEquals("данные", Files.readString(target.resolve("keep.txt")))
    }

    @Test
    fun `копирование и перенос исчезнувшего источника — ошибка, а не успех`() {
        val gone = tmp.resolve("gone.txt")
        val dest = Files.createDirectories(tmp.resolve("dest"))
        assertFailsWith<java.nio.file.NoSuchFileException> { vfs.copyTo(gone, dest.resolve("gone.txt")) }
        assertFailsWith<java.nio.file.NoSuchFileException> { vfs.moveTo(gone, dest.resolve("gone.txt")) }
        assertFalse(Files.exists(dest.resolve("gone.txt")))
    }

    @Test
    fun `запись в архив, изменённый снаружи после открытия, отклоняется`() {
        val apk = zip(tmp.resolve("app.apk"), mapOf("a.txt" to "старое"))
        val root = vfs.openArchive(apk)
        assertTrue(Files.exists(root.resolve("a.txt")))
        // Архив пересобрали другой программой: другой размер и время
        Thread.sleep(20)
        zip(apk, mapOf("a.txt" to "новая сборка", "b.txt" to "ещё файл"))
        val error = assertFailsWith<java.io.IOException> { vfs.writeBytes(root.resolve("c.txt"), "правка".toByteArray()) }
        assertTrue("другой программой" in (error.message ?: ""), error.message)
        // Новая сборка на месте, прежнее содержимое её не затёрло
        ZipFile(apk.toFile()).use { z ->
            assertEquals("новая сборка", String(z.getInputStream(z.getEntry("a.txt")).readBytes()))
            assertTrue(z.getEntry("b.txt") != null)
            assertTrue(z.getEntry("c.txt") == null)
        }
        // После перечитывания (Ctrl+R) запись снова работает
        vfs.listEntriesChecked(root)
        val fresh = vfs.live(root)
        vfs.writeBytes(fresh.resolve("c.txt"), "правка".toByteArray())
        ZipFile(apk.toFile()).use { z -> assertEquals("правка", String(z.getInputStream(z.getEntry("c.txt")).readBytes())) }
    }

    @Test
    fun `распаковка для архива или сервера не оставляет ссылок наружу`() {
        val outside = Files.createDirectories(tmp.resolve("secret"))
        Files.writeString(outside.resolve("key"), "закрытый ключ")
        val root = Files.createDirectories(tmp.resolve("extracted"))
        Files.createDirectories(root.resolve("dir"))
        Files.writeString(root.resolve("dir/file.txt"), "внутри")
        Files.createSymbolicLink(root.resolve("abs"), outside)
        Files.createSymbolicLink(root.resolve("dir/up"), Paths.get("../../secret/key"))
        Files.createSymbolicLink(root.resolve("inside"), Paths.get("dir/file.txt"))
        Files.createSymbolicLink(root.resolve("broken"), Paths.get("nowhere"))

        SafeExtract.dropEscapingLinks(root)

        assertFalse(Files.exists(root.resolve("abs"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(root.resolve("dir/up"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(root.resolve("broken"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(root.resolve("inside")))
        assertEquals("закрытый ключ", Files.readString(outside.resolve("key")))
    }

    @Test
    fun `корзина раздела со ссылкой вместо папки files не принимается`() {
        val uid = (Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Number).toInt()
        val mount = Files.createDirectories(tmp.resolve("usb"))
        val good = Files.createDirectories(mount.resolve(".Trash-$uid"))
        Files.createDirectories(good.resolve("files"))
        Files.createDirectories(good.resolve("info"))
        assertTrue(Trash.trusted(good))

        val home = Files.createDirectories(tmp.resolve("home/Documents"))
        Files.writeString(home.resolve("важное.txt"), "не удалять")
        val evilMount = Files.createDirectories(tmp.resolve("evil"))
        val evil = Files.createDirectories(evilMount.resolve(".Trash-$uid"))
        Files.createSymbolicLink(evil.resolve("files"), home.parent)
        Files.createDirectories(evil.resolve("info"))
        Files.writeString(evil.resolve("info/Documents.trashinfo"), "[Trash Info]\nPath=Documents\nDeletionDate=2026-01-01T00:00:00\n")
        assertFalse(Trash.trusted(evil))

        val item = TrashItem(evil, "Documents", evilMount.resolve("Documents"), null)
        assertFailsWith<java.io.IOException> { Trash.delete(item) }
        assertEquals("не удалять", Files.readString(home.resolve("важное.txt")))
    }

    @Test
    fun `длинное имя укорачивается для корзины, расширение остаётся`() {
        val latin = "a".repeat(300) + ".txt"
        val short = Trash.trashName(latin, 1)
        assertTrue(short.toByteArray().size + ".trashinfo".length <= 255, short)
        assertTrue(short.endsWith(".txt"))
        val second = Trash.trashName(latin, 2)
        assertTrue(second.endsWith(" (2).txt"), second)
        assertTrue(second.toByteArray().size + ".trashinfo".length <= 255)

        val cyrillic = "ж".repeat(200) + ".pdf"
        val cut = Trash.trashName(cyrillic, 1)
        assertTrue(cut.toByteArray().size + ".trashinfo".length <= 255)
        assertFalse('�' in String(cut.toByteArray()), "без обрезанных символов")
        assertEquals("обычное.txt", Trash.trashName("обычное.txt", 1))
        assertEquals("обычное (3).txt", Trash.trashName("обычное.txt", 3))
    }

    @Test
    fun `корзина принимает файл с очень длинным именем`() {
        // Только в тестовой домашней папке (build/test-home): настоящую корзину тест не трогает
        if (System.getProperty("wex.isolated") != "true") return
        val home = Paths.get(System.getProperty("user.home"))
        Files.createDirectories(home)
        val dir = Files.createTempDirectory(home, "wex_long")
        val file = dir.resolve("д".repeat(120) + ".txt")
        Files.writeString(file, "x")
        val item = Trash.trash(file)
        assertFalse(Files.exists(file))
        assertEquals(file.fileName, item.originalPath.fileName)
        Trash.restore(item)
        assertTrue(Files.exists(file))
        dir.toFile().deleteRecursively()
    }
}
