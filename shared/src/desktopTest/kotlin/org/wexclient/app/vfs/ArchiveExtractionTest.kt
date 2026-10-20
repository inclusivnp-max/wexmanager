package org.wexclient.app.vfs

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ArchiveExtractionTest {

    @Test
    fun testExtractableArchiveDetection() {
        val zipEntry = VfsEntry(
            name = "my_archive.zip",
            path = Paths.get("/tmp/my_archive.zip"),
            isDirectory = false,
            isArchive = true,
            size = 1024,
            lastModified = 1000L,
            itemType = VfsItemType.ARCHIVE_ZIP,
            extension = "zip"
        )
        assertTrue(zipEntry.isExtractableArchive)
        assertEquals("my_archive", zipEntry.archiveBaseName)

        val apkEntry = VfsEntry(
            name = "cool_app-v2.1.apk",
            path = Paths.get("/tmp/cool_app-v2.1.apk"),
            isDirectory = false,
            isArchive = true,
            size = 2048,
            lastModified = 1000L,
            itemType = VfsItemType.ARCHIVE_APK,
            extension = "apk"
        )
        assertTrue(apkEntry.isExtractableArchive)
        assertEquals("cool_app-v2.1", apkEntry.archiveBaseName)

        val tarGzEntry = VfsEntry(
            name = "source_code.tar.gz",
            path = Paths.get("/tmp/source_code.tar.gz"),
            isDirectory = false,
            isArchive = false,
            size = 4096,
            lastModified = 1000L,
            itemType = VfsItemType.ARCHIVE_ZIP,
            extension = "gz"
        )
        assertTrue(tarGzEntry.isExtractableArchive)
        assertEquals("source_code", tarGzEntry.archiveBaseName)

        val sevenZipEntry = VfsEntry(
            name = "backup_2026.7z",
            path = Paths.get("/tmp/backup_2026.7z"),
            isDirectory = false,
            isArchive = false,
            size = 8192,
            lastModified = 1000L,
            itemType = VfsItemType.ARCHIVE_ZIP,
            extension = "7z"
        )
        assertTrue(sevenZipEntry.isExtractableArchive)
        assertEquals("backup_2026", sevenZipEntry.archiveBaseName)

        val dirEntry = VfsEntry(
            name = "my_folder.zip",
            path = Paths.get("/tmp/my_folder.zip"),
            isDirectory = true,
            isArchive = false,
            size = 0,
            lastModified = 1000L,
            itemType = VfsItemType.FOLDER,
            extension = "zip"
        )
        assertFalse(dirEntry.isExtractableArchive)

        val regularFile = VfsEntry(
            name = "notes.txt",
            path = Paths.get("/tmp/notes.txt"),
            isDirectory = false,
            isArchive = false,
            size = 100,
            lastModified = 1000L,
            itemType = VfsItemType.TEXT_FILE,
            extension = "txt"
        )
        assertFalse(regularFile.isExtractableArchive)
    }

    @Test
    fun testExtractZipArchive() {
        val tempDir = Files.createTempDirectory("wex_extract_test")
        val archivePath = tempDir.resolve("test_archive.zip")
        val extractTarget = tempDir.resolve("extracted")

        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(archivePath))).use { zos ->
                zos.putNextEntry(ZipEntry("hello.txt"))
                zos.write("Hello WEX!".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("nested/world.txt"))
                zos.write("Nested file content".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val vfsManager = VfsManager()
            var reportedCount = 0
            vfsManager.extractArchive(archivePath, extractTarget) { current, total, _ ->
                reportedCount = current
            }

            assertTrue(Files.exists(extractTarget.resolve("hello.txt")))
            assertEquals("Hello WEX!", Files.readString(extractTarget.resolve("hello.txt")))

            assertTrue(Files.exists(extractTarget.resolve("nested/world.txt")))
            assertEquals("Nested file content", Files.readString(extractTarget.resolve("nested/world.txt")))
            assertEquals(2, reportedCount)
        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun testZipSlipProtection() {
        val tempDir = Files.createTempDirectory("wex_zipslip_test")
        val archivePath = tempDir.resolve("malicious.zip")
        val extractTarget = tempDir.resolve("target")

        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(archivePath))).use { zos ->
                zos.putNextEntry(ZipEntry("../outside.txt"))
                zos.write("Bad payload".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val vfsManager = VfsManager()
            assertFailsWith<SecurityException> {
                vfsManager.extractArchive(archivePath, extractTarget)
            }
        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
