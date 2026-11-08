package org.wexclient.app.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.*
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FileLocationUiTest {
    @Test fun `external reveal follows the visible tab and scrolls to the selected file`() = runComposeUiTest {
        val home = Files.createTempDirectory("wex-location-ui-")
        val folder = Files.createDirectory(home.resolve("Telegram Desktop"))
        repeat(180) { Files.writeString(folder.resolve("document-%03d.txt".format(it)), "text") }
        val target = folder.resolve("document-170.txt")
        val vfs = VfsManager()
        val panes = DualPaneTabs(PaneTabs(vfs, "", home), PaneTabs(vfs, "", home))
        WexWindows.openManager(null)
        val window = WexWindows.managers.last()
        try {
            setContent { NoctaliaTheme(backdrop = false) { DualPaneScreen(vfs, windowId = window.id, persistTabs = false, panes = panes) } }
            waitForIdle()
            runOnIdle { panes.left.add(folder) }
            waitUntil(timeoutMillis = 5000) { panes.left.active.entries.size == 180 }
            waitForIdle()
            runOnIdle { WexWindows.reveal(target) }
            waitUntil(timeoutMillis = 5000) { WexWindows.pendingFor(window.id) == null }
            waitForIdle()
            assertEquals(target.fileName.toString(), panes.left.active.entries.getOrNull(panes.left.active.focusedIndex)?.name)
            assertEquals(listOf(target), panes.left.active.selectedEntries.map { it.path })
            onNodeWithText("document-170.txt").assertIsDisplayed()
            assertEquals(2, panes.left.tabs.size)
        } finally {
            WexWindows.close(window)
            panes.left.tabs.forEach { it.dispose() };panes.right.tabs.forEach { it.dispose() }
            vfs.closeAll();home.toFile().deleteRecursively()
        }
    }
    @Test fun `reveal clears a search in the already open folder`() = runComposeUiTest {
        val folder = Files.createTempDirectory("wex-location-search-")
        repeat(80) { Files.writeString(folder.resolve("item-%03d.txt".format(it)), "text") }
        val target = folder.resolve("item-070.txt")
        val vfs = VfsManager()
        val panes = DualPaneTabs(PaneTabs(vfs, "", folder), PaneTabs(vfs, "", folder))
        WexWindows.openManager(null);val window = WexWindows.managers.last()
        try {
            setContent { NoctaliaTheme(backdrop = false) { DualPaneScreen(vfs, windowId = window.id, persistTabs = false, panes = panes) } }
            waitForIdle()
            runOnIdle { panes.left.active.searchQuery = "not-found";panes.left.active.refresh() }
            waitForIdle()
            runOnIdle { WexWindows.reveal(target) }
            waitUntil(timeoutMillis = 5000) { WexWindows.pendingFor(window.id) == null }
            waitForIdle()
            assertEquals("", panes.left.active.searchQuery)
            assertEquals(listOf(target), panes.left.active.selectedEntries.map { it.path })
            onNodeWithText("item-070.txt").assertIsDisplayed()
        } finally {
            WexWindows.close(window);panes.left.tabs.forEach { it.dispose() };panes.right.tabs.forEach { it.dispose() };vfs.closeAll();folder.toFile().deleteRecursively()
        }
    }
    @Test fun `new manager reveals the startup file after reading its folder`() = runComposeUiTest {
        val home = Files.createTempDirectory("wex-location-start-")
        val folder = Files.createDirectory(home.resolve("Downloads"))
        repeat(120) { Files.writeString(folder.resolve("book-%03d.txt".format(it)), "text") }
        val target = folder.resolve("book-110.txt")
        val vfs = VfsManager();val panes = DualPaneTabs(PaneTabs(vfs, "", home), PaneTabs(vfs, "", home))
        try {
            setContent { NoctaliaTheme(backdrop = false) { DualPaneScreen(vfs, openOnStart = folder, revealOnStart = target.fileName.toString(), panes = panes, persistTabs = false) } }
            waitUntil(timeoutMillis = 5000) { panes.activePane.selectedEntries.any { it.path == target } }
            waitForIdle()
            onNodeWithText("book-110.txt").assertIsDisplayed()
            val out = java.nio.file.Path.of("build/reports/file-location/startup.png")
            Files.createDirectories(out.parent)
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(out, it.bytes) }
            }
        } finally { panes.left.tabs.forEach { it.dispose() };panes.right.tabs.forEach { it.dispose() };vfs.closeAll();home.toFile().deleteRecursively() }
    }
    @Test fun `repeated reveal scrolls back to the same file after manual scrolling`() = runComposeUiTest {
        val folder = Files.createTempDirectory("wex-location-repeat-")
        repeat(180) { Files.writeString(folder.resolve("file-%03d.txt".format(it)), "text") }
        val target = folder.resolve("file-170.txt");val vfs = VfsManager();val state = FilePaneState(vfs, folder)
        try {
            setContent { NoctaliaTheme(backdrop = false) { FilePaneView(state = state, isActive = true, onActivate = {}, onOpenFile = {}, onCopyOther = {}, onDelete = {}, onProperties = {}) } }
            waitForIdle()
            runOnIdle { state.revealFile(target) }
            waitForIdle();onNodeWithText("file-170.txt").assertIsDisplayed();assertNull(state.revealRequest)
            onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            waitForIdle();onNodeWithText("file-170.txt").assertDoesNotExist()
            runOnIdle { state.revealFile(target) }
            waitForIdle();onNodeWithText("file-170.txt").assertIsDisplayed();assertNull(state.revealRequest)
            assertEquals(listOf(target), state.selectedEntries.map { it.path })
        } finally { state.dispose();vfs.closeAll();folder.toFile().deleteRecursively() }
    }
    @Test fun `external reveal activates the pane that already has the folder`() = runComposeUiTest {
        val home = Files.createTempDirectory("wex-location-side-")
        val folder = Files.createDirectory(home.resolve("Telegram Desktop"))
        repeat(100) { Files.writeString(folder.resolve("video-%03d.txt".format(it)), "text") }
        val target = folder.resolve("video-090.txt");val vfs = VfsManager()
        val panes = DualPaneTabs(PaneTabs(vfs, "", home), PaneTabs(vfs, "", folder))
        WexWindows.openManager(null);val window = WexWindows.managers.last()
        try {
            setContent { NoctaliaTheme(backdrop = false) { DualPaneScreen(vfs, windowId = window.id, panes = panes, persistTabs = false) } }
            waitForIdle();runOnIdle { WexWindows.reveal(target) }
            waitUntil(timeoutMillis = 5000) { panes.right.active.selectedEntries.any { it.path == target } }
            waitForIdle()
            assertFalse(panes.leftActive.value)
            assertEquals(home, panes.left.active.currentPath)
            assertEquals(1, panes.right.tabs.size)
            onNodeWithText("video-090.txt").assertIsDisplayed()
        } finally { WexWindows.close(window);panes.left.tabs.forEach { it.dispose() };panes.right.tabs.forEach { it.dispose() };vfs.closeAll();home.toFile().deleteRecursively() }
    }

}
