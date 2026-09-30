package org.wexclient.app.util

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compose рисует ImageBitmap через Image.makeFromBitmap на каждом кадре. У изменяемого Bitmap Skia при этом
 * копирует все пиксели (и заново грузит текстуру в видеопамять), у неизменяемого — делит их.
 */
class ImageFrozenTest {
    private fun pixelsAddr(image: Image): Long = image.peekPixels()!!.addr

    @Test
    fun `bitmap drawn into by hand is mutable and copied on every draw`() {
        // Так делают значки AppImage и SVG в markdown: пустая картинка, потом Canvas
        val skia = androidx.compose.ui.graphics.ImageBitmap(32, 32).asSkiaBitmap()
        assertFalse(skia.isImmutable)
        Image.makeFromBitmap(skia).use { a ->
            Image.makeFromBitmap(skia).use { b -> assertTrue(pixelsAddr(a) != pixelsAddr(b), "пиксели не копируются") }
        }
    }

    @Test
    fun `awt thumbnails come frozen`() {
        val awt = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        assertTrue(awt.toComposeImageBitmap().frozen().asSkiaBitmap().isImmutable)
    }

    @Test
    fun `frozen bitmap shares pixels between draws`() {
        val skia = androidx.compose.ui.graphics.ImageBitmap(32, 32).frozen().asSkiaBitmap()
        assertTrue(skia.isImmutable)
        Image.makeFromBitmap(skia).use { a ->
            Image.makeFromBitmap(skia).use { b -> assertEquals(pixelsAddr(a), pixelsAddr(b)) }
        }
    }

    @Test
    fun `decoded thumbnails and icons come frozen`() {
        val png = Surface.makeRasterN32Premul(300, 200).use { s ->
            s.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
        }
        val bitmap = assertNotNull(ImageScale.decodeScaled(png, maxPx = 64))
        assertEquals(64, bitmap.width)
        assertTrue(bitmap.asSkiaBitmap().isImmutable)
    }
}
