package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchRenamerTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_batch_rename")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    private fun f(name: String) = Files.writeString(dir.resolve(name), name)

    private fun listing() = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `swaps and renumbering work, conflicts are reported`() {
        val a = f("a.txt")
        val b = f("b.txt")
        val done = BatchRenamer.apply(vfs, listOf(a to "b.txt", b to "a.txt"))
        assertEquals(2, done.size)
        assertEquals("a.txt", Files.readString(dir.resolve("b.txt")))
        assertEquals("b.txt", Files.readString(dir.resolve("a.txt")))

        val one = f("01.jpg")
        val two = f("02.jpg")
        BatchRenamer.apply(vfs, listOf(one to "02.jpg", two to "03.jpg"))
        assertEquals("01.jpg", Files.readString(dir.resolve("02.jpg")))
        assertEquals("02.jpg", Files.readString(dir.resolve("03.jpg")))
        assertEquals(listOf("02.jpg", "03.jpg", "a.txt", "b.txt"), listing())

        // Отмена обмена — тоже в два шага
        val undo = FileUndoStack()
        undo.push(FileOp.BatchRenamed(done))
        assertEquals("Имена возвращены: 2", undo.undo(vfs))
        assertEquals("a.txt", Files.readString(dir.resolve("a.txt")))
        assertEquals("b.txt", Files.readString(dir.resolve("b.txt")))

        assertNotNull(BatchRenamer.problem(listOf(dir.resolve("a.txt") to "x.txt", dir.resolve("b.txt") to "x.txt")))
        assertNotNull(BatchRenamer.problem(listOf(dir.resolve("a.txt") to "03.jpg")))
        assertNotNull(BatchRenamer.problem(listOf(dir.resolve("a.txt") to "sub/a.txt")))
        assertNotNull(BatchRenamer.problem(listOf(dir.resolve("a.txt") to "..")))
        assertNull(BatchRenamer.problem(listOf(dir.resolve("a.txt") to "A.TXT")))
        assertFailsWith<IllegalArgumentException> { BatchRenamer.apply(vfs, listOf(dir.resolve("a.txt") to "03.jpg")) }
        assertEquals(listOf("02.jpg", "03.jpg", "a.txt", "b.txt"), listing())

        // Только регистр
        vfs.renameEntry(dir.resolve("a.txt"), "A.txt")
        assertTrue(listing().contains("A.txt"))
        assertTrue(listing().none { it.startsWith(".wex-") })
    }
}
