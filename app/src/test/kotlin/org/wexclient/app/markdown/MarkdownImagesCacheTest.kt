package org.wexclient.app.markdown

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Кэш картинок Markdown: ключи, ошибки и число записей не растут без предела. */
class MarkdownImagesCacheTest {
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `data address is keyed by hash`() {
        val src = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val key = MarkdownImages.key(src, null)
        assertTrue(key.length < 100, key)
        assertEquals(key, MarkdownImages.key(src, null))
        assertNotEquals(key, MarkdownImages.key("data:image/png;base64,AAAA", null))
        assertEquals(3, MarkdownImages.load(src, null).getOrThrow().bitmap.width)
        assertNotNull(MarkdownImages.cached(src, null))
    }

    @Test
    fun `network failure is retried instead of cached`() {
        var down = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/flaky.png") { ex ->
            if (down) {
                // Обрыв без ответа — как упавшая сеть
                ex.close()
            } else {
                ex.sendResponseHeaders(200, png.size.toLong())
                ex.responseBody.use { it.write(png) }
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/flaky.png"
            assertTrue(MarkdownImages.load(url, null, allowPrivate = true).isFailure)
            down = false
            assertEquals(3, MarkdownImages.load(url, null, allowPrivate = true).getOrThrow().bitmap.width)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `failures do not pile up without limit`() {
        val dir = Files.createTempDirectory("md-images")
        try {
            repeat(MarkdownImages.MAX_ENTRIES + 200) { MarkdownImages.load("missing-$it.png", dir) }
            assertTrue(MarkdownImages.cacheSize() <= MarkdownImages.MAX_ENTRIES, "${MarkdownImages.cacheSize()}")
        } finally {
            Files.deleteIfExists(dir)
        }
    }
}
