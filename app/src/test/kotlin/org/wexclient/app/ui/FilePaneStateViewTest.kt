package org.wexclient.app.ui

import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePaneStateViewTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_pane_view")
        Files.createDirectory(dir.resolve("folder10"))
        Files.createDirectory(dir.resolve("folder9"))
        Files.createDirectory(dir.resolve(".hidden_dir"))
        Files.write(dir.resolve("img2.png"), ByteArray(300))
        Files.write(dir.resolve("img10.png"), ByteArray(100))
        Files.write(dir.resolve("app.apk"), ByteArray(10))
        Files.write(dir.resolve(".env"), ByteArray(1))
        Files.write(dir.resolve("notes.txt"), ByteArray(200))
        ViewPreferences.updateShowHidden(true)
        ViewPreferences.updateFoldersFirst(true)
        ViewPreferences.updateNaturalSort(true)
    }

    @AfterTest
    fun tearDown() {
        ViewPreferences.updateShowHidden(true)
        ViewPreferences.updateFoldersFirst(true)
        ViewPreferences.updateNaturalSort(true)
        vfs.closeAll()
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun names(state: FilePaneState) = state.entries.map { it.name }

    @Test
    fun `natural sort with folders first`() {
        val state = FilePaneState(vfs, dir)
        // app.apk — архив, идёт вместе с папками
        assertEquals(
            listOf(".hidden_dir", "app.apk", "folder9", "folder10", ".env", "img2.png", "img10.png", "notes.txt"),
            names(state)
        )
        ViewPreferences.updateNaturalSort(false)
        state.refresh()
        assertTrue(names(state).indexOf("folder10") < names(state).indexOf("folder9"))
    }

    @Test
    fun `hidden files and type filter`() {
        val state = FilePaneState(vfs, dir)
        ViewPreferences.updateShowHidden(false)
        state.refresh()
        assertTrue(names(state).none { it.startsWith(".") })

        state.applyTypeFilter(TypeFilter.IMAGES)
        assertEquals(listOf("folder9", "folder10", "img2.png", "img10.png"), names(state))

        state.applyTypeFilter(TypeFilter.FOLDERS)
        assertEquals(listOf("folder9", "folder10"), names(state))

        state.applyTypeFilter(TypeFilter.APK)
        assertEquals(listOf("app.apk", "folder9", "folder10"), names(state))
    }

    @Test
    fun `sort by size and extension, sort is remembered per side`() {
        val state = FilePaneState(vfs, dir, side = "test-side")
        state.updateSort(SortField.SIZE, ascending = false)
        val files = state.entries.filter { !it.isDirectory && !it.isArchive }.map { it.name }
        assertEquals(listOf("img2.png", "notes.txt", "img10.png", ".env"), files)

        state.updateSort(SortField.EXTENSION, ascending = true)
        val byExt = state.entries.filter { !it.isDirectory && !it.isArchive }.map { it.name }
        assertEquals(listOf(".env", "img2.png", "img10.png", "notes.txt"), byExt)

        val again = FilePaneState(vfs, dir, side = "test-side")
        assertEquals(SortField.EXTENSION, again.sortField)
        assertTrue(again.sortAscending)

        ViewPreferences.updateFoldersFirst(false)
        state.updateSort(SortField.NAME, ascending = true)
        assertEquals(
            listOf(".env", ".hidden_dir", "app.apk", "folder9", "folder10", "img2.png", "img10.png", "notes.txt"),
            names(state),
            "без «папок сверху» порядок только по имени"
        )
        state.toggleSort(SortField.NAME)
        assertEquals("notes.txt", names(state).first())
    }
}
