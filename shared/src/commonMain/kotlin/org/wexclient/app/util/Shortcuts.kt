package org.wexclient.app.util

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type

/** Та же физическая клавиша, что и [key], независимо от активной раскладки. */
expect fun KeyEvent.matchesKey(key: Key): Boolean

/**
 * Клавиша с учётом раскладки: в кириллице — латинская буква той же физической клавиши
 * (Ctrl+А при русской раскладке — это Key.F), остальные клавиши как есть.
 */
expect fun KeyEvent.layoutKey(): Key

/** Латинская буква физической клавиши (для Ctrl+буква в кириллической раскладке). */
expect fun KeyEvent.physicalLatinLetter(): Char?

/**
 * Сочетание из нажатия: код клавиши в нумерации [Vk] (буква — латинская той же физической клавиши)
 * и модификаторы. null — у клавиши нет кода. Отдельно нажатые модификаторы отсекает [KeyCombo.of].
 */
expect fun KeyEvent.toKeyCombo(): KeyCombo?

/** Ctrl(/Cmd)+клавиша на нажатии, без Alt; shift — по требованию. */
fun KeyEvent.isCtrlShortcut(key: Key, shift: Boolean = false): Boolean =
    type == KeyEventType.KeyDown &&
        (isCtrlPressed || isMetaPressed) &&
        !isAltPressed &&
        isShiftPressed == shift &&
        matchesKey(key)
