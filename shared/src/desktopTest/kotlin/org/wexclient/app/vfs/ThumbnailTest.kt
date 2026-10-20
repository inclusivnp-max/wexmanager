package org.wexclient.app.vfs

import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThumbnailTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_thumbs")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `large jpeg, webp and repeated requests`() = runBlocking {
        val big = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        big.createGraphics().apply { color = java.awt.Color.ORANGE; fillRect(0, 0, 4000, 3000); dispose() }
        val jpg = dir.resolve("photo.jpg").also { ImageIO.write(big, "jpg", it.toFile()) }
        val mtime = Files.getLastModifiedTime(jpg).toMillis()
        val thumb = assertNotNull(MediaThumbnailLoader.loadThumbnail(jpg, isImage = true, isVideo = false, lastModified = mtime))
        assertEquals(64, thumb.width)
        assertEquals(64, thumb.height)
        assertSame(thumb, MediaThumbnailLoader.getCached(jpg, mtime))

        // WebP (в ImageIO нет) — через Skia
        val skia = org.jetbrains.skia.Surface.makeRasterN32Premul(200, 100)
        skia.canvas.clear(0xFF3366CC.toInt())
        val webpData = skia.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 90)!!
        val webp = Files.write(dir.resolve("pic.webp"), webpData.bytes)
        val w = assertNotNull(MediaThumbnailLoader.loadThumbnail(webp, isImage = true, isVideo = false, lastModified = 1L))
        assertEquals(64, w.width)

        // Не картинка — null, и повторно не пытаемся
        val bad = Files.writeString(dir.resolve("broken.png"), "not an image")
        assertNull(MediaThumbnailLoader.loadThumbnail(bad, isImage = true, isVideo = false, lastModified = 2L))
        assertNull(MediaThumbnailLoader.loadThumbnail(bad, isImage = true, isVideo = false, lastModified = 2L))
    }

    @Test
    fun `thumbnails of the thumbnail cache stay in memory`() = runBlocking {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        // Первая миниатюра создаёт папку кэша
        val outside = dir.resolve("seed.png").also { ImageIO.write(img, "png", it.toFile()) }
        assertNotNull(MediaThumbnailLoader.loadThumbnail(outside, isImage = true, isVideo = false, lastModified = 20L))
        val cache = Path.of(System.getProperty("user.home"), ".cache/wexdesktop/thumbnails")
        val inside = cache.resolve("zz-test-${System.nanoTime()}.png").also { ImageIO.write(img, "png", it.toFile()) }
        try {
            assertTrue(MediaThumbnailLoader.insideDiskCache(inside))
            val before = MediaThumbnailLoader.diskCacheFiles()
            for (i in 0 until 5) {
                assertNotNull(MediaThumbnailLoader.loadThumbnail(inside, isImage = true, isVideo = false, lastModified = 100L + i))
            }
            assertEquals(before, MediaThumbnailLoader.diskCacheFiles())
            assertFalse(MediaThumbnailLoader.insideDiskCache(outside))
        } finally {
            Files.deleteIfExists(inside)
        }
    }

    @Test
    fun `video thumbnails come only from the local file`() = runBlocking {
        if (!org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("ffmpeg")) return@runBlocking
        val clip = dir.resolve("clip.mp4")
        val make = ProcessBuilder(
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", "-y", clip.toString()
        ).redirectErrorStream(true).start()
        assertEquals(0, make.waitFor())
        val thumb = assertNotNull(MediaThumbnailLoader.loadThumbnail(clip, isImage = false, isVideo = true, lastModified = 10L))
        assertEquals(64, thumb.width)

        // «Видео», которое на деле плейлист со ссылкой в сеть: запроса быть не должно
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var hits = 0
        server.createContext("/") { ex -> hits++; ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/seg.ts"
            for (name in listOf("fake.ts", "fake.mp4", "fake.mkv")) {
                val fake = Files.writeString(dir.resolve(name), "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n$url\n#EXT-X-ENDLIST\n")
                assertNull(MediaThumbnailLoader.loadThumbnail(fake, isImage = false, isVideo = true, lastModified = 11L))
            }
            assertEquals(0, hits)
        } finally {
            server.stop(0)
        }
    }
}
