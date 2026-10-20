package com.tim.dsmali

/**
 * Ошибка, привязанная к строке исходного текста smali ([line] — с 1). Разбор бросает [SmaliParseException],
 * сборка — [SmaliBuildException]; обёртки выше по стеку сохраняют их как `cause`, строку достаёт [lineOf].
 */
interface SmaliSourceLine {
    val line: Int
}

class SmaliParseException(override val line: Int, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause), SmaliSourceLine

class SmaliBuildException(override val line: Int, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause), SmaliSourceLine

/** Строка самой глубокой ошибки с известным местом в цепочке причин; 0 — место неизвестно. */
fun lineOf(e: Throwable): Int {
    var line = 0
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 64) {
        if (t is SmaliSourceLine && t.line > 0) line = t.line
        t = t.cause
    }
    return line
}
