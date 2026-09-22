package org.wexclient.app.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Значок файла выбирается по его типу, поэтому здесь закреплены расширения, которые
 * встречаются каждый день: пропущенное расширение — это безликий значок в списке.
 */
class VfsItemTypeTest {

    private fun type(name: String) = VfsEntry.detectType(name, isDirectory = false)

    @Test
    fun `сборка и Android`() {
        assertEquals(VfsItemType.BUILD_SCRIPT, type("build.gradle"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("settings.gradle"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("Makefile"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("CMakeLists.txt"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("Dockerfile"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("build.gradle.kts"))
        assertEquals(VfsItemType.BUILD_SCRIPT, type("settings.gradle.kts"))
        // Обычный скрипт Kotlin остаётся Kotlin
        assertEquals(VfsItemType.CODE_KOTLIN, type("setup.main.kts"))
        assertEquals(VfsItemType.CODE_KOTLIN, type("Main.kt"))
        assertEquals(VfsItemType.PROGUARD, type("proguard-rules.pro"))
        assertEquals(VfsItemType.PROGUARD, type("consumer-rules.pro"))
        assertEquals(VfsItemType.ARCHIVE_AAR, type("material-1.11.0.aar"))
        assertEquals(VfsItemType.ARCHIVE_AAR, type("app-release.aab"))
        assertEquals(VfsItemType.ARCHIVE_APK, type("app-debug.apk"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type("gradle.properties"))
    }

    @Test
    fun `в андроидные контейнеры можно зайти как в папку`() {
        for (name in listOf("lib.aar", "app.aab", "split.apkm", "app.apk", "x.zip")) {
            assertTrue(org.wexclient.app.vfs.archive.ArchiveFormats.isBrowsable(name), name)
        }
    }

    @Test
    fun `текст и документы`() {
        assertEquals(VfsItemType.TEXT_FILE, type("заметки.txt"))
        assertEquals(VfsItemType.TEXT_FILE, type("build.log"))
        assertEquals(VfsItemType.TEXT_FILE, type("README"))
        assertEquals(VfsItemType.TEXT_FILE, type("субтитры.srt"))
        assertEquals(VfsItemType.DOCUMENT_PDF, type("договор.pdf"))
        assertEquals(VfsItemType.DOCUMENT, type("книга.epub"))
        assertEquals(VfsItemType.DOCUMENT, type("отчёт.docx"))
        assertEquals(VfsItemType.SPREADSHEET, type("данные.csv"))
        assertEquals(VfsItemType.SPREADSHEET, type("смета.xlsx"))
        assertEquals(VfsItemType.PRESENTATION, type("доклад.pptx"))
        assertEquals(VfsItemType.MARKDOWN, type("readme.md"))
    }

    @Test
    fun `языки программирования`() {
        assertEquals(VfsItemType.CODE_GO, type("main.go"))
        assertEquals(VfsItemType.CODE_GO, type("go.mod"))
        assertEquals(VfsItemType.CODE_SQL, type("dump.sql"))
        assertEquals(VfsItemType.CODE_RUBY, type("app.rb"))
        assertEquals(VfsItemType.CODE_RUBY, type("Gemfile"))
        assertEquals(VfsItemType.CODE_PHP, type("index.php"))
        assertEquals(VfsItemType.CODE_SWIFT, type("View.swift"))
        assertEquals(VfsItemType.CODE_CSHARP, type("Program.cs"))
        assertEquals(VfsItemType.CODE_DART, type("main.dart"))
        assertEquals(VfsItemType.CODE_C_CPP, type("engine.cc"))
        assertEquals(VfsItemType.CODE_JAVASCRIPT, type("app.jsx"))
        assertEquals(VfsItemType.CODE_TYPESCRIPT, type("api.mts"))
        assertEquals(VfsItemType.CODE_HTML, type("Page.vue"))
        assertEquals(VfsItemType.CODE_OTHER, type("script.pl"))
        assertEquals(VfsItemType.CODE_OTHER, type("main.zig"))
        assertEquals(VfsItemType.CODE_JAVA, type("IService.aidl"))
    }

    @Test
    fun `прочие частые файлы`() {
        assertEquals(VfsItemType.FONT, type("Inter.ttf"))
        assertEquals(VfsItemType.PACKAGE, type("firefox.deb"))
        assertEquals(VfsItemType.PACKAGE, type("system.img"))
        assertEquals(VfsItemType.BINARY, type("setup.exe"))
        assertEquals(VfsItemType.BINARY, type("module.wasm"))
        assertEquals(VfsItemType.BINARY, type("driver.sys"))
        assertEquals(VfsItemType.BINARY, type("a.out"))
        assertEquals(VfsItemType.BINARY, type("addon.node"))
        // Библиотека с версией в имени — всё равно библиотека
        assertEquals(VfsItemType.NATIVE_SO, type("libc.so.6"))
        assertEquals(VfsItemType.PATCH, type("fix.patch"))
        assertEquals(VfsItemType.DESKTOP_ENTRY, type("telegram.desktop"))
        assertEquals(VfsItemType.JSON, type("tsconfig.jsonc"))
        assertEquals(VfsItemType.XML, type("Info.plist"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type(".gitignore"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type("yarn.lock"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type(".env"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type(".env.local"))
        assertEquals(VfsItemType.PROPERTIES_CONF, type("app.env.production"))
        assertEquals(VfsItemType.XML, type("WEXdesktop.iml"))
        assertEquals(VfsItemType.DATABASE, type("places.sqlite"))
    }

    @Test
    fun `старое поведение не сломалось`() {
        assertEquals(VfsItemType.AXML_MANIFEST, type("AndroidManifest.xml"))
        assertEquals(VfsItemType.ARSC, type("resources.arsc"))
        assertEquals(VfsItemType.DEX, type("classes.dex"))
        assertEquals(VfsItemType.KEYSTORE, type("release.keystore"))
        assertEquals(VfsItemType.CERTIFICATE, type("testkey.x509.pem"))
        assertEquals(VfsItemType.NATIVE_SO, type("libapp.so"))
        assertEquals(VfsItemType.IMAGE, type("icon.png"))
        assertEquals(VfsItemType.VIDEO, type("клип.mp4"))
        assertEquals(VfsItemType.AUDIO, type("песня.flac"))
        assertEquals(VfsItemType.CODE_TYPESCRIPT, type("main.ts"))
        assertEquals(VfsItemType.FOLDER, VfsEntry.detectType("src", isDirectory = true))
        assertEquals(VfsItemType.REGULAR_FILE, type("непонятно.чтоэто"))
    }
}
