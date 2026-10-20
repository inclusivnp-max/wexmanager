package org.wexclient.app.apk

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.wexclient.app.signing.SignSchemes
import org.wexclient.app.signing.SigningKeys
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SplitBundleTest {

    private val tmp: Path = Files.createTempDirectory("wex-bundle")

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private fun zip(target: Path, entries: Map<String, ByteArray>, stored: Set<String> = emptySet()): Path {
        ZipOutputStream(Files.newOutputStream(target)).use { out ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    val crc = java.util.zip.CRC32().also { it.update(bytes) }
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = crc.value
                }
                out.putNextEntry(e)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return target
    }

    private fun dex(): ByteArray {
        val smali = File(tmp.toFile(), "Hello.smali").apply {
            writeText(
                """
                .class public Lcom/example/Hello;
                .super Ljava/lang/Object;

                .method public static greet()Ljava/lang/String;
                    .registers 1
                    const-string v0, "HelloBundle"
                    return-object v0
                .end method
                """.trimIndent()
            )
        }
        val out = tmp.resolve("classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = out.toString() }, smali.absolutePath))
        return Files.readAllBytes(out)
    }

    /** Основной APK: манифест и таблица ресурсов из тестового APK плюс classes.dex. */
    private fun baseApk(): Path {
        val fixture = File(javaClass.getResource("/fixtures/minimal-unsigned.apk")!!.toURI())
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(fixture).use { z -> for (e in z.entries()) entries[e.name] = z.getInputStream(e).readBytes() }
        entries["classes.dex"] = dex()
        return zip(tmp.resolve("base.apk"), entries, stored = setOf("resources.arsc"))
    }

    /** Часть набора: тот же манифест с атрибутом split и свои файлы. */
    private fun splitApk(base: Path, split: String, files: Map<String, ByteArray>): Path {
        val manifest = ApkModule.loadApkFile(base.toFile()).use { module ->
            val m = module.androidManifest
            m.setSplit(split, true)
            m.refresh()
            m.bytes
        }
        return zip(tmp.resolve("split_$split.apk"), linkedMapOf("AndroidManifest.xml" to manifest) + files)
    }

    private fun bundle(name: String, parts: Map<String, Path>, extra: Map<String, ByteArray> = emptyMap()): Path =
        zip(tmp.resolve(name), parts.mapValues { Files.readAllBytes(it.value) } + extra, stored = parts.keys)

    private fun verified(apk: ByteArray): Boolean {
        val f = Files.createTempFile(tmp, "check", ".apk")
        Files.write(f, apk)
        return ApkVerifier.Builder(f.toFile()).build().verify().isVerified
    }

    @Test
    fun `части распознаются по именам`() {
        fun kind(name: String) = SplitBundle.part(name, 1).kind
        assertEquals(BundlePartKind.ABI, kind("config.arm64_v8a-69252.apk"))
        assertEquals("arm64-v8a", SplitBundle.part("config.arm64_v8a-69252.apk", 1).label)
        assertEquals(BundlePartKind.DENSITY, kind("split_config.xxhdpi.apk"))
        assertEquals(BundlePartKind.LANGUAGE, kind("config.ru-69252.apk"))
        assertEquals(BundlePartKind.BASE, kind("splits/base-master.apk"))
        assertEquals(BundlePartKind.ABI, kind("splits/base-arm64_v8a.apk"))
        assertEquals(BundlePartKind.ASSET_PACK, kind("install_time_asset_pack-69252.apk"))
        assertEquals(BundlePartKind.BASE, kind("base.apk"))
        assertEquals(BundlePartKind.FEATURE, kind("com.example.app.apk"))
    }

    @Test
    fun `в XAPK основной — единственная часть не из config, OBB считаются`() {
        val base = baseApk()
        val input = bundle(
            "game.xapk",
            mapOf("com.example-7.apk" to base, "config.en-7.apk" to base),
            extra = mapOf("Android/obb/com.example/main.7.com.example.obb" to ByteArray(10), "manifest.json" to "{}".toByteArray())
        )
        val info = SplitBundle.inspect(input)
        assertEquals("com.example-7.apk", info.base?.entryName)
        assertEquals(1, info.obbFiles)
        assertEquals("основной + 1: en", info.summary())
    }

    @Test
    fun `подпись набора подписывает каждую часть одним ключом`() {
        val base = baseApk()
        val split = splitApk(base, "config.xxhdpi", mapOf("res/drawable-xxhdpi-v4/pic.png" to ByteArray(64) { it.toByte() }))
        val icon = ByteArray(32) { (it * 3).toByte() }
        val input = bundle("app.apks", mapOf("base.apk" to base, "split_config.xxhdpi.apk" to split), extra = mapOf("icon.png" to icon))
        val out = tmp.resolve("app_signed.apks")

        val result = SplitBundle.sign(input, out, SigningKeys.testKey, SignSchemes())
        assertEquals(null, result.verifyProblem)
        ZipFile(out.toFile()).use { z ->
            val apks = z.entries().toList().filter { it.name.endsWith(".apk") }
            assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), apks.map { it.name })
            // Части лежали без сжатия — так и остались
            assertTrue(apks.all { it.method == ZipEntry.STORED })
            apks.forEach { assertTrue(verified(z.getInputStream(it).readBytes()), it.name) }
            assertContentEquals(icon, z.getInputStream(z.getEntry("icon.png")).readBytes())
        }

        // На месте: файл заменяется подписанным
        SplitBundle.sign(input, input, SigningKeys.testKey, SignSchemes())
        ZipFile(input.toFile()).use { z -> assertTrue(verified(z.getInputStream(z.getEntry("base.apk")).readBytes())) }
    }

    @Test
    fun `конвертация сливает части в один подписанный APK без признаков split`() {
        val base = baseApk()
        val abi = splitApk(base, "config.arm64_v8a", mapOf("lib/arm64-v8a/libhello.so" to ByteArray(4096) { 7 }))
        val input = bundle("app.apks", mapOf("base.apk" to base, "split_config.arm64_v8a.apk" to abi))
        val out = SplitBundle.defaultApkTarget(input)
        assertEquals("app.apk", out.fileName.toString())

        val stages = ArrayList<String>()
        val result = SplitBundle.convertToApk(input, out, SigningKeys.testKey, SignSchemes()) { stages.add(it.stage) }
        assertEquals(2, result.parts)
        assertEquals(null, result.sign.verifyProblem)
        assertTrue(verified(Files.readAllBytes(out)))
        ZipFile(out.toFile()).use { z ->
            assertNotNull(z.getEntry("classes.dex"))
            assertNotNull(z.getEntry("lib/arm64-v8a/libhello.so"))
            val manifest = org.wexclient.app.axml.AxmlDecoder().decode(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
            assertFalse("split=" in manifest, manifest)
        }
        assertTrue(stages.any { it.startsWith("Слияние") })
        assertTrue("Подпись" in stages)
    }

    @Test
    fun `старые подписи узнаются, прочий META-INF остаётся`() {
        assertTrue(SplitBundle.isOldSignature("META-INF/CERT.RSA"))
        assertTrue(SplitBundle.isOldSignature("META-INF/MANIFEST.MF"))
        assertTrue(SplitBundle.isOldSignature("META-INF/BNDLTOOL.SF"))
        assertTrue(SplitBundle.isOldSignature("stamp-cert-sha256"))
        assertFalse(SplitBundle.isOldSignature("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler"))
        assertFalse(SplitBundle.isOldSignature("META-INF/androidx.core_core.version"))
    }
}
