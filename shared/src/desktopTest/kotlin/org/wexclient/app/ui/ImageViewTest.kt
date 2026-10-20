package org.wexclient.app.ui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageViewTest {
    private fun encode(img: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()

    @Test
    fun `ordinary pictures are shown as is`() {
        val view = assertNotNull(decodeForView(encode(BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png")))
        assertEquals(120, view.width)
        assertEquals(80, view.height)
        assertEquals(120, view.bitmap.width)
        assertFalse(view.reduced)

        // WebP — через Skia
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(64, 32)
        val webp = surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 80)!!.bytes
        val w = assertNotNull(decodeForView(webp))
        assertEquals(64, w.bitmap.width)
        assertNull(decodeForView("not an image".toByteArray()))
    }

    @Test
    fun `huge pictures are reduced and bombs are refused`() {
        // 72 Мп в оттенках серого — показывается уменьшенной, в памяти не больше 64 Мп
        val big = BufferedImage(9000, 8000, BufferedImage.TYPE_BYTE_GRAY)
        val jpeg = encode(big, "jpg")
        val view = assertNotNull(decodeForView(jpeg))
        assertEquals(9000, view.width)
        assertEquals(8000, view.height)
        assertTrue(view.reduced)
        assertTrue(view.bitmap.width.toLong() * view.bitmap.height <= 64_000_000L)

        // Заголовок PNG обещает 60000×60000 — такое не декодируем
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val t = type.toByteArray()
            data.write(t)
            data.write(body)
            data.writeInt(CRC32().apply { update(t); update(body) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(60000); writeInt(60000); write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 3, 0, 0, 0, 0, 1))
        chunk("IEND", ByteArray(0))
        assertNull(decodeForView(out.toByteArray()))
    }
}
