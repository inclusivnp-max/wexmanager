package org.wexclient.app.apk

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApkAnalyzerMetadataTest {
    private val root = Files.createTempDirectory("wex-apk-metadata")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `missing manifest does not invent version or SDK values`() {
        val file = root.resolve("missing-manifest.apk").toFile()
        ZipOutputStream(file.outputStream()).use { }
        val details = ApkAnalyzer.analyze(file)
        assertEquals("—", details.versionName)
        assertEquals("—", details.versionCode)
        assertEquals("—", details.minSdk)
        assertEquals("—", details.targetSdk)
        assertEquals("—", details.minSdkName)
        assertEquals("—", details.targetSdkName)
    }

    @Test
    fun `values present in the manifest are preserved`() {
        val file = root.resolve("manifest.apk").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="test.app" android:versionName="3.2" android:versionCode="7">
                    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
                    <application android:label="Example" />
                </manifest>
            """.trimIndent().toByteArray())
            zip.closeEntry()
        }
        val details = ApkAnalyzer.analyze(file)
        assertEquals("3.2", details.versionName)
        assertEquals("7", details.versionCode)
        assertEquals("26", details.minSdk)
        assertEquals("36", details.targetSdk)
    }

    @Test
    fun `unreadable APK is not described as clean and unsigned`() {
        val file = root.resolve("broken.apk").toFile().apply { writeText("not a ZIP") }
        val details = ApkAnalyzer.analyze(file)
        assertEquals("Не удалось проверить", details.protection)
        assertEquals("Не удалось проверить", details.signatureScheme)
        assertFalse(details.isSignatureValid)
    }
}
