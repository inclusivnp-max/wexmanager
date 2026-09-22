package org.wexclient.app.vfs

import org.wexclient.app.ui.Treemap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiskUsageTest {

    private val root: Path = Files.createTempDirectory("wex-du")

    @AfterTest
    fun cleanup() {
        root.toFile().walkTopDown().forEach { it.setReadable(true); it.setExecutable(true); it.setWritable(true) }
        root.toFile().deleteRecursively()
    }

    private fun file(rel: String, size: Int): Path {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeBytes(ByteArray(size))
        return p
    }

    private fun sample() {
        file("a/фильм.mp4", 100)
        file("a/фото.png", 50)
        file("b/c/архив.zip", 1000)
        file("заметка.txt", 10)
    }

    @Test
    fun `папки суммируют всё содержимое и идут по убыванию`() {
        sample()
        val r = DiskUsage.scan(root)
        assertTrue(r.complete)
        assertEquals(1160L, r.top.size)
        assertEquals(4L, r.top.files)
        assertEquals(3L, r.top.dirs) // a, b, b/c
        assertEquals(listOf("b", "a", "заметка.txt"), r.top.children.map { it.name })
        val c = r.top.children[0].children.single()
        assertEquals(root.resolve("b/c"), c.pathFrom(root))
        assertEquals(listOf(r.top, r.top.children[0], c), c.lineage())
        assertTrue(c.isWithin(r.top.children[0]))
    }

    @Test
    fun `удалённое вычитается из всех папок выше`() {
        sample()
        val r = DiskUsage.scan(root)
        val a = r.top.children.first { it.name == "a" }
        DiskUsage.detach(a)
        assertEquals(1010L, r.top.size)
        assertEquals(2L, r.top.files)
        assertEquals(2L, r.top.dirs)
        assertFalse(r.top.children.any { it.name == "a" })
    }

    @Test
    fun `пересчёт папки обновляет итоги выше`() {
        sample()
        val r = DiskUsage.scan(root)
        val a = r.top.children.first { it.name == "a" }
        file("a/ещё.bin", 5000)
        val fresh = DiskUsage.rescan(root, a)
        assertEquals(5150L, fresh.size)
        assertEquals(6160L, r.top.size)
        assertEquals(5L, r.top.files)
        // Порядок снова по размеру: a теперь больше b
        assertEquals("a", r.top.children.first().name)
        assertEquals(root.resolve("a"), fresh.pathFrom(root))
    }

    @Test
    fun `после пересчёта выбранное внутри папки находится в новом дереве`() {
        sample()
        val r = DiskUsage.scan(root)
        val b = r.top.children.first { it.name == "b" }
        val c = b.children.first { it.name == "c" }
        val zip = c.children.first()
        // Пока папку b пересчитывали, в ней выбрали файл: путь старого узла остаётся верным
        val fresh = DiskUsage.rescan(root, b)
        assertEquals(root.resolve("b/c/архив.zip"), zip.pathFrom(root))
        val mapped = DiskUsage.remap(zip, b, fresh)
        assertNotNull(mapped)
        assertTrue(mapped !== zip)
        assertEquals(root.resolve("b/c/архив.zip"), mapped.pathFrom(root))
        // Узел вне пересчитанной папки не меняется
        val note = r.top.children.first { it.name == "заметка.txt" }
        assertTrue(DiskUsage.remap(note, b, fresh) === note)
    }

    @Test
    fun `корень-ссылка на папку считается как папка`() {
        sample()
        val link = Files.createSymbolicLink(Files.createTempDirectory("wex-du-link").resolve("link"), root)
        try {
            val r = DiskUsage.scan(link)
            assertEquals(1160L, r.top.size)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(link.parent)
        }
    }

    @Test
    fun `крупные файлы и сводка по видам`() {
        sample()
        val r = DiskUsage.scan(root)
        assertEquals(listOf("архив.zip", "фильм.mp4"), DiskUsage.largestFiles(r.top, 2).map { it.name })
        val cats = DiskUsage.byCategory(r.top)
        assertEquals(DuCategory.ARCHIVE, cats.first().first)
        assertEquals(1000L, cats.first().second)
        assertEquals(setOf(DuCategory.ARCHIVE, DuCategory.VIDEO, DuCategory.IMAGE, DuCategory.CODE), cats.map { it.first }.toSet())
    }

    @Test
    fun `остановленный подсчёт сходится по тому, что успели`() {
        sample()
        var calls = 0
        val r = DiskUsage.scan(root, cancelled = { ++calls > 3 })
        assertFalse(r.complete)
        // Итог корня равен сумме содержимого, даже если посчитано не всё
        assertEquals(r.top.children.sumOf { it.size }, r.top.size)
    }

    @Test
    fun `папка без доступа отмечена, подсчёт идёт дальше`() {
        if (System.getProperty("user.name") == "root") return
        sample()
        val closed = root.resolve("закрыто").createDirectories()
        file("закрыто/секрет.txt", 70)
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"))
        val r = DiskUsage.scan(root)
        val node = r.top.children.first { it.name == "закрыто" }
        assertNotNull(node.error)
        assertEquals(1, r.errors)
        assertEquals(1160L, r.top.size)
    }

    @Test
    fun `плитки занимают всю площадь пропорционально значениям`() {
        val values = listOf(500.0, 300.0, 120.0, 50.0, 20.0, 10.0, 0.0)
        val boxes = Treemap.layout(values, 0f, 0f, 400f, 300f)
        assertEquals(values.size, boxes.size)
        val total = values.sum()
        values.forEachIndexed { i, v ->
            val area = boxes[i].w * boxes[i].h
            assertEquals(v / total * 400 * 300, area.toDouble(), 1.0)
            assertTrue(boxes[i].x >= -0.01f && boxes[i].y >= -0.01f)
            assertTrue(boxes[i].x + boxes[i].w <= 400.01f && boxes[i].y + boxes[i].h <= 300.01f)
        }
        // Плитки не налезают друг на друга
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val overlapW = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
            val overlapH = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
            assertTrue(overlapW <= 0.01f || overlapH <= 0.01f, "плитки $i и $j пересекаются")
        }
    }
}
