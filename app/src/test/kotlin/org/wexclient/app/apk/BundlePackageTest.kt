package org.wexclient.app.apk

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BundlePackageTest {
    private val baseApk = javaClass.getResourceAsStream("/fixtures/minimal-unsigned.apk")!!.use { it.readBytes() }

    private fun bundle(vararg entries: Pair<String, ByteArray>): java.nio.file.Path {
        val file = Files.createTempFile("wex_bundle", ".xapk")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `package comes from manifest json or streamed base apk`() {
        val json = bundle("manifest.json" to """{"package_name": "com.example.game", "version_code": "3"}""".toByteArray())
        assertEquals("com.example.game", ApkAnalyzer.bundlePackageName(json))

        val splits = bundle("config.arm64_v8a.apk" to ByteArray(10), "base.apk" to baseApk)
        assertEquals("org.wexclient.fixture", ApkAnalyzer.bundlePackageName(splits))

        // manifest.json больше предела не читается целиком — берётся base.apk
        val huge = bundle("manifest.json" to ByteArray(2 shl 20) { ' '.code.toByte() }, "base.apk" to baseApk)
        assertEquals("org.wexclient.fixture", ApkAnalyzer.bundlePackageName(huge))

        // APKCombo называет основной APK по пакету, bundletool — base-master.apk: пакет есть в любой части
        val combo = bundle("com.example-7.apk" to baseApk, "config.en-7.apk" to baseApk)
        assertEquals("org.wexclient.fixture", ApkAnalyzer.bundlePackageName(combo))
        val tool = bundle("toc.pb" to ByteArray(4), "splits/base-master.apk" to baseApk)
        assertEquals("org.wexclient.fixture", ApkAnalyzer.bundlePackageName(tool))
        Files.deleteIfExists(combo)
        Files.deleteIfExists(tool)

        val none = bundle("readme.txt" to "x".toByteArray())
        assertNull(ApkAnalyzer.bundlePackageName(none))
        listOf(json, splits, huge, none).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `oversized manifest is ignored`() {
        val apk = Files.createTempFile("wex_bomb", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            // 20 МБ нулей — сжимаются в килобайты
            val chunk = ByteArray(1 shl 20)
            repeat(20) { zip.write(chunk) }
            zip.closeEntry()
        }
        val details = ApkAnalyzer.analyze(apk.toFile())
        assertEquals("Не определен", details.packageName)
        Files.deleteIfExists(apk)
    }
}
