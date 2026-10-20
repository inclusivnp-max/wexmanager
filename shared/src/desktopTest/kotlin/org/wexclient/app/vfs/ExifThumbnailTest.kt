package org.wexclient.app.vfs

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExifThumbnailTest {
    private fun jpeg(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    /** JPEG с APP1: IFD0 пуст, IFD1 указывает на [thumb]. [little] — порядок байт TIFF (II или MM). */
    private fun withExif(thumb: ByteArray, little: Boolean): ByteArray {
        val tiff = java.nio.ByteBuffer.allocate(8 + 2 + 4 + 2 + 2 * 12 + 4 + thumb.size)
            .order(if (little) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
        tiff.put(if (little) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D))
        tiff.putShort(42)
        tiff.putInt(8) // IFD0
        tiff.putShort(0) // IFD0: записей нет
        val ifd1 = 8 + 2 + 4
        tiff.putInt(ifd1) // следующий IFD
        tiff.putShort(2)
        val data = ifd1 + 2 + 2 * 12 + 4
        tiff.putShort(0x0201); tiff.putShort(4); tiff.putInt(1); tiff.putInt(data)
        tiff.putShort(0x0202); tiff.putShort(4); tiff.putInt(1); tiff.putInt(thumb.size)
        tiff.putInt(0)
        tiff.put(thumb)
        val app1 = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff.array()
        val len = app1.size + 2
        val main = jpeg(400, 300)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            app1 + main.copyOfRange(2, main.size)
    }

    @Test
    fun `embedded thumbnail is found in both byte orders`() {
        val thumb = jpeg(160, 120)
        for (little in listOf(true, false)) {
            val found = assertNotNull(exifThumbnail(withExif(thumb, little)), "little=$little")
            assertContentEquals(thumb, found)
            assertNotNull(ImageIO.read(found.inputStream()))
        }
    }

    @Test
    fun `picture size is read from SOF past the EXIF segment`() {
        kotlin.test.assertEquals(400 to 300, jpegSize(withExif(jpeg(160, 120), true)))
        kotlin.test.assertEquals(123 to 45, jpegSize(jpeg(123, 45)))
        assertNull(jpegSize(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        // Начало файла без SOF (обрезано на APP1) — размера нет
        assertNull(jpegSize(withExif(jpeg(160, 120), true).copyOf(200)))
    }

    @Test
    fun `plain jpeg, png and garbage have no thumbnail`() {
        assertNull(exifThumbnail(jpeg(100, 100)))
        assertNull(exifThumbnail(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(exifThumbnail(ByteArray(0)))
        // Обрезанный APP1 и мусор внутри — без исключений
        val good = withExif(jpeg(160, 120), true)
        for (cut in listOf(8, 20, 40, 60, 200)) exifThumbnail(good.copyOf(cut))
        val random = java.util.Random(7)
        repeat(300) {
            val b = good.copyOf()
            repeat(6) { b[6 + random.nextInt(80)] = random.nextInt(256).toByte() }
            exifThumbnail(b)
        }
    }
}
