package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeExtractTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory(Path.of(System.getProperty("user.home")).also { Files.createDirectories(it) }, "wex_safe_extract")
        Trash.empty()
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Trash.empty()
        org.wexclient.app.util.FileTree.delete(dir)
    }

    private fun archive(): Path {
        val zip = dir.resolve("project.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            for ((name, text) in listOf("project/a.txt" to "from zip a", "project/b.txt" to "from zip b", "readme.txt" to "zip readme")) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return zip
    }

    private fun work(): Path {
        val work = Files.createDirectories(dir.resolve("work/project"))
        Files.writeString(work.resolve("a.txt"), "edited a")
        return work.parent
    }

    private fun leftovers(d: Path) = Files.list(d).use { s -> s.filter { it.fileName.toString().startsWith(".wex-extract") }.toList() }

    @Test
    fun `cancelled extraction with links to outside deletes only its own files`() {
        if (!org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("tar")) return
        // «Чужие» данные рядом и ссылка на них внутри архива (системный tar создаёт её как есть)
        val victim = Files.createDirectories(dir.resolve("victim"))
        Files.writeString(victim.resolve("precious.txt"), "keep me")
        val src = Files.createDirectories(dir.resolve("src/pkg"))
        Files.writeString(src.resolve("file.txt"), "x")
        Files.createSymbolicLink(src.resolve("home"), victim)
        Files.createDirectories(src.resolve("ro/inner"))
        Files.writeString(src.resolve("ro/inner/f.txt"), "ro")
        src.resolve("ro/inner").toFile().setWritable(false)
        src.resolve("ro").toFile().setWritable(false)
        val tar = dir.resolve("links.tar")
        val code = ProcessBuilder("tar", "-cf", tar.toString(), "-C", dir.resolve("src").toString(), "pkg").start().waitFor()
        assertEquals(0, code)
        src.resolve("ro").toFile().setWritable(true)
        src.resolve("ro/inner").toFile().setWritable(true)

        val dest = Files.createDirectories(dir.resolve("dest"))
        Files.createDirectories(dest.resolve("pkg"))
        Files.writeString(dest.resolve("pkg/file.txt"), "mine")
        val p = SafeExtract.prepare(vfs, tar, dest)
        assertEquals(listOf("pkg/file.txt"), p.conflicts)
        assertTrue(Files.isSymbolicLink(p.temp.resolve("pkg/home")), "системный tar сохранил ссылку")
        SafeExtract.discard(p)
        assertEquals("keep me", Files.readString(victim.resolve("precious.txt")))
        assertTrue(leftovers(dest).isEmpty())
        assertEquals("mine", Files.readString(dest.resolve("pkg/file.txt")))
    }

    @Test
    fun `replacing inside an archive can be undone`() {
        val zip = archive()
        // Архив-назначение, где уже есть такие же имена
        val box = dir.resolve("box.zip")
        ZipOutputStream(Files.newOutputStream(box)).use { z ->
            for ((name, text) in listOf("readme.txt" to "box readme", "project/a.txt" to "box a")) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        val root = vfs.openArchive(box)
        val p = SafeExtract.prepare(vfs, zip, root)
        assertEquals(listOf("project/a.txt", "readme.txt"), p.conflicts.sorted())
        val r = SafeExtract.finish(vfs, p, ConflictPolicy.REPLACE)
        assertEquals(2, r.archiveReplaced.size)
        assertEquals("zip readme", Files.readString(vfs.live(root).resolve("readme.txt")))

        FileUndo.revert(FileOp.Copied(r.created, r.replaced, r.archiveReplaced), vfs)
        val live = vfs.live(root)
        assertEquals("box readme", Files.readString(live.resolve("readme.txt")))
        assertEquals("box a", Files.readString(live.resolve("project/a.txt")))
        assertFalse(Files.exists(live.resolve("project/b.txt")))
        assertTrue(r.archiveReplaced.none { Files.exists(it.copy) }, "копии убраны после возврата")
    }

    @Test
    fun `file tree delete never follows links`() {
        val victim = Files.createDirectories(dir.resolve("victim2"))
        Files.writeString(victim.resolve("precious.txt"), "keep me")
        val tree = Files.createDirectories(dir.resolve("tree/sub"))
        Files.createSymbolicLink(tree.resolve("to-victim"), victim)
        Files.createSymbolicLink(dir.resolve("tree/top-link"), victim)
        Files.writeString(tree.resolve("f.txt"), "f")
        tree.toFile().setWritable(false)
        assertTrue(org.wexclient.app.util.FileTree.delete(dir.resolve("tree")))
        assertFalse(Files.exists(dir.resolve("tree")))
        assertEquals("keep me", Files.readString(victim.resolve("precious.txt")))
        // Ссылка как корень — удаляется сама ссылка
        val rootLink = Files.createSymbolicLink(dir.resolve("root-link"), victim)
        assertTrue(org.wexclient.app.util.FileTree.delete(rootLink))
        assertTrue(Files.exists(victim.resolve("precious.txt")))
    }

    @Test
    fun `conflicts are reported and nothing is touched until the decision`() {
        val zip = archive()
        val dest = work()
        val p = SafeExtract.prepare(vfs, zip, dest)
        assertEquals(listOf("project/a.txt"), p.conflicts)
        assertEquals(3, p.fileCount)
        assertEquals(listOf("project", "readme.txt"), p.topNames)
        assertEquals("edited a", Files.readString(dest.resolve("project/a.txt")))
        assertFalse(Files.exists(dest.resolve("readme.txt")))
        SafeExtract.discard(p)
        assertEquals(emptyList(), leftovers(dest))
        assertFalse(Files.exists(dest.resolve("readme.txt")))
    }

    @Test
    fun `replace sends old files to trash and undo brings them back`() {
        val zip = archive()
        val dest = work()
        val r = SafeExtract.finish(vfs, SafeExtract.prepare(vfs, zip, dest), ConflictPolicy.REPLACE)
        assertEquals("from zip a", Files.readString(dest.resolve("project/a.txt")))
        assertEquals("from zip b", Files.readString(dest.resolve("project/b.txt")))
        assertEquals(1, r.replaced.size)
        assertEquals(setOf(dest.resolve("project/a.txt"), dest.resolve("project/b.txt"), dest.resolve("readme.txt")), r.created.toSet())
        assertEquals(emptyList(), leftovers(dest))

        val undo = FileUndoStack()
        undo.push(FileOp.Copied(r.created, r.replaced))
        undo.undo(vfs)
        assertEquals("edited a", Files.readString(dest.resolve("project/a.txt")))
        assertFalse(Files.exists(dest.resolve("project/b.txt")))
        assertFalse(Files.exists(dest.resolve("readme.txt")))
    }

    @Test
    fun `skip and keep both`() {
        val zip = archive()
        val dest = work()
        val skipped = SafeExtract.finish(vfs, SafeExtract.prepare(vfs, zip, dest), ConflictPolicy.SKIP)
        assertEquals(1, skipped.skipped)
        assertEquals("edited a", Files.readString(dest.resolve("project/a.txt")))
        assertEquals("from zip b", Files.readString(dest.resolve("project/b.txt")))

        val both = SafeExtract.finish(vfs, SafeExtract.prepare(vfs, zip, dest), ConflictPolicy.KEEP_BOTH)
        assertEquals("edited a", Files.readString(dest.resolve("project/a.txt")))
        assertEquals("from zip a", Files.readString(dest.resolve("project/a (копия).txt")))
        assertEquals("from zip b", Files.readString(dest.resolve("project/b (копия).txt")))
        assertTrue(both.created.contains(dest.resolve("readme (копия).txt")))

        assertEquals(dest.resolve("project (2)"), SafeExtract.freeFolder(dest, "project"))
    }
}
