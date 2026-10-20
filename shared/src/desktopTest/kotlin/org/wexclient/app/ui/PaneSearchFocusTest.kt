package org.wexclient.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ctrl+F: поле поиска появляется по ходу анимации — фокус должен дождаться его и встать туда,
 * чтобы печатать можно было сразу, без щелчка по полю.
 */
@OptIn(ExperimentalTestApi::class)
class PaneSearchFocusTest {
    @Test
    fun `search request focuses the field so typing goes straight into it`() = runComposeUiTest {
        val dir = Files.createTempDirectory("wex-search-focus")
        Files.writeString(dir.resolve("alpha.txt"), "a")
        val state = FilePaneState(VfsManager(), dir)
        setContent {
            NoctaliaTheme(backdrop = false) {
                FilePaneView(
                    state = state,
                    isActive = true,
                    onActivate = {},
                    onOpenFile = {},
                    onCopyOther = {},
                    onDelete = {},
                    onProperties = {}
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { state.entries.isNotEmpty() }
        runOnIdle { state.requestSearchFocus() }
        waitUntil(timeoutMillis = 3_000) { onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        onNode(isFocused()).performTextInput("alp")
        waitForIdle()
        assertEquals("alp", state.searchQuery)
    }
}
