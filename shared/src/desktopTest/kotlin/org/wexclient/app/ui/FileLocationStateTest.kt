package org.wexclient.app.ui

import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileLocationStateTest {
    private fun fixture(block: (VfsManager, Path, Path, FilePaneState) -> Unit) {
        val home = Files.createTempDirectory("wex-location-state-")
        val folder = Files.createDirectory(home.resolve("Telegram Desktop"))
        repeat(40) { Files.writeString(folder.resolve("file-%03d.txt".format(it)), "text".repeat(it + 1)) }
        val vfs = VfsManager();val state = FilePaneState(vfs, home)
        try { block(vfs, home, folder, state) } finally { state.dispose();vfs.closeAll();home.toFile().deleteRecursively() }
    }
    @Test fun `repeated reveal reuses visible entries without waiting for disk`() = fixture { _, _, folder, state ->
        state.navigateTo(folder)
        val target = folder.resolve("file-035.txt")
        val before = state.entries.first { it.path == target }
        repeat(20) {
            state.revealFile(target)
            assertSame(before, state.entries.first { it.path == target })
            assertEquals(listOf(target), state.selectedEntries.map { it.path })
            assertFalse(state.isLoading)
        }
    }
    @Test fun `opening location selects only the requested file and requests an immediate jump`() = fixture { _, _, folder, state ->
        val target = folder.resolve("file-035.txt")
        state.revealFile(target)
        assertEquals(folder, state.currentPath)
        assertEquals(listOf(target), state.selectedEntries.map { it.path })
        assertEquals(target, state.entries[state.focusedIndex].path)
        val request = assertNotNull(state.revealRequest)
        assertTrue(request.immediate)
        assertEquals(state.focusedIndex, request.first)
        assertTrue(state.isArrived(state.entries[request.first]))
        assertEquals(state.focusedIndex, state.selectionAnchor)
    }
    @Test fun `search and type filter cannot hide an explicit reveal`() = fixture { _, _, folder, state ->
        state.navigateTo(folder);state.applyTypeFilter(TypeFilter.IMAGES)
        state.isSearchOpen = true;state.searchQuery = "absent";state.refresh()
        val target = folder.resolve("file-030.txt");state.revealFile(target)
        assertEquals("", state.searchQuery);assertFalse(state.isSearchOpen)
        assertEquals(TypeFilter.ALL, state.typeFilter)
        assertEquals(listOf(target), state.selectedEntries.map { it.path })
    }
    @Test fun `only the requested hidden file becomes visible without changing preferences`() = fixture { _, home, folder, state ->
        val old = ViewPreferences.showHidden
        try {
            ViewPreferences.updateShowHidden(false)
            val target = Files.writeString(folder.resolve(".settings"), "one")
            Files.writeString(folder.resolve(".other"), "two")
            state.revealFile(target)
            assertEquals(listOf(target), state.selectedEntries.map { it.path })
            assertFalse(ViewPreferences.showHidden)
            assertFalse(state.entries.any { it.name == ".other" })
            state.navigateTo(home);state.navigateTo(folder)
            assertFalse(state.entries.any { it.name.startsWith(".") })
        } finally { ViewPreferences.updateShowHidden(old) }
    }
    @Test fun `every repeated reveal has a fresh request and one selected file`() = fixture { _, _, folder, state ->
        val target = folder.resolve("file-030.txt");state.revealFile(target)
        val first = state.revealRequest;state.revealFile(target)
        assertNotSame(first, state.revealRequest)
        assertEquals(listOf(target), state.selectedEntries.map { it.path })
    }
    @Test fun `target index follows the current sort order`() = fixture { _, _, folder, state ->
        state.updateSort(SortField.SIZE, false)
        val target = folder.resolve("file-035.txt");state.revealFile(target)
        val expected = state.entries.indexOfFirst { it.path == target }
        assertEquals(expected, state.focusedIndex)
        assertEquals(expected, state.revealRequest!!.first)
        assertEquals(SortField.SIZE, state.sortField);assertFalse(state.sortAscending)
    }
    @Test fun `refresh retains revealed selection and focus by path`() = fixture { _, _, folder, state ->
        val target = folder.resolve("file-030.txt");state.revealFile(target)
        Files.writeString(folder.resolve("aaa.txt"), "new");state.refresh()
        assertEquals(target, state.entries[state.focusedIndex].path)
        assertEquals(listOf(target), state.selectedEntries.map { it.path })
    }
    @Test fun `missing target does not select or highlight a different file`() = fixture { _, _, folder, state ->
        state.revealFile(folder.resolve("file-030.txt"))
        state.revealFile(folder.resolve("missing.txt"))
        assertTrue(state.selectedEntries.isEmpty());assertNull(state.revealRequest)
        assertTrue(state.arrivedNames.isEmpty())
    }
    @Test fun `existing folder tab is reused even in the other pane`() = fixture { vfs, home, folder, _ ->
        val left = PaneTabs(vfs, "", home);val right = PaneTabs(vfs, "", folder)
        val panes = DualPaneTabs(left, right);val target = folder.resolve("unused/../file-030.txt")
        try {
            val initial = left.active
            left.add(home)
            assertNotSame(initial, left.active)
            val revealed = assertNotNull(panes.revealFile(target))
            assertSame(right.active, revealed);assertFalse(panes.leftActive.value)
            assertEquals(2, left.tabs.size);assertEquals(1, right.tabs.size)
            assertEquals(listOf(folder.resolve("file-030.txt")), revealed.selectedEntries.map { it.path })
        } finally { left.tabs.forEach { it.dispose() };right.tabs.forEach { it.dispose() } }
    }
}
