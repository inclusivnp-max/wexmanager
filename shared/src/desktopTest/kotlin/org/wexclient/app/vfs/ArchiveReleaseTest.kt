package org.wexclient.app.vfs

import org.wexclient.app.vfs.archive.ArchiveCache
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Давно не нужные архивы не держатся до выхода из программы, но и не закрываются из-под того,
 * кто ими ещё пользуется; вложенный zip читается из копии на диске, а не из памяти.
 */
class ArchiveReleaseTest {
    private lateinit var tmp: Path
    private lateinit var vfs: VfsManager

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("wex_release")
        vfs = VfsManager()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        tmp.toFile().deleteRecursively()
    }

    private val later get() = System.currentTimeMillis() + VfsManager.IDLE_MS + 1

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

    private fun xapk(name: String, text: String): Path =
        tmp.resolve(name).also { Files.write(it, zipBytes(mapOf("base.apk" to zipBytes(mapOf("who.txt" to text.toByteArray()))))) }

    private fun copies(): Set<Path> = Files.list(ArchiveCache.dir).use { it.toList().toSet() }

    private fun gcUntil(check: () -> Boolean): Boolean {
        repeat(100) {
            if (check()) return true
            System.gc()
            Thread.sleep(20)
        }
        return check()
    }

    // Отдельными функциями: ФС и пути не остаются в локальных переменных теста
    private fun openNestedWeak(outer: Path): WeakReference<FileSystem> =
        WeakReference(vfs.openArchive(vfs.openArchive(outer).resolve("base.apk")).fileSystem)

    private fun openWeak(archive: Path): WeakReference<FileSystem> = WeakReference(vfs.openArchive(archive).fileSystem)

    private fun tarGz(): Path {
        val src = Files.createDirectories(tmp.resolve("data"))
        Files.writeString(src.resolve("f.txt"), "x")
        val tgz = tmp.resolve("t.tar.gz")
        vfs.compressEntries(listOf(src), tgz, "tar.gz")
        return tgz
    }

    @Test
    fun `nested zip is read from a temp copy that goes away with it`() {
        val outer = xapk("a.xapk", "A")
        val before = copies()
        val root = vfs.openArchive(vfs.openArchive(outer).resolve("base.apk"))
        assertEquals("A", String(Files.readAllBytes(root.resolve("who.txt"))))
        val made = copies() - before
        assertEquals(1, made.size, "вложенный архив — копией во временном файле")

        // Правка во вложенном доходит до файла на диске, прежняя копия удаляется
        vfs.writeText(root.resolve("who.txt"), "A2")
        val inner = ZipFile(outer.toFile()).use { z -> z.getInputStream(z.getEntry("base.apk")).readBytes() }
        val check = Files.write(tmp.resolve("inner_check.zip"), inner)
        assertEquals("A2", ZipFile(check.toFile()).use { z -> String(z.getInputStream(z.getEntry("who.txt")).readBytes()) })
        assertTrue(made.none { Files.exists(it) }, "копия прежней версии осталась")

        val now = copies() - before
        assertEquals(1, now.size)
        vfs.closeAll()
        assertTrue(now.none { Files.exists(it) }, "копия осталась после закрытия")
    }

    @Test
    fun `idle archive nobody refers to is freed with its temp copy`() {
        val before = copies()
        val weak = openNestedWeak(xapk("a.xapk", "A"))
        val made = copies() - before
        assertEquals(1, made.size)
        vfs.releaseIdle(later)
        assertTrue(gcUntil { weak.get() == null }, "отпущенный архив остался в памяти")
        assertTrue(gcUntil { made.none { Files.exists(it) } }, "копия вложенного архива осталась на диске")
    }

    @Test
    fun `idle tar nobody refers to is freed with its unpacked copy`() {
        val tgz = tarGz()
        val before = copies()
        val weak = openWeak(tgz)
        val made = copies() - before
        assertTrue(made.isNotEmpty(), "tar.gz распаковывается во временный файл")
        vfs.releaseIdle(later)
        assertTrue(gcUntil { weak.get() == null }, "отпущенный tar остался в памяти")
        assertTrue(gcUntil { made.none { Files.exists(it) } }, "распакованный tar остался на диске")
    }

    @Test
    fun `released archive keeps working for whoever still holds a path`() {
        val file = vfs.openArchive(vfs.openArchive(xapk("a.xapk", "A")).resolve("base.apk")).resolve("who.txt")
        vfs.releaseIdle(later)
        repeat(5) {
            System.gc()
            Thread.sleep(20)
        }
        assertTrue(file.fileSystem.isOpen)
        // Напрямую, мимо VfsManager — так читают окна просмотра и фоновые задачи
        assertEquals("A", String(Files.readAllBytes(file)))
        // Внешний архив держится вложенным: подъём из корня вложенного ведёт в него
        val up = assertNotNull(vfs.getParent(file.parent))
        assertTrue(vfs.listEntries(up).any { it.name == "base.apk" })
    }

    @Test
    fun `recently used archive is kept even with no references`() {
        val weak = openWeak(tarGz())
        // Прошла минута, не десять: возврат в архив должен быть мгновенным
        vfs.releaseIdle(System.currentTimeMillis() + 60_000)
        repeat(10) {
            System.gc()
            Thread.sleep(10)
        }
        assertNotNull(weak.get())
    }

    @Test
    fun `tar with unsaved changes is not let go`() {
        val tgz = tarGz()
        val weak = run {
            val root = vfs.openArchive(tgz)
            // Запись мимо VfsManager: правка висит в ФС до её закрытия
            Files.writeString(root.resolve("g.txt"), "y")
            WeakReference(root.fileSystem)
        }
        vfs.releaseIdle(later)
        repeat(10) {
            System.gc()
            Thread.sleep(10)
        }
        assertNotNull(weak.get(), "правка пропала бы вместе с ФС")
        vfs.closeAll()
        val again = VfsManager()
        try {
            assertEquals("y", Files.readString(again.openArchive(tgz).resolve("g.txt")))
        } finally {
            again.closeAll()
        }
    }

    @Test
    fun `history into a released archive leads to its folder`() {
        val outer = xapk("a.xapk", "A")
        val nestedRoot = vfs.openArchive(vfs.openArchive(outer).resolve("base.apk"))
        // Архивом только что пользовались — история его держит как есть
        assertNull(vfs.releasedPlace(nestedRoot, tmp))
        vfs.releaseIdle(later)
        // Отпущен: вложенный — в папку внешнего, внешний — в папку на диске
        assertEquals(tmp, vfs.releasedPlace(nestedRoot, tmp))
        // Архив текущей папки панели не отпускается
        assertNull(vfs.releasedPlace(nestedRoot, nestedRoot))
        // Снова обратились — снова держим
        vfs.live(nestedRoot)
        assertNull(vfs.releasedPlace(nestedRoot, tmp))
    }
}
