package org.wexclient.app.ui

import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPlayerMetadataTest {

    @Test
    fun testProbeNonExistentFile() {
        val entry = VfsEntry(
            name = "missing.mp4",
            path = Paths.get("/tmp/missing_non_existent_${System.currentTimeMillis()}.mp4"),
            isDirectory = false,
            isArchive = false,
            size = 0L,
            lastModified = 0L,
            itemType = VfsItemType.VIDEO,
            extension = "mp4"
        )
        val meta = probeVideoMetadata(entry)
        assertEquals(0.0, meta.durationSec)
        assertEquals(0, meta.width)
        assertEquals(0, meta.height)
        assertEquals("", meta.fps)
    }

    @Test
    fun testProbe60FpsVideo() {
        val file = File("/home/zapahtravki/2026-06-20 14-05-23.mp4")
        if (!file.exists()) return

        val entry = VfsEntry(
            name = file.name,
            path = file.toPath(),
            isDirectory = false,
            isArchive = false,
            size = file.length(),
            lastModified = file.lastModified(),
            itemType = VfsItemType.VIDEO,
            extension = "mp4"
        )
        val meta = probeVideoMetadata(entry)
        assertEquals("60", meta.fps)
        assertEquals(1920, meta.width)
        assertEquals(1080, meta.height)
        assertEquals("h264", meta.videoCodec.lowercase())
        assertEquals("aac", meta.audioCodec.lowercase())
        assertTrue(meta.durationSec > 50.0)
    }

    @Test
    fun testProbe120FpsVideo() {
        val file = File("/home/zapahtravki/2026-09-12 15-09-51.mp4")
        if (!file.exists()) return

        val entry = VfsEntry(
            name = file.name,
            path = file.toPath(),
            isDirectory = false,
            isArchive = false,
            size = file.length(),
            lastModified = file.lastModified(),
            itemType = VfsItemType.VIDEO,
            extension = "mp4"
        )
        val meta = probeVideoMetadata(entry)
        assertEquals("120", meta.fps)
        assertEquals(1920, meta.width)
        assertEquals(1080, meta.height)
        assertEquals("h264", meta.videoCodec.lowercase())
    }
}
