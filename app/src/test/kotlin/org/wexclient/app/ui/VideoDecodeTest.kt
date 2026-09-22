package org.wexclient.app.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.vfs.archive.ArchiveFormats
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoDecodeTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_video")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `decode size follows the view area`() {
        val fullHd = VideoMetadata(width = 1920, height = 1080, fps = "30")
        // Окно меньше видео — кадр в размер окна, стороны чётные
        assertEquals(IntSize(930, 522), videoDecodeSize(fullHd, IntSize(930, 523)))
        // Окно больше — не растягиваем сверх исходника
        assertEquals(IntSize(1280, 720), videoDecodeSize(VideoMetadata(width = 1280, height = 720), IntSize(2560, 1440)))
        // 4K во весь экран 4K — не больше ~1080p по площади
        val uhd = videoDecodeSize(VideoMetadata(width = 3840, height = 2160), IntSize(3840, 2160))
        assertEquals(IntSize(1920, 1080), uhd)
        // Телефон, повёрнутый на 90°: высокий кадр
        val phone = VideoMetadata(width = 1920, height = 1080, rotation = -90)
        assertEquals(1080, phone.displayWidth)
        assertEquals(IntSize(294, 522), videoDecodeSize(phone, IntSize(930, 523)))
        // Анаморф 1440×1080 с пикселями 4:3 — на экране 1920×1080
        assertEquals(IntSize(1920, 1080), videoDecodeSize(VideoMetadata(width = 1440, height = 1080, sar = 4.0 / 3), IntSize(1920, 1080)))
        // Размеры ещё не известны — вписываем с полями, чтобы не исказить
        assertEquals(IntSize(1280, 720), videoDecodeSize(null, IntSize.Zero))
        assertTrue("pad=1280:720" in videoFilter(null, IntSize(1280, 720), playback = true))
        assertEquals("scale=930:522:flags=lanczos,setsar=1", videoFilter(fullHd, IntSize(930, 522), playback = true))
        assertTrue(videoFilter(VideoMetadata(width = 640, height = 360, fps = "120"), IntSize(640, 360), playback = true).startsWith("fps=60,"))
        assertTrue(!videoFilter(VideoMetadata(width = 640, height = 360, fps = "120"), IntSize(640, 360), playback = false).startsWith("fps="))
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf("ffmpeg", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertEquals(0, p.waitFor(), out)
    }

    private fun entry(path: Path) = VfsEntry(path.fileName.toString(), path, false, false, Files.size(path), 0, VfsItemType.VIDEO, "mp4")

    @Test
    fun `raw frames have exactly the requested size`() {
        if (!ArchiveFormats.toolAvailable("ffmpeg") || !ArchiveFormats.toolAvailable("ffprobe")) return
        val anamorphic = dir.resolve("anamorphic.mp4")
        ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=10", "-t", "1", "-vf", "setsar=4/3", "-c:v", "libx264", "-pix_fmt", "yuv420p", anamorphic.toString())
        val rotated = dir.resolve("rotated.mp4")
        ffmpeg("-display_rotation", "90", "-i", anamorphic.toString(), "-c", "copy", rotated.toString())

        val meta = probeVideoMetadata(entry(anamorphic))
        assertEquals(4.0 / 3, meta.sar, 1e-9)
        assertEquals(427, meta.displayWidth)
        val size = videoDecodeSize(meta, IntSize(2000, 2000))
        assertEquals(IntSize(426, 240), size)
        val frame = assertNotNull(extractFrameAt(entry(anamorphic), 0.5, meta, size))
        assertEquals(426, frame.width)
        assertEquals(240, frame.height)
        val pixels = frame.toPixelMap()
        assertEquals(1f, pixels[213, 120].alpha)

        val turned = probeVideoMetadata(entry(rotated))
        assertEquals(90, turned.rotation)
        val turnedSize = videoDecodeSize(turned, IntSize(2000, 2000))
        assertEquals(IntSize(240, 426), turnedSize)
        val still = assertNotNull(extractFrameAt(entry(rotated), 0.5, turned, turnedSize))
        assertEquals(240, still.width)
        assertEquals(426, still.height)

        // Поток воспроизведения: ровно по кадру на W×H×4 байт (10 кадров за секунду)
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-i", rotated.toString(), "-an",
            "-vf", videoFilter(turned, turnedSize, playback = true), "-pix_fmt", "bgra", "-f", "rawvideo", "-"
        ).start()
        val bytes = p.inputStream.readAllBytes()
        assertEquals(0, p.waitFor())
        assertEquals(10L * 240 * 426 * 4, bytes.size.toLong())
    }
}
