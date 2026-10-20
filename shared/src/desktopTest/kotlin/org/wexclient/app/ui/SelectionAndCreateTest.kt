package org.wexclient.app.ui

import org.wexclient.app.terminal.ScriptRunner
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsItemType
import org.wexclient.app.vfs.VfsManager
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionAndCreateTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_select")
        for (i in 1..6) Files.writeString(dir.resolve("f$i.txt"), "$i")
        ViewPreferences.updateShowHidden(true)
        ViewPreferences.updateFoldersFirst(true)
        ViewPreferences.updateNaturalSort(true)
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun `selection mode and shift range`() {
        val state = FilePaneState(vfs, dir)
        assertFalse(state.selectionMode)
        state.toggleSelection(state.entries[1])
        assertTrue(state.selectionMode)
        state.selectRange(4)
        assertEquals(listOf("f2.txt", "f3.txt", "f4.txt", "f5.txt"), state.selectedEntries.map { it.name }.sorted())
        // Диапазон назад от нового опорного элемента, без повторов
        state.selectRange(0)
        assertEquals(listOf("f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt"), state.selectedEntries.map { it.name }.sorted())
        assertEquals(state.selectedEntries.size, state.selectedEntries.toSet().size)
        state.toggleSelection(state.entries[0])
        assertEquals(4, state.selectedEntries.size)
        state.clearSelection()
        assertFalse(state.selectionMode)
        // Shift+клик без опоры — выделяется один элемент
        state.selectRange(2)
        assertEquals(listOf("f3.txt"), state.selectedEntries.map { it.name })
        state.selectRange(99)
        assertEquals(1, state.selectedEntries.size)
    }

    @Test
    fun `copied entries are revealed in the target pane`() {
        val target = Files.createDirectory(dir.resolve("target"))
        for (i in 1..80) Files.writeString(target.resolve("n$i.txt"), "$i")
        val state = FilePaneState(vfs, target)
        assertNull(state.revealRequest)
        // Как после копирования: файлы уже на месте, панель обновлена
        Files.writeString(target.resolve("n70a.txt"), "new")
        Files.writeString(target.resolve("n75a.txt"), "new")
        state.refresh()
        state.revealArrived(listOf("n75a.txt", "n70a.txt", "нет-такого.txt"))
        val names = state.entries.map { it.name }
        val request = assertNotNull(state.revealRequest)
        assertEquals(names.indexOf("n70a.txt"), request.first)
        assertEquals(names.indexOf("n75a.txt"), request.last)
        assertEquals(request.first, state.focusedIndex)
        assertTrue(state.isArrived(state.entries[request.first]) && state.isArrived(state.entries[request.last]))
        assertFalse(state.isArrived(state.entries[0]))
        // Повторный показ тех же файлов — новый запрос (прокрутка сработает снова)
        state.revealArrived(listOf("n70a.txt"))
        assertTrue(state.revealRequest !== request)
        // В другой папке одноимённые элементы не подсвечиваются
        Files.writeString(dir.resolve("n70a.txt"), "same name")
        state.navigateTo(dir)
        assertFalse(state.entries.filter { it.name == "n70a.txt" }.any { state.isArrived(it) })
        // Ничего не нашлось — запрос не меняется
        val before = state.revealRequest
        state.revealArrived(listOf("missing"))
        assertTrue(state.revealRequest === before)
    }

    @Test
    fun `new entry names`() {
        val existing = setOf("Новая папка", "Новая папка (2)", "новый файл.txt")
        val exists = { n: String -> n in existing }
        assertEquals("Новая папка (3)", uniqueEntryName("Новая папка", exists))
        assertEquals("новый файл (2).txt", uniqueEntryName("новый файл.txt", exists))
        assertEquals("other", uniqueEntryName("other", exists))
        assertEquals("Введите имя", newEntryNameProblem("  ", exists))
        assertEquals("Имя не может содержать «/»", newEntryNameProblem("a/b", exists))
        assertEquals("Недопустимое имя", newEntryNameProblem("..", exists))
        assertEquals("«Новая папка» уже существует", newEntryNameProblem("Новая папка", exists))
        assertTrue(newEntryNameProblem("я".repeat(200), exists)!!.contains("длинное"))
        assertNull(newEntryNameProblem(".gitignore", exists))
    }

    @Test
    fun `create file and folder through vfs`() {
        val file = vfs.createFile(dir, "новый.sh")
        assertTrue(Files.isRegularFile(file))
        assertEquals(0L, Files.size(file))
        assertFailsWith<FileAlreadyExistsException> { vfs.createFile(dir, "новый.sh") }
        val folder = vfs.createDirectory(dir, "Папка")
        assertTrue(Files.isDirectory(folder))
        val entry = vfs.listEntries(dir).single { it.name == "новый.sh" }
        assertEquals(VfsItemType.SHELL_SCRIPT, entry.itemType)
    }

    @Test
    fun `file types for keys, web and scripts`() {
        fun t(name: String) = VfsEntry.detectType(name, false)
        assertEquals(VfsItemType.KEYSTORE, t("release.jks"))
        assertEquals(VfsItemType.KEYSTORE, t("upload.keystore"))
        assertEquals(VfsItemType.KEYSTORE, t("key.p12"))
        assertEquals(VfsItemType.KEYSTORE, t("platform.pk8"))
        assertEquals(VfsItemType.KEYSTORE, t("private-key.pem"))
        assertEquals(VfsItemType.CERTIFICATE, t("testkey.x509.pem"))
        assertEquals(VfsItemType.CERTIFICATE, t("cert.pem"))
        assertEquals(VfsItemType.CERTIFICATE, t("CERT.RSA"))
        assertEquals(VfsItemType.CERTIFICATE, t("app.apk.idsig"))
        assertEquals(VfsItemType.CODE_CSS, t("style.css"))
        assertEquals(VfsItemType.CODE_CSS, t("theme.scss"))
        assertEquals(VfsItemType.CODE_HTML, t("index.html"))
        assertEquals(VfsItemType.BATCH_SCRIPT, t("gradlew.bat"))
        assertEquals(VfsItemType.SHELL_SCRIPT, t("install.fish"))
        assertEquals(VfsItemType.PROPERTIES_CONF, t("gradle.properties"))
        assertEquals(VfsItemType.ARCHIVE_ZIP, t("backup.tar.zst"))
        assertEquals(VfsItemType.ARCHIVE_ZIP, t("dump.sql.gz"))
        val gz = VfsEntry("dump.sql.gz", dir.resolve("dump.sql.gz"), false, true, 1, 0, VfsItemType.ARCHIVE_ZIP, "gz")
        assertEquals("dump.sql", gz.archiveBaseName)
        val tgz = VfsEntry("src.tar.gz", dir.resolve("src.tar.gz"), false, true, 1, 0, VfsItemType.ARCHIVE_ZIP, "gz")
        assertEquals("src", tgz.archiveBaseName)
        assertTrue(tgz.isExtractableArchive)
    }

    @Test
    fun `script run commands`() {
        fun script(name: String, text: String, exec: Boolean = false): Path {
            val p = dir.resolve(name)
            Files.writeString(p, text)
            if (exec) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
            return p
        }
        assertTrue(ScriptRunner.isRunnable(dir.resolve("a.sh"), false))
        assertTrue(ScriptRunner.isRunnable(dir.resolve("a.BAT"), false))
        assertFalse(ScriptRunner.isRunnable(dir.resolve("a.sh"), true))
        assertFalse(ScriptRunner.isRunnable(dir.resolve("a.txt"), false))

        assertEquals("/usr/bin/env bash ./env.sh", ScriptRunner.command(script("env.sh", "#!/usr/bin/env bash\necho 1\n")))
        assertEquals("/bin/sh -e ./plain.sh", ScriptRunner.command(script("plain.sh", "#!/bin/sh -e\necho 1\n")))
        // Android-скрипт: /system/bin/sh на ПК нет — запускаем обычной оболочкой
        assertEquals("bash ./magisk.sh", ScriptRunner.command(script("magisk.sh", "#!/system/bin/sh\necho 1\n")))
        assertEquals("bash ./noshebang.sh", ScriptRunner.command(script("noshebang.sh", "echo 1\n")))
        assertEquals("bash './it'\\''s.sh'", ScriptRunner.command(script("it's.sh", "echo 1\n")))
        if (org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("fish")) {
            assertEquals("fish ./conf.fish", ScriptRunner.command(script("conf.fish", "echo 1\n")))
        }
        val bat = script("gradlew.bat", "@echo off\r\n")
        if (org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("wine")) {
            assertEquals("wine cmd /c 'gradlew.bat'", ScriptRunner.command(bat))
        } else {
            assertFailsWith<java.io.IOException> { ScriptRunner.command(bat) }
        }
    }
}
