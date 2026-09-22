package org.wexclient.app.util

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type

// Физические клавиши ЙЦУКЕН -> QWERTY: при русской раскладке первой в списке
// AWT отдаёт кириллический keysym, и Key.S для Ctrl+Ы не совпадает
private const val CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбю"
private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,."

// В Compose 1.7 nativeKeyEvent — внутренняя обёртка с полем nativeEvent (исходный AWT KeyEvent)
fun KeyEvent.awtEvent(): java.awt.event.KeyEvent? {
    val native = nativeKeyEvent
    if (native is java.awt.event.KeyEvent) return native
    return try {
        native.javaClass.getMethod("getNativeEvent").invoke(native) as? java.awt.event.KeyEvent
    } catch (_: Exception) {
        null
    }
}

/** Та же физическая клавиша, что и [key], независимо от активной раскладки. */
fun KeyEvent.matchesKey(key: Key): Boolean {
    if (this.key == key) return true
    val awt = awtEvent() ?: return false
    val ext = awt.extendedKeyCode
    // Кириллица: extended key code = 0x01000000 + символ; иначе смотрим на сам символ
    val ch = when {
        ext and 0x01000000 != 0 -> (ext and 0xFFFFFF).toChar()
        awt.keyChar != java.awt.event.KeyEvent.CHAR_UNDEFINED -> awt.keyChar
        else -> return false
    }
    val idx = CYRILLIC.indexOf(Character.toLowerCase(ch))
    if (idx < 0) return false
    val latin = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(LATIN[idx].code)
    // Key.keyCode хранит ещё и расположение клавиши в старших битах — сравниваем сам VK-код
    return latin == key.nativeKeyCode
}

/** Ctrl(/Cmd)+клавиша на нажатии, без Alt; shift — по требованию. */
fun KeyEvent.isCtrlShortcut(key: Key, shift: Boolean = false): Boolean =
    type == KeyEventType.KeyDown &&
        (isCtrlPressed || isMetaPressed) &&
        !isAltPressed &&
        isShiftPressed == shift &&
        matchesKey(key)

/**
 * Клавиша с учётом раскладки: в кириллице — латинская буква той же физической клавиши
 * (Ctrl+А при русской раскладке — это Key.F), остальные клавиши как есть.
 */
fun KeyEvent.layoutKey(): Key {
    val letter = physicalLatinLetter() ?: return key
    val code = java.awt.event.KeyEvent.VK_A + (letter - 'a')
    return if (key.nativeKeyCode == code) key else Key(code)
}

/**
 * Буква, «набранная» вместе с Ctrl. В латинице AWT присылает управляющий символ (Ctrl+F — 0x06),
 * а в кириллице — саму букву «а», и поле ввода вставляло бы её вместе с сочетанием клавиш.
 * AltGr (Ctrl+Alt на части раскладок) не трогаем.
 */
fun isCtrlLetterTyped(e: java.awt.event.KeyEvent): Boolean =
    e.id == java.awt.event.KeyEvent.KEY_TYPED &&
        (e.isControlDown || e.isMetaDown) &&
        !e.isAltDown && !e.isAltGraphDown &&
        Character.isLetter(e.keyChar)

private val isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Буква или цифра, «набранная» вместе с Alt (без Ctrl и AltGr): на Linux AWT присылает её отдельным событием,
 * и Alt+Z (перенос строк) заодно вставлял в текст «z». На macOS Option набирает символы — там не трогаем.
 */
fun isAltLetterTyped(e: java.awt.event.KeyEvent): Boolean =
    !isMacOs &&
        e.id == java.awt.event.KeyEvent.KEY_TYPED &&
        e.isAltDown && !e.isAltGraphDown && !e.isControlDown && !e.isMetaDown &&
        Character.isLetterOrDigit(e.keyChar)

/** Отбрасывать [isCtrlLetterTyped] и [isAltLetterTyped] во всём приложении (до передачи в окна Compose). */
fun installCtrlLetterFilter() {
    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher {
        isCtrlLetterTyped(it) || isAltLetterTyped(it)
    }
}

/** Латинская буква физической клавиши (для Ctrl+буква в кириллической раскладке). */
fun KeyEvent.physicalLatinLetter(): Char? {
    val awt = awtEvent() ?: return null
    val code = awt.keyCode
    if (code in java.awt.event.KeyEvent.VK_A..java.awt.event.KeyEvent.VK_Z) return ('a' + (code - java.awt.event.KeyEvent.VK_A))
    val ext = awt.extendedKeyCode
    val ch = when {
        ext and 0x01000000 != 0 -> (ext and 0xFFFFFF).toChar()
        awt.keyChar != java.awt.event.KeyEvent.CHAR_UNDEFINED -> awt.keyChar
        else -> return null
    }
    val idx = CYRILLIC.indexOf(Character.toLowerCase(ch))
    if (idx < 0) return null
    val latin = LATIN[idx]
    return if (latin in 'a'..'z') latin else null
}
