package org.wexclient.app.vfs

import org.wexclient.app.vfs.archive.ArchiveFormats
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitAndContentSearchTest {
    private lateinit var dir: Path
    private val nul = Char(0).toString()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_git").toRealPath()
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun write(rel: String, text: String): Path =
        dir.resolve(rel).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }

    @Test
    fun `porcelain v2 output becomes marks for files and folders`() {
        val out = listOf(
            "# branch.oid 0123456789abcdef0123456789abcdef01234567",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 .M N... 100644 100644 100644 aaaa bbbb src/app/Main.kt",
            "1 A. N... 000000 100644 100644 0000 cccc src/New File.kt",
            "1 D. N... 100644 000000 000000 dddd 0000 old/gone.txt",
            "2 R. N... 100644 100644 100644 eeee eeee R100 docs/renamed.md",
            "docs/original.md",
            "u UU N... 100644 100644 100644 100644 f1 f2 f3 conflict.txt",
            "? notes/todo.txt",
            "? scratch/",
            "! build/",
            "! app.log"
        ).joinToString(nul, postfix = nul)
        val st = GitStatus.parse(dir, out)
        assertEquals("main", st.branch)
        assertEquals("01234567", st.commit)
        assertEquals("origin/main", st.upstream)
        assertEquals("main ↑2 ↓1", st.branchLabel)
        fun mark(rel: String, isDir: Boolean = false) = st.markFor(dir.resolve(rel), isDir)

        assertEquals(GitMark.MODIFIED, mark("src/app/Main.kt"))
        assertEquals(GitMark.ADDED, mark("src/New File.kt"))
        assertEquals(GitMark.DELETED, mark("old/gone.txt"))
        assertEquals(GitMark.RENAMED, mark("docs/renamed.md"))
        assertNull(mark("docs/original.md"), "прежнее имя — не отдельная запись")
        assertEquals(GitMark.CONFLICT, mark("conflict.txt"))
        assertEquals(GitMark.UNTRACKED, mark("notes/todo.txt"))
        assertEquals(GitMark.UNTRACKED, mark("scratch", isDir = true))
        assertEquals(GitMark.UNTRACKED, mark("scratch/deep/file.txt"))
        assertEquals(GitMark.IGNORED, mark("build", isDir = true))
        assertEquals(GitMark.IGNORED, mark("build/classes", isDir = true))
        assertEquals(GitMark.IGNORED, mark("build/out/x.class"))
        assertEquals(GitMark.IGNORED, mark("app.log"))
        // Папки — по самому важному внутри
        assertEquals(GitMark.MODIFIED, mark("src", isDir = true))
        assertEquals(GitMark.MODIFIED, mark("src/app", isDir = true))
        assertEquals(GitMark.DELETED, mark("old", isDir = true))
        assertEquals(GitMark.RENAMED, mark("docs", isDir = true))
        assertEquals(GitMark.UNTRACKED, mark("notes", isDir = true))
        assertNull(mark("clean", isDir = true))
        assertNull(mark("README.md"))
        assertNull(st.markFor(dir, true), "сам корень без пометки")
        assertNull(mark(".git", isDir = true))
        assertNull(st.markFor(dir.parent.resolve("elsewhere.txt"), false))
        assertEquals(7, st.changedCount)

        val detached = GitStatus.parse(dir, "# branch.oid abcdef1234567890$nul# branch.head (detached)$nul")
        assertNull(detached.branch)
        assertEquals("HEAD abcdef12", detached.branchLabel)
        assertEquals(0, detached.changedCount)
    }

    @Test
    fun `status of a foreign repository runs none of its commands`() {
        if (!ArchiveFormats.toolAvailable("git")) return
        fun git(vararg args: String) {
            val pb = ProcessBuilder(
                listOf("git", "-c", "user.name=WEX", "-c", "user.email=wex@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null") + args
            ).directory(dir.toFile()).redirectErrorStream(true)
            pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            val p = pb.start()
            val out = p.inputStream.readAllBytes().decodeToString()
            assertEquals(0, p.waitFor(), out)
        }
        git("init", "-q", "-b", "main")
        write("data.txt", "data\n")
        write("more.bin", "more\n")
        write(".gitattributes", "*.txt filter=evil\n*.bin filter=in.cluded\n")
        git("add", ".")
        git("commit", "-q", "-m", "init")
        // Так выглядит .git/config распакованного «чужого» проекта
        val marks = Files.createDirectories(dir.resolve("../${dir.fileName}-marks"))
        val hook = write("hook.sh", "#!/bin/sh\ntouch '${marks.resolve("fsmonitor")}'\nexit 1\n")
        hook.toFile().setExecutable(true)
        git("config", "core.fsmonitor", hook.toString())
        git("config", "filter.evil.clean", "touch '${marks.resolve("filter")}'; cat")
        write("extra.cfg", "[filter \"in.cluded\"]\n\tclean = touch '${marks.resolve("included")}'; cat\n")
        git("config", "include.path", dir.resolve("extra.cfg").toString())
        assertEquals(setOf("evil", "in.cluded"), GitStatus.repositoryFilterDrivers(dir))

        // Файлы «тронуты» — git пересчитывал бы их через фильтры
        Thread.sleep(1100)
        dir.resolve("data.txt").toFile().setLastModified(System.currentTimeMillis())
        dir.resolve("more.bin").toFile().setLastModified(System.currentTimeMillis())
        try {
            val st = assertNotNull(GitStatus.read(dir))
            assertEquals("main", st.branch)
            assertEquals(GitMark.UNTRACKED, st.markFor(dir.resolve("hook.sh"), false))
            val left = Files.list(marks).use { it.map { p -> p.fileName.toString() }.toList() }
            assertTrue(left.isEmpty(), "выполнились команды репозитория: $left")
        } finally {
            marks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config including a fifo does not hang the panel`() {
        if (!ArchiveFormats.toolAvailable("git") || !ArchiveFormats.toolAvailable("mkfifo")) return
        val init = ProcessBuilder("git", "init", "-q", "-b", "main").directory(dir.toFile()).redirectErrorStream(true)
        init.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        init.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        assertEquals(0, init.start().also { it.inputStream.readAllBytes() }.waitFor())
        val fifo = dir.resolve(".git/stuck")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
        Files.writeString(dir.resolve(".git/config"), "[include]\n\tpath = stuck\n", java.nio.file.StandardOpenOption.APPEND)
        val started = System.nanoTime()
        assertNull(GitStatus.read(dir))
        assertTrue(System.nanoTime() - started < 15_000_000_000L, "git status висел")
    }

    @Test
    fun `status of a real repository`() {
        if (!ArchiveFormats.toolAvailable("git")) return
        fun git(vararg args: String) {
            val pb = ProcessBuilder(
                listOf(
                    "git", "-c", "user.name=WEX", "-c", "user.email=wex@example.com",
                    "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"
                ) + args
            ).directory(dir.toFile()).redirectErrorStream(true)
            // Настройки пользователя не должны влиять на тест
            pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            val p = pb.start()
            val out = p.inputStream.readAllBytes().decodeToString()
            assertEquals(0, p.waitFor(), out)
        }
        git("init", "-q", "-b", "main")
        write(".gitignore", "build/\n*.log\n")
        write("src/Main.kt", "fun main() {}\n")
        write("README.md", "# test\n")
        git("add", ".")
        git("commit", "-q", "-m", "init")
        write("src/Main.kt", "fun main() { println() }\n")
        write("src/Extra.kt", "// new\n")
        write("build/out.txt", "x")
        write("debug.log", "x")
        write("Привет мир.txt", "x")

        assertEquals(dir, GitStatus.findRoot(dir.resolve("src")))
        val st = assertNotNull(GitStatus.read(dir))
        assertEquals("main", st.branch)
        assertEquals(GitMark.MODIFIED, st.markFor(dir.resolve("src/Main.kt"), false))
        assertEquals(GitMark.UNTRACKED, st.markFor(dir.resolve("src/Extra.kt"), false))
        assertEquals(GitMark.MODIFIED, st.markFor(dir.resolve("src"), true))
        assertEquals(GitMark.IGNORED, st.markFor(dir.resolve("build"), true))
        assertEquals(GitMark.IGNORED, st.markFor(dir.resolve("debug.log"), false))
        assertEquals(GitMark.UNTRACKED, st.markFor(dir.resolve("Привет мир.txt"), false))
        assertNull(st.markFor(dir.resolve("README.md"), false))

        // Из подпапки — пути всё равно от корня
        val sub = assertNotNull(GitStatus.read(dir.resolve("src")))
        assertEquals(dir, sub.root)
        assertEquals(GitMark.MODIFIED, sub.markFor(dir.resolve("src/Main.kt"), false))

        // Внутри .git и вне репозитория — ничего
        assertNull(GitStatus.read(dir.resolve(".git")))
        val plain = Files.createTempDirectory("wex_nogit")
        try {
            assertNull(GitStatus.read(plain))
        } finally {
            plain.toFile().deleteRecursively()
        }
    }

    private fun search(query: ContentQuery, builtIn: Boolean, token: SearchToken = SearchToken()): Pair<List<ContentMatch>, ContentSearchResult> {
        val found = ArrayList<ContentMatch>()
        val r = ContentSearch.search(dir, query, token, forceBuiltIn = builtIn) { found.addAll(it) }
        return found to r
    }

    private fun List<ContentMatch>.files() = map { it.path.fileName.toString() }.toSet()

    @Test
    fun `search inside files with ripgrep and without it`() {
        write("src/Main.kt", "fun main() {\n    println(\"Привет, Мир\")\n}\n")
        write("src/Util.kt", "val мир = 1\nval миръ = 2\n// TODO: мир\n")
        write("docs/readme.md", "Мир и МИР\n")
        write(".hidden/secret.txt", "мир\n")
        write("big.min.js", "x".repeat(1000) + "мир" + "y".repeat(1000))
        Files.write(dir.resolve("data.bin"), byteArrayOf(0, 1, 2) + "мир".toByteArray())

        val engines = listOfNotNull(false.takeIf { ContentSearch.usesRipgrep(dir) }, true)
        for (builtIn in engines) {
            val label = if (builtIn) "встроенный поиск" else "ripgrep"
            val (all, r) = search(ContentQuery("мир"), builtIn)
            assertNull(r.error, label)
            assertEquals(setOf("Main.kt", "Util.kt", "readme.md", "big.min.js"), all.files(), label)
            assertEquals(6, all.size, label)
            assertEquals(6, r.matches, label)
            assertEquals(4, r.files, label)

            val main = all.single { it.path.fileName.toString() == "Main.kt" }
            assertEquals(dir.resolve("src/Main.kt"), main.path, label)
            assertEquals(2, main.line, label)
            assertEquals(22, main.column, label)
            assertEquals(3, main.length, label)
            assertEquals("println(\"Привет, Мир\")", main.preview, label)
            assertEquals("Мир", main.preview.substring(main.ranges.single()), label)

            val readme = all.single { it.path.fileName.toString() == "readme.md" }
            assertEquals(listOf("Мир", "МИР"), readme.ranges.map { readme.preview.substring(it) }, label)

            // Длинная строка — кусок вокруг совпадения
            val big = all.single { it.path.fileName.toString() == "big.min.js" }
            assertTrue(big.clippedStart && big.clippedEnd, label)
            assertTrue(big.preview.length <= 240, label)
            assertEquals("мир", big.preview.substring(big.ranges.single()), label)
            assertEquals(1001, big.column, label)

            val words = search(ContentQuery("мир", wholeWord = true), builtIn).first
            assertEquals(4, words.size, label)
            assertEquals(setOf("Main.kt", "Util.kt", "readme.md"), words.files(), label)

            val exact = search(ContentQuery("Мир", caseSensitive = true), builtIn).first
            assertEquals(setOf("Main.kt", "readme.md"), exact.files(), label)
            assertEquals(1, exact.single { it.path.fileName.toString() == "readme.md" }.ranges.size, label)

            val regex = search(ContentQuery("val \\p{L}+ =", regex = true), builtIn).first
            assertEquals(listOf(1, 2), regex.map { it.line }, label)

            val broken = search(ContentQuery("(", regex = true), builtIn).second
            assertNotNull(broken.error, label)

            assertEquals(setOf("readme.md"), search(ContentQuery("мир", globs = listOf("*.md")), builtIn).first.files(), label)
            assertEquals(
                setOf("readme.md", "big.min.js"),
                search(ContentQuery("мир", globs = ContentQuery.parseGlobs("!*.kt")), builtIn).first.files(),
                label
            )
            assertEquals(
                setOf("Main.kt", "Util.kt", "readme.md", "big.min.js", "secret.txt"),
                search(ContentQuery("мир", includeHidden = true), builtIn).first.files(),
                label
            )

            val cancelled = search(ContentQuery("мир"), builtIn, SearchToken().apply { cancel() })
            assertEquals(0, cancelled.first.size, label)
        }
    }

    @Test
    fun `a minified line with many matches is searched quickly`() {
        // 4 МБ одной строкой и 500 000 совпадений: rg отдаёт их одним сообщением на 60 МБ
        write("huge.min.js", "var a=1;".repeat(500_000))
        val engines = listOfNotNull(false.takeIf { ContentSearch.usesRipgrep(dir) }, true)
        for (builtIn in engines) {
            val label = if (builtIn) "встроенный поиск" else "ripgrep"
            val started = System.nanoTime()
            val (all, r) = search(ContentQuery("a"), builtIn)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 10_000, "$label: $ms мс")
            assertNull(r.error, label)
            val m = all.single()
            assertTrue(m.preview.length <= 240, label)
            assertTrue(m.ranges.isNotEmpty() && m.ranges.size <= 256, "$label: ${m.ranges.size}")
            assertEquals("a", m.preview.substring(m.ranges.first()), label)
        }
    }

    @Test
    fun `search inside an archive uses the built-in walker`() {
        val zip = dir.resolve("a.zip")
        FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), mapOf("create" to "true")).use { fs ->
            Files.createDirectories(fs.getPath("/res"))
            Files.writeString(fs.getPath("/res/strings.xml"), "<string>Привет, мир</string>\n")
        }
        FileSystems.newFileSystem(zip).use { fs ->
            val found = ArrayList<ContentMatch>()
            val r = ContentSearch.search(fs.getPath("/"), ContentQuery("МИР"), SearchToken()) { found.addAll(it) }
            assertEquals("встроенный поиск", r.engine)
            assertEquals("/res/strings.xml", found.single().path.toString())
            assertEquals(1, found.single().line)
            assertNull(DiskSpace.of(fs.getPath("/")))
        }
    }

    @Test
    fun `byte offsets, globs and disk space`() {
        val s = "aМ😀b"
        assertEquals(0, ContentSearch.charIndex(s, 0))
        assertEquals(1, ContentSearch.charIndex(s, 1))
        assertEquals(2, ContentSearch.charIndex(s, 3))
        assertEquals(4, ContentSearch.charIndex(s, 7))
        assertEquals(5, ContentSearch.charIndex(s, 8))
        assertEquals(listOf("*.kt", "*.java", "!build"), ContentQuery.parseGlobs("*.kt, *.java !build"))
        assertEquals(emptyList(), ContentQuery.parseGlobs("  ,  "))

        val space = assertNotNull(DiskSpace.of(dir))
        assertTrue(space.total > 0 && space.free in 0..space.total)
        assertTrue(space.usedFraction in 0f..1f)
    }
}
