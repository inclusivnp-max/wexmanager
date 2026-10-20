package org.wexclient.app.vfs

import java.nio.file.Path

/** Что можно вставить в папку. */
sealed interface ClipContent {
    data class FileList(val paths: List<Path>, val cut: Boolean) : ClipContent

    /** Картинка из буфера — сразу PNG: так её и сохраняют в папку. */
    class Picture(val png: ByteArray) : ClipContent
    data class PlainText(val text: String) : ClipContent
}

/**
 * Файлы в буфере обмена: Ctrl+C / Ctrl+X и вставка. Десктоп — системный буфер (обмен с Dolphin/Nautilus),
 * Android — свой буфер WEX (пути внутри архивов системе не передать) и текст путей в системном.
 */
expect object FileClipboard {
    /** Вырезанные пути — в списке они полупрозрачные. */
    val cutPaths: Set<Path>

    fun copy(paths: List<Path>)
    fun cut(paths: List<Path>)

    /** После вставки вырезанного буфер пустеет; [moved] — что перенесено (вырезанное в другой программе). */
    fun clearCut(moved: Collection<Path> = emptyList())

    /** Читает буфер — вызывать не из потока интерфейса. */
    fun read(): ClipContent?
}
