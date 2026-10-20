package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateFinderTest {

    private val root: Path = Files.createTempDirectory("wex-dup")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun file(rel: String, bytes: ByteArray): Path {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeBytes(bytes)
        return p
    }

    private val big = Random(7).nextBytes(100_000)

    private fun names(g: DupGroup) = g.files.map { root.relativize(it.path).toString() }

    @Test
    fun `одинаковые файлы собираются в группы по убыванию лишнего места`() {
        file("a/фото.jpg", big)
        file("b/копия фото.jpg", big)
        file("c/ещё копия.jpg", big)
        file("мелкий1.txt", "одно и то же".toByteArray())
        file("d/мелкий2.txt", "одно и то же".toByteArray())
        file("один.bin", Random(1).nextBytes(100_000)) // тот же размер, другое содержимое
        val r = DuplicateFinder.find(listOf(root))
        assertTrue(r.complete)
        assertEquals(2, r.groups.size)
        assertEquals(listOf("a/фото.jpg", "b/копия фото.jpg", "c/ещё копия.jpg"), names(r.groups[0]))
        assertEquals(200_000L, r.groups[0].wasted)
        assertEquals(setOf("мелкий1.txt", "d/мелкий2.txt"), names(r.groups[1]).toSet())
    }

    @Test
    fun `совпали начало и конец, но середина разная — не дубликаты`() {
        val other = big.copyOf().also { it[50_000] = (it[50_000] + 1).toByte() }
        file("x.bin", big)
        file("y.bin", other)
        assertTrue(DuplicateFinder.find(listOf(root)).groups.isEmpty())
    }

    @Test
    fun `жёсткая ссылка на тот же файл — не копия`() {
        val original = file("исходный.bin", big)
        Files.createLink(root.resolve("ссылка.bin"), original)
        assertTrue(DuplicateFinder.find(listOf(root)).groups.isEmpty())
    }

    @Test
    fun `скрытые — только по просьбе, мелкие отсеиваются по размеру`() {
        file(".скрытая/a.bin", big)
        file("b.bin", big)
        file("c.txt", "abc".toByteArray())
        file("d.txt", "abc".toByteArray())
        val plain = DuplicateFinder.find(listOf(root), DupOptions(minSize = 1024))
        assertTrue(plain.groups.isEmpty())
        val hidden = DuplicateFinder.find(listOf(root), DupOptions(minSize = 1024, includeHidden = true))
        assertEquals(1, hidden.groups.size)
        assertEquals(2, hidden.groups[0].files.size)
    }

    @Test
    fun `вложенная папка поиска не удваивает файлы`() {
        file("a/x.bin", big)
        file("a/y.bin", big)
        val r = DuplicateFinder.find(listOf(root, root.resolve("a")))
        assertEquals(1, r.groups.size)
        assertEquals(2, r.groups[0].files.size)
        assertEquals(listOf(root), r.roots)
    }

    @Test
    fun `автоотметка оставляет по одному файлу в группе`() {
        file("a/x.bin", big)
        file("b/x.bin", big)
        file("c/x.bin", big)
        val r = DuplicateFinder.find(listOf(root))
        val marked = DuplicateFinder.markAllBut(r.groups) { g -> g.files.first() }
        assertEquals(2, marked.size)
        assertFalse(root.resolve("a/x.bin") in marked)
    }

    @Test
    fun `оставить копии в одной папке — отмечаются только копии вне её`() {
        val a = root.resolve("A")
        val b = root.resolve("B")
        file("A/photo.jpg", big)
        file("A/копия photo.jpg", big)
        file("B/photo.jpg", big)
        val other = Random(9).nextBytes(50_000)
        file("B/только в B.bin", other)
        file("B/только в B (2).bin", other)
        val r = DuplicateFinder.find(listOf(a, b))
        val marked = DuplicateFinder.markOutside(r.groups, a)
        // В A не отмечено ничего, из B — только копия файла, который есть и в A
        assertEquals(setOf(b.resolve("photo.jpg")), marked)
    }

    @Test
    fun `корень-ссылка на папку обходится, пути — от ссылки`() {
        file("real/x.bin", big)
        file("real/y.bin", big)
        val link = Files.createSymbolicLink(root.resolve("link"), root.resolve("real"))
        val r = DuplicateFinder.find(listOf(link))
        assertEquals(1, r.groups.size)
        assertTrue(r.groups[0].files.all { it.path.startsWith(link) }, r.groups[0].files.toString())
    }

    @Test
    fun `остановленный поиск помечен как неполный`() {
        file("a/x.bin", big)
        file("b/x.bin", big)
        val r = DuplicateFinder.find(listOf(root), cancelled = { true })
        assertFalse(r.complete)
    }
}
