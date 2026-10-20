package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сравнение двух папок и план синхронизации. */
class FolderDiffTest {

    private fun file(root: Path, name: String, text: String, modified: Long = 1_700_000_000_000L): Path {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified))
        return path
    }

    private fun kinds(entries: List<FolderDiffEntry>): Map<String, FolderDiffKind> =
        entries.associate { it.relative to it.kind }

    @Test
    fun `видит отсутствующие и более новые файлы`() {
        val left = Files.createTempDirectory("wex-left")
        val right = Files.createTempDirectory("wex-right")
        file(left, "same.txt", "одно и то же")
        file(right, "same.txt", "одно и то же")
        file(left, "only-left.txt", "слева")
        file(right, "only-right.txt", "справа")
        file(left, "newer.txt", "новое содержимое", modified = 1_800_000_000_000L)
        file(right, "newer.txt", "старое", modified = 1_700_000_000_000L)

        val result = kinds(FolderDiff.compare(left, right))
        assertEquals(FolderDiffKind.SAME, result["same.txt"])
        assertEquals(FolderDiffKind.LEFT_ONLY, result["only-left.txt"])
        assertEquals(FolderDiffKind.RIGHT_ONLY, result["only-right.txt"])
        assertEquals(FolderDiffKind.LEFT_NEWER, result["newer.txt"])
    }

    @Test
    fun `заходит в подпапки`() {
        val left = Files.createTempDirectory("wex-left-deep")
        val right = Files.createTempDirectory("wex-right-deep")
        file(left, "src/main/App.kt", "код")
        file(right, "src/main/App.kt", "код")
        file(left, "src/main/New.kt", "новый файл")

        val result = kinds(FolderDiff.compare(left, right))
        assertEquals(FolderDiffKind.SAME, result["src/main/App.kt"])
        assertEquals(FolderDiffKind.LEFT_ONLY, result["src/main/New.kt"])
        assertEquals(FolderDiffKind.SAME, result["src"])
    }

    @Test
    fun `без рекурсии подпапки не разбираются`() {
        val left = Files.createTempDirectory("wex-left-flat")
        val right = Files.createTempDirectory("wex-right-flat")
        file(left, "sub/inner.txt", "x")

        val result = kinds(FolderDiff.compare(left, right, FolderDiffOptions(recursive = false)))
        assertEquals(FolderDiffKind.LEFT_ONLY, result["sub"])
        assertTrue("sub/inner.txt" !in result)
    }

    @Test
    fun `сверка содержимого ловит подмену при одинаковом размере и времени`() {
        val left = Files.createTempDirectory("wex-left-hash")
        val right = Files.createTempDirectory("wex-right-hash")
        file(left, "a.bin", "AAAA")
        file(right, "a.bin", "BBBB")

        assertEquals(FolderDiffKind.SAME, kinds(FolderDiff.compare(left, right))["a.bin"])
        assertEquals(
            FolderDiffKind.DIFFERENT,
            kinds(FolderDiff.compare(left, right, FolderDiffOptions(byContent = true)))["a.bin"]
        )
    }

    @Test
    fun `скрытые файлы по умолчанию пропускаются`() {
        val left = Files.createTempDirectory("wex-left-hidden")
        val right = Files.createTempDirectory("wex-right-hidden")
        file(left, ".git/config", "секрет")

        assertTrue(FolderDiff.compare(left, right).isEmpty())
        assertEquals(
            FolderDiffKind.LEFT_ONLY,
            kinds(FolderDiff.compare(left, right, FolderDiffOptions(includeHidden = true)))[".git"]
        )
    }

    @Test
    fun `план синхронизации вправо`() {
        val left = Files.createTempDirectory("wex-left-plan")
        val right = Files.createTempDirectory("wex-right-plan")
        file(left, "only-left.txt", "a")
        file(left, "dir/inside.txt", "b")
        file(right, "only-right.txt", "c")
        file(left, "newer.txt", "новее", modified = 1_800_000_000_000L)
        file(right, "newer.txt", "старее", modified = 1_700_000_000_000L)
        file(left, "older.txt", "старее", modified = 1_600_000_000_000L)
        file(right, "older.txt", "новее", modified = 1_700_000_000_000L)

        val entries = FolderDiff.compare(left, right)
        val plan = FolderDiff.plan(entries, toRight = true).map { it.relative }.toSet()

        // Недостающее и более новое уходит вправо; папка — целиком, без отдельных файлов внутри
        assertEquals(setOf("only-left.txt", "dir", "newer.txt"), plan)
    }

    @Test
    fun `план синхронизации влево`() {
        val left = Files.createTempDirectory("wex-left-plan2")
        val right = Files.createTempDirectory("wex-right-plan2")
        file(right, "only-right.txt", "c")
        file(left, "only-left.txt", "a")

        val plan = FolderDiff.plan(FolderDiff.compare(left, right), toRight = false).map { it.relative }
        assertEquals(listOf("only-right.txt"), plan)
    }
}
