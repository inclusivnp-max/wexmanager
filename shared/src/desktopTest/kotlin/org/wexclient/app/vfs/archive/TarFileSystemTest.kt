package org.wexclient.app.vfs.archive

import org.wexclient.app.vfs.VfsManager
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystem
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TarFileSystemTest {
    private lateinit var dir: Path
    private lateinit var tree: Path
    private val big = Random(7).nextBytes(300_000)
    private val longDir = "очень-длинная-папка-".repeat(4)
    private val longName = "файл-с-длинным-именем-".repeat(6) + ".txt"
    private val mtime = FileTime.fromMillis(1_700_000_000_000)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_tar")
        tree = dir.resolve("tree")
        Files.createDirectories(tree.resolve("Папка"))
        Files.writeString(tree.resolve("README.md"), "hello tar")
        Files.writeString(tree.resolve("Папка/файл.txt"), "привет")
        Files.createDirectories(tree.resolve("deep/$longDir"))
        Files.writeString(tree.resolve("deep/$longDir/$longName"), "long")
        Files.createDirectories(tree.resolve("empty"))
        Files.write(tree.resolve("big.bin"), big)
        Files.writeString(tree.resolve("exec.sh"), "#!/bin/sh\necho ok\n")
        Files.setPosixFilePermissions(tree.resolve("exec.sh"), PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createSymbolicLink(tree.resolve("link"), tree.resolve("README.md").fileName)
        Files.createSymbolicLink(tree.resolve("dirlink"), tree.resolve("Папка").fileName)
        Files.createLink(tree.resolve("hard.txt"), tree.resolve("README.md"))
        Files.setLastModifiedTime(tree.resolve("README.md"), mtime)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().setWritable(true)
        dir.toFile().walkTopDown().forEach { it.setWritable(true) }
        dir.toFile().deleteRecursively()
    }

    private fun tool(name: String) = ArchiveFormats.toolAvailable(name)

    private fun run(vararg cmd: String, cwd: File? = null): String {
        val p = ProcessBuilder(*cmd).directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertEquals(0, p.waitFor(), "${cmd.joinToString(" ")}: $out")
        return out
    }

    private fun systemTar(name: String, vararg extra: String): Path {
        val out = dir.resolve(name)
        run("tar", *extra, "-caf", out.toString(), "-C", tree.toString(), ".")
        return out
    }

    private fun open(p: Path): FileSystem = TarFileSystemProvider.INSTANCE.newFileSystem(p, emptyMap<String, Any>())

    private fun checkContents(fs: FileSystem) {
        val root = fs.getPath("/")
        val names = Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }.toSet()
        assertTrue(names.containsAll(listOf("README.md", "Папка", "deep", "empty", "link", "dirlink", "big.bin", "exec.sh", "hard.txt")), names.toString())
        assertEquals("hello tar", Files.readString(fs.getPath("/README.md")))
        assertEquals("привет", Files.readString(fs.getPath("/Папка/файл.txt")))
        assertEquals("long", Files.readString(fs.getPath("/deep/$longDir/$longName")))
        assertContentEquals(big, Files.readAllBytes(fs.getPath("/big.bin")))
        assertEquals(big.size.toLong(), Files.size(fs.getPath("big.bin")))
        assertTrue(Files.isDirectory(fs.getPath("/empty")))
        assertTrue(Files.isSymbolicLink(fs.getPath("/link")))
        assertEquals("README.md", Files.readSymbolicLink(fs.getPath("/link")).toString())
        assertEquals("hello tar", Files.readString(fs.getPath("/link")))
        assertTrue(Files.isDirectory(fs.getPath("/dirlink")))
        assertEquals("привет", Files.readString(fs.getPath("/dirlink/файл.txt")))
        assertEquals("hello tar", Files.readString(fs.getPath("/hard.txt")))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(fs.getPath("/exec.sh")))
        assertEquals(mtime.toMillis() / 1000, Files.getLastModifiedTime(fs.getPath("/README.md")).toMillis() / 1000)
        // Обход, как делает копирование папки
        val walked = Files.walk(root).use { s -> s.map { root.relativize(it).joinToString("/") }.toList() }
        assertTrue("deep/$longDir/$longName" in walked, walked.toString())
    }

    @Test
    fun `reads archives made by system tar in every compression`() {
        val formats = listOf(
            "a.tar" to null, "a.tar.gz" to null, "a.tgz" to null, "a.tar.xz" to "xz",
            "a.tar.bz2" to "bzip2", "a.tar.zst" to "zstd", "a.tar.lz4" to "lz4"
        )
        for ((name, needs) in formats) {
            if (needs != null && !tool(needs)) continue
            val archive = if (name == "a.tar.lz4") {
                val plain = systemTar("lz4-src.tar")
                run("lz4", "-q", plain.toString(), dir.resolve(name).toString())
                dir.resolve(name)
            } else systemTar(name)
            open(archive).use { fs ->
                checkContents(fs)
                val label = (fs as TarFileSystem).label
                assertTrue(label.startsWith("TAR"), "$name → $label")
            }
        }
        // GNU и старый v7: длинные имена через @LongLink и через PAX
        for (format in listOf("gnu", "posix")) {
            open(systemTar("f-$format.tar", "--format=$format")).use { checkContents(it) }
        }
    }

    @Test
    fun `writes changes back and system tar reads them`() {
        val formats = listOf("w.tar", "w.tar.gz", "w.tar.xz").filter { !it.endsWith(".xz") || tool("xz") }
        for (name in formats) {
            val archive = systemTar(name)
            open(archive).use { fs ->
                assertFalse(fs.isReadOnly)
                Files.writeString(fs.getPath("/new.txt"), "новый файл")
                Files.writeString(fs.getPath("/README.md"), "changed", java.nio.file.StandardOpenOption.APPEND)
                Files.delete(fs.getPath("/big.bin"))
                Files.move(fs.getPath("/Папка"), fs.getPath("/Folder"))
                Files.createDirectory(fs.getPath("/made"))
                Files.copy(fs.getPath("/README.md"), fs.getPath("/made/copy.md"))
                Files.setLastModifiedTime(fs.getPath("/made/copy.md"), mtime)
                assertFailsWith<java.nio.file.DirectoryNotEmptyException> { Files.delete(fs.getPath("/made")) }
                assertFailsWith<java.nio.file.FileAlreadyExistsException> { Files.createDirectory(fs.getPath("/made")) }
                // Копирование с диска внутрь и обратно
                Files.copy(tree.resolve("big.bin"), fs.getPath("/made/from-disk.bin"))
                Files.copy(fs.getPath("/Folder/файл.txt"), dir.resolve("out-$name.txt"))
            }
            assertEquals("привет", Files.readString(dir.resolve("out-$name.txt")))
            assertTrue(dir.toFile().list()!!.none { it.startsWith(".wex-") }, "временные файлы остались")

            val listing = run("tar", "-tf", archive.toString())
            assertTrue("./new.txt" in listing.lines(), listing)
            assertFalse(listing.contains("big.bin"), listing)
            val out = dir.resolve("x-$name")
            Files.createDirectories(out)
            run("tar", "-xf", archive.toString(), "-C", out.toString())
            assertEquals("новый файл", Files.readString(out.resolve("new.txt")))
            assertEquals("hello tarchanged", Files.readString(out.resolve("README.md")))
            assertEquals("hello tarchanged", Files.readString(out.resolve("made/copy.md")))
            assertEquals(mtime.toMillis(), Files.getLastModifiedTime(out.resolve("made/copy.md")).toMillis())
            assertEquals("привет", Files.readString(out.resolve("Folder/файл.txt")))
            assertEquals("long", Files.readString(out.resolve("deep/$longDir/$longName")))
            // Жёсткая ссылка сохранила старое содержимое (правка README её не касается)
            assertEquals("hello tar", Files.readString(out.resolve("hard.txt")))
            assertTrue(Files.isSymbolicLink(out.resolve("link")))
            assertTrue(Files.isDirectory(out.resolve("empty")))
            assertTrue(Files.isExecutable(out.resolve("exec.sh")))
            assertContentEquals(big, Files.readAllBytes(out.resolve("made/from-disk.bin")))

            open(archive).use { fs ->
                assertEquals("новый файл", Files.readString(fs.getPath("/new.txt")))
                assertFalse(Files.exists(fs.getPath("/big.bin")))
            }
        }
    }

    @Test
    fun `single compressed file opens as folder with one file`() {
        val gz = dir.resolve("dump.sql.gz")
        GZIPOutputStream(Files.newOutputStream(gz)).use { it.write("select 1;".toByteArray()) }
        open(gz).use { fs ->
            fs as TarFileSystem
            assertTrue(fs.singleFile)
            assertEquals("GZ", fs.label)
            val names = Files.list(fs.getPath("/")).use { s -> s.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("dump.sql"), names)
            assertEquals("select 1;", Files.readString(fs.getPath("/dump.sql")))
            Files.writeString(fs.getPath("/dump.sql"), "select 2;")
            assertFailsWith<FileSystemException> { Files.createDirectory(fs.getPath("/x")) }
            assertFailsWith<FileSystemException> { Files.delete(fs.getPath("/dump.sql")) }
        }
        assertEquals("select 2;", GZIPInputStream(Files.newInputStream(gz)).use { it.readAllBytes().decodeToString() })

        if (tool("xz")) {
            val plain = dir.resolve("notes.txt")
            Files.writeString(plain, "xz inside")
            run("xz", "-k", plain.toString())
            open(dir.resolve("notes.txt.xz")).use { fs ->
                assertEquals("xz inside", Files.readString(fs.getPath("/notes.txt")))
            }
        }
    }

    @Test
    fun `tar inside zip is read and written through the outer archive`() {
        val inner = systemTar("inner.tar.gz")
        val zip = dir.resolve("outer.zip")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { zfs ->
            Files.copy(inner, zfs.getPath("/inner.tar.gz"))
        }
        val vfs = VfsManager()
        val zipRoot = vfs.openArchive(zip)
        val entries = vfs.listEntries(zipRoot)
        val tarEntry = entries.single { it.name == "inner.tar.gz" }
        assertTrue(tarEntry.isArchive, "tar.gz внутри zip — тоже архив")
        val tarRoot = vfs.openArchive(tarEntry.path)
        assertEquals("TAR.GZ", vfs.archiveLabel(tarRoot))
        assertEquals("ZIP", vfs.archiveLabel(zipRoot))
        assertEquals("привет", vfs.readText(tarRoot.resolve("Папка/файл.txt")))
        vfs.writeText(tarRoot.resolve("Папка/файл.txt"), "изменено")
        // После записи оба архива переоткрыты; старый путь ведёт в новую ФС
        assertEquals("изменено", vfs.readText(tarRoot.resolve("Папка/файл.txt")))
        assertEquals(zip, vfs.topArchiveFile(tarRoot))
        vfs.closeAll()

        FileSystems.newFileSystem(zip).use { zfs ->
            open(zfs.getPath("/inner.tar.gz")).use { tfs ->
                assertEquals("изменено", Files.readString(tfs.getPath("/Папка/файл.txt")))
            }
        }

        // Распаковка вложенного tar (без системного tar) — через TarFileSystem
        val vfs2 = VfsManager()
        val nested = vfs2.openArchive(zip).resolve("inner.tar.gz")
        val out = dir.resolve("nested-out")
        vfs2.extractArchive(nested, out)
        assertEquals("изменено", Files.readString(out.resolve("Папка/файл.txt")))
        assertContentEquals(big, Files.readAllBytes(out.resolve("big.bin")))
        assertTrue(Files.isSymbolicLink(out.resolve("link")))
        assertTrue(Files.isExecutable(out.resolve("exec.sh")))
        vfs2.closeAll()
    }

    @Test
    fun `vfs manager lists tar archives as folders and compresses into them`() {
        val vfs = VfsManager()
        systemTar("list.tar.gz")
        val entry = vfs.listEntries(dir).single { it.name == "list.tar.gz" }
        assertTrue(entry.isArchive)
        var progressSeen = 0L
        val root = vfs.openArchive(entry.path) { progressSeen = it }
        assertTrue(progressSeen > 0, "прогресс распаковки")
        val names = vfs.listEntries(root).map { it.name }
        assertTrue("README.md" in names && "Папка" in names, names.toString())
        assertEquals(dir, vfs.getParent(root))
        vfs.createDirectory(root, "Новая")
        assertTrue(vfs.listEntries(root).any { it.name == "Новая" && it.isDirectory })
        // Копирование с диска через VfsManager сохраняет бит исполнения
        vfs.copyEntry(tree.resolve("exec.sh"), root.resolve("Новая"))
        val copied = vfs.live(root.resolve("Новая/exec.sh"))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(copied))

        // Сжатие в tar.xz — системный tar; в .tar из архива — своя запись
        val packed = dir.resolve("packed.tar")
        vfs.compressEntries(listOf(root.resolve("Папка"), root.resolve("README.md")), packed, "tar")
        val listing = run("tar", "-tf", packed.toString()).lines()
        assertTrue("Папка/файл.txt" in listing && "README.md" in listing, listing.toString())
        if (tool("xz")) {
            val xz = dir.resolve("packed.tar.xz")
            vfs.compressEntries(listOf(tree.resolve("README.md")), xz, "tar_gz")
            assertEquals(Compression.XZ, Compression.detect(Files.newInputStream(xz).use { it.readNBytes(8) }))
        }
        vfs.closeAll()
        assertEquals("TAR.XZ", ArchiveFormats.label("x.tar.xz"))
        assertEquals("x.tar", ArchiveFormats.innerName("x.tgz"))
        assertFalse(ArchiveFormats.isBrowsable("picture.svgz"))
        assertFalse(ArchiveFormats.isBrowsable(".gz"))
    }

    @Test
    fun `read only archive and broken input`() {
        val archive = systemTar("ro.tar.gz")
        archive.toFile().setWritable(false)
        open(archive).use { fs ->
            assertTrue(fs.isReadOnly)
            assertEquals("hello tar", Files.readString(fs.getPath("/README.md")))
            assertFailsWith<AccessDeniedException> { Files.writeString(fs.getPath("/x.txt"), "x") }
        }
        val cache = ArchiveCache.dir
        fun cacheFiles() = Files.list(cache).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
        val before = cacheFiles()
        val notTar = dir.resolve("fake.tar")
        Files.write(notTar, Random(1).nextBytes(2048))
        val e = assertFailsWith<java.io.IOException> { open(notTar) }
        assertTrue(e.message!!.contains("tar"), e.message)
        val truncatedGz = dir.resolve("cut.tar.gz")
        Files.write(truncatedGz, Files.readAllBytes(archive).copyOf(200))
        assertFailsWith<java.io.IOException> { open(truncatedGz) }
        assertEquals(before, cacheFiles(), "после неудачного открытия временные файлы удалены")
    }

    @Test
    fun `path operations`() {
        val archive = systemTar("paths.tar")
        open(archive).use { fs ->
            val p = fs.getPath("/a/b/../c/./d")
            assertEquals("/a/c/d", p.normalize().toString())
            assertEquals("d", p.fileName.toString())
            assertEquals("/a/b/../c/.", p.parent.toString())
            assertEquals(fs.getPath("/"), fs.getPath("/x").parent)
            assertEquals(null, fs.getPath("/").parent)
            assertEquals(null, fs.getPath("/").fileName)
            assertEquals(fs.getPath("/"), fs.getPath("/a").root)
            assertEquals("/a/b/c", fs.getPath("/a").resolve("b/c").toString())
            assertEquals("/x", fs.getPath("/a").resolve("/x").toString())
            assertEquals("../c/d", fs.getPath("/a/b").relativize(fs.getPath("/a/c/d")).toString())
            assertEquals("", fs.getPath("/a").relativize(fs.getPath("/a")).toString())
            assertTrue(fs.getPath("/a/b/c").startsWith(fs.getPath("/a/b")))
            assertFalse(fs.getPath("/a/bc").startsWith(fs.getPath("/a/b")))
            assertTrue(fs.getPath("/a/b/c").endsWith(fs.getPath("b/c")))
            assertEquals("b/c", fs.getPath("/a/b/c").subpath(1, 3).toString())
            assertEquals(3, fs.getPath("/a/b/c").nameCount)
            assertEquals("/a/b", fs.getPath("/a//b/").toString())
            assertEquals(listOf("a", "b"), fs.getPath("/a/b").map { it.toString() })
            assertEquals("/Папка/файл.txt", fs.getPath("/dirlink/файл.txt").toRealPath().toString())
            assertEquals("/dirlink/файл.txt", fs.getPath("/dirlink/файл.txt").toRealPath(LinkOption.NOFOLLOW_LINKS).toString())
            assertTrue(Files.isSameFile(fs.getPath("/dirlink/файл.txt"), fs.getPath("/Папка/файл.txt")))
            assertTrue(fs.getPathMatcher("glob:**/*.txt").matches(fs.getPath("/Папка/файл.txt")))
            assertFalse(fs.getPathMatcher("glob:*.txt").matches(fs.getPath("/Папка/файл.txt")))
            assertNotNull(Files.getFileStore(fs.getPath("/README.md")))
            assertTrue(Files.isHidden(fs.getPath("/.hidden")))
            assertEquals(9L, Files.readAttributes(fs.getPath("/README.md"), "size")["size"])
            assertTrue(fs.getPath("/README.md").toUri().toString().startsWith("wextar:file:"))
        }
        val moved = dir.resolve("moved.tar")
        Files.move(archive, moved, StandardCopyOption.REPLACE_EXISTING)
        open(moved).use { fs ->
            assertFailsWith<FileSystemException> { Files.move(fs.getPath("/Папка"), fs.getPath("/Папка/внутрь")) }
        }
    }
}
