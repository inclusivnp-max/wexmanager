package org.wexclient.app.adb

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор ответа pm и dumpsys — без телефона; выгрузка APK — на живом, если задан WEX_ADB_SERIAL. */
class AdbPackagesTest {

    @Test
    fun `строка pm разбирается, «=» в пути не мешает`() {
        val (path, name, code) = AdbPackages.parsePackageLine(
            "package:/data/app/~~hq-9djFv6Vcf==/com.axlebolt.standoff2-R7oQl0lW==/base.apk=com.axlebolt.standoff2 versionCode:2147483647"
        )!!
        assertEquals("/data/app/~~hq-9djFv6Vcf==/com.axlebolt.standoff2-R7oQl0lW==/base.apk", path)
        assertEquals("com.axlebolt.standoff2", name)
        assertEquals(2147483647L, code)
        // Без кода версии и с чужими полями после имени
        assertEquals("client.wex", AdbPackages.parsePackageLine("package:/data/app/x/base.apk=client.wex  installer=null uid:10220")!!.second)
    }

    @Test
    fun `мусор и опасные имена не принимаются за пакет`() {
        assertNull(AdbPackages.parsePackageLine("Failed to write while dumping service package"))
        assertNull(AdbPackages.parsePackageLine("package:/x/base.apk"))
        // Имя пакета идёт в команду оболочки телефона: «;» и пробелы в нём недопустимы
        assertNull(AdbPackages.parsePackageLine("package:/x/base.apk=a;reboot"))
    }

    @Test
    fun `список собирается из трёх ответов`() {
        val output = """
            package:/data/app/~~a==/client.wex-b==/base.apk=client.wex versionCode:24
            package:/system/priv-app/Settings/Settings.apk=com.android.settings versionCode:35
            package:/product/app/Chrome/Chrome.apk=com.android.chrome versionCode:631
            --wex-section--
            package:client.wex
            --wex-section--
              Package [com.android.chrome] (abc):
                versionName=126.0
              Package [client.wex] (def):
                versionName=1.8.0
              Package [com.android.settings] (123):
                versionName=15
              Package [com.android.chrome] (old):
                versionName=100.0
        """.trimIndent()
        val list = AdbPackages.parseList(output)
        assertEquals(listOf("client.wex", "com.android.chrome", "com.android.settings"), list.map { it.name })
        val wex = list[0]
        assertFalse(wex.system)
        assertEquals("1.8.0 (24)", wex.versionLabel)
        // У обновлённого системного приложения берётся действующая версия, а не скрытая прошивочная
        assertEquals("126.0", list[1].versionName)
        assertTrue(list[1].system)
    }

    @Test
    fun `имя файла из пакета и версии годится для любой ФС`() {
        assertEquals("client.wex-1.8.0", AdbPackages.fileBase(DevicePackage("client.wex", "/x", 24, "1.8.0", false)))
        assertEquals("a.b-2.0_beta_1_", AdbPackages.fileBase(DevicePackage("a.b", "/x", 1, "2.0 beta/1?", false)))
        assertEquals("a.b-7", AdbPackages.fileBase(DevicePackage("a.b", "/x", 7, null, false)))
    }

    /** Живой телефон или эмулятор: список и выгрузка APK — только чтение с устройства. */
    @Test
    fun `APK выгружается с телефона целиком`() {
        val serial = System.getenv("WEX_ADB_SERIAL") ?: return
        val packages = AdbPackages.list(serial)
        assertTrue(packages.any { it.system }, "системные приложения есть на любом телефоне")
        val pkg = packages.firstOrNull { !it.system } ?: packages.first { it.name == "com.android.settings" }
        val dir = Files.createTempDirectory("wex-apk")
        try {
            val saved = assertNotNull(AdbPackages.saveApk(serial, pkg, dir))
            val parts = AdbPackages.apkPaths(serial, pkg.name)
            if (parts.size == 1) {
                assertTrue(saved.fileName.toString().endsWith(".apk"))
                ZipFile(saved.toFile()).use { assertNotNull(it.getEntry("AndroidManifest.xml")) }
            } else {
                assertTrue(saved.fileName.toString().endsWith(".apks"))
                ZipFile(saved.toFile()).use { zip -> assertEquals(parts.size, zip.size()) }
            }
            // Второе сохранение не затирает первое
            val again = assertNotNull(AdbPackages.saveApk(serial, pkg, dir))
            assertTrue(again != saved && Files.exists(saved))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
