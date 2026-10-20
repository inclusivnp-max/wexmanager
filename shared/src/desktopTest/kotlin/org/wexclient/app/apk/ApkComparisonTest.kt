package org.wexclient.app.apk

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkComparisonTest {
    private val root = Files.createTempDirectory("wex-apk-comparison")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun apk(vararg entries: Pair<String, String>): Path {
        val path = Files.createTempFile(root, "app-", ".apk")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `file comparison works without a Compose window`() {
        val old = apk("assets/changed.txt" to "old", "assets/same.txt" to "same")
        val new = apk("assets/changed.txt" to "new", "assets/same.txt" to "same", "assets/new.txt" to "new")
        val result = compareApks(old, new, DexCompareOptions())
        assertEquals(1, result.summary.changed)
        assertEquals(1, result.summary.added)
        assertEquals(1, result.summary.same)
        assertTrue(result.code.classes.isEmpty())
    }

    @Test
    fun `identical APK contents have no changes`() {
        val result = compareApks(apk("assets/a" to "same"), apk("assets/a" to "same"), DexCompareOptions())
        assertFalse(result.summary.hasChanges)
    }

    @Test
    fun `corrupt changed dex reports the file name`() {
        val error = assertFailsWith<IOException> {
            compareApks(apk("classes.dex" to "old"), apk("classes.dex" to "new"), DexCompareOptions())
        }
        assertTrue(error.message.orEmpty().contains("classes.dex"))
    }

    @Test
    fun `corrupt changed resources do not produce a successful comparison`() {
        assertFailsWith<IOException> {
            compareApks(apk("resources.arsc" to "old"), apk("resources.arsc" to "new"), DexCompareOptions())
        }
    }

    @Test
    fun `cancelled comparison does not open the input files`() {
        assertFailsWith<CancellationException> {
            compareApks(root.resolve("missing-a.apk"), root.resolve("missing-b.apk"), DexCompareOptions(), cancelled = { true })
        }
    }

    @Test
    fun `progress is reported to the caller`() {
        val stages = mutableListOf<CompareProgress>()
        compareApks(apk("assets/a" to "old"), apk("assets/a" to "new"), DexCompareOptions(), onProgress = { stages += it })
        assertTrue(stages.isNotEmpty())
        assertEquals("Читаю оглавление пакетов", stages.first().stage)
        assertEquals(0.5f, CompareProgress("DEX", 1, 2).fraction)
        assertEquals(1f, CompareProgress("DEX", 3, 2).fraction)
    }
}
