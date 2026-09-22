package org.wexclient.app.markdown

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownImagesTest {
    /** Однотонный PNG нужного размера: файл — килобайты, картинка — сколько угодно мегапикселей. */
    private fun blankPng(w: Int, h: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bos)
        fun chunk(type: String, data: ByteArray) {
            val t = type.toByteArray()
            out.writeInt(data.size)
            out.write(t)
            out.write(data)
            val crc = java.util.zip.CRC32()
            crc.update(t)
            crc.update(data)
            out.writeInt(crc.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(1).put(0).put(0).put(0).put(0).array())
        val row = ByteArray(1 + (w + 7) / 8)
        val z = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(z, java.util.zip.Deflater(9)).use { d -> repeat(h) { d.write(row) } }
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return bos.toByteArray()
    }

    @Test
    fun `huge declared image size is refused before decoding`() {
        val bomb = blankPng(12000, 12000)
        assertTrue(bomb.size < 1_000_000, "${bomb.size}")
        kotlin.test.assertFailsWith<java.io.IOException> { MarkdownImages.decode(bomb) }
        val small = MarkdownImages.decode(blankPng(100, 50))
        assertEquals(100f, small.cssWidth)
        assertEquals(50f, small.cssHeight)
    }

    @Test
    fun `local network addresses are recognised`() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:192.168.0.1")) {
            assertTrue(MarkdownImages.isPrivate(InetAddress.getByName(ip)), ip)
        }
        for (ip in listOf("140.82.112.3", "1.1.1.1", "2606:4700:4700::1111")) {
            assertFalse(MarkdownImages.isPrivate(InetAddress.getByName(ip)), ip)
        }
    }

    @Test
    fun `images from this machine load only on request`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var hits = 0
        server.createContext("/pic.png") { ex ->
            hits++
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }
        server.createContext("/loop") { ex ->
            ex.responseHeaders.add("Location", "/loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/pic.png"
            val blocked = MarkdownImages.load(url, null)
            assertIs<PrivateAddressException>(blocked.exceptionOrNull())
            assertEquals(0, hits, "запрос не должен уйти без разрешения")

            val allowed = MarkdownImages.load(url, null, allowPrivate = true)
            assertEquals(3, allowed.getOrThrow().bitmap.width)
            assertEquals(1, hits)
            // Уже загруженное берётся из кэша
            assertTrue(MarkdownImages.load(url, null).isSuccess)

            val loop = MarkdownImages.load("http://127.0.0.1:${server.address.port}/loop", null, allowPrivate = true)
            assertEquals("Слишком много перенаправлений", loop.exceptionOrNull()?.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `only web and mail links leave the program`() {
        for (url in listOf("https://github.com/x", "http://example.com", "mailto:me@example.com", "HTTPS://EXAMPLE.COM", "ftp://mirror/x")) {
            assertTrue(isExternalLink(url), url)
        }
        for (url in listOf("file:///tmp/x.desktop", "steam://run/1", "vscode://file/x", "javascript:alert(1)", "smb://host/share", "https:evil", "docs/readme.md")) {
            assertFalse(isExternalLink(url), url)
        }
    }
}
