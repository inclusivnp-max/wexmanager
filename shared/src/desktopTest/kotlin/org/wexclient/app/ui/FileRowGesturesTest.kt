package org.wexclient.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/** Жесты строки файла: свайп вбок — выделение (как в MT), щелчок — открыть, долгое нажатие пальцем — меню. */
@OptIn(ExperimentalTestApi::class)
class FileRowGesturesTest {
    private val entry = VfsEntry(
        name = "notes.txt",
        path = Paths.get("/tmp/wex-row-test/notes.txt"),
        isDirectory = false,
        isArchive = false,
        size = 12,
        lastModified = 0,
        itemType = VfsItemType.TEXT_FILE,
        extension = "txt"
    )

    private class Calls {
        var clicks = 0
        var swipes = 0
        var longClicks = 0
    }

    private fun run(block: androidx.compose.ui.test.ComposeUiTest.(Calls) -> Unit) = runComposeUiTest {
        val calls = Calls()
        setContent {
            NoctaliaTheme(backdrop = false) {
                FileItemRow(
                    entry = entry,
                    isSelected = false,
                    onClick = { calls.clicks++ },
                    onLongClick = { calls.longClicks++ },
                    onSwipeSelect = { calls.swipes++ },
                    onCopyOther = {},
                    onDelete = {},
                    onProperties = {}
                )
            }
        }
        block(calls)
    }

    @Test
    fun `quick mouse drag sideways selects and does not open`() = run { calls ->
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(40)
            moveBy(Offset(60f, 0f))
            advanceEventTime(40)
            moveBy(Offset(60f, 0f))
            release()
        }
        waitForIdle()
        assertEquals(1, calls.swipes)
        assertEquals(0, calls.clicks)
    }

    @Test
    fun `unhurried mouse pull sideways still selects`() = run { calls ->
        // Зажали, чуть помедлили и потянули по несколько пикселей — так тянут на самом деле
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(120)
            repeat(5) {
                moveBy(Offset(16f, 0f))
                advanceEventTime(40)
            }
            release()
        }
        waitForIdle()
        assertEquals(1, calls.swipes)
        assertEquals(0, calls.clicks)
    }

    @Test
    fun `short pull let go before the threshold selects nothing`() = run { calls ->
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(30)
            moveBy(Offset(25f, 0f))
            advanceEventTime(30)
            release()
        }
        waitForIdle()
        assertEquals(0, calls.swipes)
        assertEquals(0, calls.clicks)
    }

    @Test
    fun `mouse press held before moving is a file drag, not a selection`() = run { calls ->
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(FILE_DRAG_HOLD_MS + 60)
            moveBy(Offset(120f, 0f))
            release()
        }
        waitForIdle()
        assertEquals(0, calls.swipes)
    }

    @Test
    fun `mouse sideways drag with a vertical lean does not select`() = run { calls ->
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
            advanceEventTime(30)
            moveBy(Offset(30f, 40f))
            release()
        }
        waitForIdle()
        assertEquals(0, calls.swipes)
    }

    @Test
    fun `plain click opens`() = run { calls ->
        onNodeWithText("notes.txt").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, calls.clicks)
        assertEquals(0, calls.swipes)
    }

    @Test
    fun `finger swipe sideways selects`() = run { calls ->
        onNodeWithText("notes.txt").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 300) }
        waitForIdle()
        assertEquals(1, calls.swipes)
        assertEquals(0, calls.clicks)
    }

    @Test
    fun `finger long press opens the action window instead of selecting`() = run { calls ->
        onNodeWithText("notes.txt").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(0, calls.longClicks)
        // Окно действий — с пунктами меню под палец
        onNodeWithText("Свойства объекта").assertExists()
    }

    @Test
    fun `mouse long press still toggles selection`() = run { calls ->
        // Таймер долгого нажатия идёт по часам теста — их и двигаем, держа кнопку
        onNodeWithText("notes.txt").performMouseInput {
            moveTo(center)
            press()
        }
        mainClock.advanceTimeBy(800)
        onNodeWithText("notes.txt").performMouseInput { release() }
        waitForIdle()
        assertEquals(1, calls.longClicks)
        assertEquals(0, calls.swipes)
    }
}
