package org.wexclient.app.apk

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сравнение содержимого двух APK. */
class ApkDiffTest {

    private fun apk(vararg files: Pair<String, String>): Path {
        val path = Files.createTempFile("wex-apk", ".apk")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `видит добавленные, удалённые и изменённые файлы`() {
        val old = apk(
            "AndroidManifest.xml" to "манифест",
            "classes.dex" to "старый код",
            "res/drawable/icon.png" to "картинка",
            "res/removed.xml" to "лишнее"
        )
        val new = apk(
            "AndroidManifest.xml" to "манифест",
            "classes.dex" to "новый код, длиннее",
            "res/drawable/icon.png" to "картинка",
            "res/added.xml" to "новое"
        )

        val changes = ApkDiff.compare(ApkDiff.entries(old), ApkDiff.entries(new))
        val byName = changes.associateBy { it.name }

        assertEquals(DiffKind.CHANGED, byName["classes.dex"]?.kind)
        assertEquals(DiffKind.ADDED, byName["res/added.xml"]?.kind)
        assertEquals(DiffKind.REMOVED, byName["res/removed.xml"]?.kind)
        assertEquals(DiffKind.SAME, byName["AndroidManifest.xml"]?.kind)
        assertEquals(DiffKind.SAME, byName["res/drawable/icon.png"]?.kind)
    }

    @Test
    fun `изменённые идут первыми, совпавшие последними`() {
        val old = apk("a.txt" to "один", "b.txt" to "два")
        val new = apk("a.txt" to "один", "b.txt" to "два изменилось", "c.txt" to "три")

        val kinds = ApkDiff.compare(ApkDiff.entries(old), ApkDiff.entries(new)).map { it.kind }
        assertEquals(listOf(DiffKind.CHANGED, DiffKind.ADDED, DiffKind.SAME), kinds)
    }

    @Test
    fun `сводка считает изменения и вес`() {
        val old = apk("a.txt" to "12345", "gone.txt" to "x")
        val new = apk("a.txt" to "1234567890", "new.txt" to "yy")

        val changes = ApkDiff.compare(ApkDiff.entries(old), ApkDiff.entries(new))
        val sum = ApkDiff.summarize(changes, Files.size(old), Files.size(new))

        assertEquals(1, sum.added)
        assertEquals(1, sum.removed)
        assertEquals(1, sum.changed)
        assertEquals(0, sum.same)
        assertEquals(6L, sum.oldTotal)   // 5 + 1
        assertEquals(12L, sum.newTotal)  // 10 + 2
        assertTrue(sum.hasChanges)
    }

    @Test
    fun `одинаковые APK не дают изменений`() {
        val a = apk("classes.dex" to "код", "res/x.xml" to "ресурс")
        val b = apk("classes.dex" to "код", "res/x.xml" to "ресурс")

        val sum = ApkDiff.summarize(
            ApkDiff.compare(ApkDiff.entries(a), ApkDiff.entries(b)),
            Files.size(a),
            Files.size(b)
        )
        assertEquals(2, sum.same)
        assertTrue(!sum.hasChanges)
    }

    @Test
    fun `dex-файлы перечисляются по порядку`() {
        val file = apk(
            "classes3.dex" to "три",
            "classes.dex" to "один",
            "classes2.dex" to "два",
            "res/classes.dex.txt" to "не dex"
        )
        assertEquals(
            listOf("classes.dex", "classes2.dex", "classes3.dex"),
            ApkDiff.dexNames(ApkDiff.entries(file))
        )
    }

    @Test
    fun `папки не попадают в сравнение`() {
        val path = Files.createTempFile("wex-apk-dir", ".apk")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("res/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/a.xml"))
            zip.write("данные".toByteArray())
            zip.closeEntry()
        }
        val changes = ApkDiff.compare(ApkDiff.entries(path), ApkDiff.entries(path))
        assertEquals(listOf("res/a.xml"), changes.map { it.name })
    }

    @Test
    fun `не-архив не роняет разбор`() {
        val broken = Files.createTempFile("wex-apk-broken", ".apk")
        Files.write(broken, byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(ApkDiff.entries(broken).isEmpty())
    }

    @Test
    fun `читает содержимое записи`() {
        val file = apk("AndroidManifest.xml" to "содержимое манифеста")
        val bytes = ApkDiff.read(file, "AndroidManifest.xml")
        assertEquals("содержимое манифеста", bytes?.decodeToString())
        assertEquals(null, ApkDiff.read(file, "нет-такого"))
    }
}
