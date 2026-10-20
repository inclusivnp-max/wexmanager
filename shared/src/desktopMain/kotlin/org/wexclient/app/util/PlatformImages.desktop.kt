package org.wexclient.app.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual object PlatformImages {
    actual fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull()

    actual fun size(bytes: ByteArray): Pair<Int, Int>? =
        runCatching { Image.makeFromEncoded(bytes).use { it.width to it.height } }.getOrNull()

    actual fun encodePng(image: ImageBitmap): ByteArray? = runCatching {
        Image.makeFromBitmap(image.asSkiaBitmap()).use { img -> img.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes } }
    }.getOrNull()

    @Suppress("DEPRECATION")
    actual fun svgPainter(bytes: ByteArray): Painter = loadSvgPainter(bytes.inputStream(), Density(1f))

    actual fun fromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
        // ARGB -> BGRA premul (N32 на little-endian)
        val bytes = ByteArray(width * height * 4)
        for (p in pixels.indices) {
            val c = pixels[p]
            if (c == 0) continue
            val a = (c ushr 24) and 0xFF
            val o = p * 4
            bytes[o] = ((c and 0xFF) * a / 255).toByte()
            bytes[o + 1] = (((c shr 8) and 0xFF) * a / 255).toByte()
            bytes[o + 2] = (((c shr 16) and 0xFF) * a / 255).toByte()
            bytes[o + 3] = a.toByte()
        }
        val bmp = Bitmap()
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL)
        bmp.allocPixels(info)
        bmp.installPixels(info, bytes, width * 4)
        bmp.setImmutable() // см. frozen
        return bmp.asComposeImageBitmap()
    }

    actual fun release(image: ImageBitmap) {
        image.asSkiaBitmap().close()
    }
}
