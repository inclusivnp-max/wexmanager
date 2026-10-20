package org.wexclient.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Оттягивание строки вбок в настоящей панели — со всеми её обработчиками вокруг строк
 * (перенос файлов мышью, серия щелчков, меню фона): выделяет, как свайп в MT Manager.
 */
@OptIn(ExperimentalTestApi::class)
class PaneSwipeSelectTest {
    private fun pane(block: androidx.compose.ui.test.ComposeUiTest.(FilePaneState) -> Unit) = runComposeUiTest {
        val dir = Files.createTempDirectory("wex-swipe")
        for (n in listOf("alpha.txt", "bravo.txt", "charlie.txt", "delta.txt", "echo.txt")) Files.writeString(dir.resolve(n), n)
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
        waitUntil(timeoutMillis = 5_000) { state.entries.size == 5 }
        waitForIdle()
        block(state)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.pullWithMouse(name: String) {
        onNodeWithText(name).performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(100)
            repeat(5) {
                moveBy(Offset(20f, 0f))
                advanceEventTime(30)
            }
            release()
        }
        waitForIdle()
    }

    @Test
    fun `mouse pull selects in a real pane, second pull selects the range`() = pane { state ->
        pullWithMouse("bravo.txt")
        assertEquals(listOf("bravo.txt"), state.selectedEntries.map { it.name })
        pullWithMouse("delta.txt")
        assertEquals(setOf("bravo.txt", "charlie.txt", "delta.txt"), state.selectedEntries.map { it.name }.toSet())
    }

    @Test
    fun `pulling a selected row unselects it`() = pane { state ->
        pullWithMouse("bravo.txt")
        pullWithMouse("bravo.txt")
        assertTrue(state.selectedEntries.isEmpty())
    }

    @Test
    fun `finger swipe selects in a real pane`() = pane { state ->
        onNodeWithText("charlie.txt").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 300) }
        waitForIdle()
        assertEquals(listOf("charlie.txt"), state.selectedEntries.map { it.name })
    }
}
