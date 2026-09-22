package org.wexclient.app.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

object ImageScale {
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
    ): ImageBitmap? {
        val image = try { Image.makeFromEncoded(bytes) } catch (_: Exception) { return null }
        try {
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0 || w > maxSourcePx || h > maxSourcePx || w.toLong() * h > maxSourcePixels) return null
            val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(tw, th)
            try {
                surface.canvas.drawImageRect(
                    image, Rect.makeWH(w.toFloat(), h.toFloat()), Rect.makeWH(tw.toFloat(), th.toFloat()),
                    SamplingMode.MITCHELL, null, true
                )
                surface.makeImageSnapshot().use { return it.toComposeImageBitmap() }
            } finally {
                surface.close()
            }
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }
}
