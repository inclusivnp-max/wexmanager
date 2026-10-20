package org.wexclient.app.ui

import androidx.compose.ui.text.TextRange
import org.wexclient.app.editor.CodeEditorController
import org.wexclient.app.editor.TextTarget
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeAheadTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_typeahead")
        for (name in listOf("alpha.txt", "banana.txt", "Beta.txt", "bravo.txt", "my file.txt", "Бета.md")) {
            Files.writeString(dir.resolve(name), name)
        }
        Files.createDirectory(dir.resolve("my folder"))
        ViewPreferences.updateShowHidden(true)
        ViewPreferences.updateFoldersFirst(true)
        ViewPreferences.updateNaturalSort(true)
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `refresh keeps the cursor on the same file`() {
        val state = FilePaneState(vfs, dir)
        fun focused() = state.entries[state.focusedIndex].name
        state.focusedIndex = state.entries.indexOfFirst { it.name == "bravo.txt" }
        // Выше курсора появился файл, ниже удалили — после обновления курсор на том же файле
        Files.writeString(dir.resolve("aardvark.txt"), "a")
        Files.delete(dir.resolve("my file.txt"))
        state.refresh(force = true)
        assertEquals("bravo.txt", focused())

        // Сам файл удалили — курсор остаётся на той же строке (на соседнем)
        Files.delete(dir.resolve("bravo.txt"))
        val row = state.focusedIndex
        state.refresh()
        assertEquals(row.coerceAtMost(state.entries.size - 1), state.focusedIndex)

        // Смена папки — курсор с начала, а не «на том же пути»
        state.navigateTo(dir.resolve("my folder"))
        assertEquals(0, state.focusedIndex)
    }

    @Test
    fun `typing letters jumps to names`() {
        val state = FilePaneState(vfs, dir)
        fun focused() = state.entries[state.focusedIndex].name
        assertEquals("my folder", focused())

        // Буквы подряд складываются
        state.typeAhead('b', 10_000)
        assertEquals("banana.txt", focused())
        state.typeAhead('r', 10_300)
        assertEquals("bravo.txt", focused())
        assertEquals("br", state.typeAheadText)
        assertTrue(state.isTypingAhead(10_900))
        assertFalse(state.isTypingAhead(11_400))

        // После паузы — новый набор
        state.typeAhead('a', 12_000)
        assertEquals("alpha.txt", focused())
        assertEquals("a", state.typeAheadText)

        // Одна буква несколько раз — перебор файлов на неё
        state.typeAhead('b', 20_000)
        assertEquals("banana.txt", focused())
        state.typeAhead('b', 20_100)
        assertEquals("Beta.txt", focused())
        state.typeAhead('B', 20_200)
        assertEquals("bravo.txt", focused())
        state.typeAhead('b', 20_300)
        assertEquals("banana.txt", focused(), "по кругу")

        // Кириллица без учёта регистра
        state.typeAhead('б', 30_000)
        assertEquals("Бета.md", focused())

        // Пробел — часть имени
        for ((i, ch) in "my f".withIndex()) state.typeAhead(ch, 40_000L + i * 100)
        assertEquals("my folder", focused())
        state.typeAhead('i', 40_500)
        assertEquals("my file.txt", focused())

        // Нет такого имени — курсор на месте
        state.typeAhead('z', 40_600)
        assertTrue(state.typeAheadMissed)
        assertEquals("my file.txt", focused())
        state.typeAheadBackspace(40_700)
        assertFalse(state.typeAheadMissed)
        assertEquals("my fi", state.typeAheadText)
        assertEquals("my file.txt", focused())

        // Backspace до одной буквы: текущий остаётся, если подходит
        state.typeAhead('b', 50_000)
        state.typeAhead('r', 50_100)
        state.typeAheadBackspace(50_200)
        assertEquals("bravo.txt", focused())

        // Не с начала имени — поиск внутри
        for ((i, ch) in "nan".withIndex()) state.typeAhead(ch, 60_000L + i * 100)
        assertEquals("banana.txt", focused())

        state.clearTypeAhead()
        assertEquals("", state.typeAheadText)
        assertFalse(state.isTypingAhead(60_300))

        // Смена папки сбрасывает набор
        state.typeAhead('a', System.currentTimeMillis())
        state.navigateTo(dir.resolve("my folder"))
        assertEquals("", state.typeAheadText)
    }

    @Test
    fun `editor goes to a found line`() {
        val ctrl = CodeEditorController("first\r\nsecond line\nthird")
        ctrl.goTo(TextTarget(line = 2, column = 8, length = 4))
        assertEquals(TextRange(13, 17), ctrl.value.selection)
        assertEquals("line", ctrl.text.substring(13, 17))
        // За пределами — край строки и конец текста
        ctrl.goTo(TextTarget(line = 3, column = 99, length = 5))
        assertEquals(TextRange(ctrl.text.length), ctrl.value.selection)
        ctrl.goTo(TextTarget(line = 99))
        assertEquals(ctrl.text.lastIndexOf('\n') + 1, ctrl.value.selection.start)
    }

    private fun waitFor(timeoutMs: Long = 6000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            javax.swing.SwingUtilities.invokeAndWait { ok = cond() }
            if (ok) return true
            Thread.sleep(30)
        }
        return false
    }

    @Test
    fun `pane follows changes made by other programs`() {
        lateinit var state: FilePaneState
        javax.swing.SwingUtilities.invokeAndWait { state = FilePaneState(vfs, dir) }
        javax.swing.SwingUtilities.invokeAndWait {
            state.focusedIndex = state.entries.indexOfFirst { it.name == "bravo.txt" }
            state.toggleSelection(state.entries.first { it.name == "Beta.txt" })
        }
        // Файл появился выше курсора — курсор остаётся на том же файле, новый подсвечен
        Files.writeString(dir.resolve("aaa-new.txt"), "new")
        assertTrue(waitFor { state.entries.any { it.name == "aaa-new.txt" } })
        javax.swing.SwingUtilities.invokeAndWait {
            assertEquals("bravo.txt", state.entries[state.focusedIndex].name)
            assertEquals(listOf("Beta.txt"), state.selectedEntries.map { it.name })
            assertTrue("aaa-new.txt" in state.arrivedNames)
        }
        // Удалённый снаружи выделенный файл пропадает и из выделения
        Files.delete(dir.resolve("Beta.txt"))
        assertTrue(waitFor { state.entries.none { it.name == "Beta.txt" } })
        javax.swing.SwingUtilities.invokeAndWait { assertTrue(state.selectedEntries.isEmpty()) }

        // Папку, в которой стоим, удалили — панель уходит в ближайшую существующую
        val inner = Files.createDirectories(dir.resolve("my folder/inner"))
        javax.swing.SwingUtilities.invokeAndWait { state.navigateTo(inner) }
        dir.resolve("my folder").toFile().deleteRecursively()
        assertTrue(waitFor { state.currentPath == dir })
    }
}

