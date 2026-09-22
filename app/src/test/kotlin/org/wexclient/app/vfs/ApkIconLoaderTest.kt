package org.wexclient.app.vfs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ApkIconLoaderTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_apkicon")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun apk(name: String, vararg entries: Pair<String, ByteArray>): Path {
        val file = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply { color = java.awt.Color.GREEN; fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** PNG, в заголовке которого заявлено 60000×60000: декодировать такое — гигабайты памяти. */
    private fun hugeClaimedPng(): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            data.write(typeBytes)
            data.write(body)
            val crc = CRC32().apply { update(typeBytes); update(body) }
            data.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(60000); writeInt(60000); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 3, 0, 0, 0, 0, 1))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `icon is scaled down and cached per modification time`() = runBlocking {
        val file = apk("app.apk", "res/mipmap-xxxhdpi/ic_launcher.png" to png(512, 512))
        val mtime = Files.getLastModifiedTime(file).toMillis()
        // Много строк одновременно просят одну иконку — разбор один
        val icons = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { ApkIconLoader.loadIcon(file, mtime) } }.awaitAll()
        val icon = assertNotNull(icons.first())
        assertEquals(192, icon.width)
        assertEquals(192, icon.height)
        assertSame(icon, ApkIconLoader.getCachedIcon(file, mtime))
        // Файл заменили — старая иконка не подходит
        assertNull(ApkIconLoader.getCachedIcon(file, mtime + 1000))
    }

    @Test
    fun `hostile images are skipped without decoding`() = runBlocking {
        val huge = apk("huge.apk", "res/mipmap-xxxhdpi/ic_launcher.png" to hugeClaimedPng())
        assertNull(ApkIconLoader.loadIcon(huge, 1L))

        // Запись больше предела не читается целиком; следующий кандидат подходит
        val big = ByteArray(9 shl 20)
        val mixed = apk(
            "mixed.apk",
            "res/mipmap-xxxhdpi/ic_launcher.png" to big,
            "res/mipmap-hdpi/ic_launcher.png" to png(72, 72)
        )
        val icon = assertNotNull(ApkIconLoader.loadIcon(mixed, 1L))
        assertEquals(72, icon.width)

        val broken = Files.writeString(dir.resolve("broken.apk"), "not a zip")
        assertNull(ApkIconLoader.loadIcon(broken, 1L))
    }
}
