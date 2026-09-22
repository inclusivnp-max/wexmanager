package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastSearchEngineTest {
    private lateinit var root: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("wex_search")
        Files.createDirectories(root.resolve("src/main"))
        Files.createDirectories(root.resolve(".hidden/deep"))
        Files.write(root.resolve("src/main/Report.txt"), ByteArray(5))
        Files.write(root.resolve("src/main/report-2.log"), ByteArray(7))
        Files.write(root.resolve(".hidden/deep/report-secret.txt"), ByteArray(1))
        Files.write(root.resolve(".report.cfg"), ByteArray(1))
        Files.createDirectories(root.resolve("reports"))
        repeat(40) { Files.write(root.resolve("reports/report_$it.bin"), ByteArray(it)) }
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun names(list: List<VfsEntry>) = list.map { it.name }.toSet()

    @Test
    fun `recursive search is case-insensitive and fills metadata`() {
        val found = FastSearchEngine.search(root, "REPORT", recursive = true, vfsManager = vfs)
        assertTrue("Report.txt" in names(found))
        assertTrue("report-secret.txt" in names(found))
        val report = found.first { it.name == "Report.txt" }
        assertEquals(5L, report.size)
        assertTrue(report.lastModified > 0)
        val dir = found.first { it.name == "reports" }
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `hidden files and folders can be skipped`() {
        val found = FastSearchEngine.search(root, "report", recursive = true, vfsManager = vfs, includeHidden = false)
        assertTrue("Report.txt" in names(found))
        assertTrue("report-secret.txt" !in names(found), "содержимое скрытой папки")
        assertTrue(".report.cfg" !in names(found), "скрытый файл")
    }

    @Test
    fun `limit, progress and cancellation`() {
        val progress = mutableListOf<Int>()
        val limited = FastSearchEngine.search(root, "report_", recursive = true, vfsManager = vfs, maxResults = 10) {
            progress.add(it.size)
        }
        assertEquals(10, limited.size)
        assertTrue(progress.all { it <= 10 })

        val token = SearchToken().apply { cancel() }
        val cancelled = FastSearchEngine.search(root, "report", recursive = true, vfsManager = vfs, token = token)
        assertTrue(cancelled.isEmpty(), "отменённый поиск ничего не возвращает: $cancelled")
    }

    @Test
    fun `fd command is safe for dash queries and skips pseudo filesystems at root`() {
        val cmd = FastSearchEngine.fdCommand(Paths.get("/"), "-rf", 500, includeHidden = false)
        assertEquals(listOf("--", "-rf", "/"), cmd.takeLast(3))
        assertTrue("-H" !in cmd)
        assertTrue(cmd.windowed(2).any { it == listOf("-E", "/proc") })
        val inHome = FastSearchEngine.fdCommand(Paths.get("/home/u"), "x", 500, includeHidden = true)
        assertTrue("-H" in inHome)
        assertTrue("/proc" !in inHome)
    }

    @Test
    fun `odd names are returned exactly and cannot smuggle other paths`() {
        val odd = Files.createDirectories(root.resolve("odd"))
        // Папка «evil⏎», а в ней — путь, повторяющий настоящий файл вне папки поиска:
        // построчный разбор вывода fd выдал бы …/src/main/Report.txt как находку
        val outside = root.resolve("src/main/Report.txt")
        val inner = odd.resolve("evil\n").resolve(outside.toString().removePrefix("/"))
        Files.createDirectories(inner.parent)
        Files.write(inner, ByteArray(3))
        val spaced = Files.write(odd.resolve(" spaced report "), ByteArray(2))
        val found = FastSearchEngine.search(odd, "report", recursive = true, vfsManager = vfs)
        assertEquals(setOf(inner, spaced), found.map { it.path }.toSet())
        assertEquals(3L, found.first { it.path == inner }.size)
    }
}
