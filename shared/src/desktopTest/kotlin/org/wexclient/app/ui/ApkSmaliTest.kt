package org.wexclient.app.ui

import kotlinx.coroutines.CancellationException
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.wexclient.app.apk.ApkDexDiff
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** smali классов APK для сравнения кода: класс ищется по всем classes*.dex, разобранное запоминается. */
class ApkSmaliTest {

    private val tmp = Files.createTempDirectory("apk_smali_test_").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun cls(name: String, text: String) = """
        .class public L$name;
        .super Ljava/lang/Object;

        .method public static text()Ljava/lang/String;
            .registers 1
            const-string v0, "$text"
            return-object v0
        .end method
    """.trimIndent()

    private fun dex(vararg classes: String): ByteArray {
        val dir = Files.createTempDirectory(tmp.toPath(), "dex").toFile()
        val files = classes.mapIndexed { i, text -> File(dir, "C$i.smali").apply { writeText(text) } }
        val out = File(dir, "classes.dex")
        val options = SmaliOptions().apply { outputDexFile = out.absolutePath }
        assertTrue(Smali.assemble(options, files.map { it.absolutePath }), "smali assembly failed")
        return out.readBytes()
    }

    private fun apk(vararg dexes: Pair<String, ByteArray>): Path {
        val file = File(tmp, "app.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in dexes) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file.toPath()
    }

    @Test
    fun `класс находится в любом dex, первый dex главнее`() {
        val path = apk(
            "classes.dex" to dex(cls("test/A", "from-first"), cls("test/Dup", "dup-first")),
            "classes2.dex" to dex(cls("test/B", "from-second"), cls("test/Dup", "dup-second"))
        )
        val smali = ApkSmali(path)

        val b = assertNotNull(smali.lines("test.B"))
        assertTrue(b.any { it.contains("from-second") }, b.joinToString("\n"))
        // Класс уже просмотренного dex — без нового перебора
        val a = assertNotNull(smali.lines("test.A"))
        assertTrue(a.any { it.contains("from-first") }, a.joinToString("\n"))
        // Один класс в двух dex — как и в самом сравнении, берётся первый
        val dup = assertNotNull(smali.lines("test.Dup"))
        assertTrue(dup.any { it.contains("dup-first") }, dup.joinToString("\n"))
        assertNull(smali.lines("test.Missing"))
        // После закрытия модель разбирается заново — ответ тот же
        smali.close()
        assertEquals(b, smali.lines("test.B"))
    }

    @Test
    fun `поиск класса бросается по отмене`() {
        val path = apk("classes.dex" to dex(cls("test/A", "a")))
        assertFailsWith<CancellationException> { ApkSmali(path).lines("test.A") { true } }
    }

    @Test
    fun `разбор dex для сравнения бросается по отмене`() {
        val bytes = dex(cls("test/A", "a"))
        assertEquals(setOf("test.A"), ApkDexDiff.read(listOf(bytes)).keys)
        assertFailsWith<CancellationException> { ApkDexDiff.read(listOf(bytes), cancelled = { true }) }
    }
}
