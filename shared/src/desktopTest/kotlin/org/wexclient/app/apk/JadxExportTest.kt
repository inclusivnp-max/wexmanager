package org.wexclient.app.apk

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JadxExportTest {

    private val tmp: Path = Files.createTempDirectory("wex-jadx")

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private fun dex(): Path {
        val smali = File(tmp.toFile(), "Hello.smali").apply {
            writeText(
                """
                .class public Lcom/example/Hello;
                .super Ljava/lang/Object;

                .method public static greet()Ljava/lang/String;
                    .registers 1
                    const-string v0, "HelloJadx"
                    return-object v0
                .end method
                """.trimIndent()
            )
        }
        val out = tmp.resolve("classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = out.toString() }, smali.absolutePath))
        return out
    }

    private fun apk(): Path {
        val dexBytes = Files.readAllBytes(dex())
        val fixture = File(javaClass.getResource("/fixtures/minimal-unsigned.apk")!!.toURI())
        val apk = tmp.resolve("app.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { out ->
            java.util.zip.ZipFile(fixture).use { zip ->
                for (e in zip.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    zip.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(dexBytes)
            out.closeEntry()
        }
        return apk
    }

    private fun javaSources(dir: Path): List<Path> = dir.walk().filter { it.toString().endsWith(".java") }.toList()

    @Test
    fun `DEX выгружается в исходники`() {
        val out = tmp.resolve("out-dex")
        val stages = ArrayList<String>()
        val result = JadxExport.export(dex(), out, JadxExportOptions(gradle = false, threads = 1)) { stages.add(it.stage) }
        assertEquals(1, result.classes)
        val java = javaSources(out).single()
        assertTrue(java.toString().endsWith("com/example/Hello.java"), java.toString())
        assertContains(java.readText(), "HelloJadx")
        assertTrue(stages.isNotEmpty())
    }

    @Test
    fun `APK выгружается проектом Gradle`() {
        val out = tmp.resolve("out-apk")
        val result = JadxExport.export(apk(), out, JadxExportOptions(gradle = true, threads = 1))
        // Кроме нашего класса jadx собирает R из ресурсов
        assertTrue(result.classes >= 1)
        val java = javaSources(out).single { it.toString().endsWith("com/example/Hello.java") }
        assertContains(java.readText(), "HelloJadx")
        // Проект для Android Studio: настройки и сборка
        assertTrue(out.resolve("settings.gradle").exists() || out.resolve("settings.gradle.kts").exists(), "нет settings.gradle")
        assertTrue(out.walk().any { it.fileName.toString() == "AndroidManifest.xml" }, "нет манифеста")
    }

    @Test
    fun `остановка прерывает выгрузку`() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            JadxExport.export(dex(), tmp.resolve("out-stop"), JadxExportOptions(gradle = false), cancelled = { true })
        }
    }

    @Test
    fun `папка по умолчанию не занимает существующую`() {
        val input = dex()
        val first = JadxExport.defaultTarget(input)
        assertEquals("classes_jadx", first.fileName.toString())
        Files.createDirectories(first)
        val second = JadxExport.defaultTarget(input)
        assertTrue(second != first && !second.exists(), second.toString())
    }
}
