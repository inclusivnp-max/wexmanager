package org.wexclient.app.util

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortcutsAndOrderTest {

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun keyEvent(code: Int, char: Char, ctrl: Boolean = true, shift: Boolean = false): KeyEvent {
        var mods = 0
        if (ctrl) mods = mods or AwtKeyEvent.CTRL_DOWN_MASK
        if (shift) mods = mods or AwtKeyEvent.SHIFT_DOWN_MASK
        val awt = AwtKeyEvent(javax.swing.JPanel(), AwtKeyEvent.KEY_PRESSED, 0L, mods, code, char)
        // Как в приложении: Compose оборачивает AWT-событие (nativeEvent)
        return KeyEvent(
            key = Key(code),
            type = KeyEventType.KeyDown,
            codePoint = char.code,
            isCtrlPressed = ctrl,
            isShiftPressed = shift,
            nativeEvent = awt
        )
    }

    @Test
    fun `ctrl+s is recognized in latin and cyrillic layouts`() {
        assertTrue(keyEvent(AwtKeyEvent.VK_S, 's').isCtrlShortcut(Key.S))
        // Русская раскладка: та же клавиша даёт «ы», VK-кода нет
        assertTrue(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'ы').isCtrlShortcut(Key.S))
        assertTrue(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'Ы', shift = true).isCtrlShortcut(Key.S, shift = true))
        assertFalse(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'ы', shift = true).isCtrlShortcut(Key.S))
        assertFalse(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'ы', ctrl = false).isCtrlShortcut(Key.S))
        assertFalse(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'в').isCtrlShortcut(Key.S))
        assertTrue(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'а').matchesKey(Key.F))
        assertTrue(keyEvent(AwtKeyEvent.VK_UNDEFINED, 'я').isCtrlShortcut(Key.Z))
    }

    @Test
    fun `natural order compares numbers by value`() {
        val sorted = listOf("file10.txt", "File2.txt", "file1.txt", "file02.txt", "a", "B", "file2.txt")
            .sortedWith(NaturalOrder)
        assertEquals(listOf("a", "B", "file1.txt", "File2.txt", "file2.txt", "file02.txt", "file10.txt"), sorted)
        assertTrue(NaturalOrder.compare("v1.9", "v1.10") < 0)
        assertEquals(0, NaturalOrder.compare("same", "same"))
        assertTrue(NaturalOrder.compare("ab", "abc") < 0)
    }
}
