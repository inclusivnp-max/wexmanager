package org.wexclient.app.vfs

import org.wexclient.app.util.FileTree
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ListingCacheTest {
    private val dirs = ArrayList<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        ListingCache.clear()
        dirs.forEach { FileTree.delete(it) }
    }

    private fun dirWith(n: Int): java.nio.file.Path = Files.createTempDirectory("wex-cache").also { d ->
        dirs.add(d)
        repeat(n) { d.resolve("f$it.txt").writeText("x") }
    }

    @Test
    fun `listing is reused until the folder changes`() {
        val vfs = VfsManager()
        val dir = dirWith(5)
        val before = ListingCache.stampOf(dir)
        val (list, error) = vfs.listEntriesChecked(dir)
        assertNull(error)
        ListingCache.put(dir, list, before)
        assertSame(list, assertNotNull(ListingCache.fresh(dir)).entries)

        // Новый файл сдвигает время изменения папки — запись больше не годится
        Thread.sleep(5)
        dir.resolve("new.txt").writeText("y")
        assertNull(ListingCache.fresh(dir))
        // Устаревшую запись fresh() убирает сразу: пересортировка её уже не подберёт
        assertNull(ListingCache.last(dir))
    }

    @Test
    fun `permission change invalidates the listing`() {
        val dir = dirWith(3)
        ListingCache.put(dir, VfsManager().listEntriesChecked(dir).first, ListingCache.stampOf(dir))
        assertNotNull(ListingCache.fresh(dir))
        // chmod не двигает время изменения, но двигает ctime
        Thread.sleep(5)
        val perms = java.nio.file.Files.getPosixFilePermissions(dir)
        java.nio.file.Files.setPosixFilePermissions(dir, perms - java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
        assertNull(ListingCache.fresh(dir))
    }

    @Test
    fun `older read does not overwrite a newer one`() {
        val dir = dirWith(2)
        val oldStamp = ListingCache.stampOf(dir)
        val oldList = VfsManager().listEntriesChecked(dir).first
        Thread.sleep(5)
        dir.resolve("more.txt").writeText("z")
        val newList = VfsManager().listEntriesChecked(dir).first
        ListingCache.put(dir, newList, ListingCache.stampOf(dir))
        ListingCache.put(dir, oldList, oldStamp)
        assertSame(newList, assertNotNull(ListingCache.last(dir)).entries)
    }

    @Test
    fun `deleted folder is never served from cache`() {
        val dir = dirWith(2)
        ListingCache.put(dir, VfsManager().listEntriesChecked(dir).first, ListingCache.stampOf(dir))
        FileTree.delete(dir)
        assertNull(ListingCache.fresh(dir))
    }

    @Test
    fun `cache keeps at most sixteen folders`() {
        val made = (0 until 20).map { dirWith(1) }
        for (d in made) ListingCache.put(d, VfsManager().listEntriesChecked(d).first, ListingCache.stampOf(d))
        assertEquals(16, ListingCache.size())
        // Самые давние ушли первыми
        assertNull(ListingCache.last(made.first()))
        assertNotNull(ListingCache.last(made.last()))
    }

    @Test
    fun `listing stops when cancelled`() {
        val dir = dirWith(600)
        val (list, error) = VfsManager().listEntriesChecked(dir) { true }
        assertEquals(VfsManager.CANCELLED, error)
        // Прервалось на первой проверке — после 256 элементов
        assertEquals(255, list.size)
    }
}
