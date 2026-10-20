package org.wexclient.app.vfs

import androidx.compose.ui.graphics.ImageBitmap
import java.nio.file.Path

/** Сторона миниатюры в пикселях. */
internal const val THUMBNAIL_SIZE = 64

/** Миниатюры [THUMBNAIL_SIZE]×[THUMBNAIL_SIZE] (обрезка по центру) — пиксельная часть, у каждой платформы своя. */
internal expect object ThumbnailCodec {
    /** Из картинки; файлы больше [maxBytes] не читаем. null — не вышло. */
    fun image(path: Path, vfsManager: VfsManager?, maxBytes: Long): ImageBitmap?

    /** Кадр видео около первой секунды; null — не вышло (нет декодера, файл не на диске). */
    fun video(path: Path): ImageBitmap?
}
