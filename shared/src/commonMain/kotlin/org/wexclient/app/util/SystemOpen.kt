package org.wexclient.app.util

import java.io.File

/**
 * Команда «открыть программой по умолчанию» для файла или ссылки: xdg-open на Linux,
 * обработчик оболочки на Windows (без cmd /c start — там «&» в ссылке разрезал бы команду).
 */
fun systemOpenCommand(target: String): List<String> =
    if (File.separatorChar == '\\') listOf("rundll32", "url.dll,FileProtocolHandler", target)
    else listOf("xdg-open", target)
