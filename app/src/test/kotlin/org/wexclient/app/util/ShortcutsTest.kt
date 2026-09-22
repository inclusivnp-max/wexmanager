package org.wexclient.app.util

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

@OptIn(InternalComposeUiApi::class)
class ShortcutsTest {
    private fun ctrl(vk: Int, ch: Char, key: Key) = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        codePoint = ch.code,
        isCtrlPressed = true,
        nativeEvent = AwtKeyEvent(javax.swing.JPanel(), AwtKeyEvent.KEY_PRESSED, 0L, AwtKeyEvent.CTRL_DOWN_MASK, vk, ch)
    )

    @Test
    fun `letters resolve to the physical latin key in cyrillic layout`() {
        // Английская раскладка — как есть
        assertEquals(Key.F, ctrl(AwtKeyEvent.VK_F, 'f', Key.F).layoutKey())
        // Русская: «а» на месте F, «ф» на месте A, «т» на месте N, «р» на месте H, «в» на месте D
        assertEquals(Key.F, ctrl(AwtKeyEvent.VK_UNDEFINED, 'а', Key.Unknown).layoutKey())
        assertEquals(Key.A, ctrl(AwtKeyEvent.VK_UNDEFINED, 'ф', Key.Unknown).layoutKey())
        assertEquals(Key.N, ctrl(AwtKeyEvent.VK_UNDEFINED, 'т', Key.Unknown).layoutKey())
        assertEquals(Key.H, ctrl(AwtKeyEvent.VK_UNDEFINED, 'р', Key.Unknown).layoutKey())
        assertEquals(Key.D, ctrl(AwtKeyEvent.VK_UNDEFINED, 'В', Key.Unknown).layoutKey())
        // Не буквы — без изменений
        assertEquals(Key.F7, ctrl(AwtKeyEvent.VK_F7, AwtKeyEvent.CHAR_UNDEFINED, Key.F7).layoutKey())
        assertEquals(Key.Spacebar, ctrl(AwtKeyEvent.VK_SPACE, ' ', Key.Spacebar).layoutKey())
        assertEquals(Key.Delete, ctrl(AwtKeyEvent.VK_DELETE, AwtKeyEvent.CHAR_UNDEFINED, Key.Delete).layoutKey())
        // «х» — это «[», не буква: остаётся исходная клавиша
        assertEquals(Key.Unknown, ctrl(AwtKeyEvent.VK_UNDEFINED, 'х', Key.Unknown).layoutKey())
        assertTrue(ctrl(AwtKeyEvent.VK_UNDEFINED, 'а', Key.Unknown).isCtrlShortcut(Key.F))
    }

    @Test
    fun `typed letters with ctrl are dropped, altgr and plain typing are kept`() {
        val panel = javax.swing.JPanel()
        fun typed(ch: Char, mods: Int) = AwtKeyEvent(panel, AwtKeyEvent.KEY_TYPED, 0L, mods, AwtKeyEvent.VK_UNDEFINED, ch)
        assertTrue(isCtrlLetterTyped(typed('а', AwtKeyEvent.CTRL_DOWN_MASK)))
        assertTrue(isCtrlLetterTyped(typed('ы', AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.SHIFT_DOWN_MASK)))
        // Латиница: Ctrl+F приходит управляющим символом 0x06 — не буква
        assertFalse(isCtrlLetterTyped(typed(Char(6), AwtKeyEvent.CTRL_DOWN_MASK)))
        assertFalse(isCtrlLetterTyped(typed('а', 0)))
        assertFalse(isCtrlLetterTyped(typed('ł', AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK)))
        assertFalse(isCtrlLetterTyped(typed('€', AwtKeyEvent.ALT_GRAPH_DOWN_MASK or AwtKeyEvent.CTRL_DOWN_MASK)))
        assertFalse(isCtrlLetterTyped(AwtKeyEvent(panel, AwtKeyEvent.KEY_PRESSED, 0L, AwtKeyEvent.CTRL_DOWN_MASK, AwtKeyEvent.VK_F, 'f')))
        // Alt+буква/цифра (Alt+Z, Alt+7) — не печатается; AltGr и Ctrl+Alt — печатается
        assertTrue(isAltLetterTyped(typed('z', AwtKeyEvent.ALT_DOWN_MASK)))
        assertTrue(isAltLetterTyped(typed('7', AwtKeyEvent.ALT_DOWN_MASK)))
        assertTrue(isAltLetterTyped(typed('я', AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.SHIFT_DOWN_MASK)))
        assertFalse(isAltLetterTyped(typed('z', 0)))
        assertFalse(isAltLetterTyped(typed('ł', AwtKeyEvent.ALT_GRAPH_DOWN_MASK)))
        assertFalse(isAltLetterTyped(typed('ł', AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.ALT_GRAPH_DOWN_MASK)))
        assertFalse(isAltLetterTyped(typed('€', AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK)))
        assertFalse(isAltLetterTyped(typed('[', AwtKeyEvent.ALT_DOWN_MASK)))
    }
}
