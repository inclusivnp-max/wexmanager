package com.tim.dsmali.model

sealed class SmaliBodyElement {
    /** Строка в тексте, из которого элемент разобран (с 1); 0 — не из текста. В equals не участвует. */
    var line: Int = 0
}
