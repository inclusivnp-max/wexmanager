package org.wexclient.app.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter

/**
 * Картинки на уровне пикселей — то, чего нет в общем Compose: декодирование, PNG, SVG, освобождение
 * памяти. Десктоп — Skia, Android — android.graphics.
 */
expect object PlatformImages {
    /** PNG, JPEG, WebP, GIF, BMP… → картинка в исходном размере; null — не картинка или битая. */
    fun decode(bytes: ByteArray): ImageBitmap?

    /** Размер закодированной картинки по заголовку, без декодирования пикселей. */
    fun size(bytes: ByteArray): Pair<Int, Int>?

    fun encodePng(image: ImageBitmap): ByteArray?

    /** SVG как Painter (intrinsicSize — размер документа, если указан). */
    fun svgPainter(bytes: ByteArray): Painter

    /** Картинка из пикселей ARGB (не premultiplied), строками по [width]. */
    fun fromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap

    /** Освободить пиксели сразу, не дожидаясь сборщика мусора (они вне кучи Java). */
    fun release(image: ImageBitmap)
}

/**
 * Картинка готова, рисовать в неё больше не будут — помечаем неизменяемой. На десктопе это важно:
 * Compose рисует ImageBitmap через Image.makeFromBitmap на каждом кадре, и у изменяемого Bitmap Skia
 * копирует все пиксели и заново грузит их в видеопамять. У неизменяемого текстура берётся из кэша GPU.
 */
expect fun ImageBitmap.frozen(): ImageBitmap

expect object ImageScale {
    /**
     * Картинка из файла, уменьшенная так, чтобы большая сторона была не больше [maxPx].
     * Размер читается из заголовка до декодирования: заявленные больше [maxSourcePx] не открываются.
     */
    fun decodeScaled(
        bytes: ByteArray,
        maxPx: Int,
        maxSourcePx: Int = 4096,
        /** PNG в пару мегабайт может заявить 30 000 × 30 000 — это 3,6 ГБ после декодирования. */
        maxSourcePixels: Long = maxSourcePx.toLong() * maxSourcePx
    ): ImageBitmap?
}
