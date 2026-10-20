package org.wexclient.app.util

/**
 * Коды клавиш в нумерации AWT (java.awt.event.KeyEvent.VK_*). В ней хранятся сочетания [KeyCombo] — и в памяти,
 * и в keymap.properties (там по именам) — одинаково на десктопе и на Android, где коды клавиш свои.
 */
object Vk {
    const val VK_CONTROL = 17
    const val VK_SHIFT = 16
    const val VK_ALT = 18
    const val VK_META = 157
    const val VK_ALT_GRAPH = 65406
    const val VK_CAPS_LOCK = 20
    const val VK_NUM_LOCK = 144
    const val VK_SCROLL_LOCK = 145
    const val VK_UNDEFINED = 0
    const val VK_ENTER = 10
    const val VK_ESCAPE = 27
    const val VK_TAB = 9
    const val VK_SPACE = 32
    const val VK_BACK_SPACE = 8
    const val VK_DELETE = 127
    const val VK_INSERT = 155
    const val VK_HOME = 36
    const val VK_END = 35
    const val VK_PAGE_UP = 33
    const val VK_PAGE_DOWN = 34
    const val VK_UP = 38
    const val VK_DOWN = 40
    const val VK_LEFT = 37
    const val VK_RIGHT = 39
    const val VK_BACK_QUOTE = 192
    const val VK_MINUS = 45
    const val VK_EQUALS = 61
    const val VK_OPEN_BRACKET = 91
    const val VK_CLOSE_BRACKET = 93
    const val VK_SEMICOLON = 59
    const val VK_QUOTE = 222
    const val VK_BACK_SLASH = 92
    const val VK_COMMA = 44
    const val VK_PERIOD = 46
    const val VK_SLASH = 47
    const val VK_PLUS = 521
    const val VK_ADD = 107
    const val VK_SUBTRACT = 109
    const val VK_MULTIPLY = 106
    const val VK_DIVIDE = 111
    const val VK_DECIMAL = 110
    const val VK_F1 = 112
    const val VK_F2 = 113
    const val VK_F3 = 114
    const val VK_F4 = 115
    const val VK_F5 = 116
    const val VK_F6 = 117
    const val VK_F7 = 118
    const val VK_F8 = 119
    const val VK_F9 = 120
    const val VK_F10 = 121
    const val VK_F11 = 122
    const val VK_F12 = 123
    const val VK_A = 65
    const val VK_Z = 90
    const val VK_0 = 48
    const val VK_9 = 57
    const val VK_NUMPAD0 = 96
    const val VK_NUMPAD9 = 105
    const val VK_CONTEXT_MENU = 525
}
