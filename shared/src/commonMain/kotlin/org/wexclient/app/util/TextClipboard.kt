package org.wexclient.app.util

/** Системный буфер обмена для текста — из обработчиков меню и кнопок, вне Compose. */
expect object TextClipboard {
    fun copy(text: String)

    /** Текст из буфера; null — там не текст или он пуст. */
    fun text(): String?
}
