package org.wexclient.app.util

import android.view.KeyEvent as A
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

// На Android коды клавиш физические: раскладка их не меняет, Ctrl+С (кириллическое) — это уже KEYCODE_C

actual fun KeyEvent.matchesKey(key: Key): Boolean = this.key == key

actual fun KeyEvent.layoutKey(): Key = key

actual fun KeyEvent.physicalLatinLetter(): Char? {
    val code = nativeKeyEvent.keyCode
    return if (code in A.KEYCODE_A..A.KEYCODE_Z) 'a' + (code - A.KEYCODE_A) else null
}

actual fun KeyEvent.toKeyCombo(): KeyCombo? {
    val e = nativeKeyEvent
    val code = vkOf(e.keyCode) ?: return null
    return KeyCombo(code = code, ctrl = e.isCtrlPressed, shift = e.isShiftPressed, alt = e.isAltPressed, meta = e.isMetaPressed)
}

/** Код Android → код в нумерации AWT ([Vk]), в которой хранятся сочетания. */
private fun vkOf(k: Int): Int? = when (k) {
    in A.KEYCODE_A..A.KEYCODE_Z -> Vk.VK_A + (k - A.KEYCODE_A)
    in A.KEYCODE_0..A.KEYCODE_9 -> Vk.VK_0 + (k - A.KEYCODE_0)
    in A.KEYCODE_F1..A.KEYCODE_F12 -> Vk.VK_F1 + (k - A.KEYCODE_F1)
    in A.KEYCODE_NUMPAD_0..A.KEYCODE_NUMPAD_9 -> Vk.VK_NUMPAD0 + (k - A.KEYCODE_NUMPAD_0)
    A.KEYCODE_ENTER, A.KEYCODE_NUMPAD_ENTER -> Vk.VK_ENTER
    A.KEYCODE_ESCAPE -> Vk.VK_ESCAPE
    A.KEYCODE_TAB -> Vk.VK_TAB
    A.KEYCODE_SPACE -> Vk.VK_SPACE
    A.KEYCODE_DEL -> Vk.VK_BACK_SPACE
    A.KEYCODE_FORWARD_DEL -> Vk.VK_DELETE
    A.KEYCODE_INSERT -> Vk.VK_INSERT
    A.KEYCODE_MOVE_HOME -> Vk.VK_HOME
    A.KEYCODE_MOVE_END -> Vk.VK_END
    A.KEYCODE_PAGE_UP -> Vk.VK_PAGE_UP
    A.KEYCODE_PAGE_DOWN -> Vk.VK_PAGE_DOWN
    A.KEYCODE_DPAD_UP -> Vk.VK_UP
    A.KEYCODE_DPAD_DOWN -> Vk.VK_DOWN
    A.KEYCODE_DPAD_LEFT -> Vk.VK_LEFT
    A.KEYCODE_DPAD_RIGHT -> Vk.VK_RIGHT
    A.KEYCODE_GRAVE -> Vk.VK_BACK_QUOTE
    A.KEYCODE_MINUS -> Vk.VK_MINUS
    A.KEYCODE_EQUALS -> Vk.VK_EQUALS
    A.KEYCODE_LEFT_BRACKET -> Vk.VK_OPEN_BRACKET
    A.KEYCODE_RIGHT_BRACKET -> Vk.VK_CLOSE_BRACKET
    A.KEYCODE_SEMICOLON -> Vk.VK_SEMICOLON
    A.KEYCODE_APOSTROPHE -> Vk.VK_QUOTE
    A.KEYCODE_BACKSLASH -> Vk.VK_BACK_SLASH
    A.KEYCODE_COMMA -> Vk.VK_COMMA
    A.KEYCODE_PERIOD -> Vk.VK_PERIOD
    A.KEYCODE_SLASH -> Vk.VK_SLASH
    A.KEYCODE_PLUS -> Vk.VK_PLUS
    A.KEYCODE_NUMPAD_ADD -> Vk.VK_ADD
    A.KEYCODE_NUMPAD_SUBTRACT -> Vk.VK_SUBTRACT
    A.KEYCODE_NUMPAD_MULTIPLY -> Vk.VK_MULTIPLY
    A.KEYCODE_NUMPAD_DIVIDE -> Vk.VK_DIVIDE
    A.KEYCODE_NUMPAD_DOT -> Vk.VK_DECIMAL
    A.KEYCODE_MENU -> Vk.VK_CONTEXT_MENU
    A.KEYCODE_CTRL_LEFT, A.KEYCODE_CTRL_RIGHT -> Vk.VK_CONTROL
    A.KEYCODE_SHIFT_LEFT, A.KEYCODE_SHIFT_RIGHT -> Vk.VK_SHIFT
    A.KEYCODE_ALT_LEFT, A.KEYCODE_ALT_RIGHT -> Vk.VK_ALT
    A.KEYCODE_META_LEFT, A.KEYCODE_META_RIGHT -> Vk.VK_META
    A.KEYCODE_CAPS_LOCK -> Vk.VK_CAPS_LOCK
    A.KEYCODE_NUM_LOCK -> Vk.VK_NUM_LOCK
    A.KEYCODE_SCROLL_LOCK -> Vk.VK_SCROLL_LOCK
    else -> null
}
